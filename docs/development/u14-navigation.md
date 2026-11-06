# UI 1.4: admission и lifecycle

| Состояние / намерение | Автоматически | Только явно |
| --- | --- | --- |
| Новый navigator | Один `start` открывает библиотеку; до завершения loading | Последующие переходы |
| Библиотека ↔ общение | Сохраняется CommunicationSession и immutable playback | Выбор доски / команды общения |
| Новый маршрут редактора, Unprotected, IDLE, canonical check Completed | Один первоначально выбранный New/Edit/ResumeDraft | Последующие команды |
| Protected / отказ admission / устаревший маршрут | Ничего | Контекстная авторизация, затем «Начать» |
| PAUSED / background / reauth | Нет повторного initial intent | Только AuthoringAction.Resume |
| Transfer pending / claimed / running | Существующие отмена, preview и safe stop без изменений | После picker свежий unlock и отдельное подтверждение |
| SETTINGS для выбранной доски | Чтение проверенного snapshot, без default-save | Сохранить pager и явный глобальный COMPOSE/DIRECT; SetMode только после Completed |
| Disposal / lifecycle failure | Invalidate owner до cancel child; поздний start не создаёт owner | При quarantine общение остаётся видимым, без auto-apply |

Persisted state, schema, writers и counters не меняются. Settings пишет только существующий LibraryController с captured epoch/revision и canonical guard. Lock order сохраняется: navigation transition → close; committed notification выполняется существующим путём. Никаких новых DB/FS/native вызовов в image demand или freshness checks. Старые и новые binaries используют ту же схему; rollback не требует миграций.

Image demand привязан к реальному route/epoch/session, берётся только из composed visible demand. Старый owner не должен очищать demand нового. UI auto-entry живёт исключительно в presentation: navigator не отправляет authoring initial commands; guard AuthoringSession неизменен.

## Подключённые public seams

Public dependencies `04c0d56` и `97a4332` подключены отдельными cherry-pick, без собственных правок чужих модулей. В LinkaApp:

- Настоящий `access` передан в LibraryScreen.gate и SettingsScreen.gate; SettingsScreen.onAccess открывает ACCESS.
- Communication callback вызывает `navigate(SETTINGS, board = capturedBoardId)`.
- SETTINGS с boardId показывает CommunicationScreen под `BoardSettingsDialog`, закрывается обратно в COMMUNICATION. Обычный SETTINGS без boardId остаётся глобальным экраном.
- Только board-scoped SETTINGS исключён из audio.interrupt/CommunicationCommand.Stop при входе; глобальный SETTINGS сохраняет прежнее поведение. Canonical authorization owner и navigation close guard сохранены.
- dialog.onSaved обновляет portable settings; onSetMode явно dispatch SetMode. Dialog вызывает onSetMode только после Completed, проверки исходного generation guard и дополнительной актуальной context-проверки; root дополнительно проверяет identity маршрута. После конфликта сохраняет snapshot и ввод; после unknown блокирует повтор.
- Communication image demand учитывается, пока CommunicationScreen видим под модальным окном; целая page не запрашивается, message не сбрасывается.

Board-modal показывает borrowed access-панель только если canonical CHANGE_SETTINGS check не Completed. Non-manage fresh-none не рисует технических кнопок; `requireFreshUnlock` для подтверждения transfer после picker сохраняет явный unlock. Управление защитой остаётся отдельным экраном.

## Native Back (frozen COORD seam)

LinkaApp принимает trailing optional `onBackHandlerChanged: (((() -> Unit)?) -> Unit)? = null`. Один composition-owned publisher: startup/transition поглощает Back, библиотека публикует null для native exit, общение возвращает библиотеку без изменения message/playback. Settings/access/transfer используют существующие выходы; board-settings возвращается в общение. Editor-owned callback передаётся единственному publisher: IDLE/unknown использует прежний scaffold exit, PAUSED — прежний confirmLeave, остальные фазы — AuthoringAction.Close; открытая Card/Grid отменяется соответствующей командой. ForceDiscard не добавляется. Compose Dialog сохраняет свой native dismiss приоритет.

Регистрации эфемерны: latest callback хранится через rememberUpdatedState, каждый опубликованный wrapper имеет incarnation/liveness guard, route identity проверяется перед вызовом. Старый callback после нового маршрута или dispose не запускает новое действие. Dispose отзывает wrapper и публикует null до navigation scope cancellation. Persisted schema/writers/locks/authoring policy не меняются. Actual Android key-event/OnBackPressedDispatcher и порядок native Window.close проверяет parent; pure tests этого не доказывают.

Встроенный icon pack в текущих dependencies отсутствует: back использует доступный 48dp IconButton с текстовым шевроном и contentDescription, без новой зависимости. Старый ImageDemand.kt сохранён, поскольку у него остаются собственные тесты; root больше его не вызывает.

Проверка: JDK 17, `./gradlew :shared:jvmTest --tests 'su.linka.pictures.ui.app.*' --tests 'su.linka.pictures.ui.access.*' --tests 'su.linka.pictures.ui.portability.*' --max-workers=1 --offline`. Native/ADB/FFmpeg и полный gate не запускались.
