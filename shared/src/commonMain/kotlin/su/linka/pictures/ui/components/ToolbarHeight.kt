package su.linka.pictures.ui.components

internal fun toolbarHeightForFontScale(baseHeight: Float, fontScale: Float): Float {
    require(baseHeight.isFinite() && baseHeight > 0f && fontScale.isFinite() && fontScale > 0f)
    return baseHeight * maxOf(1f, fontScale)
}
