package su.linka.pictures.ui.app

import androidx.compose.ui.window.DialogProperties

// Non-floating insets must reach Compose so IME cannot cover pinned dialog actions on API21.
internal actual fun productDialogProperties() =
    DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
