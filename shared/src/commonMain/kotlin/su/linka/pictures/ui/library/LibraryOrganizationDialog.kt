package su.linka.pictures.ui.library

import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import su.linka.pictures.resources.*
import su.linka.pictures.application.*
import su.linka.pictures.application.library.LibraryController
import su.linka.pictures.application.ports.*
import su.linka.pictures.domain.*
import su.linka.pictures.ui.app.*

internal enum class FolderOperation { CREATE, RENAME, MOVE_FOLDER, MOVE_ENTRY, DELETE }

internal data class FolderDecision(
    val listing: LibraryListing,
    val operation: FolderOperation,
    val folderId: FolderId? = null,
    val entryId: BoardId? = null,
    val parentId: FolderId? = null,
)

internal fun folderPath(organization: LibraryOrganization, id: FolderId?): List<LibraryFolder> {
    val byId = organization.folders.associateBy { it.id }
    val result = mutableListOf<LibraryFolder>()
    var current = id
    while (current != null) { val folder = byId[current] ?: break; result.add(folder); current = folder.parentId }
    return result.asReversed()
}

internal fun applyFolderDecision(decision: FolderDecision, name: String, destination: FolderId?): LibraryOrganization {
    val value = decision.listing.organization
    return when (decision.operation) {
        FolderOperation.CREATE -> value.copy(folders = value.folders + LibraryFolder(requireNotNull(decision.folderId), name, decision.parentId))
        FolderOperation.RENAME -> value.copy(folders = value.folders.map { if (it.id == decision.folderId) it.copy(name = name) else it })
        FolderOperation.MOVE_FOLDER -> value.copy(folders = value.folders.map { if (it.id == decision.folderId) it.copy(parentId = destination) else it })
        FolderOperation.MOVE_ENTRY -> value.copy(placements = if (destination == null) value.placements - requireNotNull(decision.entryId)
            else value.placements + (requireNotNull(decision.entryId) to destination))
        FolderOperation.DELETE -> {
            require(value.folders.none { it.parentId == decision.folderId } && decision.folderId !in value.placements.values)
            value.copy(folders = value.folders.filterNot { it.id == decision.folderId })
        }
    }
}

@Composable
internal fun LibraryOrganizationDialog(decision: FolderDecision, controller: LibraryController,
    owner: String, capability: AssistantCapability?, enabled: Boolean, access: @Composable () -> Unit, onDismiss: () -> Unit) {
    val folder = decision.listing.organization.folders.firstOrNull { it.id == decision.folderId }
    val operationLabel = when (decision.operation) {
        FolderOperation.CREATE -> Res.string.library_create_folder
        FolderOperation.RENAME -> Res.string.library_rename_folder
        FolderOperation.MOVE_FOLDER, FolderOperation.MOVE_ENTRY -> Res.string.library_move_to_folder
        FolderOperation.DELETE -> Res.string.library_delete_empty_folder
    }
    var name by remember(decision) { mutableStateOf(folder?.name.orEmpty()) }
    var destination by remember(decision) { mutableStateOf(folder?.parentId ?: decision.listing.organization.placements[decision.entryId]) }
    var busy by remember(decision) { mutableStateOf(false) }
    var result by remember(decision) { mutableStateOf<OperationResult<*>?>(null) }
    val scope = rememberCoroutineScope()
    val moving = decision.operation == FolderOperation.MOVE_FOLDER || decision.operation == FolderOperation.MOVE_ENTRY
    val deleting = decision.operation == FolderOperation.DELETE
    val empty = decision.listing.organization.folders.none { it.parentId == decision.folderId } && decision.folderId !in decision.listing.organization.placements.values
    ProductDialog(stringResource(operationLabel), { if (!busy) onDismiss() }, actions = {
        Action(stringResource(if (deleting) Res.string.library_delete_empty_folder else Res.string.save), enabled && !busy && (!deleting || empty) && (moving || deleting || name.isNotBlank()), deleting) {
            val candidate = try { applyFolderDecision(decision, name, destination) } catch (_: IllegalArgumentException) {
                result = OperationResult.Failed(Problem(ProblemCode.INVALID_DATA)); null
            }
            if (candidate != null) {
                busy = true
                scope.launch { try {
                    result = controller.organization(owner, capability, candidate, decision.listing.revision, decision.listing.epoch)
                    if (libraryDecisionFinished(result!!)) onDismiss()
                } finally { busy = false } }
            }
        }
        Action(stringResource(Res.string.cancel), !busy, onClick = onDismiss)
    }) {
        val objectName = folder?.name ?: decision.listing.items.firstOrNull { it.id == decision.entryId }?.name
        objectName?.let { Text(it) }
        if (deleting && !empty) Text(stringResource(Res.string.library_folder_not_empty))
        if (!moving && !deleting) Field(name, stringResource(Res.string.name), enabled && !busy) { name = it }
        if (moving) {
            Action(stringResource(Res.string.library_root), enabled && !busy) { destination = null }
            Text(stringResource(Res.string.library_destination, destination?.let { folderPath(decision.listing.organization, it).joinToString(" / ") { f -> f.name } } ?: stringResource(Res.string.library_root)))
            for (target in decision.listing.organization.folders) {
                val valid = decision.operation != FolderOperation.MOVE_FOLDER || folderPath(decision.listing.organization, target.id).none { it.id == decision.folderId }
                if (valid) Action(folderPath(decision.listing.organization, target.id).joinToString(" / ") { it.name }, enabled && !busy) { destination = target.id }
            }
        }
        if (needsLibraryAuthorization(result)) access()
        Feedback(result)
    }
}
