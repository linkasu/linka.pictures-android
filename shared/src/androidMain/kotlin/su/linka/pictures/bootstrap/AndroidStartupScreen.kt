package su.linka.pictures.bootstrap

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material.Button
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import su.linka.pictures.application.*
import su.linka.pictures.resources.*
import su.linka.pictures.ui.theme.LinkaTheme

@Composable
fun AndroidProductRuntime.StartupContent() {
    val outcome by startup.collectAsState()
    val progress by migration.collectAsState()
    LinkaTheme(isSystemInDarkTheme(), false, 1f) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (val current = outcome) {
                    null -> {
                        Text(stringResource(Res.string.startup_opening))
                        if (progress.total > 0) Text(stringResource(Res.string.startup_progress, progress.imported, progress.retained, progress.total))
                    }
                    is OperationResult.Completed -> Unit
                    else -> {
                        Text(stringResource(if (current == OperationResult.UnconfirmedCommit) Res.string.startup_unconfirmed else Res.string.startup_blocked))
                        (current as? OperationResult.Failed)?.let { Text(it.problem.code.name) }
                        Text(stringResource(Res.string.startup_preserved))
                        if (!progress.requiresRestart && (current == OperationResult.Cancelled ||
                            (current as? OperationResult.Failed)?.problem?.code == ProblemCode.INTERRUPTED)) {
                            Button(onClick = ::resumeStartup) { Text(stringResource(Res.string.startup_resume)) }
                        } else Text(stringResource(Res.string.startup_restart))
                    }
                }
            }
        }
    }
}
