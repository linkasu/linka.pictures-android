package su.linka.pictures.activity

import android.app.AlertDialog
import android.content.Context
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.database.MatrixCursor
import android.net.Uri
import android.os.Looper
import android.provider.OpenableColumns
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.shadows.ShadowToast
import su.linka.pictures.R
import su.linka.pictures.SetsManager
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class SetReplacementConfirmationTest {
    private class DisplayNameProvider(private val displayName: String?) : ContentProvider() {
        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
            selectionArgs: Array<out String>?, sortOrder: String?) =
            MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME)).apply { addRow(arrayOf(displayName)) }
        override fun getType(uri: Uri) = "application/octet-stream"
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    }

    private fun registerDocument(context: Context, uri: Uri, displayName: String?) {
        ShadowContentResolver.registerProviderInternal(uri.authority, DisplayNameProvider(displayName))
        shadowOf(context.contentResolver).registerInputStream(uri, archive(2).inputStream())
    }

    private fun archive(rows: Int): ByteArray = ByteArrayOutputStream().also { output ->
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("config.json"))
            zip.write("""{"rows":$rows}""".toByteArray())
            zip.closeEntry()
        }
    }.toByteArray()

    @Test fun importNeedsSeparateConfirmationAndCancelPreservesOldBytes() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = SetsManager(context)
        val old = archive(3)
        val destination = manager.importSet(old.inputStream(), "same.linka")
        val incoming = File(context.cacheDir, "same.linka").apply { writeBytes(archive(2)) }
        for (confirm in listOf(false, true)) {
            val intent = Intent(context, BroadcastReceiverActivity::class.java)
                .setAction(Intent.ACTION_VIEW).setData(Uri.fromFile(incoming))
            val controller = Robolectric.buildActivity(BroadcastReceiverActivity::class.java, intent).setup()
            try {
                ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                shadowOf(Looper.getMainLooper()).idle()
                val replacementDialog = ShadowAlertDialog.getLatestAlertDialog()
                assertEquals(context.getString(R.string.confirm_replace_set),
                    org.robolectric.Shadows.shadowOf(replacementDialog).title.toString())
                assertArrayEquals(old, destination.readBytes())
                replacementDialog.getButton(if (confirm) AlertDialog.BUTTON_POSITIVE else AlertDialog.BUTTON_NEGATIVE).performClick()
                shadowOf(Looper.getMainLooper()).idle()
                assertArrayEquals(if (confirm) incoming.readBytes() else old, destination.readBytes())
                assertTrue(controller.get().isFinishing)
            } finally { controller.pause().stop().destroy() }
        }
    }

    @Test fun newEditorCancelDoesNotReplaceAndConfirmationRetriesSave() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = SetsManager(context)
        val old = archive(3)
        val destination = manager.importSet(old.inputStream(), "same.linka")
        val intent = Intent(context, SetEditActivity::class.java).putExtra(SetEditActivity.EXTRA_FILE, "same.linka")
        val controller = Robolectric.buildActivity(SetEditActivity::class.java, intent).setup()
        try {
            val activity = controller.get()
            val fresh = manager.createSet().apply { getManifest().rows = 2 }
            SetEditActivity::class.java.getDeclaredField("set").apply { isAccessible = true }.set(activity, fresh)
            val save = SetEditActivity::class.java.getDeclaredMethod("save").apply { isAccessible = true }
            for (confirm in listOf(false, true)) {
                save.invoke(activity)
                ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                shadowOf(Looper.getMainLooper()).idle()
                val replacementDialog = ShadowAlertDialog.getLatestAlertDialog()
                assertEquals(context.getString(R.string.confirm_replace_set),
                    org.robolectric.Shadows.shadowOf(replacementDialog).title.toString())
                assertArrayEquals(old, destination.readBytes())
                replacementDialog.getButton(if (confirm) AlertDialog.BUTTON_POSITIVE else AlertDialog.BUTTON_NEGATIVE).performClick()
                shadowOf(Looper.getMainLooper()).idle()
                if (confirm) {
                    assertEquals(2, manager.getSetManifest("same.linka").rows)
                    assertTrue(activity.isFinishing)
                } else {
                    assertArrayEquals(old, destination.readBytes())
                    assertNull(fresh.getManifest().archiveFile)
                    assertFalse(activity.isFinishing)
                }
            }
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun opaqueDocumentIdUsesUntrustedDisplayNameAsFileName() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val uri = Uri.parse("content://test.documents/document/primary%3ADownload%2Ffoo.linka")
        registerDocument(context, uri, "My board.linka")
        val intent = Intent(Intent.ACTION_VIEW, uri).setClass(context, BroadcastReceiverActivity::class.java)
        val controller = Robolectric.buildActivity(BroadcastReceiverActivity::class.java, intent).setup()
        try {
            ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(2, SetsManager(context).getSetManifest("My board.linka").rows)
            assertTrue(controller.get().isFinishing)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun maliciousDisplayNameCannotEscapeOrFallBackToSafeUriSegment() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = SetsManager(context)
        val outside = File(context.filesDir, "outside.linka").apply { writeText("original") }
        val uri = Uri.parse("content://test.documents/safe.linka")
        for (name in listOf("../outside.linka", "/outside.linka", "folder/board.linka", "..\\outside.linka", "")) {
            registerDocument(context, uri, name)
            val intent = Intent(Intent.ACTION_VIEW, uri).setClass(context, BroadcastReceiverActivity::class.java)
            val controller = Robolectric.buildActivity(BroadcastReceiverActivity::class.java, intent).setup()
            try {
                ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                shadowOf(Looper.getMainLooper()).idle()
                assertEquals("original", outside.readText())
                assertTrue(manager.getSetsDirectory().listFiles()!!.isEmpty())
                assertEquals(context.getString(R.string.set_open_error), ShadowToast.getTextOfLatestToast())
                assertTrue(controller.get().isFinishing)
            } finally { controller.pause().stop().destroy() }
        }
    }

    @Test fun missingDisplayNameFallsBackToLastSegment() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val uri = Uri.parse("content://test.documents/fallback.linka")
        registerDocument(context, uri, null)
        val intent = Intent(Intent.ACTION_VIEW, uri).setClass(context, BroadcastReceiverActivity::class.java)
        val controller = Robolectric.buildActivity(BroadcastReceiverActivity::class.java, intent).setup()
        try {
            ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(2, SetsManager(context).getSetManifest("fallback.linka").rows)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun replacementConfirmationCannotBeRetargetedByProviderNameChange() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = SetsManager(context)
        manager.importSet(archive(3).inputStream(), "confirmed.linka")
        val other = manager.importSet(archive(3).inputStream(), "other.linka")
        val originalOther = other.readBytes()
        val uri = Uri.parse("content://test.documents/opaque")
        registerDocument(context, uri, "confirmed.linka")
        val intent = Intent(Intent.ACTION_VIEW, uri).setClass(context, BroadcastReceiverActivity::class.java)
        val controller = Robolectric.buildActivity(BroadcastReceiverActivity::class.java, intent).setup()
        try {
            ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            registerDocument(context, uri, "other.linka")
            ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(2, manager.getSetManifest("confirmed.linka").rows)
            assertArrayEquals(originalOther, other.readBytes())
        } finally { controller.pause().stop().destroy() }
    }
}
