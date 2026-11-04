package su.linka.pictures.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.Card
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import su.linka.pictures.Card as Picture
import su.linka.pictures.shared.generated.resources.*

/** The image slot is decorative: the visible label is the single accessibility name. */
@Composable
internal fun PictureCard(
    card: Picture?,
    editing: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    image: @Composable (String?, String?, Modifier) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!editing && card?.cardType != 0 && card?.cardType != 1) {
        Box(modifier.sizeIn(minWidth = 48.dp, minHeight = 96.dp))
        return
    }
    val label = when (card?.cardType) {
        0 -> card.title?.takeIf { it.isNotBlank() } ?: stringResource(Res.string.untitled_card)
        1 -> stringResource(Res.string.space_card)
        else -> if (editing) stringResource(Res.string.edit_card) else ""
    }
    Card(
        onClick = onClick,
        enabled = enabled && (editing || card?.cardType == 0 || card?.cardType == 1),
        modifier = modifier.sizeIn(minWidth = 48.dp, minHeight = 96.dp),
    ) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (card?.cardType == 0 && card.imagePath != null) {
                Box(Modifier.fillMaxWidth().height(96.dp).clearAndSetSemantics {}) {
                    image(card.imagePath, null, Modifier.fillMaxWidth().height(96.dp))
                }
            }
            Text(label)
        }
    }
}
