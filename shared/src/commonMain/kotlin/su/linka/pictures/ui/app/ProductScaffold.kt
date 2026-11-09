package su.linka.pictures.ui.app

import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.StringResource
import su.linka.pictures.resources.*
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
    val backLabel = stringResource(Res.string.back)
    val palette = LocalLinkaPalette.current
    Surface(color = palette.background, contentColor = palette.text) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding(),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ProductTopBar(modifier = Modifier.heightIn(min = toolbarHeightForFontScale(56f, LocalDensity.current.fontScale).dp),
                title = title,
                navigationIcon = { IconButton(onClick = onBack, modifier = Modifier.semantics { contentDescription = backLabel }) {
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
    Text(stringResource(productFeedback(result), (result as? OperationResult.Failed)?.problem?.code?.name ?: ""), Modifier.semantics { liveRegion = LiveRegionMode.Polite })
}

internal fun productFeedback(result: OperationResult<*>): StringResource = when (result) {
    is OperationResult.Completed -> Res.string.done
    OperationResult.Cancelled -> Res.string.cancelled_changes_have_not_been_confirmed
    OperationResult.UnconfirmedCommit -> Res.string.write_outcome_is_unknown_do_not_repeat
    is OperationResult.Failed -> when (result.problem.code) {
        ProblemCode.UNAUTHORIZED -> Res.string.assistant_authorization_is_required_again
        ProblemCode.CONFLICT, ProblemCode.STALE_REQUEST -> Res.string.data_has_changed_refresh_the_screen_and
        ProblemCode.OWNER_VERIFICATION_UNAVAILABLE -> Res.string.system_owner_verification_is_unavailable
        ProblemCode.IO_FAILURE -> Res.string.input_output_error_io_failure
        ProblemCode.COMMIT_OUTCOME_UNKNOWN -> Res.string.write_outcome_is_unknown_do_not_repeat_57
        else -> Res.string.could_not_complete_the_action_1_s
    }
}
