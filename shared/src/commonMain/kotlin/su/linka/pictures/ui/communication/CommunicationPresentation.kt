package su.linka.pictures.ui.communication

import su.linka.pictures.application.ProblemCode
import su.linka.pictures.application.communication.CommunicationState
import su.linka.pictures.application.communication.MessageSavePhase
import su.linka.pictures.application.ports.AudioPhase
import su.linka.pictures.application.ports.VoiceCapability
import su.linka.pictures.application.ports.VoiceStatus
import su.linka.pictures.domain.BoardCell
import su.linka.pictures.domain.BoardPage
import su.linka.pictures.domain.CardId
import su.linka.pictures.domain.InteractionMode
import su.linka.pictures.domain.MessagePart

internal data class CommunicationPresentation(
    val page: BoardPage?,
    val pageIndex: Int,
    val selectedCards: Set<CardId>,
    val commandsEnabled: Boolean,
    val composing: Boolean,
    val stopping: Boolean,
    val speechEnabled: Boolean,
    val editEnabled: Boolean,
    val undoEnabled: Boolean,
    val saveStatus: String,
    val audioStatus: String,
    val voiceStatus: String,
    val problems: List<String>,
)

internal fun communicationPresentation(state: CommunicationState, voices: VoiceCapability): CommunicationPresentation {
    val cursor = state.message.cursor
    val board = state.board?.takeIf { it.document.id == cursor?.boardId }
    val pageIndex = board?.document?.pages?.indexOfFirst { it.id == cursor?.pageId } ?: -1
    val page = board?.document?.pages?.getOrNull(pageIndex)
    val codes = listOfNotNull(state.problem?.code, state.audio.problem?.code).distinct()
    val unconfirmed = state.savePhase == MessageSavePhase.UNCONFIRMED || ProblemCode.COMMIT_OUTCOME_UNKNOWN in codes
    val enabled = !unconfirmed && state.savePhase != MessageSavePhase.LOADING && ProblemCode.STALE_REQUEST !in codes
    val composing = state.message.mode == InteractionMode.COMPOSE
    val stopping = state.audio.phase in setOf(
        AudioPhase.PREPARING, AudioPhase.PLAYING, AudioPhase.REQUESTING_PERMISSION,
        AudioPhase.RECORDING, AudioPhase.FINISHING, AudioPhase.SYNTHESIZING,
    )
    return CommunicationPresentation(
        page = page,
        pageIndex = pageIndex,
        selectedCards = if (!composing || board == null || page == null) emptySet() else state.message.parts
            .filterIsInstance<MessagePart.Card>()
            .filter {
                it.snapshot.origin.boardId == board.document.id && it.snapshot.origin.boardRevision == board.revision &&
                    it.snapshot.origin.pageId == page.id
            }.mapTo(mutableSetOf()) { it.snapshot.origin.cardId },
        commandsEnabled = enabled,
        composing = composing,
        stopping = stopping,
        // Missing TTS must not disable a recording-only plan; the coordinator checks the real plan.
        speechEnabled = enabled && (stopping || (composing && state.message.parts.isNotEmpty())),
        editEnabled = enabled && composing && state.message.parts.isNotEmpty(),
        undoEnabled = enabled && composing && state.canUndoClear,
        saveStatus = if (unconfirmed) unconfirmedMessage else when (state.savePhase) {
            MessageSavePhase.LOADING -> "Загрузка сохранённого сообщения. Действия пока недоступны."
            MessageSavePhase.SAVING -> "Сохранение… Сообщение ещё не подтверждено на диске."
            MessageSavePhase.SAVED -> "Сообщение сохранено."
            MessageSavePhase.FAILED -> "Сообщение не сохранено. Оно остаётся на экране."
            MessageSavePhase.UNCONFIRMED -> unconfirmedMessage
        },
        audioStatus = when (state.audio.phase) {
            AudioPhase.IDLE -> "Воспроизведение остановлено."
            AudioPhase.PREPARING -> "Подготовка произнесения…"
            AudioPhase.PLAYING -> "Сообщение звучит."
            AudioPhase.REQUESTING_PERMISSION -> "Ожидание разрешения на запись."
            AudioPhase.RECORDING -> "Идёт запись."
            AudioPhase.FINISHING -> "Завершение записи…"
            AudioPhase.SYNTHESIZING -> "Подготовка речи…"
            AudioPhase.READY -> "Аудио подготовлено."
            AudioPhase.COMPLETED -> "Воспроизведение завершено."
            AudioPhase.CANCELLED -> "Воспроизведение отменено."
            AudioPhase.INTERRUPTED -> "Воспроизведение прервано. Автоматического повтора нет."
            AudioPhase.FAILED -> "Не удалось завершить воспроизведение. Сообщение осталось на экране."
        },
        voiceStatus = when (voices.status) {
            VoiceStatus.INITIALIZING -> "Проверка локального голоса ОС… Записи не требуют TTS."
            VoiceStatus.LOCAL_VOICE_READY -> "Локальный голос ОС доступен."
            VoiceStatus.MISSING_VOICE -> "Локальный голос ОС не установлен. Текст и изображения доступны; записи не требуют TTS. Настройте локальный голос в системе для чтения текста."
            VoiceStatus.UNSUPPORTED_LANGUAGE -> "Локальный голос не поддерживает нужный язык. Записи не требуют TTS."
            VoiceStatus.INTERRUPTED -> "Проверка локального голоса прервана. Записи не требуют TTS."
            VoiceStatus.FAILED -> "Не удалось проверить локальный голос ОС. Записи не требуют TTS."
        },
        problems = codes.filterNot { it == ProblemCode.COMMIT_OUTCOME_UNKNOWN }.map(::communicationProblemText),
    )
}

private const val unconfirmedMessage = "Результат сохранения не подтверждён. Нельзя считать операцию сохранённой или отменённой. Сообщение остаётся на экране, действия заблокированы. Полностью закройте приложение и откройте его заново для проверки данных. Не повторяйте операцию до проверки."

internal fun pageTarget(index: Int, count: Int, direction: Int, enabled: Boolean): Int? =
    (index + direction).takeIf { enabled && index in 0 until count && it in 0 until count && direction in listOf(-1, 1) }

/** Like a button, activate on release; native key repeat never changes multiple pages. */
internal class PageKeyActivation {
    private var pressed: Int? = null
    fun reset() { pressed = null }
    fun event(direction: Int, down: Boolean, textFocused: Boolean, modified: Boolean, enabled: Boolean = true, activate: (Int) -> Unit): Boolean {
        if (!enabled || textFocused || modified || direction !in listOf(-1, 1)) {
            reset()
            return false
        }
        if (down) {
            if (pressed == null) pressed = direction
        } else {
            val fire = pressed == direction
            reset()
            if (fire) activate(direction)
        }
        return true
    }
}

internal fun communicationSlots(page: BoardPage): List<BoardCell?> {
    val cells = page.cells.associateBy { it.position }
    return List(page.capacity) { cells[it] }
}

internal fun communicationProblemText(code: ProblemCode): String = when (code) {
    ProblemCode.NOT_FOUND -> "Доска не найдена. Откройте библиотеку и выберите другую доску."
    ProblemCode.CONFLICT -> "Данные были изменены другим действием. Изменения не перезаписаны."
    ProblemCode.INVALID_DATA -> "Не удалось прочитать данные: неверный формат."
    ProblemCode.NEWER_SCHEMA -> "Данные созданы более новой версией приложения. Не удаляйте их; обновите приложение."
    ProblemCode.IO_FAILURE -> "Ошибка чтения или записи. Проверьте доступное место и доступ к данным."
    ProblemCode.UNSUPPORTED_VERSION -> "Версия набора не поддерживается."
    ProblemCode.UNSUPPORTED_MODE -> "Режим набора не поддерживается."
    ProblemCode.UNSUPPORTED_FEATURE -> "Набор содержит неподдерживаемую функцию."
    ProblemCode.UNSUPPORTED_MEDIA -> "Формат изображения или аудио не поддерживается."
    ProblemCode.UNSAFE_ARCHIVE -> "Небезопасный архив отклонён."
    ProblemCode.LIMIT_EXCEEDED -> "Достигнут предел размера данных. Содержимое не обрезается молча; проверьте объём сообщения или набора."
    ProblemCode.UNAUTHORIZED -> "Для этого действия нужен доступ помощника. Общение не требует разблокировки."
    ProblemCode.OWNER_VERIFICATION_UNAVAILABLE -> "Системная проверка владельца недоступна."
    ProblemCode.PERMISSION_DENIED -> "Нет разрешения на запрошенное действие."
    ProblemCode.VOICE_UNAVAILABLE -> "Нужный локальный голос ОС недоступен. Сообщение остаётся на экране."
    ProblemCode.INTERRUPTED -> "Действие прервано. Автоматического повтора нет."
    ProblemCode.UNFINISHED_FORM -> "Сначала завершите заполнение формы."
    ProblemCode.RECOVERY_UNAVAILABLE -> "Не удалось подготовить возврат к прежней библиотеке."
    ProblemCode.STALE_REQUEST -> "Библиотека была заменена. Этот сеанс устарел. Полностью закройте приложение и откройте его заново для чтения актуальных данных без автоматического произнесения."
    ProblemCode.MISSING_ASSET -> "Не найдено нужное изображение или аудио. Сообщение остаётся на экране."
    ProblemCode.INTEGRITY_FAILURE -> "Проверка целостности данных не пройдена. Не удаляйте исходные данные."
    ProblemCode.SPEECH_UNAVAILABLE -> "Для части сообщения нет доступного произнесения. Сообщение не будет озвучено с пропусками."
    ProblemCode.SPEECH_MATERIALIZATION_REQUIRED -> "Перед переносом требуется подготовить аудио локальной речи."
    ProblemCode.COMMIT_OUTCOME_UNKNOWN -> unconfirmedMessage
}
