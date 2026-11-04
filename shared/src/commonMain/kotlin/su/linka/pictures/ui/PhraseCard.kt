package su.linka.pictures.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Card
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import su.linka.pictures.Card as Picture
import su.linka.pictures.shared.generated.resources.*

@Composable
internal fun PhraseCard(card: Picture, image: @Composable (String?, String?, Modifier) -> Unit) {
    Card(Modifier.width(120.dp)) {
        Column(Modifier.padding(8.dp)) {
            if (card.cardType == 0 && card.imagePath != null) {
                Box(Modifier.fillMaxWidth().height(64.dp).clearAndSetSemantics {}) {
                    image(card.imagePath, null, Modifier.fillMaxWidth().height(64.dp))
                }
            }
            Text(if (card.cardType == 1) stringResource(Res.string.space_card)
                else card.title?.takeIf { it.isNotBlank() } ?: stringResource(Res.string.untitled_card))
        }
    }
}
