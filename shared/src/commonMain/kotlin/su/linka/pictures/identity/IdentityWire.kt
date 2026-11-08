@file:OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class, kotlin.time.ExperimentalTime::class)

package su.linka.pictures.identity

import kotlin.io.encoding.Base64
import kotlin.time.Instant
import kotlinx.serialization.json.*

internal const val IDENTITY_ORIGIN = "https://api.identity.linka.su"
internal const val IDENTITY_PRODUCT = "linka-pictures"
internal const val INSTALLATION_POLICY = "2026-07-19-v3"
internal const val REGISTRATION_POLICY = "product-registration-v1"
internal val identityJson = Json { encodeDefaults = true }
internal fun jsonObject(value: String) = identityJson.parseToJsonElement(value).jsonObject
internal fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.let { require(it.isString); it.content }
internal fun JsonObject.number(key: String): Long = getValue(key).jsonPrimitive.let { require(!it.isString); it.long }
internal fun timestamp(millis: Long): String = Instant.fromEpochMilliseconds(millis).toString()
internal fun timeMillis(value: String): Long = Instant.parse(value).toEpochMilliseconds()
internal fun retryDeadline(now: Long, seconds: Long?): Long {
    val delay = (seconds ?: 60).coerceAtLeast(1)
    if (delay > Long.MAX_VALUE / 1000) return Long.MAX_VALUE
    val millis = delay * 1000
    return if (now > Long.MAX_VALUE - millis) Long.MAX_VALUE else now + millis
}
internal fun encode64(bytes: ByteArray): String = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(bytes)
internal fun decode64(value: String): ByteArray {
    require(value.isNotEmpty() && value.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_' })
    return Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).decode(value).also { require(encode64(it) == value) }
}
internal val opaqueKey = Regex("[a-f0-9]{64}")
internal val uuidPattern = Regex("[a-f0-9]{8}-[a-f0-9]{4}-[1-5][a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}")
internal fun IdentityDevice.uuid(): String {
    val bytes = randomBytes(16)
    require(bytes.size == 16)
    bytes[6] = ((bytes[6].toInt() and 15) or 64).toByte()
    bytes[8] = ((bytes[8].toInt() and 63) or 128).toByte()
    val hex = bytes.joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
    return "${hex.take(8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}"
}

internal fun validateAccountResponse(response: JsonObject, installation: String) {
    require(response.keys == setOf("contract_version", "product", "account_key", "installation_key", "account_profile",
        "account_token", "refresh_token", "refresh_expires_at", "registration_receipt", "metrics_token"))
    require(response.number("contract_version") == 1L && response.text("product") == IDENTITY_PRODUCT)
    require(response.text("installation_key") == installation && opaqueKey.matches(installation) && opaqueKey.matches(response.text("account_key")))
    val profile = response.getValue("account_profile").jsonObject
    require(profile.keys == setOf("product", "account_key", "version", "holder_role", "country", "region", "updated_at"))
    require(profile.text("product") == IDENTITY_PRODUCT && profile.text("account_key") == response.text("account_key"))
    require(profile.getValue("version").jsonPrimitive.let { !it.isString && it.content.toULong() > 0u })
    require(profile.text("holder_role") in setOf("self", "representative"))
    require(Regex("([A-Z]{2})?").matches(profile.text("country")) && Regex("([A-Z]{2}-[A-Z0-9]{1,3})?").matches(profile.text("region")))
    timeMillis(profile.text("updated_at"))
    val token = response.getValue("account_token").jsonObject
    require(token.keys == setOf("access_token", "token_type", "expires_at") && token.text("token_type") == "Bearer" && token.text("access_token").isNotBlank())
    timeMillis(token.text("expires_at"))
    timeMillis(response.text("refresh_expires_at"))
    require(decode64(response.text("refresh_token")).size == 32 && response.text("registration_receipt").length in 1..8192)
}

/** The only offline trust anchor is a key fetched by the native HTTPS transport and saved securely. */
internal fun validateReceipt(device: IdentityDevice, receipt: String, key: JsonObject,
    installation: String, account: String, role: String) {
    require(receipt.length <= 8192 && opaqueKey.matches(installation) && opaqueKey.matches(account))
    val parts = receipt.split('.')
    require(parts.size == 3)
    val header = jsonObject(decode64(parts[0]).decodeToString(throwOnInvalidSequence = true))
    require(header.keys.all { it in setOf("alg", "kid", "typ") })
    require(header.text("alg") == "EdDSA" && header.text("kid").isNotBlank())
    require(header["typ"] == null || header.text("typ") == "JWT")
    require(key.text("kid") == header.text("kid") && key.text("kty") == "OKP" && key.text("crv") == "Ed25519")
    require(key["alg"] == null || key.text("alg") == "EdDSA")
    require(key["use"] == null || key.text("use") == "sig")
    require("d" !in key)
    val publicKey = decode64(key.text("x"))
    val signature = decode64(parts[2])
    require(publicKey.size == 32 && signature.size == 64)
    require(device.verifyEd25519(publicKey, "${parts[0]}.${parts[1]}".encodeToByteArray(), signature))
    val claims = jsonObject(decode64(parts[1]).decodeToString(throwOnInvalidSequence = true))
    require(claims.keys == setOf("contract_version", "iss", "sub", "aud", "product", "subject_type",
        "installation_key", "holder_role", "policy_version", "verified_at", "iat", "jti"))
    require(claims.number("contract_version") == 1L && claims.text("iss") == IDENTITY_ORIGIN)
    require(claims.text("aud") == "linka-product-registration" && claims.text("product") == IDENTITY_PRODUCT)
    require(claims.text("subject_type") == "registration_receipt" && claims.text("installation_key") == installation)
    require(claims.text("sub") == account && role in setOf("self", "representative") && claims.text("holder_role") == role)
    require(claims.text("policy_version") == REGISTRATION_POLICY && uuidPattern.matches(claims.text("jti")))
    require(claims.number("verified_at") > 0 && claims.number("iat") >= claims.number("verified_at"))
}
