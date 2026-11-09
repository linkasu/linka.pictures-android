package su.linka.pictures.ui.access

import org.jetbrains.compose.resources.stringResource
import su.linka.pictures.resources.*
import androidx.compose.material.Text
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import kotlinx.coroutines.launch
import su.linka.pictures.application.*
import su.linka.pictures.application.ports.*
import su.linka.pictures.ui.app.*

@Composable
internal fun AssistantAccessPanel(access: AssistantAccess, owner: String, capability: AssistantCapability?,
    foreground: Boolean, manage: Boolean, action: ProtectedAction? = null, requireFreshUnlock: Boolean = false,
    onCapability: (AssistantCapability?) -> Unit) {
    val state by access.state.collectAsState()
    var password by remember(owner, foreground, state, capability) { mutableStateOf("") }
    var newPassword by remember(owner, foreground, state, capability) { mutableStateOf("") }
    var repeatPassword by remember(owner, foreground, state, capability) { mutableStateOf("") }
    var busy by remember(owner) { mutableStateOf(false) }
    var result by remember(owner) { mutableStateOf<OperationResult<*>?>(null) }
    var disable by remember(owner, foreground) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val passwordKeyboard = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password)
    val ready = foreground && !busy && state.commandsAvailable()
    val freshNone = canSkipContextUnlock(state, capability, access, action, owner)
    if (!manage && freshNone && !requireFreshUnlock) return
    val verification = when (val current = state) {
        is AssistantState.Protected -> current.verification
        is AssistantState.Unprotected -> current.verification
        else -> OwnerVerificationAvailability.UNKNOWN
    }
    ProductSection(if (manage) stringResource(Res.string.protect_important_actions) else stringResource(Res.string.confirm_access)) {
    Text(when (val current = state) {
        AssistantState.Initializing -> stringResource(Res.string.checking_assistant_protection_communication_remains_available)
        is AssistantState.Unavailable -> stringResource(Res.string.protected_commands_are_unavailable_1_s_communication, current.problem.code)
        is AssistantState.Unprotected -> stringResource(Res.string.no_assistant_password_is_set)
        is AssistantState.Protected -> if (current.legacy) stringResource(Res.string.existing_protection_is_preserved_legacy_password_format) else stringResource(Res.string.assistant_protection_is_enabled)
    })
    if (manage && verification != OwnerVerificationAvailability.AVAILABLE) Text(
        stringResource(Res.string.system_owner_verification_is_1_s, if (verification == OwnerVerificationAvailability.UNKNOWN) stringResource(Res.string.not_yet_determined) else stringResource(Res.string.unavailable)) +
            stringResource(Res.string.on_android_21_22_system_verification_is))
    if (state is AssistantState.Protected) Field(password, stringResource(Res.string.current_password), ready,
        PasswordVisualTransformation(), passwordKeyboard, true) { password = it }
    Action(if (state is AssistantState.Unprotected) stringResource(Res.string.allow_commands_on_this_screen) else stringResource(Res.string.unlock_this_screen), ready) {
        val input = password; password = ""; newPassword = ""; repeatPassword = ""; busy = true
        scope.launch {
            try {
                val unlocked = access.unlock(input, owner)
                result = if (unlocked is OperationResult.Completed) OperationResult.Completed(Unit) else unlocked
                onCapability((unlocked as? OperationResult.Completed)?.value)
            } finally { busy = false }
        }
    }
    if (manage) {
        Field(newPassword, stringResource(Res.string.new_password), ready, PasswordVisualTransformation(), passwordKeyboard, true) { newPassword = it }
        Field(repeatPassword, stringResource(Res.string.repeat_new_password), ready, PasswordVisualTransformation(), passwordKeyboard, true) { repeatPassword = it }
        val validNewPassword = newPassword.isNotEmpty() && newPassword.length <= 65_536 && newPassword == repeatPassword
        if (newPassword.isNotEmpty() && !validNewPassword) Text(stringResource(Res.string.new_passwords_must_match_and_contain_no))
        Action(stringResource(Res.string.set_change_password), ready && validNewPassword && verification == OwnerVerificationAvailability.AVAILABLE) {
            val input = newPassword; password = ""; newPassword = ""; repeatPassword = ""; busy = true
            scope.launch {
                try { result = access.setPassword(input, owner, capability); onCapability(null) }
                finally { busy = false }
            }
        }
        Action(stringResource(Res.string.replace_password_using_system_owner_verification), ready && validNewPassword && verification == OwnerVerificationAvailability.AVAILABLE) {
            val input = newPassword; password = ""; newPassword = ""; repeatPassword = ""; busy = true
            scope.launch {
                try { result = access.resetPasswordWithDeviceOwner(input, owner); onCapability(null) }
                finally { busy = false }
            }
        }
        if (state is AssistantState.Protected) {
            Action(stringResource(Res.string.disable_assistant_protection), ready && capability != null) { password = ""; newPassword = ""; repeatPassword = ""; disable = true }
            if (disable) {
                Text(stringResource(Res.string.disable_the_assistant_password_protected_commands_will))
                Action(stringResource(Res.string.confirm_disable_assistant_protection), ready && capability != null, true) {
                    disable = false; password = ""; newPassword = ""; repeatPassword = ""; busy = true
                    scope.launch {
                        try { capability?.let { result = access.disableLock(it, owner) }; onCapability(null) }
                        finally { busy = false }
                    }
                }
                Action(stringResource(Res.string.keep_protection)) { disable = false; password = ""; newPassword = ""; repeatPassword = "" }
            }
        }
    }
    Action(stringResource(Res.string.clear_input_revoke_access), foreground && !busy) {
        password = ""; newPassword = ""; repeatPassword = ""; disable = false; result = null; access.revokeOwner(owner); onCapability(null)
    }
    Feedback(result)
    }
}
