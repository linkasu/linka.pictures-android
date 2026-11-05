@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package su.linka.pictures.access

import kotlinx.cinterop.*
import platform.CoreCrypto.*
import platform.Security.SecRandomCopyBytes
import platform.Security.kSecRandomDefault
import platform.posix.timingsafe_bcmp

internal actual object PasswordCrypto {
    actual fun salt(): ByteArray = ByteArray(16).also { bytes ->
        bytes.usePinned { check(SecRandomCopyBytes(kSecRandomDefault, bytes.size.toULong(), it.addressOf(0)) == 0) }
    }
    actual fun derive(asciiPassword: ByteArray, salt: ByteArray, iterations: Int): ByteArray {
        require(salt.isNotEmpty() && iterations > 0)
        return ByteArray(32).also { output ->
            salt.usePinned { saltBytes -> output.usePinned { derived ->
                check(CCKeyDerivationPBKDF(kCCPBKDF2, asciiPassword.decodeToString(throwOnInvalidSequence = true), asciiPassword.size.toULong(),
                    saltBytes.addressOf(0).reinterpret(), salt.size.toULong(), kCCPRFHmacAlgSHA1,
                    iterations.toUInt(), derived.addressOf(0).reinterpret(), output.size.toULong()) == kCCSuccess)
            } }
        }
    }
    actual fun equal(left: ByteArray, right: ByteArray): Boolean {
        if (left.size != right.size) return false
        if (left.isEmpty()) return true
        return left.usePinned { a -> right.usePinned { b -> timingsafe_bcmp(a.addressOf(0), b.addressOf(0), left.size.toULong()) == 0 } }
    }
}
