@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package su.linka.pictures.identity

import kotlinx.cinterop.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import platform.CoreFoundation.*
import platform.Foundation.*
import platform.Security.*
import platform.UIKit.UIDevice
import platform.darwin.NSObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Registered by Swift before constructing the shared application. No registration means deny. */
public object IdentitySignatureVerifier {
    private var verifier: ((ByteArray, ByteArray, ByteArray) -> Boolean)? = null
    public fun initialize(verify: (ByteArray, ByteArray, ByteArray) -> Boolean) { verifier = verify }
    internal fun verify(key: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        if (key.size != 32 || signature.size != 64) return false
        return try { verifier?.invoke(key, message, signature) == true } catch (_: Exception) { false }
    }
}

internal class IosIdentityDevice : IdentityDevice {
    override val platform = "ios"
    override val appVersion get() = NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleShortVersionString") as? String ?: "unknown"
    override val appBuild get() = NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleVersion") as? String ?: "unknown"
    override val osVersion get() = UIDevice.currentDevice.systemVersion
    override val locale get() = when (val tag = NSLocale.preferredLanguages.firstOrNull() as? String) {
        "ru", "ru-RU", "en", "en-US" -> tag
        else -> "other"
    }
    override fun nowMillis() = (NSDate().timeIntervalSince1970 * 1000).toLong()
    override fun randomBytes(size: Int): ByteArray {
        if (size !in 1..LIMIT) throw IdentityDeviceFailure()
        return ByteArray(size).also { value ->
            if (value.usePinned { SecRandomCopyBytes(kSecRandomDefault, size.toULong(), it.addressOf(0)) } != errSecSuccess)
                throw IdentityDeviceFailure()
        }
    }
    override fun verifyEd25519(publicKey: ByteArray, message: ByteArray, signature: ByteArray) =
        IdentitySignatureVerifier.verify(publicKey, message, signature)

    override suspend fun readSecure(): ByteArray? = withContext(Dispatchers.IO) {
        storeLock.withLock { nativeSafe { readRaw() } }
    }

    override suspend fun writeSecure(value: ByteArray) = withContext(Dispatchers.IO) {
        storeLock.withLock { nativeSafe {
            if (value.size !in 1..LIMIT) throw IdentityDeviceFailure()
            val previous = readRaw()
            currentCoroutineContext().ensureActive()
            val data = value.usePinned { CFDataCreate(null, it.addressOf(0).reinterpret(), value.size.toLong()) }
                ?: throw IdentityDeviceFailure()
            try {
                val status = withQuery { query ->
                    val attributes = dictionary()
                    try {
                        CFDictionarySetValue(attributes, kSecValueData, data)
                        CFDictionarySetValue(attributes, kSecAttrAccessible, kSecAttrAccessibleWhenUnlockedThisDeviceOnly)
                        if (previous == null) {
                            CFDictionarySetValue(query, kSecValueData, data)
                            CFDictionarySetValue(query, kSecAttrAccessible, kSecAttrAccessibleWhenUnlockedThisDeviceOnly)
                            SecItemAdd(query, null)
                        } else SecItemUpdate(query, attributes)
                    } finally { CFRelease(attributes) }
                }
                // Never delete/add or repeat an uncertain mutation. Reconcile through a fresh read.
                if (status != errSecSuccess && readRaw()?.contentEquals(value) != true) throw IdentityDeviceFailure()
            } finally { CFRelease(data) }
        } }
    }

    override suspend fun request(method: String, url: String, bearer: String?, body: String?, idempotencyKey: String?): IdentityHttpResponse {
        validateIosIdentityRequest(method, url, bearer, body, idempotencyKey)
        return suspendCancellableCoroutine { reply ->
            val config = NSURLSessionConfiguration.ephemeralSessionConfiguration.apply {
                HTTPCookieStorage = null
                HTTPShouldSetCookies = false
                URLCache = null
                URLCredentialStorage = null
                requestCachePolicy = NSURLRequestReloadIgnoringLocalCacheData
                timeoutIntervalForRequest = 15.0
                timeoutIntervalForResource = 15.0
            }
            val delegate = IdentitySessionDelegate { result ->
                if (reply.isActive) result.fold({ reply.resume(it) }, { reply.resumeWithException(IdentityDeviceFailure()) })
            }
            val session = NSURLSession.sessionWithConfiguration(config, delegate, NSOperationQueue().apply { maxConcurrentOperationCount = 1 })
            val request = NSMutableURLRequest.requestWithURL(NSURL.URLWithString(url)!!).apply {
                HTTPMethod = method
                HTTPShouldHandleCookies = false
                setValue("application/json", "Accept")
                setValue("no-store", "Cache-Control")
                bearer?.let { setValue("Bearer $it", "Authorization") }
                idempotencyKey?.let { setValue(it, "Idempotency-Key") }
                body?.encodeToByteArray()?.let { bytes ->
                    setValue("application/json", "Content-Type")
                    HTTPBody = if (bytes.isEmpty()) NSData() else bytes.usePinned { NSData.create(bytes = it.addressOf(0), length = bytes.size.toULong()) }
                }
            }
            val task = session.dataTaskWithRequest(request)
            reply.invokeOnCancellation { task.cancel(); session.invalidateAndCancel() }
            if (reply.isActive) task.resume() else session.invalidateAndCancel()
        }
    }

    private fun readRaw(): ByteArray? = withQuery { query ->
        CFDictionarySetValue(query, kSecReturnData, kCFBooleanTrue)
        CFDictionarySetValue(query, kSecMatchLimit, kSecMatchLimitOne)
        memScoped {
            val result = alloc<CFTypeRefVar>(); result.value = null
            val status = SecItemCopyMatching(query, result.ptr)
            try {
                when (status) {
                    errSecItemNotFound -> null
                    errSecSuccess -> {
                        val returned = result.value ?: throw IdentityDeviceFailure()
                        if (CFGetTypeID(returned) != CFDataGetTypeID()) throw IdentityDeviceFailure()
                        val data: CFDataRef = returned.reinterpret()
                        val size = CFDataGetLength(data)
                        if (size !in 1L..LIMIT.toLong()) throw IdentityDeviceFailure()
                        CFDataGetBytePtr(data)?.reinterpret<ByteVar>()?.readBytes(size.toInt()) ?: throw IdentityDeviceFailure()
                    }
                    else -> throw IdentityDeviceFailure()
                }
            } finally { result.value?.let { CFRelease(it) } }
        }
    }

    private inline fun <T> withQuery(block: (CFMutableDictionaryRef) -> T): T {
        val query = dictionary()
        try {
            val service = CFStringCreateWithCString(null, "su.linka.pictures.identity.v1", kCFStringEncodingUTF8) ?: throw IdentityDeviceFailure()
            val account = CFStringCreateWithCString(null, "credential", kCFStringEncodingUTF8)
            try {
                if (account == null) throw IdentityDeviceFailure()
                CFDictionarySetValue(query, kSecClass, kSecClassGenericPassword)
                CFDictionarySetValue(query, kSecAttrService, service)
                CFDictionarySetValue(query, kSecAttrAccount, account)
                CFDictionarySetValue(query, kSecAttrSynchronizable, kCFBooleanFalse)
                CFDictionarySetValue(query, kSecUseDataProtectionKeychain, kCFBooleanTrue)
                CFDictionarySetValue(query, kSecUseAuthenticationUI, kSecUseAuthenticationUIFail)
                return block(query)
            } finally { account?.let { CFRelease(it) }; CFRelease(service) }
        } finally { CFRelease(query) }
    }

    companion object {
        private const val LIMIT = 65536
        private val storeLock = Mutex()
        private fun dictionary() = CFDictionaryCreateMutable(null, 0, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr)
            ?: throw IdentityDeviceFailure()
        private inline fun <T> nativeSafe(block: () -> T): T = try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { throw IdentityDeviceFailure() }
    }
}

private class IdentitySessionDelegate(private val complete: (Result<IdentityHttpResponse>) -> Unit) : NSObject(), NSURLSessionDataDelegateProtocol {
    private val bytes = NSMutableData()
    private var response: NSHTTPURLResponse? = null
    private var failed = false

    override fun URLSession(session: NSURLSession, dataTask: NSURLSessionDataTask, didReceiveResponse: NSURLResponse,
                            completionHandler: (NSURLSessionResponseDisposition) -> Unit) {
        response = didReceiveResponse as? NSHTTPURLResponse
        if (response == null || didReceiveResponse.expectedContentLength > 65536) {
            failed = true; completionHandler(NSURLSessionResponseCancel)
        } else completionHandler(NSURLSessionResponseAllow)
    }

    override fun URLSession(session: NSURLSession, dataTask: NSURLSessionDataTask, didReceiveData: NSData) {
        if (bytes.length + didReceiveData.length > 65536uL) { failed = true; dataTask.cancel() }
        else bytes.appendData(didReceiveData)
    }

    override fun URLSession(session: NSURLSession, task: NSURLSessionTask, willPerformHTTPRedirection: NSHTTPURLResponse,
                            newRequest: NSURLRequest, completionHandler: (NSURLRequest?) -> Unit) {
        // The original 3xx response is returned; bearer credentials are never forwarded.
        completionHandler(null)
    }

    override fun URLSession(session: NSURLSession, task: NSURLSessionTask, didCompleteWithError: NSError?) {
        try {
            val http = response
            if (failed || didCompleteWithError != null || http == null) complete(Result.failure(IdentityDeviceFailure()))
            else {
                val body = if (bytes.length == 0uL) "" else bytes.bytes!!.reinterpret<ByteVar>().readBytes(bytes.length.toInt()).decodeToString(throwOnInvalidSequence = true)
                val retry = retryAfter(http.valueForHTTPHeaderField("Retry-After"))
                complete(Result.success(IdentityHttpResponse(http.statusCode.toInt(), body, retry)))
            }
        } catch (_: Exception) { complete(Result.failure(IdentityDeviceFailure())) }
        finally { session.finishTasksAndInvalidate() }
    }

    private fun retryAfter(value: String?): Long? {
        val text = value?.trim() ?: return null
        text.toLongOrNull()?.let { return it.takeIf { seconds -> seconds >= 0 } }
        val format = NSDateFormatter().apply {
            locale = NSLocale("en_US_POSIX")
            timeZone = NSTimeZone.timeZoneForSecondsFromGMT(0)
            dateFormat = "EEE, dd MMM yyyy HH:mm:ss 'GMT'"
            lenient = false
        }
        return format.dateFromString(text)?.let {
            kotlin.math.ceil((it.timeIntervalSince1970 - NSDate().timeIntervalSince1970).coerceAtLeast(0.0)).toLong()
        }
    }
}

internal fun validateIosIdentityRequest(method: String, url: String, bearer: String?, body: String?, idempotencyKey: String? = null) {
    if (idempotencyKey != null && (!uuidPattern.matches(idempotencyKey) || method != "POST" ||
            url != "https://metrics.nkolinka.ru/v2/batches")) throw IdentityDeviceFailure()
    val components = NSURLComponents.componentsWithString(url) ?: throw IdentityDeviceFailure()
    val prefix = when {
        url.startsWith("https://api.identity.linka.su/") -> "https://api.identity.linka.su"
        url.startsWith("https://metrics.nkolinka.ru/") -> "https://metrics.nkolinka.ru"
        else -> throw IdentityDeviceFailure()
    }
    if (method !in setOf("GET", "POST", "PUT", "DELETE") || components.scheme != "https" || components.user != null ||
        components.password != null || components.port != null || components.query != null || components.fragment != null ||
        components.URL?.absoluteString != url || components.percentEncodedPath?.split('/')?.any { it == "." || it == ".." } == true ||
        url.removePrefix(prefix).any { it == '\\' || it == '%' || it.code !in 33..126 } ||
        url.removePrefix(prefix).contains("//") ||
        bearer?.any { it.code !in 33..126 } == true || body?.encodeToByteArray()?.size?.let { it > 65536 } == true
    ) throw IdentityDeviceFailure()
}
