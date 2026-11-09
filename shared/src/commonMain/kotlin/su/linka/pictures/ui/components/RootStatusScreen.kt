package su.linka.pictures.ui.components

import org.jetbrains.compose.resources.stringResource
import su.linka.pictures.resources.*
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import su.linka.pictures.application.Problem
import su.linka.pictures.ui.theme.LinkaTheme
import su.linka.pictures.ui.theme.LocalLinkaPalette

@Composable
fun RootStatusScreen(problem: Problem?) = LinkaTheme(isSystemInDarkTheme(), false, 1f) {
    val palette = LocalLinkaPalette.current
    Surface(Modifier.fillMaxSize(), color = palette.background, contentColor = palette.text) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (problem == null) Text(stringResource(Res.string.opening_the_local_library))
            else {
                Text(stringResource(Res.string.could_not_safely_open_the_app_1, problem.code))
                Text(stringResource(Res.string.data_was_not_reset_and_protection_was))
            }
        }
    }
}
