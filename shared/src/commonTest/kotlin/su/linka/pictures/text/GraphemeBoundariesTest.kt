package su.linka.pictures.text

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class GraphemeBoundariesTest {
    @Test
    fun allOfficialUnicode16Vectors() {
        assertEquals(1093, graphemeBreakVectors.size)
        for ((index, vector) in graphemeBreakVectors.withIndex()) {
            val expected = mutableListOf<Int>()
            val text = buildString {
                for (token in vector.split(' ')) {
                    when (token) {
                        "÷" -> expected.add(length)
                        "×" -> Unit
                        else -> {
                            val point = token.toInt(16)
                            if (point <= 0xFFFF) append(point.toChar()) else {
                                append((0xD800 + ((point - 0x10000) shr 10)).toChar())
                                append((0xDC00 + ((point - 0x10000) and 0x3FF)).toChar())
                            }
                        }
                    }
                }
            }
            assertContentEquals(expected.toIntArray(), graphemeBoundaries(text), "vector ${index + 1}: $vector")
            assertEquals(text.substring(0, expected[expected.lastIndex - 1]), dropLastGrapheme(text), "drop vector ${index + 1}")
        }
    }

    @Test
    fun emptyAndMalformedUtf16KeepEveryCodeUnit() {
        assertContentEquals(intArrayOf(0), graphemeBoundaries(""))
        assertEquals("", dropLastGrapheme(""))
        for (text in listOf("\uD800", "\uDC00", "\uDC00\uD800", "a\uD800b\uDC00")) {
            assertContentEquals(IntArray(text.length + 1) { it }, graphemeBoundaries(text))
            assertEquals(text.dropLast(1), dropLastGrapheme(text))
        }
        assertContentEquals(intArrayOf(0, 2, 3), graphemeBoundaries("\uD83D\uDE00\uD800"))
        assertContentEquals(intArrayOf(0, 2), graphemeBoundaries("\uD800\u0301"))
    }

    @Test
    fun combiningEmojiFlagsAndIndicDropWholeClusters() {
        for (cluster in listOf("\r\n", "e\u0301", "😀", "👩‍👩‍👧‍👦", "🇷🇺", "👍🏽", "\u0915\u094D\u0915")) {
            assertContentEquals(intArrayOf(0, cluster.length), graphemeBoundaries(cluster), cluster)
            assertEquals("x", dropLastGrapheme("x$cluster"), cluster)
        }
        assertContentEquals(intArrayOf(0, 4, 6), graphemeBoundaries("🇷🇺🇦"))
    }

    @Test
    fun longContextSequencesDoNotNeedBackwardScans() {
        val extending = "👩" + "\u0301".repeat(20_000) + "‍👩"
        assertContentEquals(intArrayOf(0, extending.length), graphemeBoundaries(extending))
        val regional = "🇦".repeat(20_001)
        val expected = IntArray(10_002) { if (it == 10_001) regional.length else it * 4 }
        assertContentEquals(expected, graphemeBoundaries(regional))
        val indic = "\u0915" + "\u0301".repeat(20_000) + "\u094D\u0915"
        assertContentEquals(intArrayOf(0, indic.length), graphemeBoundaries(indic))
    }
}
