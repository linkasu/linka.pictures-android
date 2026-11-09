package su.linka.pictures.ui.settings

import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.CancellationException
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.stringResource
import su.linka.pictures.resources.*
import su.linka.pictures.ui.app.ProductDialog
import su.linka.pictures.ui.app.ProductSection

internal const val FFMPEG_VERSION = "8.1.2"
internal const val FFMPEG_SOURCE_URL = "https://github.com/linkasu/linka.pictures-android/tree/main/third_party/ffmpeg"

@OptIn(ExperimentalResourceApi::class)
@Composable
internal fun ThirdPartyLicenses() {
    val uri = LocalUriHandler.current
    var document by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableStateOf(0) }
    var loaded by remember(document, attempt) { mutableStateOf<Result<String>?>(null) }
    var linkProblem by remember { mutableStateOf(false) }
    ProductSection(stringResource(Res.string.licenses_title)) {
        Text(stringResource(Res.string.licenses_ffmpeg_summary, FFMPEG_VERSION))
        TextButton(onClick = { document = "COPYING.LGPLv2.1" }) {
            Text(stringResource(Res.string.licenses_read_lgpl))
        }
        TextButton(onClick = { document = "LICENSE.md" }) {
            Text(stringResource(Res.string.licenses_read_ffmpeg_notices))
        }
        Text(stringResource(Res.string.licenses_source_explanation))
        TextButton(onClick = {
            try { uri.openUri(FFMPEG_SOURCE_URL); linkProblem = false }
            catch (_: Exception) { linkProblem = true }
        }) { Text(stringResource(Res.string.licenses_open_source)) }
        if (linkProblem) Text(stringResource(Res.string.licenses_link_error),
            Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    }
    document?.let { selected ->
        LaunchedEffect(selected, attempt) {
            loaded = try {
                Result.success(Res.readBytes("files/licenses/$selected").decodeToString(throwOnInvalidSequence = true))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Result.failure(failure)
            }
        }
        ProductDialog(stringResource(if (selected == "COPYING.LGPLv2.1") Res.string.licenses_read_lgpl
            else Res.string.licenses_read_ffmpeg_notices), { document = null }, actions = {
            if (loaded?.isFailure == true) TextButton(onClick = { attempt++ }) {
                Text(stringResource(Res.string.licenses_retry))
            }
            TextButton(onClick = { document = null }) { Text(stringResource(Res.string.close)) }
        }) {
            val result = loaded
            when {
                result == null -> Text(stringResource(Res.string.licenses_loading),
                    Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                result.isFailure -> Text(stringResource(Res.string.licenses_load_error),
                    Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                else -> SelectionContainer { Text(result.getOrThrow()) }
            }
        }
    }
}
