package su.linka.pictures.access

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import su.linka.pictures.application.*
import su.linka.pictures.application.ports.*
import su.linka.pictures.domain.RequestId
import su.linka.pictures.text.isNativeText

/** The root owns ActivityResult/foreground delivery; this adapter never treats SIM security as a credential. */
internal class AndroidDeviceOwnerVerifier(
    context: Context,
    private val title: String,
    private val description: String,
    private val requestCredential: suspend (Intent, RequestId, String) -> OperationResult<Unit>,
    private val cancelCredential: (RequestId) -> Unit,
    private val isRequestCurrent: (RequestId, String) -> Boolean,
    private val isForeground: (String) -> Boolean,
    private val onPromptStarted: () -> Unit,
) : DeviceOwnerVerifier {
    private val application = context.applicationContext
    private val pending = MutableStateFlow<OwnerVerificationRequest?>(null)
    val hasPendingRequest: Boolean get() = pending.value?.terminal == false

    init {
        require(title.isNotBlank() && title.length <= 1024 && isNativeText(title))
        require(description.isNotBlank() && description.length <= 1024 && isNativeText(description))
    }

    override suspend fun availability(): OwnerVerificationAvailability {
        if (Build.VERSION.SDK_INT < 23) return OwnerVerificationAvailability.UNAVAILABLE
        return withContext(Dispatchers.IO) {
            try {
                if (keyguard()?.isDeviceSecure == true) OwnerVerificationAvailability.AVAILABLE
                else OwnerVerificationAvailability.UNAVAILABLE
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { OwnerVerificationAvailability.UNKNOWN }
        }
    }

    @Suppress("DEPRECATION")
    override suspend fun verify(requestId: RequestId, ownerId: String): OperationResult<Unit> = withContext(Dispatchers.Main) {
        if (Build.VERSION.SDK_INT < 23) return@withContext failed(ProblemCode.OWNER_VERIFICATION_UNAVAILABLE)
        if (!isRequestCurrent(requestId, ownerId) || !isForeground(ownerId)) return@withContext OperationResult.Cancelled
        val request = OwnerVerificationRequest(requestId, ownerId)
        if (!pending.compareAndSet(null, request)) return@withContext failed(ProblemCode.CONFLICT)
        try {
            if (availability() != OwnerVerificationAvailability.AVAILABLE) return@withContext failed(ProblemCode.OWNER_VERIFICATION_UNAVAILABLE)
            currentCoroutineContext().ensureActive()
            if (!current(request) || request.terminal || !isForeground(ownerId)) return@withContext OperationResult.Cancelled
            val intent = keyguard()?.createConfirmDeviceCredentialIntent(title, description)
                ?: return@withContext failed(ProblemCode.OWNER_VERIFICATION_UNAVAILABLE)
            onPromptStarted()
            currentCoroutineContext().ensureActive()
            if (!current(request) || request.terminal || !isForeground(ownerId)) return@withContext OperationResult.Cancelled
            val result = requestCredential(intent, requestId, ownerId)
            currentCoroutineContext().ensureActive()
            if (!current(request)) return@withContext OperationResult.Cancelled
            request.proof(result, foreground = isForeground(ownerId))
            val proof = request.await()
            currentCoroutineContext().ensureActive()
            if (proof is OperationResult.Completed) {
                if (!current(request) || !isForeground(ownerId)) return@withContext OperationResult.Cancelled
                if (availability() != OwnerVerificationAvailability.AVAILABLE) return@withContext failed(ProblemCode.OWNER_VERIFICATION_UNAVAILABLE)
                if (!current(request) || !isForeground(ownerId)) return@withContext OperationResult.Cancelled
            }
            proof
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { failed(ProblemCode.IO_FAILURE) }
        finally {
            request.cancel()
            pending.compareAndSet(request, null)
            runCatching { cancelCredential(requestId) }
        }
    }

    fun onForeground() {
        val request = pending.value ?: return
        if (!current(request)) request.cancel()
        else if (isForeground(request.ownerId)) request.foreground()
    }

    override fun cancel(requestId: RequestId) {
        val request = pending.value?.takeIf { it.id == requestId } ?: return
        request.cancel()
        runCatching { cancelCredential(requestId) }
    }

    private fun current(request: OwnerVerificationRequest): Boolean = pending.value === request && isRequestCurrent(request.id, request.ownerId)
    private fun keyguard() = application.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
    private fun failed(code: ProblemCode) = OperationResult.Failed(Problem(code))
}
