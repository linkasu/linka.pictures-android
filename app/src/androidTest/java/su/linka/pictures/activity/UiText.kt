package su.linka.pictures.activity

import java.util.Locale

/** Exact UI resource labels only; fixture names and entered/message text are never translated. */
internal enum class UiText(private val ru: String, private val en: String) {
    NoAssistantPassword("Пароль помощника не установлен.", "No assistant password is set."),
    AllowCommands("Разрешить команды в этом экране", "Allow commands on this screen"),
    BoardOptions("Параметры доски", "Board options"),
    Close("Закрыть", "Close"),
    ComposeMessage("Составлять сообщение", "Compose a message"),
    UnsupportedScale("Масштаб не поддерживается", "Text scale is not supported"),
    Back("Назад", "Back"),
    Cancel("Отмена", "Cancel"),
    OpenLibrary("Открыть библиотеку", "Open library"),
    BoardSettings("Настройки доски", "Board settings"),
    CommunicationMenu("Меню общения", "Communication menu"),
    ComposingMessage("Составление сообщения", "Composing a message"),
    DirectMode("Прямой режим", "Direct mode"),
    SwitchToDirectMode("Перейти в прямой режим", "Switch to direct mode"),
    PlaybackStopped("Воспроизведение остановлено.", "Playback stopped."),
    SpeakingMessage("Сообщение звучит.", "Speaking message."),
    PlaybackCompleted("Воспроизведение завершено.", "Playback completed."),
    StopSpeaking("Остановить", "Stop speaking"),
    Speak("Произнести", "Speak"),
    PreviousPage("Предыдущая страница", "Previous page"),
    NextPage("Следующая страница", "Next page"),
    ImagePending("Изображение ещё не показано", "Image not yet displayed"),
    YourBoards("Ваши наборы", "Your boards"),
    LibraryMenu("Меню библиотеки", "Library menu"),
    Settings("Настройки", "Settings"),
    ImportBackupRestore("Импорт, резервная копия и восстановление", "Import, backup and restore"),
    GoToMessage("К сообщению", "Go to message"),
    Open("Открыть", "Open"),
    OperationCompleted("Операция завершена.", "Operation completed."),
    ImportedBoard("Импортированная доска", "Imported board"),
    FilesRestore("Файлы и восстановление", "Files and restore"),
    ImportedBoardName("Название импортируемой доски", "Imported board name"),
    ChooseImport("Выбрать .linka для импорта (без записи в библиотеку)", "Choose .linka to import (without writing to the library)"),
    ParentalControl("Родительский контроль", "Parental control"),
    HighContrast("Высокий контраст", "High contrast"),
    TextScale("Масштаб текста", "Text scale"),
    Apply("Применить", "Apply"),
    TextScaleDialog("Масштаб текста (0.5–3)", "Text scale (0.5–3)"),
    BoardActions("Действия с набором «%1\$s»", "Actions for board “%1\$s”"),
    ConfirmImport("Подтверждаю импорт «%1\$s» как новой доски", "Confirm import of “%1\$s” as a new board"),
    Page("Страница %1\$s из %2\$s", "Page %1\$s of %2\$s"),
    SharedMessage("Общее сообщение: %1\$s", "Shared message: %1\$s"),
    EmptyMessage("Общее сообщение: пусто", "Shared message: empty"),
    Grid("Сетка %1\$s на %2\$s.", "Grid %1\$s by %2\$s."),
    EmptyLibrary("В библиотеке пока нет досок. Создайте доску или импортируйте файл .linka.", "No boards in the library yet. Create a board or import a .linka file."),
    IncomingFile("Получен файл. Откройте библиотеку → Перенос → Импорт или восстановление, чтобы проверить его. Автоматического добавления нет.", "A file was received. Open Library → Transfer → Import or restore to inspect it. Nothing is added automatically."),
    IncomingFailed("Файл не открыт: %1\$s. Выберите файл через меню импорта; библиотека не изменена.", "The file was not opened: %1\$s. Select it through the import menu; the library has not changed."),
    PreviewPages("Страниц: %1\$s;", "Pages: %1\$s;"),
    PreviewCounts("Страниц: %1\$s; карточек: %2\$s; изображений: %3\$s; записей: %4\$s;", "Pages: %1\$s; cards: %2\$s; images: %3\$s; recordings: %4\$s;"),
    InactiveAttachments("Неактивных вложений EMPTY/SPACE: %1\$s. Как в «Смотри», они не отображаются и не озвучиваются. Эти вложения не переносятся в рабочую доску; исходный архив не изменяется.", "Inactive EMPTY/SPACE attachments: %1\$s. As in “Smotri”, they are not displayed or spoken. They are not transferred into the working board; the source archive is unchanged.");

    val labels: List<String> get() = listOf(ru, en)

    fun labels(vararg arguments: Any): List<String> = labels.map { String.format(Locale.ROOT, it, *arguments) }

    // Prefix queries inspect UI structure; returned public state is kept byte-for-byte for comparisons.
    val prefixes: List<String> get() = labels.map { it.substringBefore("%1\$s") }

    fun matches(actual: CharSequence?) = actual?.toString() in labels

    fun starts(actual: CharSequence?) = actual != null && prefixes.any { actual.startsWith(it) }

    fun containedIn(actual: CharSequence?) = actual != null && labels.any { actual.contains(it) }
}
