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
            assertTrue(backupEligible(service))
            setAccessibility(service, kSecAttrAccessibleWhenUnlockedThisDeviceOnly)
            assertFalse(backupEligible(service))
            assertEquals(ProblemCode.UNAUTHORIZED, assertIs<OperationResult.Failed>(store.allowDeviceBackup {
                OperationResult.Failed(Problem(ProblemCode.UNAUTHORIZED))
            }).problem.code)
            assertFalse(backupEligible(service))
            assertContentEquals(first, assertIs<OperationResult.Completed<ByteArray?>>(store.read()).value)
            assertIs<OperationResult.Completed<Unit>>(store.allowDeviceBackup { OperationResult.Completed(Unit) })
            assertTrue(backupEligible(service))
            val reopened = KeychainCredentialStore(service)
            assertContentEquals(first, assertIs<OperationResult.Completed<ByteArray?>>(reopened.read()).value)
            assertEquals(ProblemCode.UNAUTHORIZED, assertIs<OperationResult.Failed>(reopened.write(second) {
                OperationResult.Failed(Problem(ProblemCode.UNAUTHORIZED))
            }).problem.code)
            assertContentEquals(first, assertIs<OperationResult.Completed<ByteArray?>>(store.read()).value)
            assertIs<OperationResult.Completed<Unit>>(reopened.write(second) { OperationResult.Completed(Unit) })
            assertTrue(backupEligible(service))
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
        owned { store, service ->
            val bytes = byteArrayOf(1, 2, 3)
            assertIs<OperationResult.Completed<Unit>>(store.write(bytes) { OperationResult.Completed(Unit) })
            setAccessibility(service, kSecAttrAccessibleAfterFirstUnlock)
            assertEquals(ProblemCode.INTEGRITY_FAILURE, assertIs<OperationResult.Failed>(store.allowDeviceBackup {
                error("Unknown accessibility must not reach the mutation guard")
            }).problem.code)
            assertContentEquals(bytes, assertIs<OperationResult.Completed<ByteArray?>>(store.read()).value)
        }
        assertEquals(ProblemCode.INVALID_DATA, assertIs<OperationResult.Failed>(KeychainCredentialStore("bad\u0000service").read()).problem.code)
    }

    private fun deleteOwned(service: String) = withOwnedQuery(service) { query ->
        val status = SecItemDelete(query)
        assertTrue(status == errSecSuccess || status == errSecItemNotFound, "Owned test Keychain cleanup failed: $status")
    }

    private fun setAccessibility(service: String, value: CFStringRef?) = withOwnedQuery(service) { query ->
        val attributes = CFDictionaryCreateMutable(null, 0, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr)!!
        try {
            CFDictionarySetValue(attributes, kSecAttrAccessible, value)
            assertEquals(errSecSuccess, SecItemUpdate(query, attributes))
        } finally { CFRelease(attributes) }
    }

    private fun backupEligible(service: String): Boolean = withOwnedQuery(service) { query ->
        CFDictionarySetValue(query, kSecReturnAttributes, kCFBooleanTrue)
        CFDictionarySetValue(query, kSecMatchLimit, kSecMatchLimitOne)
        memScoped {
            val result = alloc<CFTypeRefVar>(); result.value = null
            assertEquals(errSecSuccess, SecItemCopyMatching(query, result.ptr))
            try {
                val returned = assertNotNull(result.value)
                assertEquals(CFDictionaryGetTypeID(), CFGetTypeID(returned))
                val accessible = CFDictionaryGetValue(returned.reinterpret(), kSecAttrAccessible)
                assertTrue(accessible != null)
                CFStringCompare(accessible!!.reinterpret(), kSecAttrAccessibleWhenUnlocked, 0uL) == kCFCompareEqualTo
            } finally { result.value?.let { CFRelease(it) } }
        }
    }

    private fun <T> withOwnedQuery(service: String, block: (CFMutableDictionaryRef) -> T): T {
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
            return block(query)
        } finally { CFRelease(account); CFRelease(name); CFRelease(query) }
    }
}
