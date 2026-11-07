package su.linka.pictures.ui.access

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
    ProductSection(if (manage) "Защита важных действий" else "Подтвердите доступ") {
    Text(when (val current = state) {
        AssistantState.Initializing -> "Проверяем защиту помощника… Общение остаётся доступным."
        is AssistantState.Unavailable -> "Защищённые команды недоступны: ${current.problem.code}. Общение доступно."
        is AssistantState.Unprotected -> "Пароль помощника не установлен."
        is AssistantState.Protected -> if (current.legacy) "Существующая защита сохранена (старый формат пароля)." else "Защита помощника включена."
    })
    if (manage && verification != OwnerVerificationAvailability.AVAILABLE) Text(
        "Системная проверка владельца ${if (verification == OwnerVerificationAvailability.UNKNOWN) "ещё не определена" else "недоступна"}. " +
            "На Android 21/22 системная проверка не используется из-за ограничений безопасности. Новый пароль и сброс недоступны; существующая защита автоматически не отключается.")
    if (state is AssistantState.Protected) Field(password, "Текущий пароль", ready,
        PasswordVisualTransformation(), passwordKeyboard, true) { password = it }
    Action(if (state is AssistantState.Unprotected) "Разрешить команды в этом экране" else "Разблокировать этот экран", ready) {
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
        Field(newPassword, "Новый пароль", ready, PasswordVisualTransformation(), passwordKeyboard, true) { newPassword = it }
        Field(repeatPassword, "Повторите новый пароль", ready, PasswordVisualTransformation(), passwordKeyboard, true) { repeatPassword = it }
        val validNewPassword = newPassword.isNotEmpty() && newPassword.length <= 65_536 && newPassword == repeatPassword
        if (newPassword.isNotEmpty() && !validNewPassword) Text("Новые пароли должны совпадать и содержать не более 65536 символов.")
        Action("Установить / изменить пароль", ready && validNewPassword && verification == OwnerVerificationAvailability.AVAILABLE) {
            val input = newPassword; password = ""; newPassword = ""; repeatPassword = ""; busy = true
            scope.launch {
                try { result = access.setPassword(input, owner, capability); onCapability(null) }
                finally { busy = false }
            }
        }
        Action("Заменить пароль новым через системную проверку владельца", ready && validNewPassword && verification == OwnerVerificationAvailability.AVAILABLE) {
            val input = newPassword; password = ""; newPassword = ""; repeatPassword = ""; busy = true
            scope.launch {
                try { result = access.resetPasswordWithDeviceOwner(input, owner); onCapability(null) }
                finally { busy = false }
            }
        }
        if (state is AssistantState.Protected) {
            Action("Отключить защиту помощника…", ready && capability != null) { password = ""; newPassword = ""; repeatPassword = ""; disable = true }
            if (disable) {
                Text("Отключить пароль помощника? Защищённые команды станут доступны без пароля.")
                Action("Подтверждаю: отключить защиту помощника", ready && capability != null, true) {
                    disable = false; password = ""; newPassword = ""; repeatPassword = ""; busy = true
                    scope.launch {
                        try { capability?.let { result = access.disableLock(it, owner) }; onCapability(null) }
                        finally { busy = false }
                    }
                }
                Action("Сохранить защиту") { disable = false; password = ""; newPassword = ""; repeatPassword = "" }
            }
        }
    }
    Action("Очистить ввод / закрыть доступ", foreground && !busy) {
        password = ""; newPassword = ""; repeatPassword = ""; disable = false; result = null; access.revokeOwner(owner); onCapability(null)
    }
    Feedback(result)
    }
}
