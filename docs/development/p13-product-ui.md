# P13 — общая продуктовая оболочка

Ветка `implementation/p13-product-ui`, исходная база `baafb41`. Собственный scope:
`application/library/**`, `ui/library/**`, `ui/settings/**`, `ui/access/**`,
`ui/portability/**`, `ui/app/**`, соответствующие common tests и этот документ.
Root/native, ports/domain/storage, access policy, Gradle/CI, house theme/components,
communication/editor/card не изменяются этой реализацией.

Dependency-only commits приняты в эту рабочую ветку:
`603892a`, `5fb30af`, `e22738a`, `7d74425`, corrections `5e6fb95`, `f54b8b9`.
Их изменения не являются собственными P13 diff/доказательствами root readiness.

## Обязательный root seam

```kotlin
@Composable
fun LinkaApp(
    library: LibraryStore,
    media: MediaStore,
    messages: MessageStore,
    access: AssistantAccess,
    audio: AudioPort,
    files: PlatformFilesPort,
    imagePreparation: ImagePreparationPort,
    inspection: MediaInspectionPort,
    ids: IdSource,
    scope: CoroutineScope,
    communication: CommunicationSession,
    isForeground: Boolean,
    images: Map<AssetId, ImageBitmap>,
    onAssetsNeeded: (Set<AssetId>) -> Unit,
    onOwnerOpened: (String) -> Unit,
    onOwnerClosed: (String) -> Unit,
    onLibraryReplaced: (Long) -> Unit,
)
```

Package: `su.linka.pictures.ui.app`. Все зависимости borrowed, **все четыре callback
обязательны**, fake/no-op реализаций и default grants нет. Root владеет
StorageRuntime, process-wide CommunicationSession, CoroutineScope и native ports.
UI не создаёт второй MessageDraft, не закрывает чужую communication session и не
открывает файлов по путям. `onAssetsNeeded` получает только IMAGE IDs, а не audio
asset IDs; map содержит настоящие root thumbnails, не generated UI-label bitmaps.
Current communication page, открытые формы и только составленные LazyColumn covers
определяют текущий request set. Не запрашиваются все assets библиотеки/все страницы.
Root даёт bounded 256px thumbnails с собственным heap budget, не original bitmaps.
Visible-demand gaps чужих экранов перечислены ниже; этот diff не объявляет их решёнными.

Root callbacks навигации вызываются из UI/main context. Root scope должен быть
root-owned main scope, переживающим cleanup закрываемого screen owner. Владение
root ресурсами и их окончательное закрытие остаются у платформы. При уничтожении
composition текущий owner отзывается, transfer cleanup ожидается в NonCancellable,
затем child job отменяется и join завершается. При disposal во время незавершённого
перехода новый owner не создаётся.

`onOwnerOpened(owner)` регистрирует уникальный owner **до** создания editor/transfer.
`onOwnerClosed(owner)` синхронно закрывает native admission **до** отмены child scope;
затем `revokePending` ожидает cleanup собственных handles. Root `isOwnerOpen` должен
проверять и эту регистрацию, и свой настоящий lifecycle/presentation context.
Нужен настоящий gate, проверяющий закрытый owner: UI boolean не является guard.

На native real-background root **сначала синхронно** отзывает access и прерывает
audio. Изменение `isForeground=false` затем доставляет `AuthoringAction.Background`
и `TransferCoordinator.revokePending`. Возврат ничего не запускает. Успешный unlock
не вызывает Resume/record/preview: продолжение — отдельная существующая кнопка
`AuthoringFeedback`. Own system picker/permission presentation также инвалидирует
прежние capabilities/guards на root, но **не** должно изображаться как real background:
`Background` уничтожил бы P11 private pending adoption. Готовый leased media result
остаётся приватным в PAUSED до новой авторизации и явного Resume.

`onLibraryReplaced(epoch)` — обязательный root reload communication после реального
restore/recovery swap. Навигация наблюдает committed epochs и дополнительно читает
последний committed epoch после transfer cleanup перед отменой scope: уход со страницы
не теряет результат commit. История не переигрывает уже доставленные epochs.
Root callback должен действительно переоткрывать/reload communication, не только
менять отображаемое число. Keychain/credential failures должны передаваться реальным
`AssistantState.Unavailable`, никогда fake Unprotected.

Root показывает loading/error открытия StorageRuntime **до** вызова LinkaApp:
LibraryStore предоставляется уже открытым; его API не различает "ещё загружается"
и законный пустой listing revision 0. Common UI отдельно показывает загрузку
черновиков/настроек/recovery. Incoming native documents остаются root-owned:
скопированный provider input можно выдавать настоящим PlatformFilesPort только
после явного пользовательского выбора импорта; автоматического импорта/confirm нет.

## Реализованные сценарии

- Библиотека: пустой/заполненный/ошибочный список, retained unreadable отдельно,
  неизвестное число карточек не подменяется нулём, реальные borrowed covers только
  составленных LazyColumn items (с удалением demand на dispose),
  обновление, создание через AuthoringSession, открытие в исходной communication
  session, редактирование и возобновление durable drafts.
- Rename/delete board и delete draft получают именно shown summary/version/epoch.
  Scrollable modal rename/delete confirmation называет объект и действие: не нужно
  искать confirmation в конце длинного списка. Delete draft не удаляет board.
  Для draft list сохраняется epoch прочитанного списка, не epoch последующего render.
- Малый application LibraryController создаёт **новый gate.guard на вызов** и передаёт
  WritePermit в существующий storage порт. Epoch проверяется повторно из guard,
  revision/version проверяет storage. Нет UI SQL, raw addBoard или authority из bool.
- Settings: textScale 0.5–3, highContrast, languageTag и per-board showPageButtons
  сохраняются как существующий PortableSettings, с shown library revision/epoch.
  Оpaque/непоказанные board settings не выкидываются. После сохранения применяется
  house textScale/highContrast; dark/light переключается локально, без нового
  portable dark-mode поля. Две темы × обычный/высокий контраст используют четыре
  существующие house palette. Локальные voices/status выводятся из AudioPort;
  persisted voice ID или фиктивного выбора голоса нет.
- Access: setup/change, unlock, disable с конкретным подтверждением, reset только
  явной кнопкой настоящего device-owner API. Current/new/repeat password раздельны,
  только remember, masked PasswordVisualTransformation, password IME без autocorrect.
  Ввод очищается до terminal operation, при cancel, смене owner/state/foreground.
  UNKNOWN/UNAVAILABLE OS verification не разрешают new lock/reset; сохранённая
  legacy protection остаётся. Номер Android 21/22 — только поясняющий текст, решение
  определяется AssistantState. Unknown/error credentials закрывают protected UI,
  но не communication. UI не реализует KDF и не вызывает OS verification автоматически.
- Transfer: отдельный owner и coordinator на screen child scope; import/new/explicit
  replace, board export, whole-library backup/restore, recovery swap/delete. Есть
  предварительный просмотр реальных counts, пояснение открытого незашифрованного
  архива, отдельные destructive confirmations, cancel/failure/UNCONFIRMED. Выбор
  destination не authority. Confirm требует explicit screen capability и создаёт
  свежий application guard в TransferCoordinator. После background нужен новый
  explicit prepare/выбор файла, старый pending не оживает. Возможный частичный
  внешний файл после cancel/failure указан отдельно.
- Выход отзывает outgoing product owner, останавливает только его audio и закрывает
  scope; editor/transfer не хранятся в вечной map. Browse Library / возврат в
  communication / смена страницы или доски сохраняют текущий COMPOSE immutable
  playback plan. Только вход в editor/settings/access/transfer явно останавливает
  communication playback. Общение продолжает использовать исходные
  mode/page/message semantics. Для выхода из PAUSED без доступных credentials есть
  отдельное именованное подтверждение потери **только unsaved памяти**: это lifecycle
  disposal owner, не unauthenticated SQL/delete/apply и не собственный domain reducer.
  Durable draft и published board остаются. Обычный Back из PAUSED сам ввод не теряет.
- UNCONFIRMED ставит односторонний application restart latch: LibraryController больше
  не допускает записи, новые protected sessions не создаются. Можно читать библиотеку
  и вернуться в общение; retry неизвестного commit не предлагается. Реальный StorageRuntime
  дополнительно имеет собственный fail-closed quarantine. Root должен сохранять lifespan
  приложения/ресурсов корректно и при полном пересоздании заново открыть storage.

Экраны используют house LabeledActionButton, настоящие Text/OutlinedTextField,
видимые labels, scroll/IME/safeDrawing insets и polite live regions для ошибок и
transfer phases. Long-press/drag не требуются. Это не доказательство physical
TalkBack/VoiceOver, focus traversal, landscape/large-font или screenshot acceptance.

## Конкретные COORD handoffs — не объявлены готовыми

1. P10 `CommunicationScreen` не принимает и не читает
   `PortableSettings.boards[id].showPageButtons`. P13 сохраняет настройку, но не
   копирует communication controls. Нужен P10 presentation seam, затем в LinkaApp
   передать значение для `message.message.cursor?.boardId` (по отсутствию — true).
   До этого видимость кнопок в communication не применена.
2. P12 `prepareExport` заполняет только preview.name, но не существующий preview.board.
   Для точных export page/card/image/recording/media-byte counts нужно заполнить
   BoardTransferReport из **того же** leased export snapshot/archive. TransferScreen
   уже отображает этот report; UI не вычисляет цифры по более новому listing и не
   подставляет нули. Import/backup/restore reports уже приходят настоящими.
3. Если portable languageTag должен менять default TTS для source с null languageTag,
   нужен root/native policy binding: в существующих application seams нет API выбора
   локального default voice/language. P13 сохраняет portable languageTag и показывает
   actual voice capabilities, но не изобретает новый persisted voice model.
4. Native root bindings, incoming documents ownership, synchronous lifecycle revoke,
   root communication reload и настоящий thumbnail loader нужно проверить после
   интеграции primary. Positive OS prompt / microphone / provider / signing / AMFI /
   app-host acceptance не получены этой lane. Нет valid signing identity — app-host
   gate остаётся BLOCKED, не fake-ready.
5. Image-demand seam: CommunicationScreen скрывает visible LazyRow message items и
   visible grid rows. Сейчас запрашивается только весь **текущий cursor page**; message
   thumbnails вне него не запрашиваются по неподтверждённой видимости. EditorScreen
   скрывает selectedPage и видимые grid rows внутри rememberSaveable: без form UI
   не гадает страницу и не запрашивает все страницы. Для Card form запрашивается
   только её image; для Grid form известен pageId и запрашивается только эта страница.
   COORD нужен demand callback из P10/P11, агрегирующий именно composed/visible rows
   в уже существующий root onAssetsNeeded. До этого editor grid images и message
   thumbnails вне current-page cache — явно незавершённая интеграция. Эта lane не
   меняет чужие пакеты и не заявляет all-grid thumbnail performance без runtime.

## Проверки

Focused P13 tests используют настоящий file-backed StorageRuntime, BundledSQLiteDriver
и MediaStore. Gate/OS/audio/files/image boundaries в тестах явно fake, не native proof.
Проверяются unauthorized/no-change, stale board/settings revision и epoch, revoke
между application admission и реальным storage commit, successful settings/delete,
UNCONFIRMED freeze; unique editor owners, explicit resume, close-before-scope-cancel,
pending picker cancellation/cleanup, disposal во время slow cleanup, actual restore
epoch delivery без повторного replay; unknown counts, credential-state availability,
distinct errors/phases/voices и image-only thumbnail requests.

Native compile cache скопирован только из known main generated outputs. Официальный
`ios_audio_decoder_outputs.inspect` проверен до и после relocation: source hashes,
inventories, signature provenance, archive architecture/platform/minimum и Xcode
toolchain. Изменены только generated absolute `.def` paths и manifest root/inventory;
архивы/headers не менялись. Gradle producer подтвердил VERIFIED_FASTPATH.

Focused команда P13:

```sh
env JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew \
  :shared:jvmTest \
  --tests 'su.linka.pictures.application.library.LibraryControllerTest' \
  --tests 'su.linka.pictures.ui.app.ProductNavigationTest' \
  --tests 'su.linka.pictures.ui.app.ProductPresentationTest' \
  :shared:compileDebugKotlinAndroid \
  :shared:compileKotlinIosArm64 \
  :shared:compileKotlinIosSimulatorArm64 --console=plain
```

Итог 2026-11-05: **14/14 P13 tests PASS**, 0 failures/errors/skipped
(LibraryControllerTest 5, ProductNavigationTest 5, ProductPresentationTest 4).
Android shared debug Kotlin и оба Native shared targets **PASS**; combined command
выше — BUILD SUCCESSFUL, 28 s. Только существующие expect/actual beta warnings.

Regression reproduction: без повторной проверки `disposed` после await cleanup
тест ниже действительно падал: **1 test, 1 failed / AssertionError** — после
disposal создавался новый library owner, не закрываемый уходящей composition.
Исправление проверяет disposal после cleanup и перед открытием следующего owner;
тот же тест включён в итоговые 14 PASS. Ни fixed wait, ни надежды на порядок UI
кадров в тесте нет: ожидаются picker-entered, close-owner и completion критерии.

```sh
env JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew :shared:jvmTest \
  --tests 'su.linka.pictures.ui.app.ProductNavigationTest.disposalDuringSlowTransferCleanupCannotOpenAnOrphanOwner' \
  --console=plain
```

При расширенной проверке обнаружен также неверный первоначальный test fixture:
второй restore поверх существующей recovery point правильно возвращает CONFLICT
(`StoredSnapshots.replace` не перезаписывает recovery). Не менялся storage contract:
тест проверяет restore → recovery swap, а UI объясняет и требует отдельное удаление
существующей точки перед новым restore. Root/native readiness из этого не выводится.

Компиляция shared targets не означает готовый Android+iOS продукт: реальные app roots,
screenshots и integration/device acceptance выполняет primary после binding.

## Correction после bbba81d — failure-safe owner cleanup

Primary обнаружил реальный дефект: старый `invalidate` вызывал audio.stop перед
access.revokeOwner, а `closed=true` устанавливался до callbacks. Исключение stop
оставляло старый guard допустимым, пропускало native close/child cleanup. Вторая
ошибка: deliveredEpoch записывался до вызова onLibraryReplaced, поэтому exception
callback оставлял ложное подтверждение reload и не давал restart/error.

Оба теста написаны до исправления, выполнены на bbba81d-коде и дали **2 tests,
2 failed (AssertionError)**. Первый поймал допустимый старый guard после failed
dispose, второй — отсутствие restart latch после реального restore и failed reload.

```sh
env JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew :shared:jvmTest \
  --tests 'su.linka.pictures.ui.app.ProductNavigationTest.disposalRevokesOldGuardBeforeThrowingAudioStopAndStillCleansOwner' \
  --tests 'su.linka.pictures.ui.app.ProductNavigationTest.failedRootReloadNeverClaimsDeliveredEpochOrAutomaticallyRetries' \
  --console=plain
```

Исправление:

- Первое внешнее действие close/dispose — синхронный access.revokeOwner. Потом
  независимо пробуются authoring Revoke, audio.stop и native onOwnerClosed. Ошибка
  любой попытки не обходит следующую. Completion barrier фиксирует именно конец
  invalidation attempts, не выдуманный успешный close.
- Transfer revokePending ожидается до child scope cancellation. Committed epoch
  notification выполняется независимо в finally, cancelAndJoin — в следующем
  finally. Close result завершается только после всех попыток и отдельно сообщает
  failure; queued transition/disposal не создают нового owner после такого failure.
- Поглощённый P11 cleanupProblem также проверяется при закрытии и после child join.
  Это сохраняет первичный authoring outcome, но не теряет ошибку освобождения ресурсов.
- Ошибки cleanup/reload публикуются как **структурированный IO_FAILURE** без paths,
  exception text или secrets. Неполный lifecycle ставит sticky restart halt: новые
  owner не открываются, новые и ранее выданные LibraryController guards отказывают.
  Ошибки настоящего gate не превращаются в allow. Unknown write по-прежнему остаётся
  отдельным domain outcome; IO restart latch не ослабляется поздней domain notification.
- Реальный restore остаётся COMPLETED даже при secondary stop/close failure.
  Последняя доказанная committed epoch сохраняется для UI. deliveredEpoch меняется
  **только после возврата** настоящего root reload callback. Failed reload отзывает
  admission, даёт IO_FAILURE/restart, но не переигрывается автоматически из collector,
  close или возврата на экран и не объявляется доставленным. Returning confirmed
  transfer result не отменяется искусственно ради обработки callback exception.
- LinkaApp показывает отдельный scrollable error dialog: если есть доказанная
  committed epoch, прямо сказано, что запись уже подтверждена и повторять restore
  нельзя. Закрытие сообщения ведёт к communication и завершает оставшийся owner
  cleanup; защищённые действия остаются заблокированы до перезапуска.
- **Public LinkaApp signature не менялась.** Root/native/ports и P11/P12 source не
  изменялись. У LibraryController один restartProblem StateFlow и вычисляемый
  requiresRestart; дополнительного runtime/DI или фиктивных callbacks нет.
- Пояснение access исправлено: «На Android 21/22 системная проверка не используется
  из-за ограничений безопасности». Наличие OS API больше не отрицается; existing
  legacy lock по-прежнему не отключается автоматически.

Новые проверки проходят через настоящие ProductNavigation и StorageRuntime, не через
пустой тестовый wrapper: стабильный throwing audio.stop, exception native-close
callback при активном picker, core revoke failure, explicit child cancel/join,
COMPLETED restore + secondary stop failure, failed reload без replay/ложного delivered
epoch, отказ старого application WritePermit после lifecycle halt. Старый regression
slow-cleanup disposal остаётся в suite.

Если native-close callback бросает, успешное закрытие native owner не выдумывается:
зафиксированы попытка, failed close result и IO restart halt. Остальные cleanup
действия всё равно выполняются; новый owner после неполного lifecycle не открывается.

Correction gate: **21/21 PASS** (14 прежних + 7 новых), 0 failures/errors/skipped:
LibraryControllerTest 6, ProductNavigationTest 11, ProductPresentationTest 4.
Android shared debug Kotlin, iosArm64 и iosSimulatorArm64 shared compile **PASS**.
Combined команда выше — BUILD SUCCESSFUL, 17 s; decoder producer VERIFIED_FASTPATH,
только существующие expect/actual beta warnings. `git diff --check` — PASS.
OS prompts/devices, native root bindings, signing и screenshots не выполнялись
повторно и не объявляются доказанными этой correction.

## Correction K2 после 7c1cb7e — navigation не меняет immutable playback plan

Нормативная матрица COORD уточнена: прежняя формулировка "любой переход stop"
неверна для COMPOSE. Communication → Library → OpenBoard → Communication — это
browse; речь должна продолжать **тот же** immutable PlaybackRequest/SpeechPlan.
Existing CommunicationSession.OpenBoard/ChangePage уже поддерживают эту семантику;
ошибка находилась в безусловных interrupt/Stop в ProductNavigation.open.

Принятая матрица binding:

| Переход / событие | Audio |
|---|---|
| Открыть Library для просмотра | Не interrupt и не CommunicationCommand.Stop |
| Вернуться в communication | Не interrupt/Stop и не автоматический Speak |
| Выбрать другую страницу / доску | Existing domain navigation, прежний immutable plan остаётся |
| Войти в editor/settings/access/transfer | Явный interrupt + CommunicationCommand.Stop |
| Закрыть outgoing product owner | Только audio.stop этого owner; чужой CSession owner не трогается |
| Real background / root closing app | Root синхронный interrupt и existing cleanup, без изменений |
| ModeChange/Select/Backspace/Clear | Existing domain policy; нового reducer и авто replay нет |

Revoke-first/failure-safe cleanup из 7c1cb7e сохраняется. Новый owner не создаётся
после неполного закрытия. Communication stop на protected entry выполняется только
после успешного outgoing cleanup; browse не воздействует на чужую communication
lease/plan. Public LinkaApp signature, ports, native/root, P10 не меняются.

Новый public navigation integration test использует real file-backed StorageRuntime,
реальный CommunicationSession и owner-aware fake AudioPort с активным playback,
а не просто счётчик void callbacks. Он проверяет request/plan identity, PLAYING,
отсутствие comm-owner stop/interrupt в browse и incoming OpenBoard, а затем явную
остановку во всех четырёх protected-entry destinations и отсутствие auto Speak
после возврата. Fake stop чужого owner не переводит активную comm речь в IDLE.

```sh
env JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew :shared:jvmTest \
  --tests 'su.linka.pictures.ui.app.ProductNavigationTest.composePlaybackSurvivesBrowsingAndBoardNavigationButProtectedEntryStopsIt' \
  --console=plain
```

На 7c1cb7e до исправления команда дала **1 test, 1 failed / AssertionError**:
после `open(LIBRARY)` expected same PlaybackRequest, actual null. После изменения
только условия в ProductNavigation.open тот же тест — **PASS**, BUILD SUCCESSFUL,
13 s. Для завершения всех queued domain events используются наблюдаемые cursor /
save / audio state и явные domain navigation barriers, не fixed waits.

Первый общий K2 gate: прежние P13 suites вместе с новым navigation regression
**PASS**, Android shared и оба Native shared compile **PASS**, BUILD SUCCESSFUL,
38 s. Прежние cleanup/security corrections не откатываются. После следующей
readiness-коррекции выполняется итоговый объединённый gate.

## Transfer readiness после own picker

Ненулевой AssistantCapability не означает действующий доступ: собственный picker
отзывает прежний token, не обязательно меняя его remembered object reference.
UI readiness теперь проверяет канонический sync gate.check для конкретного
TransferKind: IMPORT_BOARD, EXPORT_BOARD, CREATE_BACKUP, RESTORE_BACKUP (restore и
return recovery), DELETE_RECOVERY. Это только presentation check: fresh WritePermit /
commit guard по-прежнему создаётся **в application** перед фактической записью.

Confirm доступен только для своего AWAITING_CONFIRMATION, при enabled, !busy и
fresh nonnull capability с успешным gate.check. При awaiting + !fresh инструкция
«После выбора файла войдите повторно / разрешите команды выше» показывается и для
старого **nonnull** token. Успешный explicit unlock меняет capability flow и readiness;
ни auto reauth/reset, ни auto confirm или повторного picker нет.

Cancel readiness отделена от business enabled/freshness: своя pending operation
может быть отменена в foreground даже при busy и revoked/null capability.
В UI передаётся настоящий isForeground существующего LinkaApp, публичная сигнатура
entry не меняется. P12 application safeStop с проверкой same owner + opaque op ID
поставляется отдельной P12 lane: здесь **не** меняется coordinator.cancel и не
объявляется доказанной application ownership policy до принятия того dependency.
Confirm/deleteRecovery/restore guards не ослабляются; внешние contents не выдаются
до отдельного fresh application confirm.

Pure TransferReadinessTest использует FakeGateRevoked с реальным отказом старому
nonnull token, новым действующим token, проверкой action mapping каждого kind,
phase/enabled/busy matrix и отдельной foreground/ownership cancellation matrix.

Readiness regression до замены извлечённого nonnull-критерия каноническим check:
**1 test, 1 failed / AssertionError** на stale nonnull capability. После исправления
все **4/4 TransferReadinessTest PASS**, BUILD SUCCESSFUL, 27 s.

```sh
env JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew :shared:jvmTest \
  --tests 'su.linka.pictures.ui.portability.TransferReadinessTest.staleNonnullPickerCapabilityCannotConfirmAndFreshUnlockEnablesEveryKind' \
  --console=plain
```

Общий итог K2 + transfer readiness: **26/26 PASS**, 0 failures/errors/skipped
(LibraryController 6, ProductNavigation 12, ProductPresentation 4, TransferReadiness 4).
Все прежние failure-safe cleanup regressions из 7c1cb7e проходят. Android shared,
iosArm64 и iosSimulatorArm64 compile **PASS**, BUILD SUCCESSFUL, 34 s. Только
существующие expect/actual beta warnings, decoder VERIFIED_FASTPATH.

```sh
env JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew \
  :shared:jvmTest \
  --tests 'su.linka.pictures.application.library.LibraryControllerTest' \
  --tests 'su.linka.pictures.ui.app.ProductNavigationTest' \
  --tests 'su.linka.pictures.ui.app.ProductPresentationTest' \
  --tests 'su.linka.pictures.ui.portability.TransferReadinessTest' \
  :shared:compileDebugKotlinAndroid \
  :shared:compileKotlinIosArm64 \
  :shared:compileKotlinIosSimulatorArm64 --console=plain
```

Это не native OS playback/picker proof, не app-host/signing/device acceptance.
Application P12 safeStop integration остаётся отдельным dependency, не UI bypass.
