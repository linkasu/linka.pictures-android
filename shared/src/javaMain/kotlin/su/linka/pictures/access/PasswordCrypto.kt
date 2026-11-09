package su.linka.pictures.access

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

internal actual object PasswordCrypto {
    actual fun salt(): ByteArray = ByteArray(16).also { SecureRandom().nextBytes(it) }
    actual fun derive(asciiPassword: ByteArray, salt: ByteArray, iterations: Int): ByteArray {
        val chars = CharArray(asciiPassword.size) { (asciiPassword[it].toInt() and 255).toChar() }
        val spec = PBEKeySpec(chars, salt, iterations, 256)
        chars.fill('\u0000')
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).encoded }
        finally { spec.clearPassword() }
    }
    actual fun equal(left: ByteArray, right: ByteArray): Boolean = MessageDigest.isEqual(left, right)
}
