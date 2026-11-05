package su.linka.pictures.access

import kotlin.test.*
import kotlinx.coroutines.test.runTest
import okio.*
import su.linka.pictures.application.*
import su.linka.pictures.application.ports.*

class FileCredentialStoreTest {
    private val system = FileSystem.SYSTEM
    private val allowed: CommitGuard = { OperationResult.Completed(Unit) }
    private suspend fun owned(block: suspend (Path) -> Unit) {
        val name = PasswordCrypto.salt().joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
        val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "linka-access-test-$name"
        system.createDirectory(root, mustCreate = true)
        try { block(root) } finally { system.deleteRecursively(root) }
    }
    private fun store(root: Path, files: FileSystem = system): FileCredentialStore {
        var id = 0
        return FileCredentialStore(root / "private-no-backup", files, IdSource { "stage-${++id}" })
    }
    private suspend fun raw(store: FileCredentialStore): ByteArray? = assertIs<OperationResult.Completed<ByteArray?>>(store.read()).value

    @Test fun actualFileRoundTripReopenAndLateGuardPreservePreviousRecord() = runTest {
        owned { root ->
            val store = store(root)
            assertNull(raw(store))
            assertIs<OperationResult.Completed<Unit>>(store.write("old".encodeToByteArray(), allowed))
            var guards = 0
            assertEquals(ProblemCode.UNAUTHORIZED, assertIs<OperationResult.Failed>(store.write("new".encodeToByteArray()) {
                if (++guards == 1) OperationResult.Completed(Unit) else OperationResult.Failed(Problem(ProblemCode.UNAUTHORIZED))
            }).problem.code)
            assertEquals(2, guards)
            assertContentEquals("old".encodeToByteArray(), raw(store(root)))
            assertEquals(listOf("credential.json"), system.list(root / "private-no-backup").map { it.name })
        }
    }

    @Test fun atomicRenameExceptionsReconcileIndependentOldNewAndUnknownStates() = runTest {
        for (mode in listOf("rollback", "committed", "unknown")) owned { root ->
            var observing = false
            val fault = object : ForwardingFileSystem(system) {
                override fun atomicMove(source: Path, target: Path) {
                    if (mode != "rollback") super.atomicMove(source, target)
                    observing = true
                    throw IOException("synthetic publication fault")
                }
                override fun source(file: Path): Source {
                    if (observing && mode == "unknown" && file.name == "credential.json") throw IOException("synthetic unavailable observer")
                    return super.source(file)
                }
            }
            assertIs<OperationResult.Completed<Unit>>(store(root).write("old".encodeToByteArray(), allowed))
            val changed = store(root, fault).write("new".encodeToByteArray(), allowed)
            when (mode) {
                "rollback" -> assertEquals(ProblemCode.IO_FAILURE, assertIs<OperationResult.Failed>(changed).problem.code)
                "committed" -> assertIs<OperationResult.Completed<Unit>>(changed)
                else -> assertEquals(OperationResult.UnconfirmedCommit, changed)
            }
            assertContentEquals((if (mode == "rollback") "old" else "new").encodeToByteArray(), raw(store(root)))
        }
    }

    @Test fun unknownStagingSentinelAndOversizedOrNonregularRecordAreNeverDeletedOrAcceptedAsAbsent() = runTest {
        owned { root ->
            val directory = root / "private-no-backup"
            system.createDirectory(directory)
            val sentinel = directory / ".stage-stage-1"
            system.write(sentinel) { writeUtf8("preserve sentinel") }
            assertIs<OperationResult.Failed>(store(root).write("next".encodeToByteArray(), allowed))
            assertEquals("preserve sentinel", system.read(sentinel) { readUtf8() })
            val target = directory / "credential.json"
            system.write(target) { write(ByteArray(8193)) }
            assertEquals(ProblemCode.INTEGRITY_FAILURE, assertIs<OperationResult.Failed>(store(root).read()).problem.code)
            assertEquals(8193L, system.metadata(target).size)
            system.delete(target)
            system.createDirectory(target)
            assertEquals(ProblemCode.INTEGRITY_FAILURE, assertIs<OperationResult.Failed>(store(root).read()).problem.code)
        }
    }

    @Test fun actualAccessPersistsOnlyVerifierAndDoesNotNeedLibraryOrChangeIt() = runTest {
        owned { root ->
            val library = root / "library-sentinel"
            system.write(library) { writeUtf8("library remains untouched") }
            val first = assertIs<OperationResult.Completed<LocalAssistantAccess>>(LocalAssistantAccess.open(store(root), unavailableOwner(), IdSource { "id" })).value
            assertEquals(ProblemCode.OWNER_VERIFICATION_UNAVAILABLE, assertIs<OperationResult.Failed>(first.setPassword("secret", "a", null)).problem.code)
            assertNull(raw(store(root)))
            val record = CredentialRecord(enabled = true, salt = ByteArray(16) { it.toByte() },
                verifier = PasswordCrypto.derive(passwordBytes("passphrase"), ByteArray(16) { it.toByte() }, 1_300_000))
            val encoded = kotlinx.serialization.json.Json.encodeToString(CredentialRecord.serializer(), record).encodeToByteArray()
            assertIs<OperationResult.Completed<Unit>>(store(root).write(encoded, allowed))
            val reopened = assertIs<OperationResult.Completed<LocalAssistantAccess>>(LocalAssistantAccess.open(store(root), unavailableOwner(), IdSource { "id" })).value
            assertIs<AssistantState.Protected>(reopened.state.value)
            assertIs<OperationResult.Completed<AssistantCapability>>(reopened.unlock("passphrase", "a"))
            assertFalse(raw(store(root))!!.decodeToString().contains("passphrase"))
            assertEquals("library remains untouched", system.read(library) { readUtf8() })
        }
    }

    private fun unavailableOwner() = object : DeviceOwnerVerifier {
        override suspend fun availability() = OwnerVerificationAvailability.UNAVAILABLE
        override suspend fun verify(requestId: su.linka.pictures.domain.RequestId, ownerId: String): OperationResult<Unit> = error("No owner prompt")
        override fun cancel(requestId: su.linka.pictures.domain.RequestId) = Unit
    }
}
