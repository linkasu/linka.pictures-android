package su.linka.pictures.platform

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import su.linka.pictures.application.OperationResult
import su.linka.pictures.application.Problem
import su.linka.pictures.application.ProblemCode
import su.linka.pictures.domain.RequestId

/** OS transport only: a successful credential result is not owner verification. */
internal class AndroidSystemRequests(
    private val activity: ComponentActivity,
    private val isOwnerOpen: (String) -> Boolean,
    private val onPresentationStarted: () -> Unit,
    private val scope: CoroutineScope,
    private val registry: ActivityResultRegistry = activity.activityResultRegistry,
    private val unregister: (ActivityResultLauncher<*>) -> Unit = { it.unregister() },
) {
    private enum class State { LAUNCHING, WAITING_SYSTEM_RESULT, WAITING_FOREGROUND, TERMINAL }
    private sealed interface Slot
    private data object Closed : Slot

    private class Pending<T : Any>(
        val id: RequestId,
        val owner: String,
        val continuation: CancellableContinuation<OperationResult<T>>,
    ) : Slot {
        val invalid = AtomicBoolean(false)
        var state = State.LAUNCHING
        var launcher: ActivityResultLauncher<*>? = null
        var positive: OperationResult.Completed<T>? = null
    }

    private val pending = AtomicReference<Slot?>(null)
    private var lifetime: Job? = null
    private val destroyObserver = LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_DESTROY) close()
    }

    init {
        lifetime = scope.launch(Dispatchers.Main.immediate) {
            try {
                if (pending.get() !== Closed) activity.lifecycle.addObserver(destroyObserver)
                awaitCancellation()
            } finally {
                close()
            }
        }
    }

    suspend fun requestDocument(intent: Intent, id: RequestId, owner: String): OperationResult<Uri> =
        request(id, owner, ActivityResultContracts.StartActivityForResult(), intent) {
            if (it.resultCode == Activity.RESULT_OK && it.data?.data != null) {
                OperationResult.Completed(it.data!!.data!!)
            } else OperationResult.Cancelled
        }

    suspend fun requestCredential(intent: Intent, id: RequestId, owner: String): OperationResult<Unit> =
        request(id, owner, ActivityResultContracts.StartActivityForResult(), intent) {
            if (it.resultCode == Activity.RESULT_OK) OperationResult.Completed(Unit)
            else OperationResult.Cancelled
        }

    suspend fun requestMicrophonePermission(id: RequestId, owner: String): OperationResult<Unit> =
        request(id, owner, ActivityResultContracts.RequestPermission(), Manifest.permission.RECORD_AUDIO,
            alreadyCompleted = {
                ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
            }, alreadyResult = Unit,
        ) {
            if (it) OperationResult.Completed(Unit)
            else OperationResult.Failed(Problem(ProblemCode.PERMISSION_DENIED))
        }

    fun cancel(id: RequestId) {
        val current = pending.get() as? Pending<*> ?: return
        if (current.id == id) invalidate(current)
    }

    fun close() {
        val previous = pending.getAndSet(Closed)
        if (previous === Closed) return
        (previous as? Pending<*>)?.let(::invalidate)
        cleanupOnMain {
            try {
                attemptCleanup { activity.lifecycle.removeObserver(destroyObserver) }
            } finally {
                attemptCleanup { lifetime?.cancel() }
            }
        }
    }

    fun onForeground() {
        cleanupOnMain { (pending.get() as? Pending<*>)?.let(::resumeForeground) }
    }

    private suspend fun <T : Any, I, O> request(
        id: RequestId,
        owner: String,
        contract: ActivityResultContract<I, O>,
        input: I,
        alreadyCompleted: () -> Boolean = { false },
        alreadyResult: T? = null,
        parse: (O) -> OperationResult<T>,
    ): OperationResult<T> = withContext(Dispatchers.Main.immediate) {
        var admitted: Pending<T>? = null
        try {
            suspendCancellableCoroutine { continuation ->
                val current = Pending(id, owner, continuation)
                if (!pending.compareAndSet(null, current)) {
                    val result = if (pending.get() === Closed) OperationResult.Cancelled
                    else OperationResult.Failed(Problem(ProblemCode.CONFLICT))
                    continuation.resumeWith(Result.success(result))
                    return@suspendCancellableCoroutine
                }
                admitted = current
                continuation.invokeOnCancellation { invalidate(current) }
                if (!valid(current)) {
                    finish(current, OperationResult.Cancelled)
                    return@suspendCancellableCoroutine
                }
                try {
                    if (alreadyCompleted()) {
                        receive(current, OperationResult.Completed(checkNotNull(alreadyResult)))
                        return@suspendCancellableCoroutine
                    }
                    val launcher = registry.register("system-request-${UUID.randomUUID()}", contract) {
                        // Registry callbacks normally run on Main; retain that rule for injected registries too.
                        cleanupOnMain { receive(current, parse(it)) }
                    }
                    current.launcher = launcher
                    if (!valid(current) || !foreground()) {
                        finish(current, OperationResult.Cancelled)
                        return@suspendCancellableCoroutine
                    }
                    onPresentationStarted()
                    // The hook may synchronously revoke the owner or move the Activity into background.
                    if (!valid(current) || !foreground()) {
                        finish(current, OperationResult.Cancelled)
                        return@suspendCancellableCoroutine
                    }
                    current.state = State.WAITING_SYSTEM_RESULT
                    launcher.launch(input)
                } catch (_: Exception) {
                    finish(current, OperationResult.Failed(Problem(ProblemCode.IO_FAILURE)))
                }
            }
        } finally {
            withContext(NonCancellable + Dispatchers.Main.immediate) {
                admitted?.let { current ->
                    current.invalid.set(true)
                    pending.compareAndSet(current, null)
                    cleanup(current)
                }
            }
        }
    }

    private fun valid(current: Pending<*>): Boolean =
        pending.get() === current && !current.invalid.get() &&
            scope.isActive && current.continuation.isActive && !activity.isDestroyed &&
            !activity.isFinishing && isOwnerOpen(current.owner)

    private fun foreground(): Boolean =
        !activity.isDestroyed && !activity.isFinishing &&
            activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && activity.hasWindowFocus()

    private fun <T : Any> receive(current: Pending<T>, result: OperationResult<T>) {
        if (current.state == State.TERMINAL || current.state == State.WAITING_FOREGROUND) return
        if (!valid(current)) {
            finish(current, OperationResult.Cancelled)
        } else if (result is OperationResult.Completed && !foreground()) {
            current.positive = result
            current.state = State.WAITING_FOREGROUND
        } else finish(current, result)
    }

    private fun <T : Any> resumeForeground(current: Pending<T>) {
        if (!valid(current)) finish(current, OperationResult.Cancelled)
        else if (foreground()) current.positive?.let { finish(current, it) }
    }

    private fun <T : Any> finish(current: Pending<T>, result: OperationResult<T>) {
        if (current.state == State.TERMINAL) return
        val checked = if (result is OperationResult.Completed && !valid(current)) OperationResult.Cancelled else result
        // Cancellation from any thread wins until the terminal CAS admits delivery.
        val active = current.invalid.compareAndSet(false, true)
        val admitted = pending.compareAndSet(current, null)
        var delivered = if (active && admitted) checked else OperationResult.Cancelled
        current.state = State.TERMINAL
        current.positive = null
        try {
            if (!cleanup(current) && delivered is OperationResult.Completed) {
                delivered = OperationResult.Failed(Problem(ProblemCode.IO_FAILURE))
            }
        } finally {
            if (current.continuation.isActive) current.continuation.resumeWith(Result.success(delivered))
        }
    }

    private fun invalidate(current: Pending<*>) {
        current.invalid.set(true)
        cleanupOnMain { finish(current, OperationResult.Cancelled) }
    }

    private fun cleanup(current: Pending<*>): Boolean {
        val launcher = current.launcher ?: return true
        // Detach before native cleanup: a failed unregister must not strand or retry terminal delivery.
        current.launcher = null
        return attemptCleanup { unregister(launcher) }
    }

    private fun attemptCleanup(action: () -> Unit): Boolean = try {
        action()
        true
    } catch (_: Exception) {
        false
    }

    private fun cleanupOnMain(action: () -> Unit) {
        scope.launch(NonCancellable + Dispatchers.Main.immediate) { action() }
    }
}
