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
- Каждый переход останавливает audio, отзывает outgoing owner и закрывает scope;
  editor/transfer не хранятся в вечной map. Общение продолжает использовать исходные
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
