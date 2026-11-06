package su.linka.pictures.ui.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.*
import su.linka.pictures.application.OperationResult
import su.linka.pictures.application.ProblemCode
import su.linka.pictures.application.authoring.*
import su.linka.pictures.application.communication.*
import su.linka.pictures.application.library.LibraryController
import su.linka.pictures.application.ports.*
import su.linka.pictures.domain.*
import su.linka.pictures.ui.access.AssistantAccessPanel
import su.linka.pictures.ui.access.commandsAvailable
import su.linka.pictures.ui.communication.CommunicationScreen
import su.linka.pictures.ui.editor.EditorScreen
import su.linka.pictures.ui.editor.AuthoringFeedback
import su.linka.pictures.ui.library.LibraryScreen
import su.linka.pictures.ui.portability.TransferScreen
import su.linka.pictures.ui.portability.transferProtectedAction
import su.linka.pictures.application.portability.TransferPhase
import su.linka.pictures.ui.settings.SettingsScreen
import su.linka.pictures.ui.theme.LinkaTheme

/** All ports and the process-wide communication session are borrowed from the platform root. */
@Composable
fun LinkaApp(library: LibraryStore, media: MediaStore, messages: MessageStore, access: AssistantAccess,
    audio: AudioPort, files: PlatformFilesPort, imagePreparation: ImagePreparationPort,
    inspection: MediaInspectionPort, ids: IdSource, scope: CoroutineScope,
    communication: CommunicationSession, isForeground: Boolean,
    images: Map<AssetId, ImageBitmap>, onAssetsNeeded: (Set<AssetId>) -> Unit,
    onOwnerOpened: (String) -> Unit, onOwnerClosed: (String) -> Unit,
    onLibraryReplaced: (Long) -> Unit) {
    val opened by rememberUpdatedState(onOwnerOpened)
    val closed by rememberUpdatedState(onOwnerClosed)
    val replaced by rememberUpdatedState(onLibraryReplaced)
    val assetsNeeded by rememberUpdatedState(onAssetsNeeded)
    val controller = remember(library, access) { LibraryController(library, access) }
    val navigation = remember(library, media, messages, access, audio, files, imagePreparation, inspection, ids, scope, communication) {
        ProductNavigation(library, media, messages, access, audio, files, imagePreparation, inspection, ids, scope,
            communication, controller, { opened(it) }, { closed(it) }, { replaced(it) })
    }
    val route by navigation.route.collectAsState()
    var started by remember(navigation) { mutableStateOf(false) }
    LaunchedEffect(navigation) { navigation.start(); started = true }
    val transitioning by navigation.transitioning.collectAsState()
    val restartProblem by navigation.problem.collectAsState()
    val committedEpoch by navigation.committedEpoch.collectAsState()
    var hideLifecycleProblem by remember(navigation, restartProblem) { mutableStateOf(false) }
    val assistantState by access.state.collectAsState()
    val commandsEnabled = isForeground && restartProblem == null && assistantState.commandsAvailable()
    val message by communication.state.collectAsState()
    val voices by audio.voices.collectAsState()
    val listing by library.listing.collectAsState()
    var settings by remember(library) { mutableStateOf(PortableSettings()) }
    val systemDark = isSystemInDarkTheme()
    var dark by remember { mutableStateOf(systemDark) }
    var libraryAssets by remember(route?.owner) { mutableStateOf<Set<AssetId>>(emptySet()) }
    var editorAssets by remember(route?.owner) { mutableStateOf<Set<AssetId>>(emptySet()) }
    var communicationAssets by remember(navigation, communication, route, message.stamp.epoch) {
        mutableStateOf<Set<AssetId>>(emptySet())
    }
    val uiScope = rememberCoroutineScope()
    fun navigate(page: ProductPage, action: AuthoringAction? = null, board: BoardId? = null) {
        uiScope.launch { navigation.open(page, action, board) }
    }
    LaunchedEffect(library, listing.epoch, listing.revision) {
        val snapshot = library.listing.value
        val read = library.settings()
        currentCoroutineContext().ensureActive()
        if (library.listing.value.epoch == snapshot.epoch && library.listing.value.revision == snapshot.revision)
            (read as? OperationResult.Completed)?.value?.let { settings = it }
    }
    LaunchedEffect(isForeground, navigation) { if (!isForeground) navigation.background() }
    LaunchedEffect(assistantState.commandsAvailable(), navigation) {
        if (!assistantState.commandsAvailable()) navigation.revokeProtectedAccess()
    }
    DisposableEffect(navigation) { onDispose { navigation.dispose(); assetsNeeded(emptySet()) } }
    val requested = when (route?.page) {
        null, ProductPage.COMMUNICATION -> if (started) communicationAssets else emptySet()
        ProductPage.LIBRARY -> libraryAssets
        ProductPage.EDITOR -> editorAssets
        else -> emptySet()
    }
    LaunchedEffect(navigation, requested) { assetsNeeded(requested) }
    LinkaTheme(dark, settings.highContrast, settings.textScale) {
        val current = route
        if (!started || transitioning) {
            Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                androidx.compose.material.CircularProgressIndicator()
            }
        } else if (current == null) {
            CommunicationScreen(message, ids, voices, { if (isForeground) communication.dispatch(it) },
                { navigate(ProductPage.LIBRARY) }, images,
                showPageButtons = settings.boards[message.message.cursor?.boardId]?.showPageButtons ?: true,
                onImagesNeeded = { communicationAssets = it })
        } else key(current.owner) {
            val capability by current.capability.collectAsState()
            val transferState = current.transfer?.state?.collectAsState()?.value?.values
                ?.lastOrNull { it.ownerId == current.owner }
            val contextAction = when (current.page) {
                ProductPage.EDITOR -> current.initialAction?.initialProtectedAction()
                ProductPage.SETTINGS -> ProtectedAction.CHANGE_SETTINGS
                ProductPage.TRANSFER -> transferState?.kind?.let(::transferProtectedAction)
                else -> null
            }
            val accessPanel: @Composable () -> Unit = {
                AssistantAccessPanel(access, current.owner, capability, commandsEnabled, current.page == ProductPage.ACCESS,
                    contextAction, transferState?.transfer?.phase == TransferPhase.AWAITING_CONFIRMATION) {
                    current.capability.value = it
                }
            }
            when (current.page) {
                ProductPage.LIBRARY -> LibraryScreen(library, controller, current.owner, capability, commandsEnabled,
                    images, { libraryAssets = it }, accessPanel, { navigate(ProductPage.COMMUNICATION) },
                    { board ->
                        communication.dispatch(CommunicationCommand.OpenBoard(CommandId(ids.next()), board))
                        navigate(ProductPage.COMMUNICATION)
                    }, { navigate(ProductPage.EDITOR, AuthoringAction.NewBoard()) },
                    { navigate(ProductPage.EDITOR, AuthoringAction.EditBoard(it)) },
                    { navigate(ProductPage.EDITOR, AuthoringAction.ResumeDraft(it)) },
                    { navigate(ProductPage.SETTINGS) }, { navigate(ProductPage.ACCESS) },
                    { navigate(ProductPage.TRANSFER, board = it) })
                ProductPage.SETTINGS -> SettingsScreen(library, controller, audio, current.owner, capability, commandsEnabled,
                    dark, { dark = it }, { settings = it }, accessPanel, { navigate(ProductPage.LIBRARY) })
                ProductPage.ACCESS -> ProductScaffold("Защита помощника", { navigate(ProductPage.LIBRARY) }) { accessPanel() }
                ProductPage.TRANSFER -> TransferScreen(library, current.transfer!!, access, current.owner, capability,
                    commandsEnabled, isForeground, current.boardId, accessPanel, { navigate(ProductPage.LIBRARY) })
                ProductPage.EDITOR -> ProductEditor(current, ids, access, capability, isForeground, commandsEnabled, images, accessPanel,
                    { editorAssets = it }, { navigate(ProductPage.LIBRARY) })
                ProductPage.COMMUNICATION -> Unit
            }
        }
        if (restartProblem?.code == ProblemCode.IO_FAILURE && !hideLifecycleProblem) {
            ProductDialog("Не удалось завершить закрытие / перезагрузку", {
                hideLifecycleProblem = true
                navigate(ProductPage.COMMUNICATION)
            }) {
                committedEpoch?.let { androidx.compose.material.Text("Последнее подтверждённое изменение библиотеки — эпоха $it. Ошибка завершения не отменяет эту запись. Не повторяйте восстановление.") }
                Feedback(restartProblem?.let { OperationResult.Failed(it) })
                androidx.compose.material.Text("Для безопасного продолжения требуется перезапуск приложения. Новые защищённые действия запрещены; автоматического повтора нет.")
                Action("К экрану общения") {
                    hideLifecycleProblem = true
                    navigate(ProductPage.COMMUNICATION)
                }
            }
        }
    }
}

@Composable
private fun ProductEditor(route: ProductRoute, ids: IdSource, gate: AssistantAccess, capability: AssistantCapability?, foreground: Boolean, commandsEnabled: Boolean,
    images: Map<AssetId, ImageBitmap>, access: @Composable () -> Unit, onAssets: (Set<AssetId>) -> Unit,
    onClosed: () -> Unit) {
    val editor = route.editor!!
    val state by editor.state.collectAsState()
    var confirmLeave by remember(route.owner) { mutableStateOf(false) }
    val dispatch: (AuthoringAction) -> Unit = { action ->
        if (commandsEnabled) editor.send(AuthoringCommand(CommandId(ids.next()), route.owner, capability, action))
    }
    var entryAttempted by remember(route) { mutableStateOf(false) }
    LaunchedEffect(route) {
        val action = route.initialAction
        val permitted = action?.initialProtectedAction()?.let {
            gate.check(it, null, route.owner) is OperationResult.Completed
        } == true
        val enter = initialEditorEntryReady(action, state.phase, gate.state.value,
            foreground && commandsEnabled && !route.invalidationStarted.value, permitted, entryAttempted)
        entryAttempted = true
        if (enter) dispatch(action!!)
    }
    LaunchedEffect(state.phase) {
        if (state.phase == AuthoringPhase.CLOSED || state.phase == AuthoringPhase.APPLIED) onClosed()
    }
    if (state.phase == AuthoringPhase.UNCONFIRMED || state.savePhase == DraftSavePhase.UNCONFIRMED) {
        ProductScaffold("Исход записи черновика неизвестен", onClosed) {
            Feedback(OperationResult.UnconfirmedCommit)
            AuthoringFeedback(state, dispatch)
        }
    } else if (state.phase == AuthoringPhase.IDLE || state.phase == AuthoringPhase.PAUSED) {
        ProductScaffold(if (state.phase == AuthoringPhase.PAUSED) "Редактор приостановлен" else "Открыть редактор", {
            if (state.phase == AuthoringPhase.IDLE) onClosed()
            else confirmLeave = true
        }) {
            access()
            if (state.phase == AuthoringPhase.PAUSED) androidx.compose.material.Text("Чтобы продолжить работу, повторно откройте доступ и явно продолжите редактирование. Можно отдельно выйти, оставив только уже сохранённый черновик.")
            if (state.phase == AuthoringPhase.IDLE) Action("Начать редактирование", commandsEnabled) {
                dispatch(route.initialAction!!)
            }
            AuthoringFeedback(state, dispatch, showSaveStatus = state.draft != null)
            if (confirmLeave) {
                val name = state.draft?.document?.name ?: "Черновик"
                androidx.compose.material.Text("Выйти из редактора «$name»? Сохранённая версия ${state.durableVersion ?: "ещё отсутствует"} останется в библиотеке. Только ещё не записанный ввод будет потерян. Опубликованная доска и сообщение не удаляются.")
                Action("Подтверждаю выход из «$name» с потерей несохранённого ввода", foreground, true, onClosed)
                Action("Остаться в редакторе", foreground) { confirmLeave = false }
            }
        }
    } else EditorScreen(state, route.owner, capability, ids, { if (commandsEnabled) editor.send(it) }, images,
        onImagesNeeded = onAssets)
}
