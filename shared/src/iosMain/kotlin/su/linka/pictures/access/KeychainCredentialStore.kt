@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package su.linka.pictures.access

import kotlinx.cinterop.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import platform.CoreFoundation.*
import platform.Security.*
import su.linka.pictures.application.*
import su.linka.pictures.application.ports.CommitGuard
import su.linka.pictures.text.isNativeText

internal class KeychainCredentialStore(
    private val service: String = "su.linka.pictures.assistant.v1",
) : CredentialStore {
    private val writer = Mutex()

    override suspend fun read(): OperationResult<ByteArray?> = withContext(Dispatchers.IO) {
        writer.withLock { readRaw() }
    }

    override suspend fun write(value: ByteArray, guard: CommitGuard): OperationResult<Unit> = withContext(Dispatchers.IO) {
        writer.withLock {
            if (value.isEmpty() || value.size > 8192 || !validService()) return@withLock failure(ProblemCode.INVALID_DATA)
            val previous = when (val read = readRaw()) {
                is OperationResult.Completed -> read.value
                is OperationResult.Failed -> return@withLock read
                OperationResult.Cancelled -> return@withLock OperationResult.Cancelled
                OperationResult.UnconfirmedCommit -> return@withLock OperationResult.UnconfirmedCommit
            }
            currentCoroutineContext().ensureActive()
            var publishing = false
            var systemCode: Int? = null
            try {
                val data = value.usePinned { CFDataCreate(null, it.addressOf(0).reinterpret(), value.size.toLong()) }
                    ?: return@withLock failure(ProblemCode.IO_FAILURE)
                val status = try {
                    withQuery { query ->
                        val attributes = dictionary()
                        try {
                            CFDictionarySetValue(attributes, kSecValueData, data)
                            CFDictionarySetValue(attributes, kSecAttrAccessible, kSecAttrAccessibleWhenUnlockedThisDeviceOnly)
                            when (val allowed = guard()) {
                                is OperationResult.Completed -> Unit
                                else -> return@withLock allowed
                            }
                            publishing = true
                            if (previous != null) SecItemUpdate(query, attributes) else {
                                CFDictionarySetValue(query, kSecValueData, data)
                                CFDictionarySetValue(query, kSecAttrAccessible, kSecAttrAccessibleWhenUnlockedThisDeviceOnly)
                                SecItemAdd(query, null)
                            }
                        } finally { CFRelease(attributes) }
                    }
                } finally { CFRelease(data) }
                if (status == errSecSuccess) return@withLock OperationResult.Completed(Unit)
                systemCode = status
            } catch (cancelled: CancellationException) {
                if (!publishing) throw cancelled
            } catch (_: Exception) {
                if (!publishing) return@withLock failure(ProblemCode.IO_FAILURE)
            }
            // No delete/add or retry after an uncertain native mutation: inspect a fresh Keychain read.
            when (val observed = readRaw()) {
                is OperationResult.Completed -> when {
                    observed.value?.contentEquals(value) == true -> OperationResult.Completed(Unit)
                    same(observed.value, previous) -> failure(ProblemCode.IO_FAILURE, systemCode)
                    else -> OperationResult.UnconfirmedCommit
                }
                else -> OperationResult.UnconfirmedCommit
            }
        }
    }

    private fun readRaw(): OperationResult<ByteArray?> {
        if (!validService()) return failure(ProblemCode.INVALID_DATA)
        return try {
            withQuery { query ->
                CFDictionarySetValue(query, kSecReturnData, kCFBooleanTrue)
                CFDictionarySetValue(query, kSecMatchLimit, kSecMatchLimitOne)
                memScoped {
                    val result = alloc<CFTypeRefVar>()
                    result.value = null
                    val status = SecItemCopyMatching(query, result.ptr)
                    try {
                        when (status) {
                            errSecItemNotFound -> OperationResult.Completed(null)
                            errSecSuccess -> {
                                val returned = result.value ?: return@memScoped failure(ProblemCode.INTEGRITY_FAILURE)
                                if (CFGetTypeID(returned) != CFDataGetTypeID()) return@memScoped failure(ProblemCode.INTEGRITY_FAILURE)
                                val data: CFDataRef = returned.reinterpret()
                                val size = CFDataGetLength(data)
                                if (size !in 1L..8192L) return@memScoped failure(ProblemCode.INTEGRITY_FAILURE)
                                val pointer = CFDataGetBytePtr(data) ?: return@memScoped failure(ProblemCode.INTEGRITY_FAILURE)
                                OperationResult.Completed(pointer.reinterpret<ByteVar>().readBytes(size.toInt()))
                            }
                            else -> failure(ProblemCode.IO_FAILURE, status)
                        }
                    } finally { result.value?.let { CFRelease(it) } }
                }
            }
        } catch (_: Exception) { failure(ProblemCode.IO_FAILURE) }
    }

    private inline fun <T> withQuery(block: (CFMutableDictionaryRef) -> T): T {
        val query = dictionary()
        try {
            val name = CFStringCreateWithCString(null, service, kCFStringEncodingUTF8) ?: error("Keychain query allocation")
            try {
                val account = CFStringCreateWithCString(null, "credential", kCFStringEncodingUTF8) ?: error("Keychain query allocation")
                try {
                    CFDictionarySetValue(query, kSecClass, kSecClassGenericPassword)
                    CFDictionarySetValue(query, kSecAttrService, name)
                    CFDictionarySetValue(query, kSecAttrAccount, account)
                    CFDictionarySetValue(query, kSecAttrSynchronizable, kCFBooleanFalse)
                    CFDictionarySetValue(query, kSecUseDataProtectionKeychain, kCFBooleanTrue)
                    CFDictionarySetValue(query, kSecUseAuthenticationUI, kSecUseAuthenticationUIFail)
                    return block(query)
                } finally { CFRelease(account) }
            } finally { CFRelease(name) }
        } finally { CFRelease(query) }
    }

    private fun dictionary(): CFMutableDictionaryRef = CFDictionaryCreateMutable(
        null, 0, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr,
    ) ?: error("Keychain query allocation")

    private fun validService() = service.length in 1..128 && isNativeText(service)
    private fun same(left: ByteArray?, right: ByteArray?) = if (left == null) right == null else right != null && left.contentEquals(right)
    private fun failure(code: ProblemCode, systemCode: Int? = null) = OperationResult.Failed(Problem(code, systemCode = systemCode))
}
