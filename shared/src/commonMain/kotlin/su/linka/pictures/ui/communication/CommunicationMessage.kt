package su.linka.pictures.ui.communication

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Surface
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import su.linka.pictures.application.communication.CommunicationState
import su.linka.pictures.domain.AssetId
import su.linka.pictures.domain.MessagePart
import su.linka.pictures.ui.images.DemandVisibleImages
import su.linka.pictures.ui.theme.LocalLinkaPalette
import su.linka.pictures.ui.theme.Linka14
import su.linka.pictures.ui.theme.LinkaPalette
import su.linka.pictures.ui.theme.linkaPalette
import su.linka.pictures.ui.components.CommunicationCardMeasurePolicy
import su.linka.pictures.ui.components.CommunicationCaption
import su.linka.pictures.ui.components.PrimitiveSurfaceChrome

internal fun communicationMessageGradient(palette: LinkaPalette): List<Color>? =
    if (palette == linkaPalette(false, false)) PrimitiveSurfaceChrome.cardGradient(palette, palette.text, false) else null

@Composable
internal fun CommunicationMessage(state: CommunicationState, images: Map<AssetId, ImageBitmap>, modifier: Modifier) {
    val palette = LocalLinkaPalette.current
    BoxWithConstraints(modifier.padding(6.dp).semantics { contentDescription = "Общее сообщение: ${state.renderedText.ifEmpty { "пусто" }}" }) {
        val partWidth = maxOf(48.dp, maxHeight)
        val gradient = communicationMessageGradient(palette)
        LazyRow(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            items(state.message.parts, key = { it.id.value }) { part ->
                val card = (part as? MessagePart.Card)?.snapshot
                DemandVisibleImages(part.id, setOfNotNull(card?.image))
                Surface(
                    modifier = Modifier.width(partWidth).fillMaxHeight()
                        .padding(start = 4.dp, top = 1.5.dp, end = 4.dp, bottom = 6.dp)
                        .padding(Linka14.cardMargin).semantics(mergeDescendants = true) {},
                    color = palette.surface, contentColor = palette.text,
                    shape = RoundedCornerShape(Linka14.cardCorner),
                    border = PrimitiveSurfaceChrome.border(palette, false),
                ) {
                    BoxWithConstraints(
                        Modifier.then(if (gradient != null) Modifier.background(Brush.verticalGradient(gradient)) else Modifier)
                            .padding(Linka14.cardPadding),
                    ) {
                        Layout(content = {
                            Box(contentAlignment = Alignment.Center) {
                                card?.image?.let(images::get)?.let { image ->
                                    Image(image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                                }
                            }
                            CommunicationCaption(when (part) {
                                is MessagePart.Card -> part.snapshot.label
                                is MessagePart.Text -> part.text
                            }, maxWidth, maxHeight, Modifier.verticalScroll(rememberScrollState()))
                        }, measurePolicy = CommunicationCardMeasurePolicy)
                    }
                }
            }
        }
    }
}
