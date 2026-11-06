package su.linka.pictures.bootstrap

import android.content.Intent
import android.net.Uri
import org.junit.Assert.*
import org.junit.Test

class AndroidIncomingDocumentTest {
    @Test fun realWildcardArchivePickerCanConsumeIncomingContentButOtherRequestsCannot() {
        for (mime in listOf("*/*", "application/octet-stream", "application/zip"))
            assertTrue(acceptsIncomingArchive(Intent(Intent.ACTION_OPEN_DOCUMENT).setType(mime)))
        for (mime in listOf("image/*", "audio/*", "text/plain"))
            assertFalse(acceptsIncomingArchive(Intent(Intent.ACTION_OPEN_DOCUMENT).setType(mime)))
        for (action in listOf(Intent.ACTION_CREATE_DOCUMENT, Intent.ACTION_VIEW, Intent.ACTION_OPEN_DOCUMENT_TREE))
            assertFalse(acceptsIncomingArchive(Intent(action).setType("*/*")))
    }

    @Test fun ingressDoesNotPromiseAFileUriThatTheNativeDocumentReaderCannotOpen() {
        assertTrue(acceptsIncomingDocument(Uri.parse("content://owned-test-fixture/board.linka")))
        for (uri in listOf("file:///owned-test-fixture/board.linka", "https://example.invalid/board.linka", "/board.linka"))
            assertFalse(acceptsIncomingDocument(Uri.parse(uri)))
    }
}
