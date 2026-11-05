package su.linka.pictures.text

internal fun isWellFormedUtf16(value: String): Boolean {
    var index = 0
    while (index < value.length) {
        val unit = value[index].code
        if (unit in 0xD800..0xDBFF) {
            if (index + 1 == value.length || value[index + 1].code !in 0xDC00..0xDFFF) return false
            index += 2
        } else {
            if (unit in 0xDC00..0xDFFF) return false
            index++
        }
    }
    return true
}

// Native C-string bridges may truncate NUL; this validates content, never escapes SQL.
internal fun isNativeText(value: String): Boolean = '\u0000' !in value && isWellFormedUtf16(value)
