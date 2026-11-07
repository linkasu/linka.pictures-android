package su.linka.pictures.ui.app

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProductDialogLayoutTest {
    @Test fun shortKeyboardViewportCannotGiveAllHeightToPinnedTitleAndActions() {
        assertTrue(productDialogUsesWholeScroll(181f, 1f))
        assertTrue(productDialogUsesWholeScroll(300f, 2f))
        assertFalse(productDialogUsesWholeScroll(600f, 2f))
        assertFalse(productDialogUsesWholeScroll(480f, 2f))
    }
}
