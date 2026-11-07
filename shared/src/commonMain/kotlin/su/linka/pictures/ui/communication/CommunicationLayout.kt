package su.linka.pictures.ui.communication

import su.linka.pictures.ui.components.toolbarHeightForFontScale

internal data class CommunicationCellSize(val width: Float, val height: Float)

internal data class CommunicationVerticalBudget(val phrase: Float, val content: Float)

internal fun communicationMessagePartWidth(availableWidth: Float, height: Float, partCount: Int): Float {
    // Short landscape windows need caption room, not a square narrower than its own padding and label.
    val preferred = if (height.isFinite() && height > 0f) maxOf(96f, height) else 48f
    val visible = partCount.coerceIn(1, 2)
    return if (availableWidth.isFinite() && availableWidth > 0f) {
        maxOf(48f, minOf(preferred, availableWidth / visible))
    } else preferred
}

internal fun communicationToolbarHeight(width: Float, height: Float, fontScale: Float = 1f): Float {
    require(width.isFinite() && height.isFinite() && width >= 0 && height >= 0)
    val base = when {
        minOf(width, height) >= 600f -> 64f
        width > height -> 48f
        else -> 56f
    }
    return toolbarHeightForFontScale(base, fontScale)
}

internal fun communicationVerticalBudget(height: Float, fontScale: Float, multiplePages: Boolean): CommunicationVerticalBudget {
    require(height.isFinite() && height >= 0 && fontScale.isFinite() && fontScale > 0)
    val scale = maxOf(1f, fontScale)
    val fixed = 17f + if (multiplePages) 32f * scale else 0f
    // Only the grid scrolls; never grow the parent and scroll speech controls away.
    val phrase = maxOf(64f * scale, (height - fixed) / 5).coerceIn(0f, height / 2)
    return CommunicationVerticalBudget(phrase, height)
}

internal fun communicationCellSize(width: Float, height: Float, columns: Int, rows: Int, fontScale: Float): CommunicationCellSize {
    require(width.isFinite() && height.isFinite() && width >= 0 && height >= 0)
    require(columns > 0 && rows > 0 && fontScale.isFinite() && fontScale > 0)
    // Keep the board's dimensions and order; scroll only when readable touch targets cannot fit.
    return CommunicationCellSize(
        maxOf(56f, width / columns),
        maxOf(64f * maxOf(1f, fontScale), height / rows),
    )
}
