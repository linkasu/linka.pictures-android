package su.linka.pictures.bootstrap

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.runner.AndroidJUnitRunner
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.*
import okio.*
import okio.Path.Companion.toPath
import su.linka.pictures.application.OperationResult
import su.linka.pictures.application.ports.IdSource
import su.linka.pictures.platform.capabilities.AndroidMediaInspectionPort
import su.linka.pictures.platform.files.AndroidPlatformFilesPort

/** Explicit host-orchestrated process-death checks; never part of the ordinary JUnit suite. */
class LibraryTestRunner : AndroidJUnitRunner() {
    private var processArguments: Bundle? = null
    override fun onCreate(arguments: Bundle) {
        if (arguments.containsKey("migrationAction")) processArguments = Bundle(arguments)
        super.onCreate(arguments)
    }

    override fun onStart() {
        if (processArguments == null) { super.onStart(); return }
        try {
            runBlocking { verify() }
            finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "PASS: independent migration reopen after real process termination") })
        } catch (error: Throwable) {
            finish(Activity.RESULT_CANCELED, Bundle().apply { putString("stream", "FAIL: ${error::class.java.simpleName}") })
        }
    }

    private suspend fun verify() {
        val arguments = checkNotNull(processArguments)
        val token = requireNotNull(arguments.getString("token"))
        require(token.matches(Regex("[a-z0-9-]{1,80}")))
        val checkpoint = requireNotNull(arguments.getString("checkpoint"))
        require(checkpoint in setOf("journal", "credential.json", "active"))
        val action = requireNotNull(arguments.getString("migrationAction"))
        require(action in setOf("pause", "resume"))
        val fixture = File(targetContext.filesDir, "process-migration-$token")
        if (action == "pause") check(fixture.mkdir()) else check(fixture.isDirectory)
        val context = object : ContextWrapper(targetContext) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = File(fixture, "files").also { check(it.isDirectory || it.mkdir()) }
            override fun getNoBackupFilesDir() = File(fixture, "credentials").also { check(it.isDirectory || it.mkdir()) }
            override fun getCacheDir() = File(fixture, "cache").also { check(it.isDirectory || it.mkdir()) }
            override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("process-$token-$name", mode)
        }
        val original = ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry("config.json").apply { time = 0 })
                zip.write("""{"version":"1.3","rows":1,"columns":1,"cards":[{"id":1,"cardType":0,"title":"synthetic"}]}""".toByteArray())
                zip.closeEntry()
            }
        }.toByteArray()
        val source = File(context.filesDir, "sets/source.linka")
        if (action == "pause") { check(source.parentFile!!.mkdir()); source.writeBytes(original) }
        val system = FileSystem.SYSTEM
        val fs = object : ForwardingFileSystem(system) {
            private var paused = false
            override fun atomicMove(source: Path, target: Path) {
                super.atomicMove(source, target)
                if (action == "pause" && !paused && target.name == checkpoint) {
                    paused = true
                    File(fixture, "ready").writeText(checkpoint)
                    sendStatus(1, Bundle().apply { putString("stream", "READY: $checkpoint") })
                    CountDownLatch(1).await() // The host terminates this test-only process at the durable checkpoint.
                }
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var opened: su.linka.pictures.migration.LegacyOpenedLibrary? = null
        try {
            val admission = AndroidLibraryAdmission(context, fs, BundledSQLiteDriver(),
                IdSource { UUID.randomUUID().toString() }, AndroidMediaInspectionPort()) { generation ->
                AndroidPlatformFilesPort(context, context.cacheDir.path.toPath(), generation / "media", fs,
                    { _, _, _ -> error("No picker during migration") }, { false }, scope)
            }
            val result = admission.open(resume = action == "resume")
            check(result is OperationResult.Completed)
            opened = result.value
            check(action == "resume") { "Checkpoint was not reached" }
            check(opened.runtime.library.listing.value.items.size == 1)
            check(opened.report.imported == 1 && opened.report.retained == 0)
            check(source.readBytes().contentEquals(original))
            check(File(context.filesDir, "pokazhi-v1/active").isFile)
            check(File(context.noBackupFilesDir, "pokazhi-assistant/credential.json").isFile)
        } finally {
            withContext(NonCancellable) { opened?.runtime?.close(); scope.coroutineContext.job.cancelAndJoin() }
        }
        check(fixture.deleteRecursively())
    }
}
