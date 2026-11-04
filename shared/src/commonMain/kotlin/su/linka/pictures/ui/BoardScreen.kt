package su.linka.pictures.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import su.linka.pictures.BoardDocument
import su.linka.pictures.Card
import su.linka.pictures.shared.generated.resources.*

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BoardScreen(
    document: BoardDocument,
    page: Int,
    phrase: List<Card>,
    outputEnabled: Boolean,
    pagingEnabled: Boolean,
    busy: Boolean,
    onAction: (BoardAction) -> Unit,
    image: @Composable (String?, String?, Modifier) -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
) {
    val pagination = BoardPagination.of(document.rows, document.columns, document.cards.size, page)
    Scaffold(modifier = modifier) { insets ->
        BoardGrid(
            document, pagination, editing = false, enabled = !busy,
            onCard = { onAction(BoardAction.SelectCard(it)) }, image = image,
            modifier = Modifier.fillMaxSize().padding(insets).padding(12.dp),
            header = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(title ?: stringResource(Res.string.board_title), style = MaterialTheme.typography.headlineSmall)
                    FlowRow {
                        TextButton({ onAction(BoardAction.Close) }, enabled = !busy) { Text(stringResource(Res.string.close_board)) }
                        TextButton({ onAction(BoardAction.Settings) }, enabled = !busy) { Text(stringResource(Res.string.board_options)) }
                    }
                    ScreenStatus(busy)
                    if (outputEnabled) {
                        Text(stringResource(Res.string.phrase_title), style = MaterialTheme.typography.titleMedium)
                        if (phrase.isEmpty()) Text(stringResource(Res.string.empty_phrase))
                        if (document.withoutSpace) {
                            Text(phraseText(phrase), style = MaterialTheme.typography.headlineSmall)
                        } else {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(phrase) { card ->
                                    // Phrase cards are display-only, not disabled interactive buttons.
                                    PhraseCard(card, image)
                                }
                            }
                        }
                        FlowRow {
                            TextButton({ onAction(BoardAction.Backspace) }, enabled = !busy && phrase.isNotEmpty()) { Text(stringResource(Res.string.backspace)) }
                            TextButton({ onAction(BoardAction.Clear) }, enabled = !busy && phrase.isNotEmpty()) { Text(stringResource(Res.string.clear_phrase)) }
                            Button({ onAction(BoardAction.Speak) }, enabled = !busy && phrase.isNotEmpty()) { Text(stringResource(Res.string.speak_phrase)) }
                        }
                    } else {
                        Text(stringResource(Res.string.direct_mode))
                    }
                    if (pagingEnabled && pagination.pageCount > 1) {
                        PagingControls(pagination, !busy) { onAction(BoardAction.Page(it)) }
                    }
                }
            },
        )
    }
}
