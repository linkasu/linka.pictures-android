package su.linka.pictures.ui.card

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import su.linka.pictures.application.authoring.*
import su.linka.pictures.application.ports.*
import su.linka.pictures.domain.*
import su.linka.pictures.ui.images.VisibleImageDemand

/** Standalone entry point; the editor uses the dialog directly under its shared image-demand owner. */
@Composable
fun CardEditorScreen(
    state: AuthoringState,
    ownerId: String,
    capability: AssistantCapability?,
    ids: IdSource,
    onCommand: (AuthoringCommand) -> Unit,
    images: Map<AssetId, ImageBitmap> = emptyMap(),
    modifier: Modifier = Modifier,
    onImagesNeeded: ((Set<AssetId>) -> Unit)? = null,
) {
    VisibleImageDemand(Triple(state.epoch, ownerId, state.draft?.id), onImagesNeeded) {
        CardEditorDialog(state, ownerId,
            { onCommand(AuthoringCommand(CommandId(ids.next()), ownerId, capability, it)) },
            { action, onHandled -> onCommand(AuthoringCommand(CommandId(ids.next()), ownerId, capability, action, onHandled)) },
            images, modifier)
    }
}
