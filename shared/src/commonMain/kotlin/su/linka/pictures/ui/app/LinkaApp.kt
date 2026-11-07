package su.linka.pictures.ui.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.material.Text
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
import su.linka.pictures.ui.communication.rememberCommunicationBoardMeasurer
import su.linka.pictures.ui.communication.communicationPresentation
import su.linka.pictures.ui.communication.communicationNotices
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
    onLibraryReplaced: (Long) -> Unit,
    onBackHandlerChanged: (((() -> Unit)?) -> Unit)? = null) {
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
    var settingsLoaded by remember(library, listing.epoch) { mutableStateOf(false) }
    val systemDark = isSystemInDarkTheme()
    var dark by remember { mutableStateOf(systemDark) }
    var libraryAssets by remember(route?.owner) { mutableStateOf<Set<AssetId>>(emptySet()) }
    var editorAssets by remember(route?.owner) { mutableStateOf<Set<AssetId>>(emptySet()) }
    var communicationAssets by remember(navigation, communication, route, message.stamp.epoch) {
        mutableStateOf<Set<AssetId>>(emptySet())
    }
    var editorBack by remember(navigation, route) { mutableStateOf<(() -> Unit)?>(null) }
    val uiScope = rememberCoroutineScope()
    val measurer = rememberCommunicationBoardMeasurer()
    var openingBoard by remember { mutableStateOf(false) }
    var openingJob by remember { mutableStateOf<Job?>(null) }
    var admissionNotice by remember { mutableStateOf<String?>(null) }
    fun navigate(page: ProductPage, action: AuthoringAction? = null, board: BoardId? = null) {
        uiScope.launch { navigation.open(page, action, board) }
    }
    val backAction: (() -> Unit)? = when (productBackAction(started, transitioning, route?.page, route?.boardId != null)) {
        ProductBackAction.CONSUME -> ({})
        ProductBackAction.NATIVE_EXIT -> null
        ProductBackAction.LIBRARY -> ({ navigate(ProductPage.LIBRARY) })
        ProductBackAction.COMMUNICATION -> ({ navigate(ProductPage.COMMUNICATION) })
        ProductBackAction.EDITOR -> ({ editorBack?.invoke() })
    }
    val latestBack by rememberUpdatedState(backAction)
    val ownedBack = remember(navigation, onBackHandlerChanged) { OwnedProductBack(onBackHandlerChanged ?: {}) }
    val latestOwnedBack by key(navigation) { rememberUpdatedState(ownedBack) }
    SideEffect {
        val snapshot = route
        ownedBack.update(backAction?.let { {
            if (navigation.route.value === snapshot && !navigation.transitioning.value) latestBack?.invoke()
        } })
    }
    DisposableEffect(ownedBack) {
        onDispose { ownedBack.close() }
    }
    LaunchedEffect(library, listing.epoch, listing.revision) {
        val snapshot = library.listing.value
        val read = library.settings()
        currentCoroutineContext().ensureActive()
        if (library.listing.value.epoch == snapshot.epoch && library.listing.value.revision == snapshot.revision)
            (read as? OperationResult.Completed)?.value?.let { settings = it; settingsLoaded = true }
    }
    LaunchedEffect(isForeground, navigation) { if (!isForeground) navigation.background() }
    LaunchedEffect(assistantState.commandsAvailable(), navigation) {
        if (!assistantState.commandsAvailable()) navigation.revokeProtectedAccess()
    }
    DisposableEffect(navigation) { onDispose {
        latestOwnedBack.close()
        navigation.dispose()
        assetsNeeded(emptySet())
    } }
    val requested = when (route?.page) {
        null, ProductPage.COMMUNICATION -> if (started) communicationAssets else emptySet()
        ProductPage.LIBRARY -> libraryAssets
        ProductPage.EDITOR -> editorAssets
        ProductPage.SETTINGS -> if (route?.boardId != null) communicationAssets else emptySet()
        else -> emptySet()
    }
    LaunchedEffect(navigation, requested) { assetsNeeded(requested) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
    val density = LocalDensity.current
    val insets = WindowInsets.systemBars.union(WindowInsets.displayCutout)
    val boardWidth = maxWidth.value - with(density) {
        (insets.getLeft(this, LayoutDirection.Ltr) + insets.getRight(this, LayoutDirection.Ltr)).toDp().value
    }
    val boardHeight = maxHeight.value - with(density) { (insets.getTop(this) + insets.getBottom(this)).toDp().value }
    fun measureBoard(document: BoardDocument, value: PortableSettings) = measurer.measure(document,
        boardWidth, boardHeight, value.textScale, value.boards[document.id]?.showPageButtons ?: true)
    val boardLayout = remember(message.board, measurer, boardWidth, boardHeight, settings) {
        message.board?.let { measureBoard(it.document, settings) }
    }
    val presentation = remember(message, voices) { communicationPresentation(message, voices) }
    LaunchedEffect(route, isForeground, measurer, boardWidth, boardHeight, settings) {
        openingJob?.cancel()
    }
    fun returnToCommunication() {
        if (boardLayout == null) admissionNotice = "Масштаб не поддерживается"
        else if (!presentation.commandsEnabled) return
        else navigate(ProductPage.COMMUNICATION)
    }
    suspend fun validateSettings(value: PortableSettings): String? =
        message.board?.let { if (measureBoard(it.document, value) == null) "Масштаб не поддерживается" else null }
    fun openBoard(board: BoardId) {
        if (openingBoard || !isForeground || !settingsLoaded) return
        val previousRoute = navigation.route.value
        openingBoard = true
        openingJob = uiScope.launch {
            try {
                when (val read = library.board(board)) {
                    is OperationResult.Completed -> {
                        val loaded = read.value
                        val supported = try { measureBoard(loaded.board.document, settings) != null }
                            finally { loaded.lease.close() }
                        currentCoroutineContext().ensureActive()
                        if (!supported) admissionNotice = "Масштаб не поддерживается"
                        else if (navigation.route.value === previousRoute && isForeground) {
                            when (val openedBoard = communication.openBoard(board, expectedRevision = loaded.board.revision)) {
                                is OperationResult.Completed -> if (navigation.route.value === previousRoute)
                                    navigate(ProductPage.COMMUNICATION)
                                is OperationResult.Failed -> admissionNotice = "Не удалось открыть набор. Вернитесь к выбору набора."
                                else -> Unit
                            }
                        }
                    }
                    else -> admissionNotice = "Не удалось открыть набор. Вернитесь к выбору набора."
                }
            } finally { openingBoard = false }
        }
    }
    LaunchedEffect(route, boardLayout) {
        if (started && route == null && boardLayout == null) navigation.open(ProductPage.LIBRARY)
    }
    LinkaTheme(dark, settings.highContrast, settings.textScale) {
        val current = route
        val boardSettings = current?.page == ProductPage.SETTINGS && current.boardId != null
        val communicationVisible = current == null || boardSettings
        val capability = current?.capability?.collectAsState()?.value
        val transferState = current?.transfer?.state?.collectAsState()?.value?.values
            ?.lastOrNull { it.ownerId == current.owner }
        val contextAction = when (current?.page) {
            ProductPage.EDITOR -> current.initialAction?.initialProtectedAction()
            ProductPage.SETTINGS -> ProtectedAction.CHANGE_SETTINGS
            ProductPage.TRANSFER -> transferState?.kind?.let(::transferProtectedAction)
            else -> null
        }
        val accessPanel: @Composable () -> Unit = {
            if (current != null) AssistantAccessPanel(access, current.owner, capability, commandsEnabled,
                current.page == ProductPage.ACCESS, contextAction,
                transferState?.transfer?.phase == TransferPhase.AWAITING_CONFIRMATION) {
                current.capability.value = it
            }
        }
        if (!started || (transitioning && !communicationVisible)) {
            Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                androidx.compose.material.CircularProgressIndicator()
            }
        } else if (communicationVisible && boardLayout != null && settingsLoaded) {
            val currentBoard = message.message.cursor?.boardId
            CommunicationScreen(message, ids, voices, { if (isForeground && current == null) communication.dispatch(it) },
                { navigate(ProductPage.LIBRARY) }, boardLayout, images,
                showPageButtons = settings.boards[currentBoard]?.showPageButtons ?: true,
                onImagesNeeded = { communicationAssets = it },
                onOpenBoardSettings = currentBoard?.let { board -> {
                    if (commandsEnabled && current == null) navigate(ProductPage.SETTINGS, board = board)
                } })
            if (boardSettings) key(current.owner) {
                BoardSettingsDialog(library, controller, access, current.owner, capability, current.boardId!!,
                    message.message.mode, commandsEnabled, accessPanel, { settings = it },
                    { mode ->
                        if (isForeground && navigation.route.value === current)
                            communication.dispatch(CommunicationCommand.SetMode(CommandId(ids.next()), mode))
                    }, { if (navigation.route.value === current) returnToCommunication() },
                    validateSettings = ::validateSettings)
            }
        } else if (current != null) key(current.owner) {
            when (current.page) {
                ProductPage.LIBRARY -> LibraryScreen(library, controller, current.owner, capability, commandsEnabled && !openingBoard,
                    images, { libraryAssets = it }, accessPanel, ::returnToCommunication,
                    ::openBoard, { navigate(ProductPage.EDITOR, AuthoringAction.NewBoard()) },
                    { navigate(ProductPage.EDITOR, AuthoringAction.EditBoard(it)) },
                    { navigate(ProductPage.EDITOR, AuthoringAction.ResumeDraft(it)) },
                    { navigate(ProductPage.SETTINGS) }, { navigate(ProductPage.ACCESS) },
                    { navigate(ProductPage.TRANSFER, board = it) }, gate = access,
                    canOpenBoards = settingsLoaded,
                    canReturnToCommunication = settingsLoaded && boardLayout != null && !openingBoard && presentation.commandsEnabled,
                    communicationNotices = communicationNotices(message, presentation, voices))
                ProductPage.SETTINGS -> SettingsScreen(library, controller, audio, current.owner, capability, commandsEnabled,
                    dark, { dark = it }, { settings = it; settingsLoaded = true }, accessPanel, { navigate(ProductPage.LIBRARY) },
                    gate = access, onAccess = { navigate(ProductPage.ACCESS) }, validateSettings = ::validateSettings)
                ProductPage.ACCESS -> ProductScaffold("Защита помощника", { navigate(ProductPage.LIBRARY) }) { accessPanel() }
                ProductPage.TRANSFER -> TransferScreen(library, current.transfer!!, access, current.owner, capability,
                    commandsEnabled, isForeground, current.boardId, accessPanel, { navigate(ProductPage.LIBRARY) })
                ProductPage.EDITOR -> ProductEditor(current, ids, access, capability, isForeground, commandsEnabled, images, accessPanel,
                    { editorAssets = it }, { navigate(ProductPage.LIBRARY) }, { editorBack = it })
                ProductPage.COMMUNICATION -> Unit
            }
        }
        if (admissionNotice != null && current != null) ProductDialog(admissionNotice!!,
            { admissionNotice = null }, actions = { Action("Закрыть") { admissionNotice = null } }) {
            Text("Выберите другой масштаб или набор во взрослом разделе. Текущая доска и сообщение не изменены.")
        }
        if (restartProblem?.code == ProblemCode.IO_FAILURE && !hideLifecycleProblem) {
            ProductDialog("Не удалось завершить закрытие / перезагрузку", {
                hideLifecycleProblem = true
                navigate(ProductPage.COMMUNICATION)
            }, actions = {
                Action("К экрану общения") {
                    hideLifecycleProblem = true
                    navigate(ProductPage.COMMUNICATION)
                }
            }) {
                committedEpoch?.let { androidx.compose.material.Text("Последнее подтверждённое изменение библиотеки — эпоха $it. Ошибка завершения не отменяет эту запись. Не повторяйте восстановление.") }
                Feedback(restartProblem?.let { OperationResult.Failed(it) })
                androidx.compose.material.Text("Для безопасного продолжения требуется перезапуск приложения. Новые защищённые действия запрещены; автоматического повтора нет.")
            }
        }
    }
    }
}

@Composable
private fun ProductEditor(route: ProductRoute, ids: IdSource, gate: AssistantAccess, capability: AssistantCapability?, foreground: Boolean, commandsEnabled: Boolean,
    images: Map<AssetId, ImageBitmap>, access: @Composable () -> Unit, onAssets: (Set<AssetId>) -> Unit,
    onClosed: () -> Unit, onBackChanged: ((() -> Unit)?) -> Unit) {
    val editor = route.editor!!
    val state by editor.state.collectAsState()
    var confirmLeave by remember(route.owner) { mutableStateOf(false) }
    val dispatch: (AuthoringAction) -> Unit = { action ->
        if (commandsEnabled) editor.send(AuthoringCommand(CommandId(ids.next()), route.owner, capability, action))
    }
    val close: () -> Unit = {
        when {
            state.phase == AuthoringPhase.UNCONFIRMED || state.savePhase == DraftSavePhase.UNCONFIRMED ||
                state.phase == AuthoringPhase.IDLE -> onClosed()
            state.phase == AuthoringPhase.PAUSED -> confirmLeave = true
            state.draft?.form is EditorForm.Card -> dispatch(AuthoringAction.CancelCard)
            state.draft?.form is EditorForm.Grid -> dispatch(AuthoringAction.CancelGrid)
            else -> dispatch(AuthoringAction.Close)
        }
    }
    val latestClose by rememberUpdatedState(close)
    val publishBack by rememberUpdatedState(onBackChanged)
    DisposableEffect(route) {
        publishBack { latestClose() }
        onDispose { publishBack(null) }
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
        ProductScaffold("Исход записи черновика неизвестен", close) {
            Feedback(OperationResult.UnconfirmedCommit)
            AuthoringFeedback(state, dispatch)
        }
    } else if (state.phase == AuthoringPhase.IDLE || state.phase == AuthoringPhase.PAUSED) {
        ProductScaffold(if (state.phase == AuthoringPhase.PAUSED) "Редактор приостановлен" else "Открыть редактор", close) {
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
    } else EditorScreen(state, route.owner, capability, ids, { if (commandsEnabled) editor.send(it) else it.onHandled?.invoke(editor.state.value) }, images,
        onImagesNeeded = onAssets)
}
