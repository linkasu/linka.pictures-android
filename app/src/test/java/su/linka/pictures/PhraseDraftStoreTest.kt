package su.linka.pictures

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class PhraseDraftStoreTest {
    @Test
    fun newOwnerRestoresFullOrderedSequenceAndRejectsOtherDocumentWithoutDeletingActiveDrafts() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = PhraseDraftStore(context)
        val sequence = IntArray(20_000) { (it * 7 + it / 3) % 17 }
        val token = store.capture(null, "document-a", sequence)
        val other = store.capture(null, "document-b", intArrayOf(3, 1))
        try {
            val newOwner = PhraseDraftStore(context)
            assertArrayEquals(sequence, newOwner.restore(token, "document-a"))
            assertNull(newOwner.restore(token, "document-b"))
            assertArrayEquals(sequence, newOwner.restore(token, "document-a"))
            val file = File(context.noBackupFilesDir, "phrase-drafts/$token.bin")
            assertTrue(newOwner.owns(token, file))
            assertFalse(newOwner.owns(token, File(context.filesDir, "foreign")))
            store.discard(other)
            assertArrayEquals(sequence, newOwner.restore(token, "document-a"))
            assertEquals(token, newOwner.capture(token, "document-a", intArrayOf(4, 2, 4)))
            assertArrayEquals(intArrayOf(4, 2, 4), store.restore(token, "document-a"))
        } finally {
            store.discard(token)
            store.discard(other)
        }
    }

    @Test
    fun rejectsInvalidTokenAndTruncatedSnapshot() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = PhraseDraftStore(context)
        assertNull(store.restore("../../foreign", "document"))
        assertThrows(IllegalArgumentException::class.java) { store.discard("../../foreign") }
        val token = store.capture(null, "document", intArrayOf(1, 2, 3))
        try {
            val file = File(context.noBackupFilesDir, "phrase-drafts/$token.bin")
            file.writeBytes(file.readBytes().dropLast(1).toByteArray())
            assertNull(store.restore(token, "document"))
        } finally {
            store.discard(token)
        }
    }
}
