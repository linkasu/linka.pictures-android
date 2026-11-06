package su.linka.pictures.ui.communication

internal data class CommunicationCellSize(val width: Float, val height: Float)

internal data class CommunicationVerticalBudget(val phrase: Float, val content: Float)

internal fun communicationVerticalBudget(height: Float, fontScale: Float, multiplePages: Boolean): CommunicationVerticalBudget {
    require(height.isFinite() && height >= 0 && fontScale.isFinite() && fontScale > 0)
    val scale = maxOf(1f, fontScale)
    val phrase = maxOf(48f * scale, height / 5)
    val minimum = phrase + 64f * scale + 17f + if (multiplePages) 32f * scale else 0f
    return CommunicationVerticalBudget(phrase, maxOf(height, minimum))
}

internal fun communicationCellSize(width: Float, height: Float, columns: Int, rows: Int, fontScale: Float): CommunicationCellSize {
    require(width.isFinite() && height.isFinite() && width >= 0 && height >= 0)
    require(columns > 0 && rows > 0 && fontScale.isFinite() && fontScale > 0)
    // Keep the board's dimensions and order; scroll only when readable touch targets cannot fit.
    return CommunicationCellSize(
        maxOf(48f, (width - 4f * (columns - 1)) / columns),
        maxOf(64f * maxOf(1f, fontScale), (height - 4f * (rows - 1)) / rows),
    )
}
