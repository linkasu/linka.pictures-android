package su.linka.pictures.bootstrap

import su.linka.pictures.application.*
import su.linka.pictures.application.ports.*

/** Closes new admission during a confirmed replacement; native access.block latches actual failures. */
internal class RootAssistantAccess(private val access: AssistantAccess, private val recovery: RootRecovery) : AssistantAccess by access {
    private fun denied(): OperationResult.Failed? = recovery.denied()?.let { OperationResult.Failed(it) }
    override fun check(action: ProtectedAction, capability: AssistantCapability?, ownerId: String): OperationResult<Unit> =
        denied() ?: access.check(action, capability, ownerId)
    override fun guard(action: ProtectedAction, capability: AssistantCapability?, ownerId: String): CommitGuard {
        denied()?.let { rejected -> return { rejected } }
        val original = access.guard(action, capability, ownerId)
        return { denied() ?: original() }
    }
    override suspend fun unlock(password: String, ownerId: String): OperationResult<AssistantCapability> =
        denied() ?: access.unlock(password, ownerId)
    override suspend fun setPassword(password: String, ownerId: String, capability: AssistantCapability?): OperationResult<Unit> =
        denied() ?: access.setPassword(password, ownerId, capability)
    override suspend fun resetPasswordWithDeviceOwner(password: String, ownerId: String): OperationResult<Unit> =
        denied() ?: access.resetPasswordWithDeviceOwner(password, ownerId)
    override suspend fun disableLock(capability: AssistantCapability, ownerId: String): OperationResult<Unit> =
        denied() ?: access.disableLock(capability, ownerId)
}
