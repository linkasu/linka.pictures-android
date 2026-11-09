package su.linka.pictures.access

import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path.Companion.toPath
import org.junit.Assert.*
import org.junit.Test
import su.linka.pictures.application.OperationResult
import su.linka.pictures.application.ports.*
import su.linka.pictures.domain.RequestId

class PasswordCryptoAndroidTest {
    @Test fun actualApiProviderPreservesUnicodeAndEmbeddedNulAtProductionWorkFactor() {
        val encoded = passwordBytes(" мёд👩‍👩‍👧‍👦\u0000\uD800 ")
        val derived = PasswordCrypto.derive(encoded, ByteArray(16) { it.toByte() }, 1_300_000)
        val expected = "70561a17a1f4a0f70b79678a6e6f1fc3bb1c9205340688574a92ba8b71e0a8d6"
        assertEquals(expected, derived.joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') })
        assertTrue(PasswordCrypto.equal(derived, derived.copyOf()))
        assertFalse(PasswordCrypto.equal(derived, derived.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }))
        assertEquals(16, PasswordCrypto.salt().size)
    }

    @Test fun verifierReopensFromOwnedNoBackupRootWithoutAnyOsPromptOrLibraryAccess() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = (context.noBackupFilesDir.absolutePath + "/p08-test-" + UUID.randomUUID()).toPath()
        val files = FileSystem.SYSTEM
        files.createDirectory(root, mustCreate = true)
        val owner = object : DeviceOwnerVerifier {
            override suspend fun availability() = OwnerVerificationAvailability.UNAVAILABLE
            override suspend fun verify(requestId: RequestId, ownerId: String): OperationResult<Unit> = error("No OS prompt in this test")
            override fun cancel(requestId: RequestId) = Unit
        }
        try {
            fun store() = FileCredentialStore(root, files, IdSource { UUID.randomUUID().toString() })
            val record = CredentialRecord(enabled = true, salt = ByteArray(16) { it.toByte() },
                verifier = PasswordCrypto.derive(passwordBytes("exact пароль"), ByteArray(16) { it.toByte() }, 1_300_000))
            assertTrue(store().write(Json.encodeToString(CredentialRecord.serializer(), record).encodeToByteArray()) { OperationResult.Completed(Unit) } is OperationResult.Completed)
            val reopened = LocalAssistantAccess.open(store(), owner, IdSource { UUID.randomUUID().toString() })
            assertTrue(reopened is OperationResult.Completed)
            val access = (reopened as OperationResult.Completed).value
            assertTrue(access.state.value is AssistantState.Protected)
            assertTrue(access.unlock("exact пароль", "test-owner") is OperationResult.Completed)
            assertTrue(access.unlock(" exact пароль ", "test-owner") is OperationResult.Failed)
            val bytes = (store().read() as OperationResult.Completed).value!!
            assertFalse(bytes.decodeToString().contains("пароль"))
        } finally { files.deleteRecursively(root) }
    }
}
