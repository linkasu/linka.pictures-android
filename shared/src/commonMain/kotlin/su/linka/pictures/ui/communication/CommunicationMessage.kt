package su.linka.pictures.ui.communication

import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import su.linka.pictures.application.communication.CommunicationState
import su.linka.pictures.domain.AssetId
import su.linka.pictures.domain.MessagePart
import su.linka.pictures.ui.theme.LocalLinkaPalette

@Composable
internal fun CommunicationMessage(state: CommunicationState, images: Map<AssetId, ImageBitmap>, modifier: Modifier) {
    val palette = LocalLinkaPalette.current
    val pictures = remember(state.message.parts) {
        state.message.parts.filterIsInstance<MessagePart.Card>().filter { it.snapshot.image != null }
    }
    Column(
        modifier.border(1.dp, palette.border).verticalScroll(rememberScrollState()).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Общее сообщение", style = MaterialTheme.typography.h6, modifier = Modifier.semantics { heading() })
        Text(
            when {
                state.message.parts.isEmpty() -> "Сообщение пусто."
                state.renderedText.isEmpty() -> "В сообщении нет текста."
                else -> state.renderedText
            },
            style = MaterialTheme.typography.h6,
            softWrap = true,
            modifier = Modifier.fillMaxWidth(),
        )
        if (pictures.isNotEmpty()) LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            items(pictures, key = { it.id.value }) { part ->
                Box(Modifier.size(96.dp).border(1.dp, palette.border)) {
                    val image = images[part.snapshot.image]
                    if (image != null) Image(
                        image, part.snapshot.label.ifEmpty { "Изображение выбранной карточки без подписи" },
                        modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit,
                    )
                }
            }
        }
    }
}
