package su.linka.pictures.ui.components

/** Base sp only: callers must retain the user's font scale when displaying the caption. */
internal fun communicationCaptionSize(
    label: String,
    heightDp: Float,
    availableWidthPx: Float,
    measureWord: (String, Float) -> Float,
): Float {
    val preferred = if (heightDp.isFinite() && heightDp >= 0f) (heightDp / 6f).coerceAtLeast(12f) else 14f
    if (availableWidthPx == Float.POSITIVE_INFINITY) return preferred
    if (!availableWidthPx.isFinite() || availableWidthPx <= 0f) return 12f
    val longestWordWidth = label.splitToSequence(Regex("\\s+"))
        .filter { it.isNotEmpty() }
        .maxOfOrNull { measureWord(it, preferred) } ?: return preferred
    if (!longestWordWidth.isFinite() || longestWordWidth < 0f) return 12f
    if (longestWordWidth <= availableWidthPx) return preferred
    return (preferred * ((availableWidthPx - 1f).coerceAtLeast(0f) / longestWordWidth)).coerceAtLeast(12f)
}
