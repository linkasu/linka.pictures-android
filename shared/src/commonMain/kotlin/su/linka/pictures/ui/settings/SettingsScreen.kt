package su.linka.pictures.ui.settings

import androidx.compose.material.Text
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import su.linka.pictures.application.*
import su.linka.pictures.application.library.LibraryController
import su.linka.pictures.application.ports.*
import su.linka.pictures.domain.*
import su.linka.pictures.ui.app.*

private data class SettingsSnapshot(val listing: LibraryListing, val value: PortableSettings)

@Composable
internal fun SettingsScreen(library: LibraryStore, controller: LibraryController, audio: AudioPort,
    owner: String, capability: AssistantCapability?, enabled: Boolean, dark: Boolean, onDark: (Boolean) -> Unit,
    onSaved: (PortableSettings) -> Unit, access: @Composable () -> Unit, onBack: () -> Unit) {
    var loaded by remember { mutableStateOf<SettingsSnapshot?>(null) }
    var result by remember { mutableStateOf<OperationResult<*>?>(null) }
    var refresh by remember { mutableStateOf(0) }
    val voices by audio.voices.collectAsState()
    LaunchedEffect(refresh) {
        loaded = null; result = null
        val listing = library.listing.value
        when (val read = library.settings()) {
            is OperationResult.Completed -> {
                if (library.listing.value.epoch == listing.epoch && library.listing.value.revision == listing.revision && listing.refreshProblem == null)
                    loaded = SettingsSnapshot(listing, read.value)
                else result = OperationResult.Failed(Problem(ProblemCode.STALE_REQUEST))
            }
            else -> result = read
        }
    }
    ProductScaffold("Настройки", onBack) {
        access()
        Action(if (dark) "Тема: тёмная — выбрать светлую" else "Тема: светлая — выбрать тёмную", enabled) { onDark(!dark) }
        Text("Голос на этом устройстве: ${voiceStatusLabel(voices.status)}")
        if (voices.voices.isEmpty()) Text("Локальные голоса пока недоступны. Установите голос средствами ОС; общение без озвучивания доступно.")
        voices.voices.forEach { Text("${it.name} · ${it.languageTag}") }
        Text("Голоса локальные; идентификаторы голосов не записываются в резервную копию.")
        if (loaded == null && result == null) Text("Загружаем настройки…")
        loaded?.let { snapshot -> key(snapshot) {
            SettingsForm(snapshot, controller, owner, capability, enabled, onSaved) { result = it }
        } }
        Feedback(result)
        Action("Загрузить настройки заново (отбросить ввод)", enabled) { refresh++ }
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
    capability: AssistantCapability?, enabled: Boolean, onSaved: (PortableSettings) -> Unit,
    onResult: (OperationResult<*>) -> Unit) {
    var scale by remember { mutableStateOf(snapshot.value.textScale.toString()) }
    var language by remember { mutableStateOf(snapshot.value.languageTag) }
    var contrast by remember { mutableStateOf(snapshot.value.highContrast) }
    var boards by remember { mutableStateOf(snapshot.value.boards) }
    var busy by remember { mutableStateOf(false) }
    var committed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Field(scale, "Масштаб текста (0.5–3)", enabled && !busy && !committed) { scale = it }
    Field(language, "Язык (например ru-RU)", enabled && !busy && !committed) { language = it }
    Action(if (contrast) "Высокий контраст: включён — выключить" else "Высокий контраст: выключен — включить", enabled && !busy && !committed) { contrast = !contrast }
    snapshot.listing.items.filter { it.kind == LibraryEntryKind.BOARD }.forEach { entry ->
        val show = boards[entry.id]?.showPageButtons ?: true
        Action("«${entry.name}»: кнопки страниц ${if (show) "показаны — скрыть" else "скрыты — показать"}", enabled && !busy && !committed) {
            boards = boards + (entry.id to BoardPresentation(!show))
        }
    }
    val parsed = scale.toFloatOrNull()
    val valid = parsed != null && parsed.isFinite() && parsed in 0.5f..3f && language.isNotBlank() && language.length <= 128
    if (!valid) Text("Введите масштаб от 0.5 до 3 и непустой код языка длиной до 128 символов.")
    Action("Сохранить настройки", enabled && !busy && !committed && valid) {
        val value = PortableSettings(parsed!!, contrast, language, boards)
        busy = true
        scope.launch {
            try {
                val result = controller.settings(owner, capability, value, snapshot.listing.revision, snapshot.listing.epoch)
                onResult(result)
                if (result is OperationResult.Completed) { committed = true; onSaved(value) }
                if (result == OperationResult.UnconfirmedCommit) committed = true
            } finally { busy = false }
        }
    }
}
