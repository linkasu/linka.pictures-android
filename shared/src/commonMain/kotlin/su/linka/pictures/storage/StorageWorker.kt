package su.linka.pictures.storage

import kotlinx.coroutines.*
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.suspendCoroutine

private class StorageCaller(val job: Job?) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<StorageCaller>
}

internal fun CoroutineContext.ensureStorageActive() {
    ensureActive()
    this[StorageCaller]?.job?.ensureActive()
}

internal suspend fun storageActive() = currentCoroutineContext().ensureStorageActive()

internal suspend fun <T> storageWorker(block: suspend () -> T): T {
    val context = currentCoroutineContext()
    if (context[StorageCaller] != null && context[ContinuationInterceptor] === Dispatchers.IO) return block()
    val original = context[StorageCaller]?.job ?: context[Job]
    // An atomic continuation avoids withContext's prompt-cancellation loss of a durable result.
    // Cancellation is checked explicitly before mutation/commit and during streamed IO instead.
    return suspendCoroutine { result ->
        CoroutineScope(Dispatchers.IO + NonCancellable + StorageCaller(original)).launch {
            result.resumeWith(runCatching { block() })
        }
    }
}

internal fun storageCleanup(block: () -> Unit, finished: () -> Unit) {
    try {
        CoroutineScope(Dispatchers.IO + NonCancellable).launch {
            try { runCatching(block) } finally { finished() }
        }
    } catch (error: Throwable) { finished(); throw error }
}
