package su.linka.pictures.ui.communication

import org.jetbrains.compose.resources.stringResource
import su.linka.pictures.resources.*
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.CollectionInfo
import androidx.compose.ui.semantics.CollectionItemInfo
import androidx.compose.ui.semantics.collectionInfo
import androidx.compose.ui.semantics.collectionItemInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import su.linka.pictures.domain.AssetId
import su.linka.pictures.domain.BoardPage
import su.linka.pictures.domain.CardId
import su.linka.pictures.domain.CellKind
import su.linka.pictures.ui.components.CommunicationCard
import su.linka.pictures.ui.images.DemandVisibleImages
import su.linka.pictures.ui.images.visibleRowImages
import su.linka.pictures.ui.theme.LocalLinkaPalette

@Composable
internal fun CommunicationGrid(
    page: BoardPage?,
    firstCell: Int,
    selectedCards: Set<CardId>,
    enabled: Boolean,
    composing: Boolean,
    images: Map<AssetId, ImageBitmap>,
    onSelect: (Int) -> Unit,
    pageLayout: FixedCommunicationPageLayout,
    modifier: Modifier,
    dragGesture: CommunicationDragGesture? = null,
    onDragPreview: (String?, Offset) -> Unit = { _, _ -> },
    onDrop: () -> Unit = {},
) {
    val palette = LocalLinkaPalette.current
    if (page == null) {
        Box(modifier) { Text(stringResource(Res.string.choose_a_board_in_the_library_the)) }
        return
    }
    val slots = remember(page) { communicationSlots(page) }
    val focus = LocalFocusManager.current
    val cellWidth = pageLayout.cellWidth.dp
    val cellHeight = pageLayout.cellHeight.dp
    val gridDescription = stringResource(Res.string.grid_1_s_by_2_s, page.rows, page.columns)
    Box(modifier) {
        Column(
            Modifier.requiredSize(cellWidth * page.columns, cellHeight * page.rows).focusGroup()
                .semantics {
                    collectionInfo = CollectionInfo(page.rows, page.columns)
                    stateDescription = gridDescription
                }
                .onPreviewKeyEvent { event ->
                    if (!enabled || event.type != KeyEventType.KeyDown || event.isAltPressed || event.isCtrlPressed || event.isMetaPressed) false
                    else {
                        val direction = when (event.key) {
                            Key.DirectionLeft -> FocusDirection.Left
                            Key.DirectionRight -> FocusDirection.Right
                            Key.DirectionUp -> FocusDirection.Up
                            Key.DirectionDown -> FocusDirection.Down
                            else -> null
                        }
                        direction != null && focus.moveFocus(direction)
                    }
                },
        ) {
            for (row in 0 until page.rows) {
                DemandVisibleImages(page.id to row, visibleRowImages(page, row))
                Row {
                    for (column in 0 until page.columns) {
                        val position = row * page.columns + column
                        val cell = slots[position]
                        val itemModifier = Modifier.requiredSize(cellWidth, cellHeight).semantics {
                            collectionItemInfo = CollectionItemInfo(row, 1, column, 1)
                        }
                        if (cell == null || cell.kind == CellKind.EMPTY) {
                            Box(itemModifier.border(1.dp, palette.border))
                        } else {
                            val label = if (cell.kind == CellKind.SPACE) stringResource(Res.string.space) else cell.card!!.label
                            CommunicationCard(
                                label = if (cell.kind == CellKind.SPACE) "⎵" else label,
                                accessibleName = label.ifEmpty { stringResource(Res.string.unlabelled_card_position_1_s, position + 1) },
                                activationLabel = if (composing) stringResource(Res.string.add_to_message) else stringResource(Res.string.speak_card),
                                image = cell.card?.image?.let { images[it] },
                                hasImage = cell.card?.image != null,
                                imageBesideCaption = pageLayout.imageSides[position]?.dp,
                                selected = cell.card?.id in selectedCards,
                                enabled = enabled,
                                onActivate = { onSelect(position) },
                                modifier = itemModifier.then(communicationDragModifier(
                                    dragGesture, position, label, onDragPreview, onDrop)),
                            )
                        }
                    }
                }
            }
        }
    }
}
