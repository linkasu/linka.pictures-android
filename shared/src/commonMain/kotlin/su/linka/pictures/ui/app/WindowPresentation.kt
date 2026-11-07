package su.linka.pictures.ui.app

/** Keep room for both the board and a readable, scrollable form at larger text sizes. */
internal fun editorUsesInspector(width: Float, height: Float, fontScale: Float): Boolean {
    val scale = fontScale.coerceAtLeast(1f)
    return width >= 900f * scale && height >= 480f * scale
}
