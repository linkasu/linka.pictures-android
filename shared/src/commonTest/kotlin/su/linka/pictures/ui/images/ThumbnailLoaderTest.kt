package su.linka.pictures.ui.images

import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okio.Source
import su.linka.pictures.application.*
import su.linka.pictures.application.ports.*
import su.linka.pictures.domain.*

class ThumbnailLoaderTest {
    private val id = AssetId("a".repeat(64))
    private class Media : MediaStore {
        var open = 0
        override suspend fun acquire(assets: Set<AssetId>): OperationResult<MediaLease> {
            open++
            return OperationResult.Completed(object : MediaLease {
                override val assets = assets
                override fun retain(assets: Set<AssetId>): OperationResult<MediaLease> = error("Not used")
                override fun close() { open-- }
            })
        }
        override fun file(id: AssetId, lease: MediaLease) = OperationResult.Completed(LocalFile("/owned/synthetic-image"))
        override suspend fun prepare(source: Source, kind: MediaKind, mediaType: String): OperationResult<PreparedMedia> = error("Not used")
        override suspend fun metadata(id: AssetId): OperationResult<MediaAsset> = error("Not used")
        override fun source(id: AssetId, lease: MediaLease): OperationResult<Source> = error("Not used")
        override suspend fun collectGarbage(): OperationResult<GarbageCollectionReport> = error("Not used")
    }

    @Test fun pageAndMessagePreviewPixelsHaveAThirtyTwoMiBBudget() {
        assertEquals(256, thumbnailSide(0))
        for (count in 1..8192) {
            val side = assertNotNull(thumbnailSide(count))
            assertTrue(side in 32..256)
            assertTrue(count.toLong() * side * side * 4 <= 32L * 1024 * 1024)
        }
        assertNull(thumbnailSide(8193))
        assertNull(thumbnailSide(-1))
    }

    @Test fun failedNativeDecodeReportsTheErrorAndReleasesTheBorrowedFileLease() = runTest {
        val media = Media()
        val job = SupervisorJob()
        val loader = ThumbnailLoader(media, { _, _ -> OperationResult.Failed(Problem(ProblemCode.INVALID_DATA)) },
            CoroutineScope(coroutineContext + job))
        try {
            loader.request("window", setOf(id))
            assertEquals(ProblemCode.INVALID_DATA, loader.problem.first { it != null }!!.code)
        } finally { job.cancelAndJoin() }
        assertEquals(0, media.open)
        assertTrue(loader.images.value.isEmpty())
    }

    @Test fun disappearingWindowCancelsDecoderAndClosesItsLeaseWithoutPublishingLatePixels() = runTest {
        val media = Media()
        val job = SupervisorJob()
        val started = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        val loader = ThumbnailLoader(media, { _, _ ->
            started.complete(Unit)
            try { awaitCancellation() } finally { stopped.complete(Unit) }
        }, CoroutineScope(coroutineContext + job))
        try {
            loader.request("window", setOf(id))
            started.await()
            loader.remove("window")
            stopped.await()
        } finally { job.cancelAndJoin() }
        assertEquals(0, media.open)
        assertTrue(loader.images.value.isEmpty())
    }
}
