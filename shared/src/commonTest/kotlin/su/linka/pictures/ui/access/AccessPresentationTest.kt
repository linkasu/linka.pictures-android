package su.linka.pictures.ui.access

import kotlin.test.*
import su.linka.pictures.application.*
import su.linka.pictures.application.ports.*

class AccessPresentationTest {
    @Test fun freshNoneUsesCanonicalContextCheckNeverNonNullOrStaleCapability() {
        var allowed = true
        var checks = 0
        val gate = object : AssistantGate {
            override fun check(action: ProtectedAction, capability: AssistantCapability?, ownerId: String): OperationResult<Unit> {
                checks++
                assertEquals(ProtectedAction.CREATE_BOARD, action)
                assertEquals("owner", ownerId)
                assertNull(capability)
                return if (allowed) OperationResult.Completed(Unit) else OperationResult.Failed(Problem(ProblemCode.UNAUTHORIZED))
            }
            override fun guard(action: ProtectedAction, capability: AssistantCapability?, ownerId: String): CommitGuard = { check(action, capability, ownerId) }
        }
        val state = AssistantState.Unprotected(OwnerVerificationAvailability.UNAVAILABLE)
        assertTrue(canSkipContextUnlock(state, null, gate, ProtectedAction.CREATE_BOARD, "owner"))
        allowed = false
        assertFalse(canSkipContextUnlock(state, null, gate, ProtectedAction.CREATE_BOARD, "owner"))
        allowed = true
        assertFalse(canSkipContextUnlock(state, AssistantCapability("stale", "owner"), gate, ProtectedAction.CREATE_BOARD, "owner"))
        assertFalse(canSkipContextUnlock(state, null, gate, null, "owner"))
        assertFalse(canSkipContextUnlock(AssistantState.Protected(false, OwnerVerificationAvailability.AVAILABLE), null,
            gate, ProtectedAction.CREATE_BOARD, "owner"))
        assertEquals(2, checks)
    }
}
