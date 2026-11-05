package su.linka.pictures.ui.portability

import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import kotlinx.coroutines.launch
import su.linka.pictures.application.*
import su.linka.pictures.application.portability.*
import su.linka.pictures.application.ports.*
import su.linka.pictures.domain.*
import su.linka.pictures.ui.app.*

internal fun transferPhaseLabel(phase: TransferPhase): String = when (phase) {
    TransferPhase.VALIDATING -> "Проверяем файл…"
    TransferPhase.PREPARING -> "Готовим предварительный просмотр…"
    TransferPhase.AWAITING_CONFIRMATION -> "Проверьте данные и подтвердите отдельной кнопкой."
    TransferPhase.WRITING -> "Записываем… Не повторяйте действие."
    TransferPhase.COMPLETED -> "Операция завершена."
    TransferPhase.CANCELLED -> "Операция отменена."
    TransferPhase.FAILED -> "Операция не выполнена."
    TransferPhase.UNCONFIRMED -> "Исход записи неизвестен. Повтор запрещён. Перезапустите приложение и проверьте библиотеку / файл назначения."
}

@Composable
internal fun TransferScreen(library: LibraryStore, transfer: TransferCoordinator, gate: AssistantGate,
    owner: String, capability: AssistantCapability?, enabled: Boolean, boardId: BoardId?,
    access: @Composable () -> Unit, onBack: () -> Unit) {
    val operations by transfer.state.collectAsState()
    val listing by library.listing.collectAsState()
    val current = operations.values.lastOrNull { it.ownerId == owner }
    var result by remember { mutableStateOf<OperationResult<*>?>(null) }
    var recovery by remember { mutableStateOf<OperationResult<RecoveryInfo?>?>(null) }
    var name by remember { mutableStateOf("Импортированная доска") }
    var busy by remember { mutableStateOf(false) }
    var replace by remember(current?.transfer?.id) { mutableStateOf<LibraryEntrySummary?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(current?.transfer?.phase) { recovery = library.recovery() }
    val terminal = current == null || current.transfer.phase in setOf(TransferPhase.COMPLETED, TransferPhase.CANCELLED, TransferPhase.FAILED)
    val canPrepare = enabled && !busy && terminal
    fun prepare(action: ProtectedAction, block: suspend () -> OperationResult<OperationId>) {
        val admission = gate.check(action, capability, owner)
        if (admission !is OperationResult.Completed) { result = admission; return }
        busy = true
        scope.launch { try { result = block() } finally { busy = false } }
    }
    fun confirm(confirmation: TransferConfirmation) {
        val id = current?.transfer?.id ?: return
        busy = true
        scope.launch { try { result = transfer.confirm(owner, id, capability, confirmation) } finally { busy = false } }
    }
    ProductScaffold("Файлы и восстановление", onBack) {
        access()
        Text("Файлы .linka и резервные копии — открытые архивы, не зашифрованные. Они могут содержать личные изображения, записи голоса и текст. Выбирайте доверенный файл и место хранения.")
        Field(name, "Название импортируемой доски", canPrepare) { name = it }
        Action("Выбрать .linka для импорта (без записи в библиотеку)", canPrepare && name.isNotBlank()) {
            val entered = name; prepare(ProtectedAction.IMPORT_BOARD) { transfer.prepareImport(owner, entered) }
        }
        if (boardId != null) Action("Подготовить экспорт выбранной доски .linka", canPrepare) {
            prepare(ProtectedAction.EXPORT_BOARD) { transfer.prepareExport(owner, boardId) }
        }
        Action("Подготовить резервную копию всей библиотеки", canPrepare) {
            prepare(ProtectedAction.CREATE_BACKUP) { transfer.prepareBackup(owner) }
        }
        val recoveryAbsent = (recovery as? OperationResult.Completed)?.value == null && recovery is OperationResult.Completed
        Action("Выбрать резервную копию для проверки", canPrepare && recoveryAbsent) {
            prepare(ProtectedAction.RESTORE_BACKUP) { transfer.prepareRestore(owner) }
        }
        Text("Резервная копия переносит доски, черновики, медиа и переносимые настройки. Пароль и локальные голоса не переносятся.")
        when (val info = recovery) {
            null -> Text("Проверяем точку восстановления…")
            is OperationResult.Completed -> if (info.value == null) Text("Точки восстановления нет.") else {
                val saved = info.value
                Text("Точка восстановления: досок ${saved.boardCount}, черновиков ${saved.draftCount}, байт медиа ${saved.assetBytes}; время ${saved.createdAtEpochMillis}.")
                Text("Для нового восстановления из файла сначала отдельно удалите существующую точку. Приложение не перезаписывает её молча.")
                Action("Проверить возврат к точке восстановления", canPrepare) {
                    prepare(ProtectedAction.RESTORE_BACKUP) { transfer.prepareReturnToRecovery(owner, saved.id) }
                }
                Action("Подготовить удаление точки восстановления", canPrepare, true) {
                    prepare(ProtectedAction.DELETE_RECOVERY) { transfer.prepareDeleteRecovery(owner, saved.id) }
                }
            }
            else -> Feedback(info)
        }
        Action("Обновить сведения о точке восстановления", canPrepare) { scope.launch { recovery = library.recovery() } }
        current?.let { operation ->
            Text(transferPhaseLabel(operation.transfer.phase), Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            if (operation.kind == TransferKind.EXPORT || operation.kind == TransferKind.BACKUP)
                Text("Если запись отменена или прервана, внешний файл мог сохраниться частично. Проверьте файл назначения перед передачей другим людям.")
            Text("Проверено записей архива: ${operation.transfer.processedEntries}; байт: ${operation.transfer.processedBytes}.")
            operation.preview?.let { preview ->
                preview.name?.let { Text("Доска: «$it»") }
                preview.board?.let { Text("Страниц: ${it.pages}; карточек: ${it.cards}; изображений: ${it.images}; записей: ${it.recordings}; байт медиа: ${it.mediaBytes}.${if (it.layoutNormalized) " Сетка нормализована для приложения." else ""}") }
                preview.snapshot?.let { Text("Досок: ${it.boards}; черновиков: ${it.drafts}; сохранённых нечитаемых архивов: ${it.retained}; медиа: ${it.assets}; байт медиа: ${it.assetBytes}.") }
                preview.expectedLibraryRevision?.let { Text("Подтверждение относится к версии библиотеки $it и показанному сообщению. При изменении данных операция будет отклонена.") }
                val canConfirm = enabled && capability != null && !busy && operation.transfer.phase == TransferPhase.AWAITING_CONFIRMATION
                if (operation.transfer.phase == TransferPhase.AWAITING_CONFIRMATION && capability == null)
                    Text("Перед подтверждением явно откройте доступ кнопкой «Разблокировать этот экран» / «Разрешить команды в этом экране» выше. Выбор файла не предоставляет доступ.")
                when (operation.kind) {
                    TransferKind.IMPORT -> {
                        Action("Подтверждаю импорт «${preview.name}» как новой доски", canConfirm) { confirm(TransferConfirmation.ImportNew) }
                        listing.items.filter { it.kind == LibraryEntryKind.BOARD }.forEach { entry ->
                            Action("Заменить «${entry.name}» импортируемой доской…", canConfirm, true) { replace = entry }
                        }
                        replace?.let { entry ->
                            Text("Полностью заменить «${entry.name}» версии ${entry.revision} импортируемой доской «${preview.name}»?")
                            Action("Подтверждаю замену «${entry.name}»", canConfirm, true) { confirm(TransferConfirmation.ImportReplace(entry.id, entry.revision)) }
                            Action("Не заменять", !busy) { replace = null }
                        }
                    }
                    TransferKind.EXPORT, TransferKind.BACKUP -> {
                        Text("Выбор места в системном диалоге ещё не разрешает запись. Файл будет открыт для получателя без пароля.")
                        Action("Подтверждаю запись открытого ${if (operation.kind == TransferKind.EXPORT) "файла доски" else "архива всей библиотеки"}", canConfirm) { confirm(TransferConfirmation.WriteFile) }
                    }
                    TransferKind.RESTORE -> {
                        Text("Восстановление заменит ВСЮ библиотеку, настройки и текущее сообщение. Прежняя библиотека станет точкой восстановления.")
                        Action("Подтверждаю замену ВСЕЙ библиотеки из резервной копии", canConfirm, true) { confirm(TransferConfirmation.ReplaceWholeLibrary) }
                    }
                    TransferKind.RETURN_TO_RECOVERY -> {
                        Text("Возврат заменит всю текущую библиотеку и сообщение. Текущая библиотека займёт место точки восстановления.")
                        Action("Подтверждаю возврат всей библиотеки к точке восстановления", canConfirm, true) { confirm(TransferConfirmation.ReturnToRecovery) }
                    }
                    TransferKind.DELETE_RECOVERY -> Action("Подтверждаю безвозвратное удаление точки восстановления", canConfirm, true) { confirm(TransferConfirmation.DeleteRecovery) }
                }
            }
            operation.transfer.problem?.let { Feedback(OperationResult.Failed(it)) }
            if (!terminal && operation.transfer.phase != TransferPhase.UNCONFIRMED) Action("Отменить текущую операцию", enabled) {
                scope.launch { result = transfer.cancel(owner, operation.transfer.id, capability) }
            }
        }
        Feedback(result)
        Text("После ухода в фон операция не возобновляется автоматически: снова откройте доступ и явно выберите файл / подготовку заново.")
    }
}
