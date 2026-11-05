package su.linka.pictures.bootstrap

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.*
import su.linka.pictures.application.*
import su.linka.pictures.application.ports.*

class RootAssistantAccessTest {
    @Test fun guardCreatedDuringReplacementNeverBecomesValidLaterAndNewCommandsDoNotReachNativeAccess() = runTest {
        var guards = 0
        val native = object : AssistantAccess {
            override val state = MutableStateFlow<AssistantState>(AssistantState.Unprotected(OwnerVerificationAvailability.UNKNOWN))
            override fun check(action: ProtectedAction, capability: AssistantCapability?, ownerId: String): OperationResult<Unit> = OperationResult.Completed(Unit)
            override fun guard(action: ProtectedAction, capability: AssistantCapability?, ownerId: String): CommitGuard {
                guards++
                return { OperationResult.Completed(Unit) }
            }
            override suspend fun unlock(password: String, ownerId: String): OperationResult<AssistantCapability> = error("Blocked command reached native access")
            override suspend fun setPassword(password: String, ownerId: String, capability: AssistantCapability?): OperationResult<Unit> = error("Blocked command reached native access")
            override suspend fun resetPasswordWithDeviceOwner(password: String, ownerId: String): OperationResult<Unit> = error("Blocked command reached native access")
            override suspend fun disableLock(capability: AssistantCapability, ownerId: String): OperationResult<Unit> = error("Not used")
            override suspend fun refreshPrerequisites() { error("Not used") }
            override fun revokeOwner(ownerId: String) { error("Not used") }
            override fun revokeAll() { error("Not used") }
        }
        val recovery = RootRecovery()
        val access = RootAssistantAccess(native, recovery)
        recovery.beginReplacement(4)
        val guard = access.guard(ProtectedAction.CHANGE_SETTINGS, null, "owner")
        assertIs<OperationResult.Failed>(guard())
        assertIs<OperationResult.Failed>(access.unlock("not-used", "owner"))
        assertIs<OperationResult.Failed>(access.setPassword("not-used", "owner", null))
        assertIs<OperationResult.Failed>(access.resetPasswordWithDeviceOwner("not-used", "owner"))
        assertEquals(0, guards)
        recovery.replacementReady()
        assertIs<OperationResult.Failed>(guard())
        assertIs<OperationResult.Completed<Unit>>(access.check(ProtectedAction.CHANGE_SETTINGS, null, "owner"))
    }
}
