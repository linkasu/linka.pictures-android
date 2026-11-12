package su.linka.pictures.ui.settings

import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.StringResource
import su.linka.pictures.resources.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextAlign
import kotlinx.coroutines.launch
import su.linka.pictures.application.*
import su.linka.pictures.application.library.LibraryController
import su.linka.pictures.application.ports.*
import su.linka.pictures.domain.*
import su.linka.pictures.ui.app.*
import su.linka.pictures.ui.library.needsLibraryAuthorization
import su.linka.pictures.ui.library.LibraryIcons
import su.linka.pictures.text.isNativeText
import su.linka.pictures.ui.components.toolbarHeightForFontScale
import su.linka.pictures.ui.theme.LocalLinkaPalette
import su.linka.pictures.ui.theme.linkaPalette

private enum class SettingsInput { TEXT_SCALE, LANGUAGE }

@Composable
internal fun SettingsScreen(library: LibraryStore, controller: LibraryController, audio: AudioPort,
    owner: String, capability: AssistantCapability?, enabled: Boolean, dark: Boolean, onDark: (Boolean) -> Unit,
    onSaved: (PortableSettings) -> Unit, access: @Composable () -> Unit, onBack: () -> Unit,
    gate: AssistantGate? = null, onAccess: (() -> Unit)? = null,
    validateSettings: suspend (PortableSettings) -> String? = { null }, telemetry: @Composable () -> Unit = {}) {
    var loaded by remember { mutableStateOf<SettingsSnapshot?>(null) }
    var result by remember { mutableStateOf<OperationResult<*>?>(null) }
    var refresh by remember { mutableStateOf(0) }
    var menu by remember { mutableStateOf(false) }
    var showAccess by remember { mutableStateOf(false) }
    var savedAwaitingReload by remember { mutableStateOf(false) }
    val voices by audio.voices.collectAsState()
    LaunchedEffect(refresh) {
        if (!savedAwaitingReload) loaded = null
        result = null
        when (val read = readSettingsSnapshot(library)) {
            is OperationResult.Completed -> {
                loaded = read.value
                savedAwaitingReload = false
            }
            else -> result = read
        }
    }
    Scaffold(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding(), topBar = {
        ProductTopBar(stringResource(Res.string.settings), modifier = Modifier.heightIn(min = toolbarHeightForFontScale(56f, LocalDensity.current.fontScale).dp),
            navigationIcon = {
            IconButton(onClick = onBack) { Icon(LibraryIcons.Back, stringResource(Res.string.back)) }
        }, actions = { Box {
            IconButton(onClick = { menu = true }) { Icon(LibraryIcons.More, stringResource(Res.string.settings_menu)) }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem(onClick = { menu = false; refresh++ }, enabled = enabled) { Text(stringResource(Res.string.reload_discard_input)) }
            }
        } })
    }) { padding ->
      Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
          verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ProductSection(stringResource(Res.string.parental_control)) {
            SettingsRow(stringResource(Res.string.assistant_protection), stringResource(Res.string.configure_protection_for_important_actions), enabled) {
                if (onAccess != null) onAccess() else showAccess = true
            }
        }
        ProductSection(stringResource(Res.string.session_appearance)) {
            SettingsSwitch(stringResource(Res.string.dark_theme), dark, enabled, stringResource(Res.string.only_for_this_session)) { onDark(it) }
        }
        telemetry()
        if (loaded == null && result == null) Text(stringResource(Res.string.loading_settings))
        loaded?.let { snapshot -> key(snapshot) {
            SettingsForm(snapshot, controller, owner, capability, enabled, voices, gate, access, result, validateSettings) { outcome, value ->
                result = if (outcome is OperationResult.Completed) null else outcome
                if (outcome is OperationResult.Completed) savedAwaitingReload = true
                when (val read = reloadSettingsAfterSave(library, outcome, value, onSaved)) {
                    null -> Unit
                    is OperationResult.Completed -> { loaded = read.value; savedAwaitingReload = false }
                    else -> result = read
                }
            }
        } }
        if (savedAwaitingReload) {
            Text(if (result == null) stringResource(Res.string.settings_saved_refreshing_parameters)
                else stringResource(Res.string.settings_saved_could_not_refresh_parameters_reload),
                Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            if (result is OperationResult.Failed) Feedback(result)
        } else if (result !is OperationResult.Completed) Feedback(result)
        ProductSection(stringResource(Res.string.data_on_this_device)) {
            SettingsRow(stringResource(Res.string.local_data), stringResource(Res.string.boards_images_and_audio_stay_on_this))
        }
        ThirdPartyLicenses()
      }
    }
    if (showAccess) ProductDialog(stringResource(Res.string.assistant_protection), { showAccess = false }, actions = {
        TextButton(onClick = { showAccess = false }) { Text(stringResource(Res.string.close)) }
    }) {
        Text(stringResource(Res.string.manage_protection_using_library_menu_assistant_protection))
    }
}

internal fun voiceStatusLabel(status: VoiceStatus): StringResource = when (status) {
    VoiceStatus.INITIALIZING -> Res.string.checking_local_voices
    VoiceStatus.LOCAL_VOICE_READY -> Res.string.local_voice_ready
    VoiceStatus.MISSING_VOICE -> Res.string.install_a_local_voice_using_the_os
    VoiceStatus.UNSUPPORTED_LANGUAGE -> Res.string.selected_language_is_not_supported_by_installed
    VoiceStatus.INTERRUPTED -> Res.string.speech_interrupted
    VoiceStatus.FAILED -> Res.string.voice_unavailable
}

@Composable
private fun SettingsForm(snapshot: SettingsSnapshot, controller: LibraryController, owner: String,
    capability: AssistantCapability?, enabled: Boolean, voices: VoiceCapability, gate: AssistantGate?,
    access: @Composable () -> Unit, feedback: OperationResult<*>?,
    validateSettings: suspend (PortableSettings) -> String?,
    onResult: suspend (OperationResult<Long>, PortableSettings) -> Unit) {
    var scale by remember { mutableStateOf(snapshot.value.textScale.toString()) }
    var messageRowPercent by remember { mutableStateOf(snapshot.value.messageRowPercent) }
    var language by remember { mutableStateOf(snapshot.value.languageTag) }
    var contrast by remember { mutableStateOf(snapshot.value.highContrast) }
    var boards by remember { mutableStateOf(snapshot.value.boards) }
    var busy by remember { mutableStateOf(false) }
    var committed by remember { mutableStateOf(false) }
    var authorize by remember { mutableStateOf(false) }
    var voiceMenu by remember { mutableStateOf(false) }
    var messageRowMenu by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<PortableSettings?>(null) }
    var input by remember { mutableStateOf<SettingsInput?>(null) }
    var editingValue by remember { mutableStateOf("") }
    var layoutNotice by remember { mutableStateOf<String?>(null) }
    val currentValidation by rememberUpdatedState(validateSettings)
    val scope = rememberCoroutineScope()
    val editable = enabled && !busy && !committed && pending == null
    fun save(value: PortableSettings) {
        pending = value
        busy = true
        scope.launch {
            try {
                layoutNotice = currentValidation(value)
                if (layoutNotice != null) { pending = null; authorize = false; return@launch }
                val result = controller.settings(owner, capability, value, snapshot.listing.revision, snapshot.listing.epoch)
                authorize = needsLibraryAuthorization(result)
                if (!authorize) pending = null
                if (result is OperationResult.Completed) { committed = true; pending = null }
                if (result == OperationResult.UnconfirmedCommit) { committed = true; pending = null }
                onResult(result, value)
            } finally { busy = false }
        }
    }
    ProductSection(stringResource(Res.string.card_appearance)) {
        SettingsSwitch(stringResource(Res.string.high_contrast), contrast, editable) { contrast = it }
        SettingsRow(stringResource(Res.string.text_scale), scale, editable) { editingValue = scale; input = SettingsInput.TEXT_SCALE }
        Box {
            SettingsRow(stringResource(Res.string.message_row_height), "$messageRowPercent%", editable) { messageRowMenu = true }
            DropdownMenu(messageRowMenu, { messageRowMenu = false }) {
                PortableSettings.allowedMessageRowPercents.forEach { percent ->
                    DropdownMenuItem(onClick = { messageRowPercent = percent; messageRowMenu = false }, enabled = editable,
                        modifier = Modifier.semantics { selected = messageRowPercent == percent }) { Text("$percent%") }
                }
            }
        }
        Text(stringResource(Res.string.message_row_height_description), style = MaterialTheme.typography.body2)
        SettingsPreview(scale.toFloatOrNull() ?: snapshot.value.textScale, snapshot.value.textScale, contrast)
    }
    ProductSection(stringResource(Res.string.speech)) {
        SettingsRow(stringResource(Res.string.voice_on_this_device), stringResource(voiceStatusLabel(voices.status)))
        SettingsRow(stringResource(Res.string.language), language, editable) { editingValue = language; input = SettingsInput.LANGUAGE }
        if (voices.voices.isEmpty()) SettingsRow(stringResource(Res.string.local_voices_unavailable), stringResource(Res.string.install_a_voice_using_the_os_communication))
        SettingsRow(stringResource(Res.string.local_voices), stringResource(Res.string.voice_identifiers_are_not_included_in_backups))
    }
    input?.let { shown ->
      val isScale = shown == SettingsInput.TEXT_SCALE
      val validInput = if (isScale) settingsInputValid(editingValue.toFloatOrNull(), language)
          else settingsInputValid(scale.toFloatOrNull(), editingValue)
      ProductDialog(if (isScale) stringResource(Res.string.text_scale) else stringResource(Res.string.language), { input = null }, actions = {
        TextButton(onClick = {
            if (!isScale) { language = editingValue; input = null }
            else {
                busy = true
                scope.launch {
                    try {
                        val proposed = snapshot.value.copy(textScale = editingValue.toFloat(), highContrast = contrast,
                            languageTag = language, boards = boards, messageRowPercent = messageRowPercent)
                        layoutNotice = currentValidation(proposed)
                        if (layoutNotice == null) { scale = editingValue; input = null }
                    } finally { busy = false }
                }
            }
        }, enabled = editable && validInput) { Text(stringResource(Res.string.apply)) }
        TextButton(onClick = { input = null }) { Text(stringResource(Res.string.cancel)) }
      }) {
        Field(editingValue, if (isScale) stringResource(Res.string.text_scale_0_5_3) else stringResource(Res.string.language_for_example_ru_ru), editable,
            keyboard = KeyboardOptions(keyboardType = if (isScale) KeyboardType.Decimal else KeyboardType.Text), singleLine = true) { editingValue = it }
        if (!isScale && voices.voices.isNotEmpty()) Box {
            TextButton(onClick = { voiceMenu = true }, enabled = editable) { Text(stringResource(Res.string.installed_voice_languages)) }
            DropdownMenu(voiceMenu, { voiceMenu = false }) {
                voices.voices.forEach { voice ->
                    DropdownMenuItem(onClick = { editingValue = voice.languageTag; voiceMenu = false }) { Text("${voice.name} · ${voice.languageTag}") }
                }
            }
        }
      }
    }
    if (layoutNotice != null) ProductDialog(layoutNotice!!, { layoutNotice = null }, actions = {
        TextButton(onClick = { layoutNotice = null }) { Text(stringResource(Res.string.close)) }
    }) { Text(stringResource(Res.string.the_current_board_does_not_fit_entirely)) }
    ProductSection(stringResource(Res.string.page_buttons)) {
        snapshot.listing.items.filter { it.kind == LibraryEntryKind.BOARD }.forEach { entry ->
            val show = boards[entry.id]?.showPageButtons ?: true
            SettingsSwitch(entry.name, show, editable) { selected ->
                boards = boards + (entry.id to boardPageButtons(boards[entry.id] ?: BoardPresentation(), selected))
            }
        }
    }
    val parsed = scale.toFloatOrNull()
    val valid = settingsInputValid(parsed, language)
    if (!valid) Text(stringResource(Res.string.enter_a_scale_from_0_5_to))
    Text(stringResource(Res.string.other_boards_are_checked_before_opening_all))
    Button(onClick = {
        val value = snapshot.value.copy(textScale = parsed!!, highContrast = contrast,
            languageTag = language, boards = boards, messageRowPercent = messageRowPercent)
        authorize = needsLibraryAuthorization(gate?.check(ProtectedAction.CHANGE_SETTINGS, capability, owner))
        if (authorize) pending = value else save(value)
    }, enabled = editable && valid) { Text(stringResource(Res.string.save_settings)) }
    if (authorize) ProductDialog(stringResource(Res.string.saving_settings), { if (!busy) { authorize = false; pending = null } }, actions = {
        TextButton(onClick = {
            val value = pending ?: snapshot.value.copy(textScale = parsed!!, highContrast = contrast,
                languageTag = language, boards = boards, messageRowPercent = messageRowPercent)
            save(value)
        }, enabled = enabled && !busy && !committed) { Text(stringResource(Res.string.save_settings)) }
        TextButton(onClick = { authorize = false; pending = null }, enabled = !busy) { Text(stringResource(Res.string.cancel)) }
    }) {
        Text(stringResource(Res.string.confirm_saving_the_validated_settings_authorization_alone))
        access()
        Feedback(feedback)
    }
}

internal fun settingsInputValid(scale: Float?, language: String): Boolean =
    scale != null && scale.isFinite() && scale in 0.5f..3f && language.isNotBlank() && language.length <= 128 && isNativeText(language)

internal fun settingsPreviewFontScale(current: Float, saved: Float, draft: Float): Float = current / saved * draft

@Composable
private fun SettingsPreview(scale: Float, savedScale: Float, contrast: Boolean) {
    val palette = linkaPalette(!MaterialTheme.colors.isLight, contrast)
    val density = LocalDensity.current
    Column(Modifier.padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(Res.string.card_preview_no_speech_or_actions), style = MaterialTheme.typography.body2)
        CompositionLocalProvider(LocalDensity provides Density(density.density,
            settingsPreviewFontScale(density.fontScale, savedScale, scale))) {
            Surface(Modifier.fillMaxWidth().heightIn(min = 120.dp), color = palette.surface,
                contentColor = palette.text, border = BorderStroke(if (contrast) 2.dp else 1.dp, palette.border)) {
                Box(Modifier.padding(16.dp), contentAlignment = Alignment.Center) {
                    Text(stringResource(Res.string.card), fontSize = 16.sp, textAlign = TextAlign.Center)
                }
            }
        }
        Text(stringResource(Res.string.changes_apply_only_after_saving_settings), style = MaterialTheme.typography.body2)
    }
}

@Composable
private fun SettingsRow(title: String, summary: String? = null, enabled: Boolean = false, onClick: (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().then(if (onClick == null) Modifier else Modifier.clickable(enabled = enabled, onClick = onClick))
        .padding(vertical = 16.dp)) {
        Text(title, style = MaterialTheme.typography.body1)
        summary?.let { Text(it, style = MaterialTheme.typography.body2, color = LocalLinkaPalette.current.secondaryText) }
    }
}

@Composable
private fun SettingsSwitch(title: String, checked: Boolean, enabled: Boolean, summary: String? = null, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            Text(title)
            summary?.let { Text(it, style = MaterialTheme.typography.body2) }
        }
        Switch(checked, onChange, enabled = enabled, modifier = Modifier.semantics { contentDescription = title })
    }
}
