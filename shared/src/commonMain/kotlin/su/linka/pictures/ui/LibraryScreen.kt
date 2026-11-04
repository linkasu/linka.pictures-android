package su.linka.pictures.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import su.linka.pictures.shared.generated.resources.*

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LibraryScreen(
    sets: List<SetSummary>,
    busy: Boolean,
    error: String?,
    onAction: (LibraryAction) -> Unit,
    image: @Composable (SetSummary, Modifier) -> Unit,
    modifier: Modifier = Modifier,
    optionsFor: String? = null,
) {
    Scaffold(modifier = modifier) { insets ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(200.dp),
            modifier = Modifier.fillMaxSize().padding(insets),
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    Text(stringResource(Res.string.library_title), style = MaterialTheme.typography.headlineSmall)
                    FlowRow {
                        TextButton({ onAction(LibraryAction.Create) }, enabled = !busy) { Text(stringResource(Res.string.create_set)) }
                        TextButton({ onAction(LibraryAction.Import) }, enabled = !busy) { Text(stringResource(Res.string.import_set)) }
                        TextButton({ onAction(LibraryAction.Settings) }, enabled = !busy) { Text(stringResource(Res.string.settings_title)) }
                    }
                    ScreenStatus(busy, error)
                    if (sets.isEmpty() && !busy && error == null) Text(stringResource(Res.string.empty_library))
                }
            }
            items(sets, key = { it.name }) { set ->
                Card {
                    TextButton(
                        onClick = { onAction(LibraryAction.Open(set)) },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp),
                    ) {
                        Column(Modifier.fillMaxWidth()) {
                            image(set, Modifier.fillMaxWidth().height(120.dp).clearAndSetSemantics {})
                            Text(set.name.removeSuffix(".linka"))
                        }
                    }
                    Box {
                        TextButton(
                            onClick = { onAction(LibraryAction.Options(set)) }, enabled = !busy,
                            modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                        ) { Text(stringResource(Res.string.set_options, set.name.removeSuffix(".linka"))) }
                        DropdownMenu(
                            expanded = optionsFor == set.name && !busy,
                            onDismissRequest = { onAction(LibraryAction.Options(null)) },
                        ) {
                            DropdownMenuItem(text = { Text(stringResource(Res.string.edit_set)) }, onClick = { onAction(LibraryAction.Edit(set)) })
                            DropdownMenuItem(text = { Text(stringResource(Res.string.rename_set)) }, onClick = { onAction(LibraryAction.Rename(set)) })
                            DropdownMenuItem(text = { Text(stringResource(Res.string.export_set)) }, onClick = { onAction(LibraryAction.Export(set)) })
                            DropdownMenuItem(text = { Text(stringResource(Res.string.delete_set)) }, onClick = { onAction(LibraryAction.Delete(set)) })
                        }
                    }
                }
            }
        }
    }
}
