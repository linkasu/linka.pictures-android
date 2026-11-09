package su.linka.pictures.ui.app

import kotlin.test.*

class ProductBackTest {
    @Test fun startupAndEveryTransitionConsumeBackIncludingLibrary() {
        (ProductPage.entries + listOf(null)).forEach { page ->
            assertEquals(ProductBackAction.CONSUME, productBackAction(false, false, page, false))
            assertEquals(ProductBackAction.CONSUME, productBackAction(true, true, page, false))
        }
    }

    @Test fun routesFollowExistingExitPathsWithoutASecondBackStack() {
        assertEquals(ProductBackAction.NATIVE_EXIT, productBackAction(true, false, ProductPage.LIBRARY, false))
        assertEquals(ProductBackAction.LIBRARY, productBackAction(true, false, null, false))
        assertEquals(ProductBackAction.LIBRARY, productBackAction(true, false, ProductPage.COMMUNICATION, false))
        assertEquals(ProductBackAction.EDITOR, productBackAction(true, false, ProductPage.EDITOR, false))
        listOf(ProductPage.SETTINGS, ProductPage.ACCESS, ProductPage.TRANSFER).forEach {
            assertEquals(ProductBackAction.LIBRARY, productBackAction(true, false, it, false))
        }
        assertEquals(ProductBackAction.COMMUNICATION, productBackAction(true, false, ProductPage.SETTINGS, true))
    }

    @Test fun oldCallbackCannotInvokeReplacementRouteOrDisposedComposition() {
        var native: (() -> Unit)? = null
        var registrations = 0
        val owner = OwnedProductBack { native = it; registrations++ }
        var oldCalls = 0
        var newCalls = 0
        owner.update { oldCalls++ }
        val old = assertNotNull(native)
        old()
        assertEquals(1, oldCalls)
        owner.update { newCalls++ }
        val replacement = assertNotNull(native)
        old()
        assertEquals(1, oldCalls)
        assertEquals(0, newCalls)
        replacement()
        assertEquals(1, newCalls)
        owner.update(null)
        assertNull(native)
        replacement()
        assertEquals(1, newCalls)
        owner.close()
        val closedRegistrations = registrations
        owner.close()
        owner.update { fail("Closed owner must never register again") }
        assertEquals(closedRegistrations, registrations)
        old(); replacement()
        assertEquals(1, oldCalls)
        assertEquals(1, newCalls)

        val nextWindow = OwnedProductBack { native = it }
        nextWindow.update { newCalls++ }
        old(); replacement()
        assertEquals(1, newCalls)
        assertNotNull(native).invoke()
        assertEquals(2, newCalls)
        nextWindow.close()
        assertNull(native)
    }
}
