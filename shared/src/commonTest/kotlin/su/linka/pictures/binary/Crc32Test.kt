package su.linka.pictures.binary

import kotlin.test.*

class Crc32Test {
    @Test fun emptyAndStandardVectorKeepOneArgumentSemantics() {
        assertEquals(0L, Crc32().value)
        assertEquals(0xcbf43926L, Crc32().also { it.update("123456789".encodeToByteArray()) }.value)
    }

    @Test fun slicesAndDefaultRemainingCountMatchTheStandardVector() {
        val bytes = "--123456789++".encodeToByteArray()
        val sliced = Crc32().also { it.update(bytes, 2, 9) }
        val streamed = Crc32().also {
            it.update(bytes, 2, 3)
            it.update("456789".encodeToByteArray(), 0)
            it.update(bytes, bytes.size, 0)
        }
        assertEquals(0xcbf43926L, sliced.value)
        assertEquals(sliced.value, streamed.value)
    }

    @Test fun invalidSlicesDoNotMutateChecksum() {
        val crc = Crc32()
        val bytes = byteArrayOf(1, 2, 3)
        for ((offset, count) in listOf(-1 to 1, 0 to -1, 4 to 0, 2 to 2, Int.MAX_VALUE to 1)) {
            assertFailsWith<IllegalArgumentException> { crc.update(bytes, offset, count) }
            assertEquals(0L, crc.value)
        }
        assertFailsWith<IllegalArgumentException> { crc.update(bytes, 4) }
    }
}
