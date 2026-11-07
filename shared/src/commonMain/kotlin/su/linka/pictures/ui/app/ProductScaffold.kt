package su.linka.pictures.ui.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import su.linka.pictures.application.*
import su.linka.pictures.ui.components.LabeledActionButton
import su.linka.pictures.ui.components.ToolbarTitle
import su.linka.pictures.ui.components.toolbarHeightForFontScale
import su.linka.pictures.ui.theme.LocalLinkaPalette

@Composable
internal fun ProductScaffold(title: String, onBack: () -> Unit, scrollContent: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    val palette = LocalLinkaPalette.current
    Surface(color = palette.background, contentColor = palette.text) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding(),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ProductTopBar(modifier = Modifier.heightIn(min = toolbarHeightForFontScale(56f, LocalDensity.current.fontScale).dp),
                title = title,
                navigationIcon = { IconButton(onClick = onBack, modifier = Modifier.semantics { contentDescription = "Назад" }) {
                    Text("‹", style = MaterialTheme.typography.h4)
                } })
            val scrolling = if (scrollContent) Modifier.verticalScroll(rememberScrollState()) else Modifier
            Column(Modifier.weight(1f).fillMaxWidth().then(scrolling).padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
        }
    }
}

@Composable
internal fun Action(label: String, enabled: Boolean = true, destructive: Boolean = false, onClick: () -> Unit) =
    LabeledActionButton(label, enabled, destructive, onClick, Modifier.fillMaxWidth(),
        appearance = if (destructive) su.linka.pictures.ui.components.ActionAppearance.DESTRUCTIVE
            else su.linka.pictures.ui.components.ActionAppearance.SECONDARY)

@Composable
internal fun Field(value: String, label: String, enabled: Boolean = true,
    visual: VisualTransformation = VisualTransformation.None, keyboard: KeyboardOptions = KeyboardOptions.Default,
    singleLine: Boolean = false, onChange: (String) -> Unit) {
    val p = LocalLinkaPalette.current
    OutlinedTextField(value, onChange, Modifier.fillMaxWidth(), enabled = enabled,
        label = { Text(label) }, visualTransformation = visual, keyboardOptions = keyboard, singleLine = singleLine,
        colors = TextFieldDefaults.outlinedTextFieldColors(textColor = p.text, cursorColor = p.text,
            focusedBorderColor = p.primary, unfocusedBorderColor = p.text,
            focusedLabelColor = p.text, unfocusedLabelColor = p.text,
            disabledTextColor = p.text, disabledBorderColor = p.text, disabledLabelColor = p.text,
            backgroundColor = p.surface))
}

@Composable
internal fun Feedback(result: OperationResult<*>?) {
    if (result == null) return
    Text(productFeedback(result), Modifier.semantics { liveRegion = LiveRegionMode.Polite })
}

internal fun productFeedback(result: OperationResult<*>): String = when (result) {
    is OperationResult.Completed -> "Готово"
    OperationResult.Cancelled -> "Отменено. Изменения не подтверждены."
    OperationResult.UnconfirmedCommit -> "Исход записи неизвестен. Не повторяйте действие; закройте экран и проверьте библиотеку после перезапуска."
    is OperationResult.Failed -> when (result.problem.code) {
        ProblemCode.UNAUTHORIZED -> "Нужна повторная авторизация помощника."
        ProblemCode.CONFLICT, ProblemCode.STALE_REQUEST -> "Данные изменились. Обновите экран и повторно проверьте действие."
        ProblemCode.OWNER_VERIFICATION_UNAVAILABLE -> "Системная проверка владельца недоступна."
        ProblemCode.IO_FAILURE -> "Ошибка ввода-вывода (IO_FAILURE)."
        ProblemCode.COMMIT_OUTCOME_UNKNOWN -> "Исход записи неизвестен. Не повторяйте действие. Перезапустите приложение и проверьте данные."
        else -> "Не удалось выполнить действие: ${result.problem.code}."
    }
}
