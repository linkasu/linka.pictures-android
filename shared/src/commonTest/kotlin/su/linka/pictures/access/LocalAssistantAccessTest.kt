package su.linka.pictures.access

import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import su.linka.pictures.application.*
import su.linka.pictures.application.ports.*
import su.linka.pictures.domain.RequestId

class LocalAssistantAccessTest {
    private val json = Json { encodeDefaults = true }
    private class Store(var bytes: ByteArray? = null) : CredentialStore {
        var result: OperationResult<Unit> = OperationResult.Completed(Unit)
        var beforeGuard: () -> Unit = {}
        var afterPublish: () -> Unit = {}
        var writes = 0
        override suspend fun read(): OperationResult<ByteArray?> = OperationResult.Completed(bytes?.copyOf())
        override suspend fun write(value: ByteArray, guard: CommitGuard): OperationResult<Unit> {
            beforeGuard()
            when (val allowed = guard()) {
                is OperationResult.Completed -> Unit
                else -> return allowed
            }
            writes++
            if (result is OperationResult.Completed) bytes = value.copyOf()
            afterPublish()
            return result
        }
    }
    private class Owner : DeviceOwnerVerifier {
        var available = OwnerVerificationAvailability.AVAILABLE
        var proof: suspend () -> OperationResult<Unit> = { OperationResult.Completed(Unit) }
        var verifications = 0
        val cancellations = mutableListOf<RequestId>()
        override suspend fun availability() = available
        override suspend fun verify(requestId: RequestId, ownerId: String): OperationResult<Unit> { verifications++; return proof() }
        override fun cancel(requestId: RequestId) { cancellations += requestId }
    }
    private suspend fun access(store: Store = Store(), owner: Owner = Owner()): LocalAssistantAccess {
        var id = 0
        return assertIs<OperationResult.Completed<LocalAssistantAccess>>(LocalAssistantAccess.open(store, owner, IdSource { "token-${++id}" })).value
    }
    private fun denied(result: OperationResult<*>) { assertEquals(ProblemCode.UNAUTHORIZED, assertIs<OperationResult.Failed>(result).problem.code) }
    private fun bytes(record: CredentialRecord) = json.encodeToString(CredentialRecord.serializer(), record).encodeToByteArray()
    private fun legacy(password: String) = CredentialRecord(enabled = true, legacy = true, salt = ByteArray(16) { it.toByte() },
        verifier = PasswordCrypto.derive(passwordBytes(password), ByteArray(16) { it.toByte() }, 1_300_000))

    @Test fun unprotectedCommandsStillHaveOwnerBoundRevocableGuards() = runTest {
        val owner = Owner()
        val gate = access(owner = owner)
        val a = gate.guard(ProtectedAction.CREATE_BOARD, null, "a")
        val b = gate.guard(ProtectedAction.CREATE_BACKUP, null, "b")
        assertIs<OperationResult.Completed<Unit>>(a())
        gate.revokeOwner("a")
        denied(a())
        assertIs<OperationResult.Completed<Unit>>(b())
        gate.revokeAll()
        denied(b())
        denied(gate.check(ProtectedAction.RESET_LOCK, null, "b"))
        denied(gate.check(ProtectedAction.EDIT_BOARD, null, ""))
        assertTrue(owner.cancellations.isEmpty())
    }

    @Test fun modernPasswordIsExactAndCapabilitiesCannotMoveBetweenOwnersOrSurviveChanges() = runTest {
        val store = Store()
        val gate = access(store)
        assertIs<OperationResult.Completed<Unit>>(gate.setPassword("  мёд\u0000👩‍👩‍👧‍👦  ", "a", null))
        denied(gate.unlock("мёд\u0000👩‍👩‍👧‍👦", "a"))
        val cap = assertIs<OperationResult.Completed<AssistantCapability>>(gate.unlock("  мёд\u0000👩‍👩‍👧‍👦  ", "a")).value
        denied(gate.check(ProtectedAction.EDIT_BOARD, cap, "b"))
        denied(gate.check(ProtectedAction.EDIT_BOARD, AssistantCapability(cap.token, cap.owner), "a"))
        val before = gate.guard(ProtectedAction.EDIT_BOARD, cap, "a")
        assertIs<OperationResult.Completed<Unit>>(before())
        assertIs<OperationResult.Completed<Unit>>(gate.disableLock(cap, "a"))
        denied(before())
        denied(gate.check(ProtectedAction.EDIT_BOARD, cap, "a"))
        assertIs<AssistantState.Unprotected>(gate.state.value)
        assertFalse(store.bytes!!.decodeToString().contains("мёд"))
        assertIs<AssistantState.Unprotected>(access(store).state.value)
    }

    @Test fun legacyInputTrimDoesNotNormalizeStoredUnicodeOrUnlockUnreachablePasswords() = runTest {
        for (password in listOf("", " secret ", "   ")) {
            val gate = access(Store(bytes(legacy(password))))
            assertIs<AssistantState.Protected>(gate.state.value).also { assertTrue(it.legacy) }
            denied(gate.unlock(password, "a"))
        }
        val gate = access(Store(bytes(legacy("éмёд"))))
        denied(gate.unlock("e\u0301мёд", "a"))
        assertIs<OperationResult.Completed<AssistantCapability>>(gate.unlock(" \téмёд\n", "a"))
    }

    @Test fun newLockRequiresActualAvailablePrerequisiteAndDoesNotWriteOnFailure() = runTest {
        val store = Store()
        val owner = Owner().also { it.available = OwnerVerificationAvailability.UNAVAILABLE }
        val gate = access(store, owner)
        assertEquals(ProblemCode.OWNER_VERIFICATION_UNAVAILABLE, assertIs<OperationResult.Failed>(gate.setPassword("secret", "a", null)).problem.code)
        assertEquals(0, store.writes)
        owner.available = OwnerVerificationAvailability.AVAILABLE
        gate.refreshPrerequisites()
        assertEquals(OwnerVerificationAvailability.AVAILABLE, assertIs<AssistantState.Unprotected>(gate.state.value).verification)
        assertEquals(ProblemCode.INVALID_DATA, assertIs<OperationResult.Failed>(gate.setPassword("", "a", null)).problem.code)
        assertEquals(0, store.writes)
    }

    @Test fun unavailableOsVerificationPreservesLegacyUnlockAndCannotResetOrReplaceItsPassword() = runTest {
        val original = bytes(legacy("старый пароль"))
        val store = Store(original.copyOf())
        val owner = Owner().also { it.available = OwnerVerificationAvailability.UNAVAILABLE }
        val gate = access(store, owner)
        gate.refreshPrerequisites()
        val state = assertIs<AssistantState.Protected>(gate.state.value)
        assertTrue(state.legacy)
        assertEquals(OwnerVerificationAvailability.UNAVAILABLE, state.verification)
        denied(gate.unlock("неверный пароль", "a"))
        val capability = assertIs<OperationResult.Completed<AssistantCapability>>(gate.unlock(" \tстарый пароль\n", "a")).value
        assertIs<OperationResult.Completed<Unit>>(gate.check(ProtectedAction.EDIT_BOARD, capability, "a"))
        for (result in listOf(
            gate.setPassword("новый пароль", "a", capability),
            gate.resetPasswordWithDeviceOwner("новый пароль", "a"),
        )) assertEquals(ProblemCode.OWNER_VERIFICATION_UNAVAILABLE, assertIs<OperationResult.Failed>(result).problem.code)
        assertEquals(0, owner.verifications)
        assertEquals(0, store.writes)
        assertContentEquals(original, store.bytes)
        assertTrue(assertIs<AssistantState.Protected>(gate.state.value).legacy)
        assertIs<OperationResult.Completed<AssistantCapability>>(access(store, owner).unlock("старый пароль", "b"))
    }

    @Test fun ownerResetNeedsFreshProofAndRevokeDefeatsLateSuccess() = runTest {
        val store = Store(bytes(legacy("")))
        val owner = Owner()
        val gate = access(store, owner)
        owner.proof = { gate.revokeOwner("a"); OperationResult.Completed(Unit) }
        denied(gate.resetPasswordWithDeviceOwner("new", "a"))
        assertEquals(0, store.writes)
        owner.proof = { OperationResult.Cancelled }
        assertEquals(OperationResult.Cancelled, gate.resetPasswordWithDeviceOwner("new", "a"))
        assertEquals(0, store.writes)
        owner.proof = { OperationResult.Completed(Unit) }
        assertIs<OperationResult.Completed<Unit>>(gate.resetPasswordWithDeviceOwner("new", "a"))
        assertEquals(3, owner.verifications)
        assertFalse(assertIs<AssistantState.Protected>(gate.state.value).legacy)
        assertIs<OperationResult.Completed<AssistantCapability>>(access(store).unlock("new", "a"))
    }

    @Test fun revokeAtLastCredentialGuardDoesNotPublishAndFailedWriteKeepsOldProtection() = runTest {
        val store = Store()
        val gate = access(store)
        store.beforeGuard = { gate.revokeAll() }
        denied(gate.setPassword("secret", "a", null))
        assertEquals(0, store.writes)
        store.beforeGuard = {}
        store.result = OperationResult.Failed(Problem(ProblemCode.IO_FAILURE))
        assertEquals(store.result, gate.setPassword("secret", "a", null))
        assertIs<AssistantState.Unprotected>(gate.state.value)
        assertNull(store.bytes)
    }

    @Test fun unknownCredentialCommitQuarantinesRatherThanPretendingTheLockIsOff() = runTest {
        val store = Store().also { it.result = OperationResult.UnconfirmedCommit }
        val gate = access(store)
        assertEquals(OperationResult.UnconfirmedCommit, gate.setPassword("secret", "a", null))
        assertEquals(ProblemCode.COMMIT_OUTCOME_UNKNOWN, assertIs<AssistantState.Unavailable>(gate.state.value).problem.code)
        denied(gate.check(ProtectedAction.CREATE_BACKUP, null, "a"))
        denied(gate.unlock("secret", "a"))
        denied(gate.resetPasswordWithDeviceOwner("another", "a"))
        gate.refreshPrerequisites()
        assertIs<AssistantState.Unavailable>(gate.state.value)
        assertEquals(1, store.writes)
    }

    @Test fun malformedUnknownSchemaAndStoreReadErrorsCannotBecomeUnprotected() = runTest {
        for (raw in listOf("{}", "null", "{\"schema\":2,\"enabled\":false}", "x".repeat(8193))) {
            assertEquals(ProblemCode.INTEGRITY_FAILURE, assertIs<OperationResult.Failed>(LocalAssistantAccess.open(Store(raw.encodeToByteArray()), Owner(), IdSource { "id" })).problem.code)
        }
        val unreadable = object : CredentialStore {
            override suspend fun read(): OperationResult<ByteArray?> = OperationResult.Failed(Problem(ProblemCode.IO_FAILURE))
            override suspend fun write(value: ByteArray, guard: CommitGuard): OperationResult<Unit> = error("No write")
        }
        assertEquals(ProblemCode.IO_FAILURE, assertIs<OperationResult.Failed>(LocalAssistantAccess.open(unreadable, Owner(), IdSource { "id" })).problem.code)
    }

    @Test fun ownerContextsAreBoundedAndRevokingOneDoesNotInvalidateAnother() = runTest {
        val gate = access()
        val caps = (0 until 64).map { assertIs<OperationResult.Completed<AssistantCapability>>(gate.unlock("", "owner-$it")).value }
        denied(gate.unlock("", "extra"))
        gate.revokeOwner("owner-0")
        denied(gate.check(ProtectedAction.EDIT_BOARD, caps[0], "owner-0"))
        assertIs<OperationResult.Completed<Unit>>(gate.check(ProtectedAction.EDIT_BOARD, caps[1], "owner-1"))
        assertIs<OperationResult.Completed<AssistantCapability>>(gate.unlock("", "extra"))
    }

    @Test fun cancelledOwnerPromptCannotPublishEvenWhenItsNativeCallbackIgnoresCancellation() = runTest {
        val store = Store()
        val owner = Owner()
        val gate = access(store, owner)
        val started = CompletableDeferred<Unit>()
        val complete = CompletableDeferred<Unit>()
        owner.proof = {
            withContext(NonCancellable) { started.complete(Unit); complete.await(); OperationResult.Completed(Unit) }
        }
        val reset = async { gate.resetPasswordWithDeviceOwner("new", "a") }
        started.await()
        reset.cancel()
        gate.revokeOwner("a")
        complete.complete(Unit)
        assertFailsWith<CancellationException> { reset.await() }
        assertEquals(0, store.writes)
        assertEquals(1, owner.cancellations.size)
        assertIs<AssistantState.Unprotected>(gate.state.value)
    }

    @Test fun cancellationAfterKnownPublishCannotLeaveRuntimePretendingThePasswordIsUnset() = runTest {
        val store = Store()
        val gate = access(store)
        val operation = async(start = CoroutineStart.LAZY) { gate.setPassword("secret", "a", null) }
        store.afterPublish = { operation.cancel() }
        operation.start()
        operation.join()
        assertEquals(1, store.writes)
        assertIs<AssistantState.Protected>(gate.state.value)
        assertIs<AssistantState.Protected>(access(store).state.value)
        denied(gate.check(ProtectedAction.CREATE_BACKUP, null, "a"))
    }

    @Test fun systemPromptSuspensionRevokesCapabilitiesAndGuardsWithoutCancellingItsOwnRequest() = runTest {
        val store = Store()
        val owner = Owner()
        val gate = access(store, owner)
        val capability = assertIs<OperationResult.Completed<AssistantCapability>>(gate.unlock("", "editor")).value
        val previousGuard = gate.guard(ProtectedAction.CREATE_BOARD, capability, "editor")
        owner.proof = {
            gate.suspendForSystemPrompt()
            denied(previousGuard())
            denied(gate.check(ProtectedAction.CREATE_BOARD, capability, "editor"))
            assertTrue(owner.cancellations.isEmpty())
            OperationResult.Completed(Unit)
        }
        assertIs<OperationResult.Completed<Unit>>(gate.resetPasswordWithDeviceOwner("new", "reset"))
        assertEquals(1, store.writes)
    }

    @Test fun replacingAnOwnerContextCancelsItsPreviousNativeRequest() = runTest {
        val owner = Owner()
        val gate = access(owner = owner)
        owner.proof = {
            gate.guard(ProtectedAction.CREATE_BOARD, null, "reset")
            assertEquals(0, owner.cancellations.size)
            // A new explicit reset replaces this same owner's pending challenge before writer admission.
            val replacement = launch(start = CoroutineStart.UNDISPATCHED) {
                gate.resetPasswordWithDeviceOwner("replacement", "reset")
            }
            replacement.cancel()
            OperationResult.Completed(Unit)
        }
        denied(gate.resetPasswordWithDeviceOwner("original", "reset"))
        assertEquals(1, owner.cancellations.size)
    }

    @Test fun rootReadFailureKeepsCommunicationSeparateAndKnownCredentialPublishCannotReopenTheGate() = runTest {
        val store = Store()
        val gate = access(store)
        store.afterPublish = { gate.block(Problem(ProblemCode.IO_FAILURE)) }
        assertIs<OperationResult.Completed<Unit>>(gate.setPassword("confirmed", "owner", null))
        assertEquals(1, store.writes)
        assertEquals(ProblemCode.IO_FAILURE, assertIs<AssistantState.Unavailable>(gate.state.value).problem.code)
        denied(gate.unlock("confirmed", "owner"))
        denied(gate.check(ProtectedAction.EDIT_BOARD, null, "owner"))
        // Reopening reads the confirmed record; blocking never erased/replaced it.
        assertIs<OperationResult.Completed<AssistantCapability>>(access(store).unlock("confirmed", "new-owner"))
    }
}
