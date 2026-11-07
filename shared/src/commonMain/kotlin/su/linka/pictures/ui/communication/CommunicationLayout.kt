package su.linka.pictures.ui.communication

import su.linka.pictures.ui.components.toolbarHeightForFontScale

internal data class CommunicationCellSize(val width: Float, val height: Float)

internal data class CommunicationVerticalBudget(val phrase: Float, val content: Float, val actions: Float = 0f)

internal fun communicationUsesStackedActions(width: Float, height: Float, fontScale: Float): Boolean =
    width < height && width < 600f * maxOf(1f, fontScale)

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

internal fun communicationVerticalBudget(height: Float, fontScale: Float, multiplePages: Boolean, stackedActions: Boolean = false): CommunicationVerticalBudget {
    require(height.isFinite() && height >= 0 && fontScale.isFinite() && fontScale > 0)
    val scale = maxOf(1f, fontScale)
    val actions = if (stackedActions) minOf(64f * scale, height / 3) else 0f
    val remaining = height - actions
    val fixed = 1f + if (multiplePages) 32f * scale else 0f
    // Only the grid scrolls; never grow the parent and scroll speech controls away.
    val previewMinimum = if (stackedActions) maxOf(104f, 64f * scale) else 64f * scale
    val phrase = maxOf(previewMinimum, (remaining - fixed) / 5).coerceIn(0f, remaining / 2)
    return CommunicationVerticalBudget(phrase, height, actions)
}

internal fun communicationCellSize(width: Float, height: Float, columns: Int, rows: Int, fontScale: Float,
    minimumWidth: Float = 0f, minimumHeight: Float = 0f): CommunicationCellSize {
    require(width.isFinite() && height.isFinite() && width >= 0 && height >= 0)
    require(columns > 0 && rows > 0 && fontScale.isFinite() && fontScale > 0)
    // Keep the board's dimensions and order; scroll only when readable touch targets cannot fit.
    return CommunicationCellSize(
        maxOf(56f, width / columns, minimumWidth),
        maxOf(64f * maxOf(1f, fontScale), height / rows, minimumHeight),
    )
}
