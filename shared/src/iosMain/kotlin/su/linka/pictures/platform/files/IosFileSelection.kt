@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package su.linka.pictures.platform.files

import kotlinx.coroutines.*
import platform.Foundation.*
import platform.UIKit.*
import platform.UniformTypeIdentifiers.*
import platform.darwin.NSObject
import su.linka.pictures.application.ProblemCode
import su.linka.pictures.application.ports.PickedFileKind
import su.linka.pictures.text.isNativeText
import kotlin.coroutines.resume

// Accessed only on Main; it prevents concurrent presentations without touching another controller.
private var presentedSelection: IosFileSelection? = null
private val requestLock = NSLock()
private val requests = mutableMapOf<String, IosFileSelection>()
private inline fun <T> requestsLocked(block: () -> T): T {
    requestLock.lock()
    try { return block() } finally { requestLock.unlock() }
}

internal suspend fun selectIosFile(
    kind: PickedFileKind?, folder: Boolean, ownerId: String,
    presenter: () -> UIViewController?, isOwnerActive: (String) -> Boolean,
    onCreated: (IosFileSelection) -> Unit,
): IosFileSelection = withContext(Dispatchers.Main) {
    if (ownerId.isBlank() || !isNativeText(ownerId)) fail(ProblemCode.INVALID_DATA)
    if (!isOwnerActive(ownerId)) fail(ProblemCode.UNAUTHORIZED)
    if (UIApplication.sharedApplication.applicationState != UIApplicationState.UIApplicationStateActive) failCancelled()
    if (presentedSelection != null) fail(ProblemCode.CONFLICT)
    val host = presenter() ?: fail(ProblemCode.UNSUPPORTED_MODE)
    if (host.viewIfLoaded?.window == null || host.presentedViewController != null) fail(ProblemCode.CONFLICT)
    val selection = IosFileSelection(ownerId, isOwnerActive)
    onCreated(selection)
    requestsLocked { requests.put(ownerId, selection) }?.revoke()
    presentedSelection = selection
    try {
        val type = if (folder) UTTypeFolder else when (kind!!) {
            PickedFileKind.IMAGE -> UTTypeImage
            PickedFileKind.AUDIO -> UTTypeAudio
            PickedFileKind.LINKA, PickedFileKind.BACKUP -> UTTypeData
        }
        val controller = UIDocumentPickerViewController(forOpeningContentTypes = listOf(type), asCopy = false)
        controller.allowsMultipleSelection = false
        controller.delegate = selection
        try {
            selection.await(controller, host)
            selection
        } finally {
            // Cancellation also reaches this path; dismiss only our exact controller.
            withContext(NonCancellable + Dispatchers.Main) {
                controller.delegate = null
                if (controller.presentingViewController != null) controller.dismissViewControllerAnimated(false, null)
            }
        }
    } catch (failure: Throwable) {
        selection.close()
        throw failure
    } finally {
        if (presentedSelection === selection) presentedSelection = null
    }
}

internal class IosFileSelection(
    private val ownerId: String,
    private val ownerActive: (String) -> Boolean,
) : NSObject(), UIDocumentPickerDelegateProtocol {
    private val lock = NSRecursiveLock()
    private var closed = false
    private var active = true
    private var revoked = false
    private var selected: NSURL? = null
    private var scoped = false
    private var continuation: CancellableContinuation<Unit>? = null
    private var backgroundAction: (() -> Unit)? = null
    private val observers = mutableListOf<Any>()
    val url: NSURL get() = locked { selected ?: fail(ProblemCode.INVALID_DATA) }
    var onBackground: (() -> Unit)?
        get() = locked { backgroundAction }
        set(value) {
            val invoke = locked { backgroundAction = value; revoked }
            if (invoke) value?.invoke()
        }

    init {
        val center = NSNotificationCenter.defaultCenter
        observers += center.addObserverForName(UIApplicationWillResignActiveNotification, null, NSOperationQueue.mainQueue) {
            locked { active = false }
        }
        observers += center.addObserverForName(UIApplicationDidBecomeActiveNotification, null, NSOperationQueue.mainQueue) {
            locked { active = true }
        }
        observers += center.addObserverForName(UIApplicationDidEnterBackgroundNotification, null, NSOperationQueue.mainQueue) {
            revoke()
        }
    }

    fun current(): Boolean = locked { !closed && !revoked && active } &&
        requestsLocked { requests[ownerId] === this } && ownerActive(ownerId)

    fun revoke() {
        val action = locked { revoked = true; continuation?.cancel(); backgroundAction }
        action?.invoke()
    }

    suspend fun await(controller: UIDocumentPickerViewController, host: UIViewController) {
        suspendCancellableCoroutine<Unit> { pending ->
            locked { continuation = pending }
            host.presentViewController(controller, true, null)
        }
    }

    override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) {
        val pending = locked { continuation.also { continuation = null } } ?: return
        if (!pending.isActive || !current()) { pending.cancel(); return }
        val candidate = didPickDocumentsAtURLs.singleOrNull() as? NSURL
        if (candidate == null || !candidate.fileURL) { pending.resumeWith(Result.failure(FileFailure(problem(ProblemCode.INVALID_DATA)))); return }
        locked {
            selected = candidate
            scoped = candidate.startAccessingSecurityScopedResource()
        }
        pending.resume(Unit)
    }

    override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
        locked { continuation.also { continuation = null } }?.resumeWith(Result.failure(FileFailure(su.linka.pictures.application.OperationResult.Cancelled)))
    }

    fun close() {
        val acquired = locked {
            if (closed) return
            closed = true
            continuation?.cancel()
            continuation = null
            backgroundAction = null
            selected.takeIf { scoped }.also { selected = null; scoped = false }
        }
        acquired?.stopAccessingSecurityScopedResource()
        requestsLocked { if (requests[ownerId] === this) requests.remove(ownerId) }
        observers.forEach { NSNotificationCenter.defaultCenter.removeObserver(it) }
    }

    private inline fun <T> locked(block: () -> T): T {
        lock.lock()
        try { return block() } finally { lock.unlock() }
    }
}
