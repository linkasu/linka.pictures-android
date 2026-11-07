package su.linka.pictures.ui.communication

import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
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
    modifier: Modifier,
) {
    val palette = LocalLinkaPalette.current
    if (page == null) {
        Box(modifier) { Text("Выберите доску в библиотеке. Общее сообщение доступно между досками.") }
        return
    }
    val slots = remember(page) { communicationSlots(page) }
    val focus = LocalFocusManager.current
    BoxWithConstraints(modifier) {
        val size = measuredCommunicationCells(page, maxWidth.value, maxHeight.value)
        val cellWidth = size.width.dp
        val cellHeight = size.height.dp
        val cursor = firstCell.coerceIn(0, page.capacity - 1)
        val horizontal = rememberScrollState(with(LocalDensity.current) { (cellWidth * (cursor % page.columns)).roundToPx() })
        val vertical = rememberLazyListState(initialFirstVisibleItemIndex = cursor / page.columns)
        Box(
            Modifier.fillMaxSize().horizontalScroll(horizontal).focusGroup()
                .semantics {
                    collectionInfo = CollectionInfo(page.rows, page.columns)
                    stateDescription = "Сетка ${page.rows} на ${page.columns}." +
                        if (cellWidth * page.columns > maxWidth || cellHeight * page.rows > maxHeight) " Доступна прокрутка." else ""
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
            LazyColumn(
                state = vertical,
                modifier = Modifier.width(cellWidth * page.columns).fillMaxSize(),
            ) {
                items(page.rows, key = { it }) { row ->
                    DemandVisibleImages(page.id to row, visibleRowImages(page, row))
                    Row {
                        for (column in 0 until page.columns) {
                            val position = row * page.columns + column
                            val cell = slots[position]
                            val itemModifier = Modifier.width(cellWidth).height(cellHeight)
                            if (cell == null || cell.kind == CellKind.EMPTY) {
                                Box(itemModifier.border(1.dp, palette.border))
                            } else {
                                val label = if (cell.kind == CellKind.SPACE) "Пробел" else cell.card!!.label
                                CommunicationCard(
                                    label = label,
                                    accessibleName = label.ifEmpty { "Карточка без подписи, позиция ${position + 1}" },
                                    activationLabel = if (composing) "Добавить в сообщение" else "Произнести карточку",
                                    image = cell.card?.image?.let { images[it] },
                                    hasImage = cell.card?.image != null,
                                    selected = cell.card?.id in selectedCards,
                                    enabled = enabled,
                                    onActivate = { onSelect(position) },
                                    modifier = itemModifier.semantics {
                                         collectionItemInfo = CollectionItemInfo(row, 1, column, 1)
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
