package su.linka.pictures.access

internal expect object PasswordCrypto {
    fun salt(): ByteArray
    fun derive(asciiPassword: ByteArray, salt: ByteArray, iterations: Int): ByteArray
    fun equal(left: ByteArray, right: ByteArray): Boolean
}

internal fun passwordBytes(password: String): ByteArray {
    require(password.length <= 65_536)
    val digits = "0123456789abcdef"
    return ByteArray(password.length * 4).also { bytes ->
        password.forEachIndexed { index, char ->
            for (part in 0..3) bytes[index * 4 + part] = digits[(char.code ushr (12 - part * 4)) and 15].code.toByte()
        }
    }
}
