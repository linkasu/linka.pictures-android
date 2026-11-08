package su.linka.pictures.ui.communication

import org.jetbrains.compose.resources.stringResource
import su.linka.pictures.resources.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.Surface
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import su.linka.pictures.application.communication.CommunicationState
import su.linka.pictures.domain.AssetId
import su.linka.pictures.domain.MessagePart
import su.linka.pictures.ui.images.DemandVisibleImages
import su.linka.pictures.ui.theme.LocalLinkaPalette
import su.linka.pictures.ui.theme.LinkaMetrics
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import su.linka.pictures.ui.components.CommunicationCaptionStyle
import su.linka.pictures.ui.components.CommunicationCardMeasurePolicy

@Composable
internal fun CommunicationMessage(state: CommunicationState, images: Map<AssetId, ImageBitmap>, modifier: Modifier) {
    val palette = LocalLinkaPalette.current
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.body1.merge(CommunicationCaptionStyle)
    val description = stringResource(Res.string.shared_message_1_s, state.renderedText.ifEmpty { stringResource(Res.string.empty) })
    BoxWithConstraints(modifier.padding(2.dp).semantics { contentDescription = description }) {
        val partWidth = communicationMessagePartWidth(maxWidth.value, maxHeight.value, state.message.parts.size).dp
        LazyRow(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            items(state.message.parts, key = { it.id.value }) { part ->
                val card = (part as? MessagePart.Card)?.snapshot
                val label = (when (part) {
                    is MessagePart.Card -> part.snapshot.label
                    is MessagePart.Text -> part.text
                }).replace('\n', ' ')
                val labelWidth = with(density) { measurer.measure(label, style = style, softWrap = false).size.width.toDp() }
                DemandVisibleImages(part.id, setOfNotNull(card?.image))
                Surface(
                    modifier = Modifier.width(maxOf(partWidth, labelWidth + 12.dp)).fillMaxHeight()
                        .padding(horizontal = 2.dp).semantics(mergeDescendants = true) {},
                    color = palette.surface, contentColor = palette.text,
                    shape = RoundedCornerShape(LinkaMetrics.cardRadius),
                ) {
                    Layout(modifier = Modifier.padding(4.dp), content = {
                            Box(contentAlignment = Alignment.Center) {
                                card?.image?.let(images::get)?.let { image ->
                                    Image(image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                                }
                            }
                            Text(label, style = style, softWrap = false)
                    }, measurePolicy = CommunicationCardMeasurePolicy)
                }
            }
        }
    }
}
