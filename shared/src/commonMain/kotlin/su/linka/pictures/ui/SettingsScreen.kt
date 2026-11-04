package su.linka.pictures.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import su.linka.pictures.shared.generated.resources.*

@Composable
fun SettingsScreen(
    parentEnabled: Boolean,
    analyticsEnabled: Boolean,
    passwordRecoveryRequired: Boolean,
    onAction: (SettingsAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val dispatch: (SettingsAction) -> Unit = { action ->
        if (action.isAllowed(passwordRecoveryRequired)) onAction(action)
    }
    Scaffold(modifier = modifier) { insets ->
        Column(
            Modifier.fillMaxSize().padding(insets).verticalScroll(rememberScrollState()).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(Res.string.settings_title), style = MaterialTheme.typography.headlineSmall)
            if (passwordRecoveryRequired) Text(stringResource(Res.string.password_recovery_required))
            TextButton({ dispatch(SettingsAction.Back) }, enabled = !passwordRecoveryRequired) { Text(stringResource(Res.string.back)) }
            ToggleSetting(stringResource(Res.string.parent_control), parentEnabled, enabled = !passwordRecoveryRequired) {
                dispatch(SettingsAction.ToggleParent(it))
            }
            TextButton({ dispatch(SettingsAction.ChangePassword) }) {
                Text(stringResource(Res.string.change_password))
            }
            ToggleSetting(stringResource(Res.string.analytics), analyticsEnabled, enabled = !passwordRecoveryRequired) {
                dispatch(SettingsAction.ToggleAnalytics(it))
            }
            Text(stringResource(Res.string.analytics_description))
            HorizontalDivider()
            Text(stringResource(Res.string.backup_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(Res.string.manual_backup_description))
        }
    }
}
