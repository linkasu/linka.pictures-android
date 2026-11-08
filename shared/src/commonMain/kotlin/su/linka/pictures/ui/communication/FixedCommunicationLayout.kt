package su.linka.pictures.ui.communication

import org.jetbrains.compose.resources.stringResource
import su.linka.pictures.resources.*
import androidx.compose.material.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.floor
import su.linka.pictures.domain.BoardDocument
import su.linka.pictures.domain.CellKind
import su.linka.pictures.domain.PageId
import su.linka.pictures.ui.components.CommunicationCaptionStyle
import su.linka.pictures.ui.theme.LinkaTypography

internal data class FixedCommunicationBoardLayout(
    val toolbarHeight: Float,
    val phraseHeight: Float,
    val footerHeight: Float,
    val actionWidth: Float,
    val gridHeight: Float,
    val pages: Map<PageId, FixedCommunicationPageLayout>,
)

internal data class FixedCommunicationPageLayout(
    val cellWidth: Float,
    val cellHeight: Float,
    val imageSides: Map<Int, Float>,
)

@Composable
internal fun rememberCommunicationBoardMeasurer(): CommunicationBoardMeasurer {
    val text = rememberTextMeasurer()
    val density = LocalDensity.current
    val typography = LinkaTypography
    val actionLabels = listOf(stringResource(Res.string.speak), stringResource(Res.string.remove),
        stringResource(Res.string.clear), stringResource(Res.string.undo), stringResource(Res.string.stop))
    val pageLabel = stringResource(Res.string.page_1_s_of_2_s)
    return remember(text, density, typography, actionLabels, pageLabel) {
        CommunicationBoardMeasurer(text, density, typography, actionLabels, pageLabel)
    }
}

internal class CommunicationBoardMeasurer(
    private val text: TextMeasurer,
    private val systemDensity: Density,
    private val typography: Typography,
    private val actionLabels: List<String>,
    private val pageLabel: String,
) {
    fun measure(
        document: BoardDocument,
        width: Float,
        height: Float,
        textScale: Float,
        showPageButtons: Boolean = true,
    ): FixedCommunicationBoardLayout? {
        if (!width.isFinite() || !height.isFinite() || width <= 0 || height <= 0 ||
            !textScale.isFinite() || textScale <= 0) return null
        val density = Density(systemDensity.density, systemDensity.fontScale * textScale)
        if (!density.fontScale.isFinite()) return null
        with(density) {
            fun caption(label: String, style: TextStyle, maxWidth: Int): IntSize = text.measure(
                label, style = style, density = density,
                constraints = Constraints(maxWidth = maxWidth.coerceAtLeast(1)),
            ).size
            fun textHeight(label: String, style: TextStyle, available: Float): Float? {
                if (available <= 0) return null
                return caption(label, style, available.dp.roundToPx()).height.toDp().value
            }
            val stackedTitle = width < 600f && fontScale > 1f
            val titleHeight = textHeight(document.name, typography.h6, width - if (stackedTitle) 32f else 184f)
                ?: return null
            val toolbar = maxOf(communicationToolbarHeight(width, height, fontScale),
                if (stackedTitle) 48f + titleHeight + 16f else maxOf(48f, titleHeight + 16f))
            val actionStyle = typography.button.merge(TextStyle(fontSize = 12.sp, lineHeight = 14.sp, textAlign = TextAlign.Center))
            val actionWidth = maxOf((width / 8f).coerceIn(48f, 96f), actionLabels.maxOf {
                caption(it, actionStyle, Constraints.Infinity).width.toDp().value
            } + 8f)
            if (width - 3f * actionWidth < 48f) return null
            val actionHeight = actionLabels.maxOf {
                textHeight(it, actionStyle, actionWidth - 8f)!!
            } + 24f + 4f + 8f
            val captionStyle = typography.body1.merge(CommunicationCaptionStyle)
            val phraseHeight = maxOf(64f, actionHeight, caption("M", captionStyle, width.dp.roundToPx()).height.toDp().value + 8f + 6f + 24f)
            val footerHeight = if (document.pages.size == 1) 0f else maxOf(
                if (showPageButtons) 48f else 0f,
                textHeight(pageLabel.replace("%1\$s", document.pages.size.toString()).replace("%2\$s", document.pages.size.toString()), typography.body1,
                    width - if (showPageButtons) 96f else 0f) ?: return null,
            )
            val gridHeight = height - toolbar - phraseHeight - footerHeight - 1f
            if (gridHeight <= 0) return null
            val pages = document.pages.associate { page ->
                // Round down once so independently rounded cells never overflow the viewport.
                val cellSize = fixedCommunicationCellSize(width.dp.toPx(), gridHeight.dp.toPx(),
                    page.rows, page.columns, 48.dp.roundToPx()) ?: return null
                val cellWidthPx = cellSize.width
                val cellHeightPx = cellSize.height
                val innerWidth = cellWidthPx - 2 * 4.dp.roundToPx()
                val innerHeight = cellHeightPx - 2 * 4.dp.roundToPx()
                val sides = mutableMapOf<Int, Float>()
                for (cell in page.cells) {
                    if (cell.kind == CellKind.EMPTY) continue
                    if (cell.kind == CellKind.SPACE) {
                        if (24.sp.roundToPx() > innerWidth || 28.8.sp.roundToPx() > innerHeight) return null
                        continue
                    }
                    val card = cell.card!!
                    val packing = packCommunicationCell(innerWidth, innerHeight, card.label, card.image != null,
                        32.dp.roundToPx(), 6.dp.roundToPx()) { label, maxWidth ->
                        caption(label, captionStyle, maxWidth)
                    } ?: return null
                    packing.imageSide?.let { sides[cell.position] = it.toDp().value }
                }
                page.id to FixedCommunicationPageLayout(cellWidthPx.toDp().value, cellHeightPx.toDp().value, sides)
            }
            return FixedCommunicationBoardLayout(toolbar, phraseHeight, footerHeight, actionWidth, gridHeight, pages)
        }
    }
}

internal data class CommunicationCellPacking(val imageSide: Int? = null)

internal fun fixedCommunicationCellSize(width: Float, height: Float, rows: Int, columns: Int, minimumTouch: Int): IntSize? {
    val cellWidth = floor(width / columns).toInt()
    val cellHeight = floor(height / rows).toInt()
    return if (cellWidth >= minimumTouch && cellHeight >= minimumTouch) IntSize(cellWidth, cellHeight) else null
}

/** All sizes are pixels; a null side is the existing stacked or image-only presentation. */
internal fun packCommunicationCell(
    width: Int,
    height: Int,
    label: String,
    hasImage: Boolean,
    minimumImage: Int,
    gap: Int,
    measureCaption: (String, Int) -> IntSize,
): CommunicationCellPacking? {
    if (width <= 0 || height <= 0) return null
    if (hasImage && label.isBlank()) return if (minOf(width, height) >= minimumImage) CommunicationCellPacking() else null
    val longestWord = label.split(Regex("\\s+")).filter { it.isNotEmpty() }
        .maxOfOrNull { measureCaption(it, Constraints.Infinity).width } ?: 0
    if (longestWord > width) return null
    val stacked = measureCaption(label, width)
    if (!hasImage) return if (stacked.height <= height) CommunicationCellPacking() else null
    if (width >= minimumImage && stacked.height + gap + minimumImage <= height) return CommunicationCellPacking()
    val captionWidth = width - gap - minimumImage
    if (captionWidth <= 0 || longestWord > captionWidth) return null
    val beside = measureCaption(label, captionWidth)
    if (beside.height > height) return null
    val imageSide = minOf(height, width - gap - beside.width)
    if (imageSide < minimumImage) return null
    val actualWidth = width - gap - imageSide
    if (measureCaption(label, actualWidth).height > height) return null
    return CommunicationCellPacking(imageSide)
}
