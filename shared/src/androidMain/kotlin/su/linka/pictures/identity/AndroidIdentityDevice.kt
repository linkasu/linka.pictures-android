package su.linka.pictures.identity

import android.content.Context
import android.os.Build
import android.security.KeyPairGeneratorSpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.math.BigInteger
import java.net.URI
import java.net.URL
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.HttpsURLConnection
import javax.security.auth.x500.X500Principal
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class AndroidIdentityDevice(context: Context) : IdentityDevice {
    private val context = context.applicationContext
    private val random = SecureRandom()
    private val file = AtomicFile(File(this.context.noBackupFilesDir, "identity-v1/credential"))
    override val platform = "android"
    private val packageInfo get() = context.packageManager.getPackageInfo(context.packageName, 0)
    override val appVersion get() = packageInfo.versionName ?: "unknown"
    @Suppress("DEPRECATION")
    override val appBuild get() = if (Build.VERSION.SDK_INT >= 28) packageInfo.longVersionCode.toString() else packageInfo.versionCode.toString()
    override val osVersion get() = Build.VERSION.RELEASE ?: "unknown"
    override val locale get() = identityLocale(Locale.getDefault().toLanguageTag())
    override fun nowMillis() = System.currentTimeMillis()
    override fun randomBytes(size: Int): ByteArray = safe { if (size !in 1..LIMIT) throw IdentityDeviceFailure(); ByteArray(size).also(random::nextBytes) }
    override fun verifyEd25519(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        if (publicKey.size != 32 || signature.size != 64) return false
        return try {
            Ed25519Signer().run {
                init(false, Ed25519PublicKeyParameters(publicKey, 0))
                update(message, 0, message.size)
                verifySignature(signature)
            }
        } catch (_: Exception) { false }
    }

    override suspend fun readSecure(): ByteArray? = withContext(Dispatchers.IO) {
        storeLock.withLock { safe {
            if (!exists()) return@safe null
            readExisting(keyStore())
        } }
    }

    override suspend fun writeSecure(value: ByteArray) = withContext(Dispatchers.IO) {
        storeLock.withLock { safe {
            if (value.isEmpty() || value.size > LIMIT) throw IdentityDeviceFailure()
            val store = keyStore()
            val existing = exists()
            // Validate the existing envelope before any mutation, including key generation.
            if (existing) readExisting(store)
            if (!store.containsAlias(ALIAS)) generateKey()
            val key: SecretKey
            val wrapped: ByteArray
            val type: Int
            // Existing RSA keys stay usable after an OS upgrade; never replace a device key.
            if (store.getKey(ALIAS, null) is SecretKey) {
                type = 1; key = store.getKey(ALIAS, null) as SecretKey; wrapped = byteArrayOf()
            } else {
                type = 2; key = SecretKeySpec(randomBytes(32), "AES")
                wrapped = Cipher.getInstance("RSA/ECB/PKCS1Padding").run {
                    init(Cipher.ENCRYPT_MODE, store.getCertificate(ALIAS).publicKey); doFinal(key.encoded)
                }
            }
            val encoded = IdentityEnvelope.seal(value, type, key, wrapped, randomBytes(12))
            if (!file.baseFile.parentFile!!.let { it.isDirectory || it.mkdirs() }) throw IdentityDeviceFailure()
            currentCoroutineContext().ensureActive()
            val output = file.startWrite()
            try { output.write(encoded); file.finishWrite(output) }
            catch (failure: Exception) { file.failWrite(output); throw failure }
        } }
    }

    override suspend fun request(method: String, url: String, bearer: String?, body: String?, idempotencyKey: String?): IdentityHttpResponse {
        validateIdentityRequest(method, url, bearer, body, idempotencyKey)
        // URLConnection consults the process-wide handler even with per-request cookies disabled.
        if (java.net.CookieHandler.getDefault() != null) throw IdentityDeviceFailure()
        return suspendCancellableCoroutine { reply ->
            val connection = AtomicReference<HttpsURLConnection?>()
            val worker = CoroutineScope(Dispatchers.IO).launch {
                try {
                    ensureActive()
                    val http = URL(url).openConnection() as HttpsURLConnection
                    connection.set(http)
                    if (!reply.isActive) { http.disconnect(); return@launch }
                    try {
                        http.instanceFollowRedirects = false
                        http.connectTimeout = 15_000; http.readTimeout = 15_000
                        http.useCaches = false; http.defaultUseCaches = false
                        http.requestMethod = method
                        http.setRequestProperty("Accept", "application/json")
                        // Explicitly suppress CookieHandler-provided cookies.
                        http.setRequestProperty("Cookie", "")
                        http.setRequestProperty("Cache-Control", "no-store")
                        bearer?.let { http.setRequestProperty("Authorization", "Bearer $it") }
                        idempotencyKey?.let { http.setRequestProperty("Idempotency-Key", it) }
                        body?.encodeToByteArray()?.let { bytes ->
                            http.doOutput = true
                            http.setRequestProperty("Content-Type", "application/json")
                            http.setFixedLengthStreamingMode(bytes.size)
                            http.outputStream.use { it.write(bytes) }
                        }
                        val status = http.responseCode
                        val stream = if (status >= 400) http.errorStream else http.inputStream
                        val bytes = stream?.use { readBounded(it, LIMIT) } ?: byteArrayOf()
                        val result = IdentityHttpResponse(status, bytes.decodeToString(throwOnInvalidSequence = true),
                            retryAfter(http.getHeaderField("Retry-After")))
                        if (reply.isActive) reply.resume(result)
                    } finally { http.disconnect() }
                } catch (_: Exception) { if (reply.isActive) reply.resumeWithException(IdentityDeviceFailure()) }
            }
            reply.invokeOnCancellation { connection.get()?.disconnect(); worker.cancel() }
        }
    }

    private fun exists() = file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()
    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private fun readExisting(store: KeyStore): ByteArray {
        if (!store.containsAlias(ALIAS)) throw IdentityDeviceFailure()
        return IdentityEnvelope.open(file.openRead().use { readBounded(it, IdentityEnvelope.MAX_SIZE) }) { type, wrapped ->
            if (type == 1) store.getKey(ALIAS, null) as? SecretKey ?: throw IdentityDeviceFailure()
            else {
                val raw = Cipher.getInstance("RSA/ECB/PKCS1Padding").run {
                    init(Cipher.DECRYPT_MODE, store.getKey(ALIAS, null)); doFinal(wrapped)
                }
                if (raw.size != 32) throw IdentityDeviceFailure()
                SecretKeySpec(raw, "AES")
            }
        }
    }
    @Suppress("DEPRECATION")
    private fun generateKey() {
        if (Build.VERSION.SDK_INT >= 23) {
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256).setRandomizedEncryptionRequired(true).build())
            }.generateKey()
        } else {
            val end = Calendar.getInstance().apply { add(Calendar.YEAR, 50) }.time
            KeyPairGenerator.getInstance("RSA", "AndroidKeyStore").apply {
                initialize(KeyPairGeneratorSpec.Builder(context).setAlias(ALIAS).setKeySize(2048)
                    .setSubject(X500Principal("CN=Identity v1")).setSerialNumber(BigInteger.ONE)
                    .setStartDate(Calendar.getInstance().time).setEndDate(end).build())
            }.generateKeyPair()
        }
    }

    companion object {
        private const val ALIAS = "su.linka.pictures.identity.v1"
        internal const val LIMIT = 65536
        private val storeLock = Mutex()
        private inline fun <T> safe(block: () -> T): T = try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { throw IdentityDeviceFailure() }
        private fun readBounded(input: java.io.InputStream, bound: Int): ByteArray {
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (true) {
                val size = input.read(buffer)
                if (size < 0) break
                if (output.size() + size > bound) throw IdentityDeviceFailure()
                output.write(buffer, 0, size)
            }
            return output.toByteArray()
        }
        private fun retryAfter(value: String?): Long? {
            val text = value?.trim() ?: return null
            text.toLongOrNull()?.let { return it.takeIf { seconds -> seconds >= 0 } }
            return try {
                val format = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).apply {
                    timeZone = java.util.TimeZone.getTimeZone("GMT"); isLenient = false
                }
                format.parse(text)?.time?.let { ((it - System.currentTimeMillis()).coerceAtLeast(0) + 999) / 1000 }
            } catch (_: Exception) { null }
        }
    }
}

/** v1 (big-endian): IDV1 | type:u8 | IV:12 | wrapped-size:u16 | wrapped-key | ciphertext-size:i32 | ciphertext+tag.
 * The complete header (including the wrapped key) is GCM AAD. */
internal object IdentityEnvelope {
    const val MAX_SIZE = 65536 + 295
    fun seal(value: ByteArray, type: Int, key: SecretKey, wrapped: ByteArray, iv: ByteArray): ByteArray {
        if (value.size !in 1..65536 || iv.size != 12 || (type == 1 && wrapped.isNotEmpty()) ||
            (type == 2 && wrapped.size != 256) || type !in 1..2) throw IdentityDeviceFailure()
        val header = ByteArrayOutputStream().also { bytes -> DataOutputStream(bytes).apply {
            writeInt(0x49445631); writeByte(type); write(iv); writeShort(wrapped.size); write(wrapped); writeInt(value.size + 16)
        } }.toByteArray()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        // Android Keystore generates its own IV when encrypting; regular AES keys use the supplied random IV.
        if (type == 1) cipher.init(Cipher.ENCRYPT_MODE, key) else cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        if (cipher.iv.size != 12) throw IdentityDeviceFailure()
        val actualHeader = header.copyOf().apply { cipher.iv.copyInto(this, 5) }
        cipher.updateAAD(actualHeader)
        return actualHeader + cipher.doFinal(value)
    }

    fun open(envelope: ByteArray, key: (Int, ByteArray) -> SecretKey): ByteArray {
        if (envelope.size !in 39..MAX_SIZE) throw IdentityDeviceFailure()
        val input = DataInputStream(ByteArrayInputStream(envelope))
        if (input.readInt() != 0x49445631) throw IdentityDeviceFailure()
        val type = input.readUnsignedByte()
        val iv = ByteArray(12).also(input::readFully)
        val wrappedSize = input.readUnsignedShort()
        if ((type == 1 && wrappedSize != 0) || (type == 2 && wrappedSize != 256) || type !in 1..2) throw IdentityDeviceFailure()
        val wrapped = ByteArray(wrappedSize).also(input::readFully)
        val size = input.readInt()
        if (size !in 17..65552 || input.available() != size) throw IdentityDeviceFailure()
        val headerSize = envelope.size - size
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, key(type, wrapped), GCMParameterSpec(128, iv))
            updateAAD(envelope, 0, headerSize)
            doFinal(envelope, headerSize, size)
        }
    }
}

internal fun identityLocale(tag: String) = when (tag) { "ru", "ru-RU", "en", "en-US" -> tag; else -> "other" }

internal fun validateIdentityRequest(method: String, url: String, bearer: String?, body: String?, idempotencyKey: String? = null) {
    try {
        if (idempotencyKey != null && (!uuidPattern.matches(idempotencyKey) || method != "POST" ||
                url != "https://metrics.nkolinka.ru/v2/batches")) throw IdentityDeviceFailure()
        val uri = URI(url)
        if (method !in setOf("GET", "POST", "PUT", "DELETE") || uri.scheme != "https" ||
            uri.rawAuthority !in setOf("api.identity.linka.su", "metrics.nkolinka.ru") ||
            uri.rawQuery != null || uri.rawFragment != null || uri.rawUserInfo != null ||
            uri.rawPath.isNullOrEmpty() || !uri.rawPath.startsWith("/") || uri.normalize() != uri ||
            uri.rawPath.any { it == '\\' || it == '%' || it.code !in 33..126 } || uri.rawPath.contains("//") ||
            bearer?.any { it.code !in 33..126 } == true || body?.encodeToByteArray()?.size?.let { it > 65536 } == true
        ) throw IdentityDeviceFailure()
    } catch (_: Exception) { throw IdentityDeviceFailure() }
}
