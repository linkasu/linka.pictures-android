package su.linka.pictures.platform.files

import android.content.*
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.*
import android.provider.OpenableColumns
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import okio.*
import okio.Path.Companion.toPath
import su.linka.pictures.application.*
import su.linka.pictures.application.ports.*
import su.linka.pictures.domain.RequestId

internal class FilesTestRig {
    val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
    val root = File(app.cacheDir, "p06-files-${UUID.randomUUID()}").also { check(it.mkdir()) }
    val temp = File(root, "temporary").path.toPath()
    val media = File(root, "media").path.toPath()
    val active = AtomicBoolean(true)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    val deleted = kotlinx.coroutines.channels.Channel<Path>(kotlinx.coroutines.channels.Channel.UNLIMITED)
    @Volatile var failDelete: Path? = null
    val fs = object : ForwardingFileSystem(FileSystem.SYSTEM) {
        override fun onPathParameter(path: Path, functionName: String, parameterName: String): Path {
            check(Looper.myLooper() != Looper.getMainLooper()) { "Physical FS on Main" }
            return path
        }
        override fun delete(path: Path, mustExist: Boolean) {
            if (path == failDelete) throw java.io.IOException("controlled unlink fault")
            super.delete(path, mustExist); deleted.trySend(path)
        }
    }
    val provider = Provider(File(root, "document").also { it.writeBytes("provider-source".toByteArray()) })
    val intents = mutableListOf<Intent>()
    val ids = mutableListOf<RequestId>()
    var requester: suspend (Intent, RequestId, String) -> OperationResult<Uri> = { _, _, _ -> OperationResult.Completed(provider.uri) }
    val port: AndroidPlatformFilesPort
    init {
        provider.attachInfo(app, ProviderInfo().also { it.authority = "p06.files.test"; it.exported = false })
        val resolver = if (Build.VERSION.SDK_INT >= 29) ContentResolver.wrap(provider) else {
            // Public legacy framework testing API. No hidden-API reflection or packaged SDK dependency.
            val type = Class.forName("android.test.mock.MockContentResolver")
            val value = type.getConstructor(Context::class.java).newInstance(app)
            type.getMethod("addProvider", String::class.java, ContentProvider::class.java).invoke(value, "p06.files.test", provider)
            value as ContentResolver
        }
        val context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getContentResolver(): ContentResolver = resolver
        }
        port = AndroidPlatformFilesPort(context, temp, media, fs, { intent, id, owner ->
            check(Looper.myLooper() == Looper.getMainLooper())
            intents += Intent(intent); ids += id
            requester(intent, id, owner)
        }, { active.get() }, scope)
    }
    suspend fun temporary(bytes: ByteArray): TemporaryFile = withContext(Dispatchers.IO) {
        val file = NativeFileRules.value(port.createTemporaryFile("own data.bin", "application/octet-stream"))
        NativeFileRules.value(port.sink(file)).buffer().use { it.write(bytes) }
        file
    }
    suspend fun immutable(bytes: ByteArray): LocalFile = withContext(Dispatchers.IO) {
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
        val file = media / hash.take(2) / hash
        fs.createDirectories(file.parent!!); fs.write(file) { write(bytes) }; LocalFile(file.toString())
    }
    suspend fun close() {
        scope.coroutineContext.job.cancelAndJoin()
        withContext(Dispatchers.IO) { root.walkBottomUp().forEach { check(it.delete() || !it.exists()) } }
    }
    class Provider(val file: File) : ContentProvider() {
        val uri = Uri.parse("content://p06.files.test/document")
        var name = "selected.bin"
        var mime = "application/octet-stream"
        var queryClosed = 0
        var reads = 0
        var writes = 0
        var deletes = 0
        var onOpen: ((String) -> Unit)? = null
        val modes = mutableListOf<String>()
        val closed = kotlinx.coroutines.channels.Channel<String>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        override fun onCreate() = true
        override fun getType(uri: Uri) = mime
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor =
            object : MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME)) {
                override fun close() { queryClosed++; super.close() }
            }.also { it.addRow(arrayOf(name)) }
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            check(Looper.myLooper() != Looper.getMainLooper())
            if (mode == "r") reads++ else writes++
            modes += mode; onOpen?.invoke(mode)
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode), Handler(Looper.getMainLooper())) { closed.trySend(mode) }
        }
        override fun insert(uri: Uri, values: ContentValues?): Uri = error("No insert")
        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = error("No update")
        override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int { deletes++; error("Provider documents must never be deleted") }
    }
}
