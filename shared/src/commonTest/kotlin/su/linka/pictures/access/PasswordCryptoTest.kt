package su.linka.pictures.access

import kotlin.test.*

class PasswordCryptoTest {
    @Test fun platformPbkdf2MatchesIndependentSha1VectorsIncludingEmbeddedNulAndEmptyPassword() {
        val vectors = listOf(
            Triple("password", "salt", 1) to "0c60c80f961f0e71f3a9b524af6012062fe037a6e0f0eb94fe8fc46bdc637164",
            Triple("password", "salt", 2) to "ea6c014dc72d6f8ccd1ed92ace1d41f0d8de8957cae93136266537a8d7bf4b76",
            Triple("pass\u0000word", "sa\u0000lt", 4096) to "56fa6aa75548099dcc37d7f03425e0c37f1c42b2e380c9bcd397764eb05be64a",
            Triple("", "salt", 1) to "a33dddc30478185515311f8752895d36ea4363a2000d3d26b98e7dffaaa13c3c",
        )
        for ((input, expected) in vectors) {
            val derived = PasswordCrypto.derive(input.first.encodeToByteArray(), input.second.encodeToByteArray(), input.third)
            assertEquals(expected, derived.hex())
            assertTrue(PasswordCrypto.equal(derived, derived.copyOf()))
            assertFalse(PasswordCrypto.equal(derived, derived.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }))
            assertFalse(PasswordCrypto.equal(derived, derived.copyOf(31)))
        }
    }

    @Test fun utf16EncodingPreservesEveryCodeUnitAndProductionWorkFactorMatchesIndependentVector() {
        val text = " мёд👩‍👩‍👧‍👦\u0000\uD800 "
        val encoded = passwordBytes(text)
        assertEquals("0020043c04510434d83ddc69200dd83ddc69200dd83ddc67200dd83ddc660000d8000020", encoded.decodeToString())
        assertEquals("70561a17a1f4a0f70b79678a6e6f1fc3bb1c9205340688574a92ba8b71e0a8d6",
            PasswordCrypto.derive(encoded, ByteArray(16) { it.toByte() }, 1_300_000).hex())
        assertFalse(passwordBytes("é").contentEquals(passwordBytes("e\u0301")))
        assertEquals(65_536 * 4, passwordBytes("x".repeat(65_536)).size)
        assertFailsWith<IllegalArgumentException> { passwordBytes("x".repeat(65_537)) }
        val first = PasswordCrypto.salt()
        assertEquals(16, first.size)
        assertFalse(first.contentEquals(PasswordCrypto.salt()))
    }

    private fun ByteArray.hex(): String = joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
}
