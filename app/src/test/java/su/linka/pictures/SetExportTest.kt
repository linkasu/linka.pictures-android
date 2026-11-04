package su.linka.pictures

import android.net.Uri
import android.app.Activity
import android.content.Intent
import android.content.DialogInterface
import android.os.Bundle
import android.widget.EditText
import androidx.activity.ComponentActivity
import androidx.preference.PreferenceManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.shadows.ShadowLooper
import su.linka.pictures.components.ComponentTestUtils
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.zip.ZipFile
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class SetExportTest {
    class ExportHost : ComponentActivity() {
        var successes = 0
        var errors = 0
        val exportController = SetExportController(this, { successes++ }, { errors++ })

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            exportController.restoreState(savedInstanceState)
        }

        override fun onSaveInstanceState(outState: Bundle) {
            exportController.saveState(outState)
            super.onSaveInstanceState(outState)
        }
    }

    @Test
    fun activityResultRegistryDeliversPickerResultToRecreatedHost() {
        val (context, source, workspace) = ComponentTestUtils.createSet()
        PreferenceManager.getDefaultSharedPreferences(context).edit().putBoolean("parent", false).commit()
        val host = Robolectric.buildActivity(ExportHost::class.java).setup()
        host.get().exportController.export("board.linka") { source }
        awaitHostWork(host.get().exportController)
        val request = shadowOf(host.get()).nextStartedActivityForResult
        val state = Bundle()
        host.saveInstanceState(state).pause().stop().destroy()
        val recreated = Robolectric.buildActivity(ExportHost::class.java).create(state).start().resume()
        val output = ByteArrayOutputStream()
        val uri = Uri.parse("content://exports/recreated")
        shadowOf(recreated.get().contentResolver).registerOutputStream(uri, output)
        recreated.get().activityResultRegistry.dispatchResult(request.requestCode, Activity.RESULT_OK, Intent().setData(uri))
        awaitWork { !recreated.get().exportController.isPending }
        assertEquals(1, recreated.get().successes)
        assertEquals(0, recreated.get().errors)
        assertTrue(output.size() > 0)
        assertFalse(recreated.get().exportController.isPending)
        recreated.pause().stop().destroy()
        workspace.deleteRecursively()
    }

    @Test
    fun enabledParentControlWithoutPasswordDeniesExportBeforeReadingSource() {
        val host = Robolectric.buildActivity(ComponentActivity::class.java).create()
        val preferences = PreferenceManager.getDefaultSharedPreferences(host.get())
        preferences.edit().putBoolean("parent", true).remove("parent_password").commit()
        var reads = 0
        var errors = 0
        val controller = SetExportController(host.get(), {}, { errors++ })
        host.start().resume()
        try {
            controller.export("board") { reads++; error("Source must not be read before authorization") }
            assertEquals(0, reads)
            assertEquals(1, errors)
            assertFalse(controller.isPending)
            assertNull(shadowOf(host.get()).nextStartedActivityForResult)
        } finally {
            preferences.edit().clear().commit()
            host.pause().stop().destroy()
        }
    }

    @Test
    fun parentGuardRunsBeforeSourceReadAndPreparation() {
        val (context, source, workspace) = ComponentTestUtils.createSet()
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        preferences.edit().putBoolean("parent", true).putString("parent_password", "secret").commit()
        val host = Robolectric.buildActivity(ComponentActivity::class.java).create()
        host.get().setTheme(R.style.AppTheme)
        var reads = 0
        var errors = 0
        val controller = SetExportController(host.get(), {}, { errors++ })
        host.start().resume()
        try {
            controller.export("board") { reads++; source }
            assertEquals(0, reads)
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            dialog.findViewById<EditText>(R.id.input_prompt).setText("wrong")
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).callOnClick()
            ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
            assertEquals(0, reads)
            assertEquals(1, errors)
            assertFalse(controller.isPending)
            controller.export("board") { reads++; source }
            val correctDialog = ShadowAlertDialog.getLatestAlertDialog()
            correctDialog.findViewById<EditText>(R.id.input_prompt).setText("secret")
            correctDialog.getButton(DialogInterface.BUTTON_POSITIVE).callOnClick()
            ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
            awaitHostWork(controller)
            assertEquals(1, reads)
            assertTrue(controller.isPending)
            controller.complete(null)
        } finally {
            preferences.edit().clear().commit()
            host.pause().stop().destroy()
            workspace.deleteRecursively()
        }
    }

    @Test
    fun snapshotReimportsFullMediaWithoutPreferencesOrOriginalChanges() {
        val (context, source, workspace) = ComponentTestUtils.createSet()
        val manager = SetsManager(context)
        File(workspace, "config.json").writeText("unchanged source config")
        File(workspace, "picture.png").writeBytes(byteArrayOf(1, 2, 3))
        File(workspace, "media").mkdir()
        File(workspace, "media/audio.3gp").writeBytes(byteArrayOf(4, 5, 6))
        File(workspace, "snapshot.json").writeText("private draft state")
        File(workspace, "settings.xml").writeText("parent password / telemetry consent")
        File(workspace, "orphan.png").writeText("unused")
        source.addCard(0, Card(1, "picture.png", "unsaved card", "media/audio.3gp", 0))
        source.getManifest().rows = 2
        val original = File(manager.getSetsDirectory(), "original.linka").apply { writeText("original") }
        source.getManifest().archiveFile = original
        val exported = manager.prepareExport(source)
        val importedArchive = File(manager.getSetsDirectory(), "exported.linka")
        try {
            ZipFile(exported).use { zip ->
                assertEquals(setOf("config.json", "picture.png", "media/audio.3gp"),
                    zip.entries().asSequence().map { it.name }.toSet())
            }
            exported.copyTo(importedArchive, overwrite = true)
            val imported = manager.getSet(importedArchive.name)
            try {
                assertEquals(2, imported.getManifest().rows)
                assertEquals("unsaved card", imported.getManifest().cards.single().title)
                assertArrayEquals(byteArrayOf(1, 2, 3), File(imported.getFolder(), "picture.png").readBytes())
                assertArrayEquals(byteArrayOf(4, 5, 6), File(imported.getFolder(), "media/audio.3gp").readBytes())
            } finally {
                imported.getFolder().deleteRecursively()
            }
            assertEquals("unchanged source config", File(workspace, "config.json").readText())
            assertEquals("original", original.readText())
            assertEquals(original, source.getManifest().archiveFile)
        } finally {
            exported.delete()
            importedArchive.delete()
            original.delete()
            workspace.deleteRecursively()
        }
    }

    @Test
    fun invalidOrMissingMediaLeavesNoPreparedArchiveAndDoesNotTouchSource() {
        val (context, source, workspace) = ComponentTestUtils.createSet()
        val root = File(context.cacheDir, "exports")
        val before = root.list()?.toSet().orEmpty()
        try {
            for (path in listOf("missing.png", "../outside.png", "nested/../config.json", "config.json", "..\\outside.png")) {
                source.getManifest().cards.clear()
                source.addCard(0, Card(1, path, "bad", null, 0))
                assertThrows(IOException::class.java) { SetsManager(context).prepareExport(source) }
                assertEquals(before, root.list()?.toSet().orEmpty())
                assertTrue(workspace.isDirectory)
            }
        } finally {
            workspace.deleteRecursively()
        }
    }

    @Test
    fun pendingExportSurvivesRecreationAndSuccessRequiresCompleteCopy() {
        val (context, source, workspace) = ComponentTestUtils.createSet()
        PreferenceManager.getDefaultSharedPreferences(context).edit().putBoolean("parent", false).commit()
        var successes = 0
        var error: Exception? = null
        val host = Robolectric.buildActivity(ComponentActivity::class.java).create()
        val controller = SetExportController(host.get(), { successes++ }, { error = it })
        host.start().resume()
        controller.export("board") { source }
        awaitHostWork(controller)
        assertTrue(controller.isPending)
        assertEquals("application/vnd.linka", shadowOf(host.get()).nextStartedActivityForResult.intent.type)
        val state = Bundle()
        controller.saveState(state)
        assertEquals(1, state.size())
        val token = state.getString("preparedSetExport")!!
        val archive = File(context.cacheDir, "exports/$token.linka")
        val expected = archive.readBytes()
        host.pause().stop().destroy()

        val restoredHost = Robolectric.buildActivity(ComponentActivity::class.java).create()
        val restored = SetExportController(restoredHost.get(), { successes++ }, { error = it })
        restored.restoreState(state)
        restoredHost.start().resume()
        val uri = Uri.parse("content://exports/success")
        val output = ByteArrayOutputStream()
        shadowOf(restoredHost.get().contentResolver).registerOutputStream(uri, output)
        restored.complete(uri)
        awaitWork { !restored.isPending }
        assertArrayEquals(expected, output.toByteArray())
        assertEquals(1, successes)
        assertNull(error)
        assertFalse(restored.isPending)
        assertFalse(archive.exists())
        assertTrue(workspace.isDirectory)
        restoredHost.pause().stop().destroy()
        workspace.deleteRecursively()
    }

    @Test
    fun cancellationAndWriteOrCloseFailureNeverReportSuccessAndCleanCache() {
        val (context, source, workspace) = ComponentTestUtils.createSet()
        PreferenceManager.getDefaultSharedPreferences(context).edit().putBoolean("parent", false).commit()
        val host = Robolectric.buildActivity(ComponentActivity::class.java).create()
        var successes = 0
        var cancellations = 0
        var failure: Exception? = null
        val controller = SetExportController(host.get(), { successes++ }, { failure = it }, { cancellations++ })
        host.start().resume()
        try {
            controller.export("board.linka") { source }
            awaitHostWork(controller)
            val state = Bundle().also(controller::saveState)
            val archive = File(context.cacheDir, "exports/${state.getString("preparedSetExport")}.linka")
            controller.complete(null)
            assertEquals(1, cancellations)
            assertFalse(archive.exists())
            for (failOnClose in listOf(false, true)) {
                controller.export("board.linka") { source }
                awaitHostWork(controller)
                val pending = Bundle().also(controller::saveState)
                val pendingFile = File(context.cacheDir, "exports/${pending.getString("preparedSetExport")}.linka")
                val uri = Uri.parse("content://exports/failure-$failOnClose")
                val expected = IOException("provider failure")
                shadowOf(host.get().contentResolver).registerOutputStream(uri, object : OutputStream() {
                    override fun write(value: Int) { if (!failOnClose) throw expected }
                    override fun close() { if (failOnClose) throw expected }
                })
                controller.complete(uri)
                awaitWork { !controller.isPending }
                assertEquals(expected.javaClass, failure?.javaClass)
                assertEquals(expected.message, failure?.message)
                assertFalse(pendingFile.exists())
                assertFalse(controller.isPending)
            }
            assertEquals(0, successes)
            assertTrue(workspace.isDirectory)
        } finally {
            host.pause().stop().destroy()
            workspace.deleteRecursively()
        }
    }

    @Test
    fun missingCacheAndInvalidRestoredTokenAreErrorsWithoutOpeningDestination() {
        val (context, source, workspace) = ComponentTestUtils.createSet()
        PreferenceManager.getDefaultSharedPreferences(context).edit().putBoolean("parent", false).commit()
        val host = Robolectric.buildActivity(ComponentActivity::class.java).create()
        var errors = 0
        var successes = 0
        val controller = SetExportController(host.get(), { successes++ }, { errors++ })
        host.start().resume()
        controller.export("board") { source }
        awaitHostWork(controller)
        val state = Bundle().also(controller::saveState)
        File(context.cacheDir, "exports/${state.getString("preparedSetExport")}.linka").delete()
        host.pause().stop().destroy()
        val restoredHost = Robolectric.buildActivity(ComponentActivity::class.java).create()
        val restored = SetExportController(restoredHost.get(), { successes++ }, { errors++ })
        restored.restoreState(state)
        assertEquals(1, errors)
        restoredHost.start().resume()
        val output = ByteArrayOutputStream()
        val uri = Uri.parse("content://exports/missing")
        shadowOf(restoredHost.get().contentResolver).registerOutputStream(uri, output)
        restored.complete(uri)
        awaitWork { !restored.isPending }
        assertEquals(2, errors)
        assertEquals(0, output.size())
        assertEquals(0, successes)
        assertFalse(restored.isPending)
        val invalidHost = Robolectric.buildActivity(ComponentActivity::class.java).create()
        val invalid = SetExportController(invalidHost.get(), { successes++ }, { errors++ })
        invalid.restoreState(Bundle().apply { putString("preparedSetExport", "../../outside") })
        assertEquals(3, errors)
        assertFalse(invalid.isPending)
        assertTrue(workspace.isDirectory)
        invalidHost.destroy()
        restoredHost.pause().stop().destroy()
        workspace.deleteRecursively()
    }

    @Test fun blockedSafWriteSurvivesRotationAndOnlyTheNewOwnerReceivesCompletionOnce() {
        val (context, source, workspace) = ComponentTestUtils.createSet()
        PreferenceManager.getDefaultSharedPreferences(context).edit().putBoolean("parent", false).commit()
        val host = Robolectric.buildActivity(ExportHost::class.java).setup()
        val old = host.get()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val output = ByteArrayOutputStream()
        val uri = Uri.parse("content://exports/blocked-rotation")
        shadowOf(context.contentResolver).registerOutputStream(uri, object : OutputStream() {
            override fun write(value: Int) {
                assertNotEquals(android.os.Looper.getMainLooper(), android.os.Looper.myLooper())
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS))
                output.write(value)
            }
        })
        try {
            old.exportController.export("board") { source }
            awaitHostWork(old.exportController)
            old.exportController.complete(uri)
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val saved = Bundle().also(old.exportController::saveState)
            assertTrue(saved.getBoolean("writingSetExport"))
            host.recreate()
            val current = host.get()
            assertNotSame(old, current)
            assertTrue(current.exportController.isPending)
            var repeatedReads = 0
            current.exportController.export("duplicate") { repeatedReads++; source }
            assertEquals(0, repeatedReads)
            release.countDown()
            awaitWork { current.successes == 1 }
            assertFalse(current.exportController.isPending)
            assertEquals(0, current.errors)
            assertEquals(0, old.successes)
            assertEquals(0, old.errors)
            assertTrue(output.size() > 0)
            assertTrue(File(context.cacheDir, "exports").listFiles().orEmpty().isEmpty())
            host.recreate()
            assertFalse(host.get().exportController.isPending)
            assertEquals(0, host.get().successes) // The retained result has already been consumed.
        } finally {
            release.countDown()
            host.pause().stop().destroy()
            workspace.deleteRecursively()
        }
    }

    @Test fun rotationDuringPreparationKeepsSingleFlightAndCleansAbandonedSnapshot() {
        val (context, source, workspace) = ComponentTestUtils.createSet()
        PreferenceManager.getDefaultSharedPreferences(context).edit().putBoolean("parent", false).commit()
        val host = Robolectric.buildActivity(ExportHost::class.java).setup()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            host.get().exportController.export("board") {
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS))
                source
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            host.recreate()
            assertTrue(host.get().exportController.isPending)
            var repeats = 0
            host.get().exportController.export("repeat") { repeats++; source }
            release.countDown()
            awaitWork { !host.get().exportController.isPending }
            awaitWork { File(context.cacheDir, "exports").listFiles().orEmpty().isEmpty() }
            assertEquals(0, repeats)
            assertEquals(0, host.get().successes)
            assertEquals(0, host.get().errors)
            assertNull(shadowOf(host.get()).nextStartedActivityForResult)
        } finally {
            release.countDown()
            host.pause().stop().destroy()
            workspace.deleteRecursively()
        }
    }

    @Test fun processDeathDuringWriteReportsInterruptionWithoutRetryOrPermanentPendingState() {
        val (context, source, workspace) = ComponentTestUtils.createSet()
        val archive = SetsManager(context).prepareExport(source)
        val saved = Bundle().apply {
            putString("preparedSetExport", archive.nameWithoutExtension)
            putBoolean("writingSetExport", true)
        }
        val host = Robolectric.buildActivity(ExportHost::class.java).setup(saved)
        try {
            awaitWork { host.get().errors == 1 }
            assertFalse(host.get().exportController.isPending)
            assertEquals(0, host.get().successes)
            assertFalse(archive.exists())
            assertNull(shadowOf(host.get()).nextStartedActivityForResult)
            host.recreate()
            assertFalse(host.get().exportController.isPending)
            assertEquals(0, host.get().errors)
        } finally {
            host.pause().stop().destroy()
            workspace.deleteRecursively()
        }
    }
}
