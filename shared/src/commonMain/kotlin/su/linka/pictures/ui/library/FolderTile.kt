package su.linka.pictures.ui.library

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import su.linka.pictures.resources.*
import su.linka.pictures.domain.LibraryFolder
import su.linka.pictures.ui.theme.LocalLinkaPalette

@Composable
internal fun FolderTile(folder: LibraryFolder, enabled: Boolean, onOpen: () -> Unit,
    onRename: () -> Unit, onMove: () -> Unit, onDelete: () -> Unit) {
    var menu by remember(folder.id) { mutableStateOf(false) }
    val palette = LocalLinkaPalette.current
    Surface(Modifier.fillMaxWidth().padding(1.dp), color = palette.surface, contentColor = palette.text,
        border = BorderStroke(if (palette.highContrast) 2.dp else 1.dp, palette.border)) {
        Box(Modifier.combinedClickable(role = Role.Button, onClick = onOpen,
            onLongClickLabel = stringResource(Res.string.library_context_menu), onLongClick = { menu = true })) {
            Column(Modifier.fillMaxWidth().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.fillMaxWidth().height(88.dp), contentAlignment = Alignment.Center) {
                    Icon(LibraryIcons.Folder, stringResource(Res.string.library_folder), Modifier.size(64.dp))
                }
                Text(folder.name, Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(top = 8.dp))
            }
            Box(Modifier.align(Alignment.TopEnd)) {
                IconButton({ menu = true }) { Icon(LibraryIcons.More, stringResource(Res.string.library_context_menu)) }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem({ menu = false; onOpen() }) { Text(stringResource(Res.string.open)) }
                    DropdownMenuItem({ menu = false; onRename() }, enabled = enabled) { Text(stringResource(Res.string.library_rename_folder)) }
                    DropdownMenuItem({ menu = false; onMove() }, enabled = enabled) { Text(stringResource(Res.string.library_move_to_folder)) }
                    DropdownMenuItem({ menu = false; onDelete() }, enabled = enabled) { Text(stringResource(Res.string.library_delete_empty_folder)) }
                }
            }
        }
    }
}
