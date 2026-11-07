package su.linka.pictures.ui.app

import kotlin.test.*

class WindowPresentationTest {
    @Test fun inspectorRequiresEnoughWidthAndHeight() {
        assertTrue(editorUsesInspector(900f, 480f, 1f))
        assertTrue(editorUsesInspector(1280f, 720f, 1f))
        assertFalse(editorUsesInspector(899f, 720f, 1f))
        assertFalse(editorUsesInspector(1280f, 479f, 1f))
        assertFalse(editorUsesInspector(900f, 480f, 1.5f))
        assertTrue(editorUsesInspector(1350f, 720f, 1.5f))
        assertTrue(editorUsesInspector(900f, 480f, 0.8f))
    }
}
