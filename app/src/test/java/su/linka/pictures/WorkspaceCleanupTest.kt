package su.linka.pictures

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class WorkspaceCleanupTest {
    @Test fun startupRemovesOnlyScratchAndNeverFollowsItsSymlinks() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scratch = File(context.cacheDir, "workspace/${UUID.randomUUID()}").apply { mkdirs() }
        File(scratch, "partial").writeText("scratch")
        val durable = File(context.noBackupFilesDir, "editor-drafts/${UUID.randomUUID()}").apply { mkdirs() }
        val phrase = File(context.noBackupFilesDir, "phrase-drafts/${UUID.randomUUID()}").apply {
            parentFile!!.mkdirs(); writeText("phrase")
        }
        val marker = File(durable, "keep").apply { writeText("draft") }
        val foreign = File(scratch.parentFile, "unowned").apply { mkdirs() }
        File(foreign, "keep").writeText("foreign")
        Files.createSymbolicLink(File(scratch, "linked-draft").toPath(), durable.toPath())
        try {
            val cleanup = WorkspaceCleanup.onProcessStart(context)
            val current = SetsManager(context).createSet()
            cleanup?.join(5_000)
            assertFalse(cleanup?.isAlive == true)
            assertFalse(scratch.exists())
            assertEquals("draft", marker.readText())
            assertEquals("phrase", phrase.readText())
            assertEquals("foreign", File(foreign, "keep").readText())
            try { assertTrue(current.getFolder().isDirectory) } finally { current.close() }
        } finally {
            durable.deleteRecursively()
            phrase.delete()
            foreign.deleteRecursively()
        }
    }
}
