package su.linka.pictures.bootstrap

import kotlinx.coroutines.test.runTest
import kotlin.test.*
import su.linka.pictures.access.CredentialStore
import su.linka.pictures.application.*
import su.linka.pictures.application.ports.CommitGuard

class RootCredentialAdmissionTest {
    @Test fun nativeReadErrorIsNotFreshUnprotectedStorage() = runTest {
        val failure = OperationResult.Failed(Problem(ProblemCode.IO_FAILURE, systemCode = -34018))
        val store = MemoryCredentials(failure)
        assertEquals(failure, admitRootCredentials(store, false) { OperationResult.Completed(Unit) })
        assertEquals(0, store.writes)
    }

    @Test fun missingCredentialForAnExistingLibraryIsAnIntegrityFailure() = runTest {
        val store = MemoryCredentials(OperationResult.Completed(null))
        assertEquals(OperationResult.Failed(Problem(ProblemCode.INTEGRITY_FAILURE)),
            admitRootCredentials(store, true) { OperationResult.Completed(Unit) })
        assertEquals(0, store.writes)
    }

    @Test fun onlyFreshLibraryCanInitializeADurableDisabledRecordAndRevokedAdmissionDoesNotWrite() = runTest {
        val cancelled = MemoryCredentials(OperationResult.Completed(null))
        assertEquals(OperationResult.Cancelled, admitRootCredentials(cancelled, false) { OperationResult.Cancelled })
        assertEquals(0, cancelled.writes)
        val fresh = MemoryCredentials(OperationResult.Completed(null))
        assertIs<OperationResult.Completed<Unit>>(admitRootCredentials(fresh, false) { OperationResult.Completed(Unit) })
        assertEquals(1, fresh.writes)
        assertIs<OperationResult.Completed<Unit>>(admitRootCredentials(fresh, true) { OperationResult.Completed(Unit) })
        assertEquals(1, fresh.writes)
    }

    private class MemoryCredentials(var read: OperationResult<ByteArray?>) : CredentialStore {
        var writes = 0
        override suspend fun read(): OperationResult<ByteArray?> = when (val result = read) {
            is OperationResult.Completed -> OperationResult.Completed(result.value?.copyOf())
            else -> result
        }
        override suspend fun write(value: ByteArray, guard: CommitGuard): OperationResult<Unit> {
            when (val allowed = guard()) {
                is OperationResult.Completed -> Unit
                else -> return allowed
            }
            writes++
            read = OperationResult.Completed(value.copyOf())
            return OperationResult.Completed(Unit)
        }
    }
}
