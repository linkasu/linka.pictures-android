package su.linka.pictures.platform.audio

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import okio.Sink
import okio.Source
import su.linka.pictures.application.OperationResult
import su.linka.pictures.application.ports.*

/** Fresh directory in the self-instrumenting test UID; never normal pictures app data. */
internal class OwnedAudioTestFiles : PlatformFilesPort, AutoCloseable {
    val root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "p06-test-${UUID.randomUUID()}").also { check(it.mkdir()) }
    val created = mutableListOf<TemporaryFile>()
    val discarded = mutableListOf<TemporaryFile>()
    var initialBytes: ByteArray? = null
    override fun createTemporaryFile(suggestedName: String, mediaType: String): OperationResult<TemporaryFile> {
        val file = File(root, UUID.randomUUID().toString())
        check(file.createNewFile())
        initialBytes?.let(file::writeBytes)
        return OperationResult.Completed(TemporaryFile(file.path, suggestedName, mediaType).also { created += it })
    }
    fun file(bytes: ByteArray, hint: String = "wrong/type"): LocalFile {
        val value = (createTemporaryFile("fixture.not-media", hint) as OperationResult.Completed).value
        File(value.path).writeBytes(bytes)
        return value
    }
    override fun discard(file: TemporaryFile) {
        check(File(file.path).parentFile?.canonicalFile == root.canonicalFile)
        check(File(file.path).delete() || !File(file.path).exists())
        discarded += file
    }
    override fun close() { root.walkBottomUp().forEach { check(it.delete() || !it.exists()) } }
    override suspend fun pick(kind: PickedFileKind, ownerId: String): OperationResult<TemporaryFile> = error("Not used")
    override suspend fun chooseDestination(suggestedName: String, mediaType: String, ownerId: String): OperationResult<ExportDestination> = error("Not used")
    override fun source(file: LocalFile): OperationResult<Source> = error("Not used")
    override fun sink(file: TemporaryFile): OperationResult<Sink> = error("Not used")
}
