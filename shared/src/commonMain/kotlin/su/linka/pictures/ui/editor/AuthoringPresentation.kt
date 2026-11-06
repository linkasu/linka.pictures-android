package su.linka.pictures.ui.editor

import su.linka.pictures.application.ProblemCode
import su.linka.pictures.application.authoring.*
import su.linka.pictures.domain.BoardDocument
import su.linka.pictures.domain.InputStyle

internal fun withoutSpaceDetails(document: BoardDocument, checked: Boolean): AuthoringAction.Details =
    AuthoringAction.Details(document.name, document.description,
        document.behavior.copy(inputStyle = if (checked) InputStyle.LITERAL else InputStyle.CARDS))

internal fun AuthoringState.editable(): Boolean = phase == AuthoringPhase.EDITING || phase == AuthoringPhase.CONFLICT

internal fun draftSaveLabel(phase: DraftSavePhase): String = when (phase) {
    DraftSavePhase.UNSAVED -> "Черновик ещё не сохранён"
    DraftSavePhase.SAVING -> "Сохранение черновика…"
    DraftSavePhase.SAVED -> "Черновик сохранён на устройстве"
    DraftSavePhase.FAILED -> "Черновик не сохранён. Не закрывайте редактор без сохранения."
    DraftSavePhase.UNCONFIRMED -> "Результат записи неизвестен. Редактирование остановлено; не повторяйте применение."
}

internal fun authoringProblemLabel(code: ProblemCode): String = when (code) {
    ProblemCode.CONFLICT -> "Доска или черновик изменены другим редактором. Ваш сохранённый черновик не потерян; применение остановлено."
    ProblemCode.STALE_REQUEST -> "Библиотека была заменена. Откройте редактор заново; старый черновик не будет применён к новой библиотеке."
    ProblemCode.UNAUTHORIZED -> "Требуется повторный вход помощника и явное продолжение."
    ProblemCode.UNFINISHED_FORM -> "Завершите форму и дождитесь сохранения черновика."
    ProblemCode.LIMIT_EXCEEDED -> "Превышен предел: 256 страниц, сетка 1–64 × 1–64, текст 65536 символов, изображение 16 Мп / 8192 по стороне, сложность GIF или доступная память, аудио 10 минут / 64 МиБ. Данные не обрезаны."
    ProblemCode.MISSING_ASSET -> "Нет изображения или записи. Выберите файл заново."
    ProblemCode.INVALID_DATA -> "Проверьте введённые данные и размер сетки."
    ProblemCode.NOT_FOUND -> "Доска или черновик больше не существуют."
    ProblemCode.PERMISSION_DENIED -> "Нет разрешения на выбранную операцию."
    ProblemCode.UNSUPPORTED_MEDIA -> "Этот формат изображения или звука не поддерживается."
    ProblemCode.VOICE_UNAVAILABLE, ProblemCode.SPEECH_UNAVAILABLE -> "Локальный голос недоступен. Выберите запись или речь без звука."
    ProblemCode.INTERRUPTED -> "Операция прервана. Изменения формы сохранены только при наличии подтверждения выше."
    ProblemCode.COMMIT_OUTCOME_UNKNOWN -> "Результат записи неизвестен. Не повторяйте применение; откройте библиотеку заново для проверки."
    else -> "Не удалось выполнить операцию (${code.name}). Черновик не считается сохранённым без подтверждения."
}
