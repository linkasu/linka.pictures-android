package su.linka.pictures.ui.access

import su.linka.pictures.application.OperationResult
import su.linka.pictures.application.ports.*

internal fun AssistantState.commandsAvailable(): Boolean = this is AssistantState.Protected || this is AssistantState.Unprotected

internal fun canSkipContextUnlock(state: AssistantState, capability: AssistantCapability?, gate: AssistantGate,
    action: ProtectedAction?, owner: String): Boolean = action != null && capability == null &&
    state is AssistantState.Unprotected && gate.check(action, null, owner) is OperationResult.Completed
