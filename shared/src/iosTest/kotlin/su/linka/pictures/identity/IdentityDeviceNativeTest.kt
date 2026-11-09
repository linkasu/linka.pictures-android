package su.linka.pictures.identity

import kotlin.test.*

class IdentityDeviceNativeTest {
    @Test fun unregisteredOrMalformedSignaturesDeny() {
        val device = IosIdentityDevice()
        assertFalse(device.verifyEd25519(ByteArray(32), byteArrayOf(), ByteArray(64)))
        assertFalse(device.verifyEd25519(ByteArray(31), byteArrayOf(), ByteArray(64)))
        assertFalse(device.verifyEd25519(ByteArray(32), byteArrayOf(), ByteArray(63)))
        assertEquals(32, device.randomBytes(32).size)
        assertFailsWith<IdentityDeviceFailure> { device.randomBytes(65537) }
    }

    @Test fun transportRejectsNonCanonicalUrlsAndHeaderInjection() {
        validateIosIdentityRequest("PUT", "https://api.identity.linka.su/v1/public/installations/telemetry-preference", "installation-refresh", "{}")
        validateIosIdentityRequest("GET", "https://api.identity.linka.su/v1/public/installations/telemetry-preference", "installation-refresh", null)
        validateIosIdentityRequest("POST", "https://api.identity.linka.su/v1/register", "token", "{}")
        validateIosIdentityRequest("POST", "https://metrics.nkolinka.ru/v1/events", "token", "{}")
        for (url in listOf("http://api.identity.linka.su/a", "https://api.identity.linka.su:443/a",
            "https://api.identity.linka.su/a?secret", "https://api.identity.linka.su/a#fragment",
            "https://user@api.identity.linka.su/a", "https://api.identity.linka.su.evil/a",
            "https://api.identity.linka.su/a/../b")) assertFailsWith<IdentityDeviceFailure> {
            validateIosIdentityRequest("GET", url, null, null)
        }
        assertFailsWith<IdentityDeviceFailure> {
            validateIosIdentityRequest("GET", "https://api.identity.linka.su/a", "token\r\nInjected: true", null)
        }
    }

    @Test fun idempotencyKeyRequiresCanonicalUuidAndMetricsPost() {
        val url = "https://metrics.nkolinka.ru/v2/batches"
        val key = "12345678-1234-4234-8234-123456789abc"
        validateIosIdentityRequest("POST", url, "token", "{}", key)
        assertFailsWith<IdentityDeviceFailure> { validateIosIdentityRequest("GET", url, null, null, key) }
        assertFailsWith<IdentityDeviceFailure> { validateIosIdentityRequest("POST", "https://api.identity.linka.su/v2/batches", null, "{}", key) }
        assertFailsWith<IdentityDeviceFailure> { validateIosIdentityRequest("POST", "$url/", null, "{}", key) }
        for (invalid in listOf(key.uppercase(), "$key\r\nInjected: true", "", "not-a-uuid"))
            assertFailsWith<IdentityDeviceFailure> { validateIosIdentityRequest("POST", url, null, "{}", invalid) }
    }
}
