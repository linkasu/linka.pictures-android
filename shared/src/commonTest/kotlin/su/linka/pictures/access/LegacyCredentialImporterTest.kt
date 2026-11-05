package su.linka.pictures.access

import kotlin.test.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import su.linka.pictures.application.*
import su.linka.pictures.application.ports.*
import su.linka.pictures.domain.RequestId

class LegacyCredentialImporterTest {
    private class Store : CredentialStore {
        var bytes: ByteArray? = null
        var writes = 0
        var outcome: OperationResult<Unit> = OperationResult.Completed(Unit)
        override suspend fun read(): OperationResult<ByteArray?> = OperationResult.Completed(bytes?.copyOf())
        override suspend fun write(value: ByteArray, guard: CommitGuard): OperationResult<Unit> {
            val allowed = guard()
            if (allowed !is OperationResult.Completed) return allowed
            writes++
            if (outcome is OperationResult.Completed) bytes = value.copyOf()
            return outcome
        }
    }
    private val allow: CommitGuard = { OperationResult.Completed(Unit) }
    private val unavailable = object : DeviceOwnerVerifier {
        override suspend fun availability() = OwnerVerificationAvailability.UNAVAILABLE
        override suspend fun verify(requestId: RequestId, ownerId: String): OperationResult<Unit> = error("No native prompt")
        override fun cancel(requestId: RequestId) = Unit
    }

    @Test fun exactLegacyPasswordSurvivesResumeAndCannotBeReplacedByAnotherSource() = runTest {
        val store = Store()
        val importer = LegacyCredentialImporter(store)
        assertIs<OperationResult.Completed<Unit>>(importer.prepare(true, "пароль\u0000\uD800", allow))
        val before = store.bytes!!.copyOf()
        assertFalse(before.decodeToString().contains("пароль"))
        assertIs<OperationResult.Completed<Unit>>(importer.prepare(true, "пароль\u0000\uD800", allow))
        assertEquals(1, store.writes)
        assertContentEquals(before, store.bytes)
        assertEquals(ProblemCode.CONFLICT, assertIs<OperationResult.Failed>(importer.prepare(true, "другой", allow)).problem.code)
        assertContentEquals(before, store.bytes)
        val access = assertIs<OperationResult.Completed<LocalAssistantAccess>>(LocalAssistantAccess.open(store, unavailable, IdSource { "token" })).value
        assertIs<OperationResult.Completed<AssistantCapability>>(access.unlock(" \tпароль\u0000\uD800\n", "owner"))
    }

    @Test fun missingEmptyAndUnreachableStoredPasswordsNeverDisableLegacyLock() = runTest {
        for (password in listOf(null, "", "   ", " secret ")) {
            val store = Store()
            assertIs<OperationResult.Completed<Unit>>(LegacyCredentialImporter(store).prepare(true, password, allow))
            val access = assertIs<OperationResult.Completed<LocalAssistantAccess>>(LocalAssistantAccess.open(store, unavailable, IdSource { "token" })).value
            assertTrue(assertIs<AssistantState.Protected>(access.state.value).legacy)
            assertIs<OperationResult.Failed>(access.unlock(password.orEmpty(), "owner"))
        }
    }

    @Test fun anAdmittedRootRequiresARecordEvenWhenProtectionWasExplicitlyDisabled() = runTest {
        val store = Store()
        val importer = LegacyCredentialImporter(store)
        assertEquals(ProblemCode.INTEGRITY_FAILURE, assertIs<OperationResult.Failed>(importer.validate()).problem.code)
        assertIs<OperationResult.Completed<Unit>>(importer.prepare(false, null, allow))
        assertIs<OperationResult.Completed<Unit>>(importer.validate())
        val before = store.bytes!!.copyOf()
        assertIs<OperationResult.Completed<Unit>>(importer.prepare(false, "ignored old password", allow))
        assertEquals(1, store.writes)
        assertContentEquals(before, store.bytes)
        store.bytes = null
        assertIs<OperationResult.Failed>(importer.validate())
    }

    @Test fun corruptAndModernRecordsAreNotOverwrittenAndRevokedAdmissionCannotWrite() = runTest {
        val store = Store()
        val importer = LegacyCredentialImporter(store)
        for (raw in listOf("{}", "{\"schema\":2,\"enabled\":false}")) {
            store.bytes = raw.encodeToByteArray()
            assertEquals(ProblemCode.INTEGRITY_FAILURE, assertIs<OperationResult.Failed>(importer.prepare(false, null, allow)).problem.code)
            assertContentEquals(raw.encodeToByteArray(), store.bytes)
        }
        store.bytes = Json.encodeToString(CredentialRecord.serializer(), CredentialRecord(enabled = true,
            salt = ByteArray(16), verifier = ByteArray(32))).encodeToByteArray()
        assertEquals(ProblemCode.CONFLICT, assertIs<OperationResult.Failed>(importer.prepare(true, "secret", allow)).problem.code)
        store.bytes = null
        assertEquals(OperationResult.Cancelled, importer.prepare(false, null) { OperationResult.Cancelled })
        assertEquals(0, store.writes)
        assertNull(store.bytes)
    }

    @Test fun unknownCredentialPublicationCannotBecomeReadyForLibraryActivation() = runTest {
        val store = Store().also { it.outcome = OperationResult.UnconfirmedCommit }
        val importer = LegacyCredentialImporter(store)
        assertEquals(OperationResult.UnconfirmedCommit, importer.prepare(false, null, allow))
        assertIs<OperationResult.Failed>(importer.validate())
    }
}
