package su.linka.pictures.bootstrap

import su.linka.pictures.access.*
import su.linka.pictures.application.*
import su.linka.pictures.application.ports.CommitGuard

/** Absence is meaningful only before the first library root exists; native read failures are never absence. */
internal suspend fun admitRootCredentials(store: CredentialStore, rootExisted: Boolean, guard: CommitGuard): OperationResult<Unit> {
    val existing = when (val result = store.read()) {
        is OperationResult.Completed -> result.value
        is OperationResult.Failed -> return result
        OperationResult.Cancelled -> return OperationResult.Cancelled
        OperationResult.UnconfirmedCommit -> return OperationResult.UnconfirmedCommit
    }
    if (existing == null) {
        if (rootExisted) return OperationResult.Failed(Problem(ProblemCode.INTEGRITY_FAILURE))
        return LegacyCredentialImporter(store).prepare(false, null, guard)
    }
    existing.fill(0)
    return LegacyCredentialImporter(store).validate()
}
