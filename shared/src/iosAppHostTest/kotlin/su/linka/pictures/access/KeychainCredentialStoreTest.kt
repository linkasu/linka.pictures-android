@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package su.linka.pictures.access

import kotlin.test.*
import kotlinx.cinterop.*
import kotlinx.coroutines.test.runTest
import platform.CoreFoundation.*
import platform.Foundation.NSUUID
import platform.Security.*
import su.linka.pictures.application.*

class KeychainCredentialStoreTest {
    private suspend fun owned(block: suspend (KeychainCredentialStore, String) -> Unit) {
        val service = "su.linka.pictures.test.access." + NSUUID().UUIDString
        val store = KeychainCredentialStore(service)
        val initial = store.read()
        assertNull(assertIs<OperationResult.Completed<ByteArray?>>(initial, "Initial Keychain read: ${(initial as? OperationResult.Failed)?.problem}").value)
        try { block(store, service) } finally { deleteOwned(service) }
    }

    @Test fun actualKeychainBytesReopenUpdateAndRevokedGuardPreserveExistingRecord() = runTest {
        owned { store, service ->
            val first = ByteArray(256) { it.toByte() }
            val second = ByteArray(512) { (it * 13).toByte() }
            assertIs<OperationResult.Completed<Unit>>(store.write(first) { OperationResult.Completed(Unit) })
            val reopened = KeychainCredentialStore(service)
            assertContentEquals(first, assertIs<OperationResult.Completed<ByteArray?>>(reopened.read()).value)
            assertEquals(ProblemCode.UNAUTHORIZED, assertIs<OperationResult.Failed>(reopened.write(second) {
                OperationResult.Failed(Problem(ProblemCode.UNAUTHORIZED))
            }).problem.code)
            assertContentEquals(first, assertIs<OperationResult.Completed<ByteArray?>>(store.read()).value)
            assertIs<OperationResult.Completed<Unit>>(reopened.write(second) { OperationResult.Completed(Unit) })
            assertContentEquals(second, assertIs<OperationResult.Completed<ByteArray?>>(KeychainCredentialStore(service).read()).value)
        }
    }

    @Test fun emptyOversizedAndUnsafeServiceAreRejectedWithoutNativeMutation() = runTest {
        owned { store, _ ->
            for (bytes in listOf(byteArrayOf(), ByteArray(8193))) {
                assertEquals(ProblemCode.INVALID_DATA, assertIs<OperationResult.Failed>(store.write(bytes) { error("No guard for invalid data") }).problem.code)
            }
            assertNull(assertIs<OperationResult.Completed<ByteArray?>>(store.read()).value)
        }
        assertEquals(ProblemCode.INVALID_DATA, assertIs<OperationResult.Failed>(KeychainCredentialStore("bad\u0000service").read()).problem.code)
    }

    private fun deleteOwned(service: String) {
        val query = CFDictionaryCreateMutable(null, 0, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr)!!
        val name = CFStringCreateWithCString(null, service, kCFStringEncodingUTF8)!!
        val account = CFStringCreateWithCString(null, "credential", kCFStringEncodingUTF8)!!
        try {
            CFDictionarySetValue(query, kSecClass, kSecClassGenericPassword)
            CFDictionarySetValue(query, kSecAttrService, name)
            CFDictionarySetValue(query, kSecAttrAccount, account)
            CFDictionarySetValue(query, kSecAttrSynchronizable, kCFBooleanFalse)
            CFDictionarySetValue(query, kSecUseDataProtectionKeychain, kCFBooleanTrue)
            CFDictionarySetValue(query, kSecUseAuthenticationUI, kSecUseAuthenticationUIFail)
            val status = SecItemDelete(query)
            assertTrue(status == errSecSuccess || status == errSecItemNotFound, "Owned test Keychain cleanup failed: $status")
        } finally { CFRelease(account); CFRelease(name); CFRelease(query) }
    }
}
