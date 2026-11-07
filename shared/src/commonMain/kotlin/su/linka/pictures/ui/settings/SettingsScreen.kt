package su.linka.pictures.ui.settings

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
    validateSettings: suspend (PortableSettings) -> String? = { null }) {
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
        ProductTopBar("Настройки", modifier = Modifier.heightIn(min = toolbarHeightForFontScale(56f, LocalDensity.current.fontScale).dp),
            navigationIcon = {
            IconButton(onClick = onBack) { Icon(LibraryIcons.Back, "Назад") }
        }, actions = { Box {
            IconButton(onClick = { menu = true }) { Icon(LibraryIcons.More, "Меню настроек") }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem(onClick = { menu = false; refresh++ }, enabled = enabled) { Text("Загрузить заново (отбросить ввод)") }
            }
        } })
    }) { padding ->
      Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
          verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ProductSection("Родительский контроль") {
            SettingsRow("Защита помощника", "Настроить блокировку важных действий", enabled) {
                if (onAccess != null) onAccess() else showAccess = true
            }
        }
        ProductSection("Оформление сеанса") {
            SettingsSwitch("Тёмная тема", dark, enabled, "Только для текущего сеанса") { onDark(it) }
        }
        if (loaded == null && result == null) Text("Загружаем настройки…")
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
            Text(if (result == null) "Настройки сохранены. Обновляем параметры…"
                else "Настройки сохранены. Не удалось обновить параметры. Загрузите настройки заново через меню.",
                Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            if (result is OperationResult.Failed) Feedback(result)
        } else if (result !is OperationResult.Completed) Feedback(result)
        ProductSection("Данные на этом устройстве") {
            SettingsRow("Локальные данные", "Доски, изображения и аудио остаются на устройстве. Ручной экспорт и резервное копирование доступны через меню библиотеки.")
        }
      }
    }
    if (showAccess) ProductDialog("Защита помощника", { showAccess = false }, actions = {
        TextButton(onClick = { showAccess = false }) { Text("Закрыть") }
    }) {
        Text("Управление защитой доступно через меню библиотеки → Защита помощника.")
    }
}

internal fun voiceStatusLabel(status: VoiceStatus): String = when (status) {
    VoiceStatus.INITIALIZING -> "проверяем локальные голоса…"
    VoiceStatus.LOCAL_VOICE_READY -> "локальный голос готов"
    VoiceStatus.MISSING_VOICE -> "нужна установка локального голоса средствами ОС"
    VoiceStatus.UNSUPPORTED_LANGUAGE -> "выбранный язык не поддерживается установленными голосами"
    VoiceStatus.INTERRUPTED -> "озвучивание прервано"
    VoiceStatus.FAILED -> "голос недоступен"
}

@Composable
private fun SettingsForm(snapshot: SettingsSnapshot, controller: LibraryController, owner: String,
    capability: AssistantCapability?, enabled: Boolean, voices: VoiceCapability, gate: AssistantGate?,
    access: @Composable () -> Unit, feedback: OperationResult<*>?,
    validateSettings: suspend (PortableSettings) -> String?,
    onResult: suspend (OperationResult<Long>, PortableSettings) -> Unit) {
    var scale by remember { mutableStateOf(snapshot.value.textScale.toString()) }
    var language by remember { mutableStateOf(snapshot.value.languageTag) }
    var contrast by remember { mutableStateOf(snapshot.value.highContrast) }
    var boards by remember { mutableStateOf(snapshot.value.boards) }
    var busy by remember { mutableStateOf(false) }
    var committed by remember { mutableStateOf(false) }
    var authorize by remember { mutableStateOf(false) }
    var voiceMenu by remember { mutableStateOf(false) }
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
    ProductSection("Оформление карточек") {
        SettingsSwitch("Высокий контраст", contrast, editable) { contrast = it }
        SettingsRow("Масштаб текста", scale, editable) { editingValue = scale; input = SettingsInput.TEXT_SCALE }
        SettingsPreview(scale.toFloatOrNull() ?: snapshot.value.textScale, snapshot.value.textScale, contrast)
    }
    ProductSection("Озвучивание") {
        SettingsRow("Голос на этом устройстве", voiceStatusLabel(voices.status))
        SettingsRow("Язык", language, editable) { editingValue = language; input = SettingsInput.LANGUAGE }
        if (voices.voices.isEmpty()) SettingsRow("Локальные голоса недоступны", "Установите голос средствами ОС; общение без озвучивания доступно.")
        SettingsRow("Локальные голоса", "Идентификаторы голосов не записываются в резервную копию.")
    }
    input?.let { shown ->
      val isScale = shown == SettingsInput.TEXT_SCALE
      val validInput = if (isScale) settingsInputValid(editingValue.toFloatOrNull(), language)
          else settingsInputValid(scale.toFloatOrNull(), editingValue)
      ProductDialog(if (isScale) "Масштаб текста" else "Язык", { input = null }, actions = {
        TextButton(onClick = {
            if (!isScale) { language = editingValue; input = null }
            else {
                busy = true
                scope.launch {
                    try {
                        val proposed = PortableSettings(editingValue.toFloat(), contrast, language, boards)
                        layoutNotice = currentValidation(proposed)
                        if (layoutNotice == null) { scale = editingValue; input = null }
                    } finally { busy = false }
                }
            }
        }, enabled = editable && validInput) { Text("Применить") }
        TextButton(onClick = { input = null }) { Text("Отмена") }
      }) {
        Field(editingValue, if (isScale) "Масштаб текста (0.5–3)" else "Язык (например ru-RU)", editable,
            keyboard = KeyboardOptions(keyboardType = if (isScale) KeyboardType.Decimal else KeyboardType.Text), singleLine = true) { editingValue = it }
        if (!isScale && voices.voices.isNotEmpty()) Box {
            TextButton(onClick = { voiceMenu = true }, enabled = editable) { Text("Языки установленных голосов") }
            DropdownMenu(voiceMenu, { voiceMenu = false }) {
                voices.voices.forEach { voice ->
                    DropdownMenuItem(onClick = { editingValue = voice.languageTag; voiceMenu = false }) { Text("${voice.name} · ${voice.languageTag}") }
                }
            }
        }
      }
    }
    if (layoutNotice != null) ProductDialog(layoutNotice!!, { layoutNotice = null }, actions = {
        TextButton(onClick = { layoutNotice = null }) { Text("Закрыть") }
    }) { Text("Текущая доска не помещается целиком. Настройки и сообщение не изменены.") }
    ProductSection("Кнопки страниц") {
        snapshot.listing.items.filter { it.kind == LibraryEntryKind.BOARD }.forEach { entry ->
            val show = boards[entry.id]?.showPageButtons ?: true
            SettingsSwitch(entry.name, show, editable) { selected ->
                boards = boards + (entry.id to BoardPresentation(selected))
            }
        }
    }
    val parsed = scale.toFloatOrNull()
    val valid = settingsInputValid(parsed, language)
    if (!valid) Text("Введите масштаб от 0.5 до 3 и корректный непустой код языка длиной до 128 символов.")
    Text("Совместимость остальных наборов проверяется до открытия: все страницы должны помещаться целиком.")
    Button(onClick = {
        val value = PortableSettings(parsed!!, contrast, language, boards)
        authorize = needsLibraryAuthorization(gate?.check(ProtectedAction.CHANGE_SETTINGS, capability, owner))
        if (authorize) pending = value else save(value)
    }, enabled = editable && valid) { Text("Сохранить настройки") }
    if (authorize) ProductDialog("Сохранение настроек", { if (!busy) { authorize = false; pending = null } }, actions = {
        TextButton(onClick = {
            val value = pending ?: PortableSettings(parsed!!, contrast, language, boards)
            save(value)
        }, enabled = enabled && !busy && !committed) { Text("Сохранить настройки") }
        TextButton(onClick = { authorize = false; pending = null }, enabled = !busy) { Text("Отмена") }
    }) {
        Text("Подтвердите сохранение проверенных настроек. Авторизация сама по себе не применяет изменения.")
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
        Text("Предпросмотр карточки — без озвучивания и действий", style = MaterialTheme.typography.body2)
        CompositionLocalProvider(LocalDensity provides Density(density.density,
            settingsPreviewFontScale(density.fontScale, savedScale, scale))) {
            Surface(Modifier.fillMaxWidth().heightIn(min = 120.dp), color = palette.surface,
                contentColor = palette.text, border = BorderStroke(if (contrast) 2.dp else 1.dp, palette.border)) {
                Box(Modifier.padding(16.dp), contentAlignment = Alignment.Center) {
                    Text("Карточка", fontSize = 16.sp, textAlign = TextAlign.Center)
                }
            }
        }
        Text("Изменения применятся только после сохранения настроек.", style = MaterialTheme.typography.body2)
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
