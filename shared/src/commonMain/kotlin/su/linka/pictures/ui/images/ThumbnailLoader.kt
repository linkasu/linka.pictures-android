package su.linka.pictures.ui.images

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import su.linka.pictures.application.*
import su.linka.pictures.application.ports.*
import su.linka.pictures.domain.AssetId
import kotlin.math.sqrt

/** Root-owned previews only; canonical files are borrowed through a lease and never transformed in place. */
class ThumbnailLoader(
    private val media: MediaStore,
    private val decode: suspend (LocalFile, Int) -> OperationResult<ImageBitmap>,
    scope: CoroutineScope,
) {
    private val requests = MutableStateFlow<Map<String, Set<AssetId>>>(emptyMap())
    private val mutableImages = MutableStateFlow<Map<AssetId, ImageBitmap>>(emptyMap())
    val images: StateFlow<Map<AssetId, ImageBitmap>> = mutableImages.asStateFlow()
    private val mutableProblem = MutableStateFlow<Problem?>(null)
    val problem: StateFlow<Problem?> = mutableProblem.asStateFlow()

    init {
        scope.launch {
            requests.map { owners -> owners.values.flatMap { it }.toSet() }.distinctUntilChanged().collectLatest { needed ->
                try { withContext(Dispatchers.IO) {
                    mutableProblem.value = null
                    val side = thumbnailSide(needed.size)
                    if (side == null) {
                        mutableImages.value = emptyMap()
                        mutableProblem.value = Problem(ProblemCode.LIMIT_EXCEEDED)
                        return@withContext
                    }
                    mutableImages.value = mutableImages.value.filter { (id, bitmap) ->
                        id in needed && bitmap.width <= side && bitmap.height <= side
                    }
                    if (needed.isEmpty()) return@withContext
                    val lease = when (val acquired = media.acquire(needed)) {
                        is OperationResult.Completed -> acquired.value
                        is OperationResult.Failed -> { mutableProblem.value = acquired.problem; return@withContext }
                        else -> { mutableProblem.value = Problem(ProblemCode.IO_FAILURE); return@withContext }
                    }
                    try {
                        for (id in needed) {
                            currentCoroutineContext().ensureActive()
                            if (id in mutableImages.value) continue
                            val file = media.file(id, lease)
                            val result: OperationResult<ImageBitmap> = when (file) {
                                is OperationResult.Completed -> decode(file.value, side)
                                is OperationResult.Failed -> file
                                else -> OperationResult.Failed(Problem(ProblemCode.IO_FAILURE))
                            }
                            currentCoroutineContext().ensureActive()
                            when (result) {
                                is OperationResult.Completed -> {
                                    val bitmap = result.value
                                    if (bitmap.width !in 1..side || bitmap.height !in 1..side || bitmap.config != ImageBitmapConfig.Argb8888)
                                        mutableProblem.value = Problem(ProblemCode.LIMIT_EXCEEDED)
                                    else mutableImages.value = mutableImages.value + (id to bitmap)
                                }
                                is OperationResult.Failed -> mutableProblem.value = result.problem
                                else -> mutableProblem.value = Problem(ProblemCode.IO_FAILURE)
                            }
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { mutableProblem.value = Problem(ProblemCode.IO_FAILURE) }
                    finally { try { lease.close() } catch (_: Exception) { mutableProblem.value = Problem(ProblemCode.IO_FAILURE) } }
                } } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { mutableProblem.value = Problem(ProblemCode.IO_FAILURE) }
            }
        }
    }

    fun request(ownerId: String, assets: Set<AssetId>) { requests.update { it + (ownerId to assets.toSet()) } }
    fun remove(ownerId: String) { requests.update { it - ownerId } }
}

// shortcut: fixed 32 MiB preview-pixel cap (not RSS); calibrate on minimum physical devices before G6.
internal fun thumbnailSide(count: Int): Int? = when {
    count !in 0..8192 -> null
    count == 0 -> 256
    else -> minOf(256, sqrt((32L * 1024 * 1024 / 4 / count).toDouble()).toInt())
}
