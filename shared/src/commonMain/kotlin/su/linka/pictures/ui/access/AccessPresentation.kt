package su.linka.pictures.ui.access

import su.linka.pictures.application.ports.AssistantState

internal fun AssistantState.commandsAvailable(): Boolean = this is AssistantState.Protected || this is AssistantState.Unprotected
