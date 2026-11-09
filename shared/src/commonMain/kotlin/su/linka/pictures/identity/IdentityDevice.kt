package su.linka.pictures.identity

/** Native transport and device-only credentials; never part of a library or exported backup. */
internal interface IdentityDevice {
    val platform: String
    val appVersion: String
    val appBuild: String
    val osVersion: String
    val locale: String
    fun nowMillis(): Long
    fun randomBytes(size: Int): ByteArray
    fun verifyEd25519(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean
    suspend fun readSecure(): ByteArray?
    suspend fun writeSecure(value: ByteArray)
    /** HTTPS only, no redirects. The sender supplies the immutable metrics batch's idempotency key. */
    suspend fun request(method: String, url: String, bearer: String? = null, body: String? = null, idempotencyKey: String? = null): IdentityHttpResponse
}

internal data class IdentityHttpResponse(val status: Int, val body: String, val retryAfterSeconds: Long? = null)

internal class IdentityDeviceFailure : Exception()
