package su.linka.pictures.ui.components

/** Find the narrowest caption width that preserves its measured number of lines. */
internal fun communicationCaptionWidth(
    availableWidthPx: Int,
    originalLineCount: Int,
    hasOrphan: Boolean,
    measureLineCount: (Int) -> Int,
): Int {
    if (!hasOrphan || originalLineCount <= 1 || availableWidthPx <= 1) return availableWidthPx
    var low = 1
    var high = availableWidthPx
    while (low < high) {
        val middle = low + (high - low) / 2
        if (measureLineCount(middle) <= originalLineCount) high = middle else low = middle + 1
    }
    return if (measureLineCount(low) == originalLineCount) low else availableWidthPx
}
