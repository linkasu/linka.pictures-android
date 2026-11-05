package su.linka.pictures.access

import kotlinx.serialization.Serializable

@Serializable
internal class CredentialRecord(
    val schema: Int = 1,
    val encoding: String = "utf16hex",
    val iterations: Int = 1_300_000,
    val enabled: Boolean,
    val legacy: Boolean = false,
    val salt: ByteArray = byteArrayOf(),
    val verifier: ByteArray = byteArrayOf(),
) {
    fun valid(): Boolean = schema == 1 && encoding == "utf16hex" && iterations == 1_300_000 &&
        if (enabled) salt.size == 16 && verifier.size == 32 else !legacy && salt.isEmpty() && verifier.isEmpty()
}
