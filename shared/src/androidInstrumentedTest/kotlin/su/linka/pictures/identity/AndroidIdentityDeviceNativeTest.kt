package su.linka.pictures.identity

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AndroidIdentityDeviceNativeTest {
    @Test fun rfc8032Ed25519AndNativeRandom() {
        val device = AndroidIdentityDevice(InstrumentationRegistry.getInstrumentation().targetContext)
        val key = hex("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a")
        val signature = hex("e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b")
        assertTrue(device.verifyEd25519(key, byteArrayOf(), signature))
        assertFalse(device.verifyEd25519(key, byteArrayOf(1), signature))
        assertFalse(device.verifyEd25519(key.copyOf(31), byteArrayOf(), signature))
        assertFalse(device.verifyEd25519(key, byteArrayOf(), signature.copyOf(63)))
        assertFalse(device.randomBytes(32).contentEquals(device.randomBytes(32)))
    }

    @Test fun nativeKeystoreEnvelopeReopensAndFailsClosedAfterTampering() = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        // Only ciphertext is isolated; the nonexportable identity key is reused and never deleted.
        val directory = File(base.noBackupFilesDir, "identity-native-check-${System.nanoTime()}")
        val isolated = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = directory
        }
        val file = File(directory, "identity-v1/credential")
        try {
            val value = ByteArray(65536) { (it % 251).toByte() }
            val device = AndroidIdentityDevice(isolated)
            assertNull(device.readSecure())
            device.writeSecure(value)
            assertArrayEquals(value, AndroidIdentityDevice(isolated).readSecure())
            val envelope = file.readBytes()
            assertTrue(envelope[4].toInt() in 1..2)
            file.writeBytes(envelope.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() })
            try { device.readSecure(); fail("Tampered ciphertext accepted") } catch (_: IdentityDeviceFailure) { }
            val corrupted = file.readBytes()
            try { device.writeSecure(byteArrayOf(1)); fail("Corrupt ciphertext replaced") } catch (_: IdentityDeviceFailure) { }
            assertArrayEquals(corrupted, file.readBytes())
        } finally {
            file.delete()
            File(file.path + ".bak").delete()
            File(file.path + ".new").delete()
            file.parentFile?.delete()
            directory.delete()
        }
    }

    private fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
