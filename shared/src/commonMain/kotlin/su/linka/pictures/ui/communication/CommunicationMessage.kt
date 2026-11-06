package su.linka.pictures.ui.communication

import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import su.linka.pictures.application.communication.CommunicationState
import su.linka.pictures.domain.AssetId
import su.linka.pictures.domain.MessagePart
import su.linka.pictures.ui.images.DemandVisibleImages
import su.linka.pictures.ui.theme.LocalLinkaPalette

@Composable
internal fun CommunicationMessage(state: CommunicationState, images: Map<AssetId, ImageBitmap>, modifier: Modifier) {
    val palette = LocalLinkaPalette.current
    BoxWithConstraints(modifier.semantics { contentDescription = "Общее сообщение: ${state.renderedText.ifEmpty { "пусто" }}" }) {
        val partWidth = maxOf(48.dp, maxHeight)
        val imageHeight = maxOf(24.dp, maxHeight - 30.dp)
        LazyRow(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            items(state.message.parts, key = { it.id.value }) { part ->
                val card = (part as? MessagePart.Card)?.snapshot
                DemandVisibleImages(part.id, setOfNotNull(card?.image))
                Column(
                    Modifier.width(partWidth).fillMaxHeight().border(1.dp, palette.border)
                        .verticalScroll(rememberScrollState()).padding(3.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (card?.image != null) Box(Modifier.fillMaxWidth().height(imageHeight)) {
                        images[card.image]?.let { image ->
                            Image(image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                        }
                    }
                    Text(when (part) {
                        is MessagePart.Card -> part.snapshot.label
                        is MessagePart.Text -> part.text
                    })
                }
            }
        }
    }
}
