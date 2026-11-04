package su.linka.pictures.text

// Unicode 16.0 UAX #29, default extended grapheme clusters (GB3–GB999).
// Property IDs follow the pinned generator; unmatched values, including lone surrogates, are Other.
internal fun graphemeBoundaries(text: String): IntArray {
    val boundaries = IntArray(text.length + 1)
    var size = 1
    var offset = 0
    var previous = 0
    var regionalCount = 0
    var pictographicExtend = false
    var pictographicZwj = false
    var indicConsonant = false
    var indicLinker = false
    while (offset < text.length) {
        val first = text[offset].code
        val paired = first in 0xD800..0xDBFF && offset + 1 < text.length && text[offset + 1].code in 0xDC00..0xDFFF
        val point = if (paired) 0x10000 + ((first - 0xD800) shl 10) + text[offset + 1].code - 0xDC00 else first
        val current = graphemeProperty(GraphemeTables.gcb, point)
        val pictographic = graphemeProperty(GraphemeTables.extendedPictographic, point) != 0
        val indic = graphemeProperty(GraphemeTables.incb, point)
        val joins = when {
            previous == 1 && current == 2 -> true // GB3: CR × LF
            previous in 1..3 || current in 1..3 -> false // GB4, GB5
            previous == 9 && (current == 9 || current == 10 || current == 12 || current == 13) -> true // GB6
            (previous == 12 || previous == 10) && (current == 10 || current == 11) -> true // GB7
            (previous == 13 || previous == 11) && current == 11 -> true // GB8
            current == 4 || current == 5 || current == 8 -> true // GB9, GB9a
            previous == 7 -> true // GB9b
            indic == 1 && indicConsonant && indicLinker -> true // GB9c
            pictographic && pictographicZwj -> true // GB11
            previous == 6 && current == 6 && regionalCount % 2 == 1 -> true // GB12, GB13
            else -> false // GB999
        }
        if (offset != 0 && !joins) boundaries[size++] = offset

        regionalCount = if (current == 6) regionalCount + 1 else 0
        pictographicZwj = current == 5 && pictographicExtend
        pictographicExtend = pictographic || (current == 4 && pictographicExtend)
        when (indic) {
            1 -> { indicConsonant = true; indicLinker = false }
            2 -> if (indicConsonant) indicLinker = true
            3 -> Unit
            else -> { indicConsonant = false; indicLinker = false }
        }
        previous = current
        offset += if (paired) 2 else 1
    }
    if (text.isNotEmpty()) boundaries[size++] = text.length // GB1, GB2; empty has only boundary 0.
    return boundaries.copyOf(size)
}

internal fun dropLastGrapheme(text: String): String {
    val boundaries = graphemeBoundaries(text)
    return if (boundaries.size == 1) text else text.substring(0, boundaries[boundaries.lastIndex - 1])
}

private fun graphemeProperty(ranges: IntArray, point: Int): Int {
    var low = 0
    var high = ranges.size / 3 - 1
    while (low <= high) {
        val middle = (low + high) ushr 1
        val index = middle * 3
        when {
            point < ranges[index] -> high = middle - 1
            point > ranges[index + 1] -> low = middle + 1
            else -> return ranges[index + 2]
        }
    }
    return 0
}
