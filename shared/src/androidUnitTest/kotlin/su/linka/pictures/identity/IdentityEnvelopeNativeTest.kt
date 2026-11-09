package su.linka.pictures.identity

import java.security.KeyPairGenerator
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlin.test.*

class IdentityEnvelopeNativeTest {
    @Test fun aesKeystoreEnvelopeRoundTripAndTampering() {
        val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
        val value = ByteArray(65536) { (it % 251).toByte() }
        val sealed = IdentityEnvelope.seal(value, 1, key, byteArrayOf(), ByteArray(12) { 7 })
        assertContentEquals(byteArrayOf(0x49, 0x44, 0x56, 0x31, 1), sealed.copyOfRange(0, 5))
        assertEquals(65536 + 39, sealed.size)
        assertContentEquals(value, IdentityEnvelope.open(sealed) { type, wrapped ->
            assertEquals(1, type); assertTrue(wrapped.isEmpty()); key
        })
        for (offset in listOf(0, 4, 5, 17, 19, sealed.lastIndex)) {
            val changed = sealed.copyOf().apply { this[offset] = (this[offset].toInt() xor 1).toByte() }
            assertFails { IdentityEnvelope.open(changed) { _, _ -> key } }
        }
        assertFails { IdentityEnvelope.open(sealed + 0) { _, _ -> key } }
        assertFails { IdentityEnvelope.open(sealed.copyOf(sealed.size - 1)) { _, _ -> key } }
    }

    @Test fun api21RsaWrappedEnvelopeRoundTripAndTampering() {
        val rsa = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val key = SecretKeySpec(ByteArray(32) { (it + 1).toByte() }, "AES")
        val wrapped = Cipher.getInstance("RSA/ECB/PKCS1Padding").run { init(Cipher.ENCRYPT_MODE, rsa.public); doFinal(key.encoded) }
        val value = ByteArray(65536) { (it % 251).toByte() }
        val sealed = IdentityEnvelope.seal(value, 2, key, wrapped, ByteArray(12) { 3 })
        assertEquals(IdentityEnvelope.MAX_SIZE, sealed.size)
        assertEquals(2, sealed[4].toInt())
        assertContentEquals(wrapped, sealed.copyOfRange(19, 275))
        fun open(bytes: ByteArray) = IdentityEnvelope.open(bytes) { type, encrypted ->
            assertEquals(2, type)
            SecretKeySpec(Cipher.getInstance("RSA/ECB/PKCS1Padding").run {
                init(Cipher.DECRYPT_MODE, rsa.private); doFinal(encrypted)
            }, "AES")
        }
        assertContentEquals(value, open(sealed))
        for (offset in listOf(4, 5, 17, 18, 19, 275, sealed.lastIndex)) {
            assertFails { open(sealed.copyOf().apply { this[offset] = (this[offset].toInt() xor 1).toByte() }) }
        }
        assertFails { IdentityEnvelope.seal(value, 2, key, ByteArray(255), ByteArray(12)) }
    }

    @Test fun transportRejectsNonCanonicalAndSensitiveUrls() {
        validateIdentityRequest("PUT", "https://api.identity.linka.su/v1/public/installations/telemetry-preference", "installation-refresh", "{}")
        validateIdentityRequest("GET", "https://api.identity.linka.su/v1/public/installations/telemetry-preference", "installation-refresh", null)
        for (url in listOf("https://api.identity.linka.su/v1/register", "https://metrics.nkolinka.ru/v1/events"))
            validateIdentityRequest("POST", url, "token", "{}")
        for (url in listOf("http://api.identity.linka.su/a", "https://api.identity.linka.su:443/a",
            "https://api.identity.linka.su/a?secret", "https://api.identity.linka.su/a#fragment",
            "https://user@api.identity.linka.su/a", "https://api.identity.linka.su.evil/a",
            "https://api.identity.linka.su/a/../b")) assertFails { validateIdentityRequest("GET", url, null, null) }
        assertFails { validateIdentityRequest("GET", "https://api.identity.linka.su/a", "token\r\nInjected: true", null) }
        assertEquals("other", identityLocale("ru-KZ"))
        assertEquals("ru-RU", identityLocale("ru-RU"))
    }

    @Test fun idempotencyKeyRequiresCanonicalUuidAndMetricsPost() {
        val url = "https://metrics.nkolinka.ru/v2/batches"
        val key = "12345678-1234-4234-8234-123456789abc"
        validateIdentityRequest("POST", url, "token", "{}", key)
        assertFailsWith<IdentityDeviceFailure> { validateIdentityRequest("GET", url, null, null, key) }
        assertFailsWith<IdentityDeviceFailure> { validateIdentityRequest("POST", "https://api.identity.linka.su/v2/batches", null, "{}", key) }
        assertFailsWith<IdentityDeviceFailure> { validateIdentityRequest("POST", "$url/", null, "{}", key) }
        for (invalid in listOf(key.uppercase(), "$key\r\nInjected: true", "", "not-a-uuid"))
            assertFailsWith<IdentityDeviceFailure> { validateIdentityRequest("POST", url, null, "{}", invalid) }
    }
}
