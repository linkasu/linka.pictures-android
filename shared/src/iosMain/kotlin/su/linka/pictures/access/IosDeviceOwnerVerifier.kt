@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package su.linka.pictures.access

import kotlinx.cinterop.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import platform.Foundation.*
import platform.LocalAuthentication.*
import platform.UIKit.*
import su.linka.pictures.application.*
import su.linka.pictures.application.ports.*
import su.linka.pictures.domain.RequestId
import su.linka.pictures.text.isNativeText

internal class IosDeviceOwnerVerifier(
    private val reason: String,
    private val isRequestCurrent: (RequestId, String) -> Boolean,
    private val isOwnerForeground: (String) -> Boolean,
    private val onPromptStarted: () -> Unit,
    private val scope: CoroutineScope,
) : DeviceOwnerVerifier {
    private class Attempt(val request: OwnerVerificationRequest, val context: LAContext)
    private val pending = MutableStateFlow<Attempt?>(null)
    val hasPendingRequest: Boolean get() = pending.value?.request?.terminal == false

    init { require(reason.isNotBlank() && reason.length <= 1024 && isNativeText(reason)) }

    override suspend fun availability(): OwnerVerificationAvailability = withContext(Dispatchers.Main) {
        if (scope.coroutineContext[Job]?.isActive == false) return@withContext OwnerVerificationAvailability.UNAVAILABLE
        val context = LAContext()
        try {
            if (canEvaluate(context)) OwnerVerificationAvailability.AVAILABLE else OwnerVerificationAvailability.UNAVAILABLE
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { OwnerVerificationAvailability.UNKNOWN }
        finally { context.invalidate() }
    }

    override suspend fun verify(requestId: RequestId, ownerId: String): OperationResult<Unit> = withContext(Dispatchers.Main) {
        if (!isRequestCurrent(requestId, ownerId) || !active(ownerId)) return@withContext OperationResult.Cancelled
        if (scope.coroutineContext[Job]?.isActive == false) return@withContext OperationResult.Cancelled
        val context = LAContext()
        val attempt = Attempt(OwnerVerificationRequest(requestId, ownerId), context)
        if (!pending.compareAndSet(null, attempt)) {
            context.invalidate()
            return@withContext failed(ProblemCode.CONFLICT)
        }
        val observers = mutableListOf<Any>()
        val center = NSNotificationCenter.defaultCenter
        val rootCompletion = scope.coroutineContext[Job]?.invokeOnCompletion { attempt.request.cancel() }
        try {
            context.touchIDAuthenticationAllowableReuseDuration = 0.0
            observers += center.addObserverForName(UIApplicationDidEnterBackgroundNotification, null, NSOperationQueue.mainQueue) {
                cancel(requestId)
            }
            observers += center.addObserverForName(UIApplicationDidBecomeActiveNotification, null, NSOperationQueue.mainQueue) {
                onForeground(ownerId)
            }
            if (!canEvaluate(context)) return@withContext failed(ProblemCode.OWNER_VERIFICATION_UNAVAILABLE)
            currentCoroutineContext().ensureActive()
            if (!current(attempt) || attempt.request.terminal || !active(ownerId)) return@withContext OperationResult.Cancelled
            onPromptStarted()
            currentCoroutineContext().ensureActive()
            if (!current(attempt) || attempt.request.terminal || !active(ownerId)) return@withContext OperationResult.Cancelled
            context.evaluatePolicy(LAPolicyDeviceOwnerAuthentication, localizedReason = reason) { success, error ->
                // LocalAuthentication replies on a private queue; UIKit and delivery stay on Main.
                scope.launch(Dispatchers.Main.immediate) {
                    if (!current(attempt)) attempt.request.cancel()
                    else attempt.request.proof(if (success) OperationResult.Completed(Unit) else failure(error), active(ownerId))
                }
            }
            val result = attempt.request.await()
            currentCoroutineContext().ensureActive()
            if (result is OperationResult.Completed && (!current(attempt) || !active(ownerId))) OperationResult.Cancelled else result
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { failed(ProblemCode.IO_FAILURE) }
        finally {
            attempt.request.cancel()
            pending.compareAndSet(attempt, null)
            rootCompletion?.dispose()
            withContext(NonCancellable + Dispatchers.Main) {
                observers.forEach { token -> runCatching { center.removeObserver(token) } }
                observers.clear()
                runCatching { context.invalidate() }
            }
        }
    }

    override fun cancel(requestId: RequestId) {
        val attempt = pending.value?.takeIf { it.request.id == requestId } ?: return
        attempt.request.cancel()
        scope.launch(Dispatchers.Main.immediate) { runCatching { attempt.context.invalidate() } }
    }

    // Scene activation may occur while a different scene already keeps UIApplication active.
    fun onForeground(ownerId: String) {
        scope.launch(Dispatchers.Main.immediate) {
            val attempt = pending.value?.takeIf { it.request.ownerId == ownerId } ?: return@launch
            if (!current(attempt)) attempt.request.cancel()
            else if (active(ownerId)) attempt.request.foreground()
        }
    }

    private fun current(attempt: Attempt): Boolean = pending.value === attempt &&
        isRequestCurrent(attempt.request.id, attempt.request.ownerId)

    private fun active(ownerId: String): Boolean = isOwnerForeground(ownerId) &&
        UIApplication.sharedApplication.applicationState == UIApplicationState.UIApplicationStateActive
    private fun canEvaluate(context: LAContext): Boolean = memScoped {
        val error = alloc<ObjCObjectVar<NSError?>>()
        context.canEvaluatePolicy(LAPolicyDeviceOwnerAuthentication, error.ptr)
    }

    private fun failure(error: NSError?): OperationResult<Unit> {
        if (error?.domain != LAErrorDomain) return failed(ProblemCode.IO_FAILURE)
        return when (error.code) {
            LAErrorUserCancel, LAErrorAppCancel, LAErrorSystemCancel -> OperationResult.Cancelled
            LAErrorPasscodeNotSet, LAErrorBiometryNotAvailable, LAErrorBiometryNotEnrolled -> failed(ProblemCode.OWNER_VERIFICATION_UNAVAILABLE)
            LAErrorAuthenticationFailed, LAErrorBiometryLockout -> failed(ProblemCode.UNAUTHORIZED)
            else -> failed(ProblemCode.IO_FAILURE)
        }
    }

    private fun failed(code: ProblemCode) = OperationResult.Failed(Problem(code))
}
