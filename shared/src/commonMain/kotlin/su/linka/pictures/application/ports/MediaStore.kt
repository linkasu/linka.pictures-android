package su.linka.pictures.application.ports

import okio.Source
import su.linka.pictures.application.OperationResult
import su.linka.pictures.domain.AssetId
import su.linka.pictures.domain.MediaAsset
import su.linka.pictures.domain.MediaKind

interface MediaLease : AutoCloseable {
    val assets: Set<AssetId>
    fun retain(assets: Set<AssetId>): OperationResult<MediaLease>
}

/** Borrowed proof only: storage still authenticates the leaf's owner, asset and liveness. */
internal interface DelegatingMediaLease : MediaLease {
    fun readProof(id: AssetId): MediaLease?
}

internal fun MediaLease.readProof(id: AssetId): MediaLease? =
    if (this is DelegatingMediaLease) readProof(id) else this

data class PreparedMedia(val asset: MediaAsset, val lease: MediaLease)
data class GarbageCollectionReport(val removedAssets: Int, val removedBytes: Long, val failedAssets: Int)

interface MediaStore {
    // Consumes/closes source; publication still requires a library/draft transaction.
    suspend fun prepare(source: Source, kind: MediaKind, mediaType: String): OperationResult<PreparedMedia>
    suspend fun acquire(assets: Set<AssetId>): OperationResult<MediaLease>
    suspend fun metadata(id: AssetId): OperationResult<MediaAsset>
    fun file(id: AssetId, lease: MediaLease): OperationResult<LocalFile>
    fun source(id: AssetId, lease: MediaLease): OperationResult<Source>
    suspend fun collectGarbage(): OperationResult<GarbageCollectionReport>
}
