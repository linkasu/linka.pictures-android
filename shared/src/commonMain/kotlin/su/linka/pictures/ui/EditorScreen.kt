package su.linka.pictures.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import su.linka.pictures.BoardDocument
import su.linka.pictures.shared.generated.resources.*

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EditorScreen(
    document: BoardDocument,
    page: Int,
    busy: Boolean,
    error: String?,
    onAction: (EditorAction) -> Unit,
    image: @Composable (String?, String?, Modifier) -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
) {
    val pagination = BoardPagination.of(document.rows, document.columns, document.cards.size, page)
    Scaffold(modifier = modifier) { insets ->
        BoardGrid(
            document, pagination, editing = true, enabled = !busy,
            onCard = { onAction(EditorAction.EditCard(it)) }, image = image,
            modifier = Modifier.fillMaxSize().padding(insets).padding(12.dp),
            header = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(title ?: stringResource(Res.string.editor_title), style = MaterialTheme.typography.headlineSmall)
                    FlowRow {
                        TextButton({ onAction(EditorAction.Back) }, enabled = !busy) { Text(stringResource(Res.string.back)) }
                        Button({ onAction(EditorAction.Save) }, enabled = !busy) { Text(stringResource(Res.string.save_set)) }
                        TextButton({ onAction(EditorAction.Export) }, enabled = !busy) { Text(stringResource(Res.string.export_set)) }
                    }
                    ScreenStatus(busy, error)
                    DimensionControl(stringResource(Res.string.rows), document.rows, !busy) {
                        onAction(EditorAction.Resize(it, document.columns))
                    }
                    DimensionControl(stringResource(Res.string.columns), document.columns, !busy) {
                        onAction(EditorAction.Resize(document.rows, it))
                    }
                    ToggleSetting(stringResource(Res.string.without_space), document.withoutSpace, !busy) {
                        onAction(EditorAction.WithoutSpace(it))
                    }
                    PagingControls(pagination, !busy) { onAction(EditorAction.Page(it)) }
                    TextButton({ onAction(EditorAction.NewPage) }, enabled = !busy) {
                        Text(stringResource(Res.string.new_page))
                    }
                }
            },
        )
    }
}
