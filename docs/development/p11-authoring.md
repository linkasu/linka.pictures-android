# P11 — authoring session и общие экраны

**Актуальная UI-цель (6 ноября):** [паритет 1.4](ui-1.4-parity.md).
Application/ownership evidence ниже сохраняется, но прежняя вёрстка не принята.
Редактор — большая сетка с «+», компактные параметры и toolbar; карточка —
модальный диалог как в 1.4. Autosave/SaveCard/Apply guards не меняются ради паритета.
Код на паузе; перед продолжением нужна визуальная приёмка на заполненных данных.

Область: `application/authoring/**`, `ui/editor/**`, `ui/card/**`, соответствующие
common tests. Root, Gradle, manifests, domain, storage, access, ports, communication
и house components не изменены. Base: `baafb415c93ab503938627cfaf7e9d7d48ec3fb2`,
ветка `implementation/p11-authoring`.

## Публичный seam для COORD

```kotlin
AuthoringSession(
    library: LibraryStore,
    media: MediaStore,
    audio: AudioPort,
    files: PlatformFilesPort,
    images: ImagePreparationPort,
    inspection: MediaInspectionPort,
    gate: AssistantGate,
    ids: IdSource,
    scope: CoroutineScope,
)
```

`state: StateFlow<AuthoringState>` — immutable presentation snapshot.
`send(AuthoringCommand(id, ownerId, capability, action)): Boolean` подтверждает
только постановку команды в очередь, не запись на диск. `CommandId` дедуплицируется
в окне последних 1024 команд, без вечного журнала на каждый keystroke;
новое осознанное действие получает новый ID.

Первый авторизованный command закрепляет owner. Другой owner не может менять
состояние или инвалидировать медиа. После CLOSED/APPLIED нужна новая сессия для
другого редактирования. Scope должен принадлежать editor owner и отменяться при
окончательном уничтожении этого owner; переход в background — не уничтожение scope.

Защищённые действия: CREATE_BOARD для создания/автосохранения новой доски,
EDIT_BOARD для редактирования/возобновления, APPLY_DRAFT для применения,
DELETE_DRAFT для удаления черновика. Capability передаётся явно с каждой командой,
не сохраняется в DTO или SQL. Commit получает отдельный свежий guard с epoch и
проверкой поколения lifecycle; guard не читает незащищённые actor-local переменные
из storage worker.

Экраны имеют одинаковый вход:

```kotlin
EditorScreen(state, ownerId, capability, ids, onCommand, images, modifier)
CardEditorScreen(state, ownerId, capability, ids, onCommand, images, modifier)
```

`images: Map<AssetId, ImageBitmap>` — уже подготовленные root thumbnails; UI не
читает файлы и не вызывает SQL. `EditorScreen` сам показывает CardEditorScreen
при открытой card form. Root открывает сессию через NewBoard, EditBoard(boardId)
или ResumeDraft(draftId), наблюдает CLOSED/APPLIED и выполняет навигацию.
Screen callbacks передают только команды сессии. В UI остаются только transient
выбор страницы, выбор пары ячеек для обмена и подтверждение удаления.

## Матрица состояний и durability

| Ситуация | Поведение |
|---|---|
| NewBoard | Board/Page/Draft IDs выдаются один раз; немедленный snapshot, затем autosave |
| EditBoard | Настоящий leased PublishedBoard становится исходником; создаётся отдельный draft с baseRevision |
| ResumeDraft | Восстановление только StoredDraft, включая незавершённую Card/Grid form |
| Изменение формы/документа | State обновляется без ожидания IO; один save in-flight, только последний pending snapshot |
| Старый save завершён | Обновляется доказанный durableVersion; более новое состояние не объявляется SAVED |
| Save Failed/Cancelled | Локальное состояние остаётся; ошибка видна, автоматического бесконечного retry нет |
| Save Failed, затем новое изменение | Диагностика не стирается новым optimistic snapshot |
| RetrySave | Явная новая попытка от последнего доказанного expectedVersion |
| SaveCard | Перенос формы в document черновика, не публикация доски |
| Apply с открытой формой | UNFINISHED_FORM, публикации нет |
| Apply при pending save | Ожидает последний durable snapshot; новое изменение отменяет запрос применения |
| Apply | publishDraft с expectedDraftVersion/baseRevision/epoch и свежим APPLY_DRAFT guard |
| Изменённая/удалённая доска | CONFLICT; draft остаётся в SQL, чужая правка не перезаписывается |
| Другой epoch | STALE_REQUEST/CONFLICT, не запись в восстановленную библиотеку |
| Unknown commit | UNCONFIRMED, команды заморожены; ни retry, ни повторного Apply |
| Close до durable save | UNFINISHED_FORM; не скрытая потеря изменений |
| Close после durable save | Закрытие, черновик остаётся, в том числе его сохранённая открытая форма |
| ForceDiscard | Отдельная подтверждённая команда: закрыть с потерей только unsaved state; последний durable draft остаётся |
| ForceDiscard во время commit | Отказ до завершения IO: нельзя скрыть ещё неизвестный исход записи |
| DeleteDraft | Только доказанная версия, отдельный DELETE_DRAFT guard; опубликованная доска не удаляется |
| Background/Revoke | Синхронная инвалидизация поколения, отмена зарегистрированного media job и stop собственного audio owner |
| Возврат | Повторная авторизация и явный Resume; запись/выбор файла/проигрывание не возобновляются |

Отказ gate у уже открытого owner также переводит сессию в PAUSED и навсегда
инвалидирует ранее выданные permits. Последующее открытие gate само по себе не
делает старый autosave допустимым: нужен Resume и новый permit.

Если Resume выполнен до завершения старого save, поздний UNAUTHORIZED старого
поколения не отменяет новую авторизацию: latest pending snapshot сохраняется с
новым permit и последней доказанной expectedVersion. Известный отказ старой записи
не считается unknown commit. Уже запрошенный более новый pending save может
выполняться после известной ошибки предшественника; это не автоматический retry
того же snapshot. CONFLICT, смена epoch и UNCONFIRMED по-прежнему блокируют запись.

Actor не ожидает SQL, provider picker, native decoding, запись или синтез.
Blocking source/sink/file operations выполняются на `Dispatchers.IO`, не UI Main.
Снимки списков копируются перед передачей worker. Каждый in-flight/pending save
имеет независимую lease; замена pending закрывает только его собственную lease.
Composite lease переиспользует существующий internal `MessageLease`, без второй
реализации pin/retain алгоритма и без изменения communication.

## Редактирование документа и форм

- Название, описание, default COMPOSE/DIRECT, input CARDS/LITERAL.
- Добавление и перестановка страниц; удаление страницы переносит все её явные
  ячейки в свободные позиции оставшихся страниц, затем на дополнительные страницы.
  Последняя страница не удаляется. Удаление самого page object явно удаляет его
  page-level metadata, но не cell/card metadata перемещённых ячеек.
- Resize сохраняет позиции, помещающиеся в новую capacity, и переносит overflow
  на следующие страницы с новой сеткой. Ни CONTENT, ни SPACE, ни explicit EMPTY
  с metadata не обрезаются. Превышение 256 страниц отклоняет операцию целиком.
- Uniform означает одна ячейка на позицию: opaque extensions не читаются как spans.
- MoveCell обменивает две позиции, в том числе между страницами. CardId и opaque
  card/cell fields остаются у перемещаемого содержимого.
- Grid form хранит исходный введённый текст даже при ошибке числа; autosave не
  пытается преждевременно применить невалидную сетку.
- Card form создаётся из настоящего source card: отдельные label и speechText,
  TTS/RECORDING/NONE, useLabelForSpeech, recording metadata, images и extensions.
  Набранный текст не подменяется новым source snapshot во время autosave.
- Существующая EditorForm.Card не имеет languageTag. Новый скрытый сериализуемый
  формат/перегрузка audioVoice не добавлялись: язык исходного SpeechSource.Tts
  сохраняется при редактировании и показывается в UI, новый TTS использует
  установленный локальный голос. Произвольный новый persisted language selector
  потребует согласованного расширения domain form у COORD.
- SetCell EMPTY/SPACE — явное удаление карточки из данной позиции, с сохранением
  cell-level extensions; CancelCard отбрасывает только изменения открытой формы.

## Медиа и отмена

Image picker → owned TemporaryFile → actual inspection → prepareImage → actual
inspection результата → MediaStore.prepare(consumes source) → owned PreparedMedia
lease → form → autosave SQL refs. ImageFromText — отдельная явная медиаоперация,
не способ рисовать UI labels.

Audio picker / record / synthesis → owned TemporaryFile → actual audio inspection
→ prepare AUDIO → owned lease. Synthesis сохраняет отдельный owned output, не
перезаписывает canonical media; его текст сохраняется как recording metadata.
Record и FinishRecording используют существующий AudioPort. Preview передаёт
свою lease, owner с AudioPurpose.PREVIEW и отдельный RequestId существующему player.

Native handles не создаются authoring-кодом через непроверенный file factory:
получаются только как owned результат соответствующего порта. Поэтому authoring
не «присваивает» и не удаляет nonempty/foreign factory paths. Поведение фабрик и
реальных provider/native результатов остаётся в P06/P07 contracts и тестах.

Все свои picker/prepared/synthesized temporary handles освобождаются в finally,
включая ошибки inspection/source/prepare. Immutable assets не удаляются вручную.
Leases формы живут до появления SQL refs/замены формы; in-flight save имеет свои pins.
Generation + lifecycle + сравнение исходной формы + свежая gate-проверка на callback
не позволяют старому результату переписать изменённую форму. Передача late result
после отмены закрывает его lease, не затрагивая новый результат.

Media job регистрируется атомарно доступным handle **до** старта worker.
Background/Revoke отменяют его синхронно; worker дополнительно проверяет исходное
resumed generation. Это закрывает гонку «stop до регистрации нового record/synthesis».
Preview проверяет generation до передачи player и повторно останавливает request,
если invalidation произошла во время передачи. Поздний audio callback не обновляет UI.

Budgets: grid 1..64 × 1..64, pages ≤256, content cells ≤100000, native text
≤65536 UTF-16 units, image side ≤8192 / area ≤16 million pixels, audio ≤600000 ms,
prepared asset ≤64 MiB. Actual bytes/type и native decode allocation budgets проверяет
существующий inspector; hint/расширение не используются как доказательство типа.
Overflow — LIMIT_EXCEEDED, не усечение.

## UI и accessibility

Используются существующие LinkaTheme/palette, CommunicationCard и
LabeledActionButton. Ввод — стандартные Material2 OutlinedTextField с явными labels
и непрозрачными palette colors для borders/text/disabled. Save/Apply/Close закреплены
отдельно от прокручиваемой рабочей области. Параметры отделены от основной сетки;
large grid не уменьшает hit targets: ленивые строки и управляемая горизонтальная
прокрутка. Ошибки и save-status имеют live-region semantics. IME и safe drawing
insets учтены. Для обмена/перестановки есть подписанные кнопки вместо drag-only жестов.
Изображения Fit, подписи — обычный переносимый текст, не bitmap.

Это не physical accessibility acceptance: TalkBack/VoiceOver, focus traversal,
большие системные шрифты и реальные native screenshots требуют P15 проверки.

## Проверки и границы доказательства

Focused common tests запускаются через JVM, используют **реальный StorageRuntime,
BundledSQLiteDriver, file-backed SQLite и MediaStore** в отдельных owned temporary
roots. Native audio/files/images/inspection/gate — явно обозначенные fakes.

AuthoringSessionTest проверяет create/edit/resume, label/speech, coalescing и latest
durability, deferred Apply, IO/close/retry, lifecycle guards/re-auth, unknown save и
publish, source change/deletion conflict, grid form/overflow/limits/EMPTY metadata,
cross-page swaps, source IDs/language/extensions, media leases/GC, late cancellation,
record/finish/synthesis/preview, explicit discard/delete и media budgets.
AuthoringPresentationTest проверяет разрешённые editable phases, distinct durability
labels и actionable conflict/limit/unknown diagnostics.

Команда итогового focused gate (JDK17 только для конкретного процесса, SDK env
унаследован без подмены старым alias):

Первичный gate до correction, 2026-11-05: **27/27 PASS** (24 AuthoringSessionTest + 3 presentation),
0 failures/errors/skipped, реальный SQLite/media runtime; обе Native compilation
**PASS**, Android shared debug Kotlin compilation **PASS**. Последний combined
gate: BUILD SUCCESSFUL, 14 s. Только существующие expect/actual beta warnings,
новых compile warnings в authoring/UI нет. `git diff --check` — PASS.

```sh
env JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew \
  :shared:jvmTest \
  --tests 'su.linka.pictures.application.authoring.AuthoringSessionTest' \
  --tests 'su.linka.pictures.ui.editor.AuthoringPresentationTest' \
  :shared:compileKotlinIosArm64 \
  :shared:compileKotlinIosSimulatorArm64 \
  :shared:compileDebugKotlinAndroid --console=plain
```

Native dependency cache взят из существующих generated outputs основного checkout:
до и после relocation в worktree выполнен официальный `ios_audio_decoder_outputs.inspect`
с проверкой текущих source hashes, library/header inventories, ARM64 platforms/minimums,
signature provenance и Xcode toolchain. Изменены только generated absolute `.def` paths
и manifest root/inventory; архивы и headers не менялись. Gradle producer подтвердил
VERIFIED_FASTPATH. FFmpeg, simulator proofs и прежние native test suites не повторялись.

Root wiring, navigation, native lifecycle/access binding, реальные pickers/mic/TTS,
provider permissions, Keychain/AMFI, frameworks/app packaging и устройство не проверялись
этой lane и не объявляются готовым Android+iOS продуктом. COORD должен подключить
экраны/owner scope, lifecycle Background/Revoke и явный Resume с новой capability,
а затем выполнить общий integration/device gate. После process death восстанавливается
только последний SQL StoredDraft; recording jobs/capabilities/temporary handles не
сериализуются и не возобновляются.

При review обнаружена и воспроизведена гонка Resume/старого UNAUTHORIZED:
`resumedLatestSnapshotSurvivesLateUnauthorizedCompletionFromOldGeneration` падал
до исправления (focused JVM: 1 failed, TimeoutCancellationException — новый save
не стартовал). Исправление сохраняет generation у save и не применяет устаревший
отказ авторизации к уже возобновлённой сессии. Отдельный regression фиксирует
background между media authorization и регистрацией worker: AudioPort.record
вообще не вызывается.

## Correction после COORD review (`e22738a`, без amend)

### Подтверждённые ownership/cleanup gaps

- Mailbox теперь хранит typed owned event, а не голый callback. Каждый load/media
  result передаёт свою lease event-у; успешный load явно `adopt`-ит её, media callback
  либо retain-ит ссылки в форму, либо передаёт lease ephemeral pending-adoption слоту.
  `onUndeliveredElement`, actor `finally`/completion и `Channel.cancel()` освобождают
  queued result при owner cancellation. Неудачный late enqueue закрывает собственный
  event сразу; worker finally остаётся безопасен благодаря одноразовому close.
- `AuthoringLease` изолирует каждую retained delegate lease: один бросающий `close`
  не обрывает cleanup остальных детей существующего composite MessageLease. Все
  current/pending/active-save/result leases имеют явного владельца. Cleanup закрывает
  каждый собственный handle один раз; не угадывает физический исход OS close и не
  повторяет произвольный delegate close после исключения.
- Close/stop/discard cleanup best-effort, как у AudioWork: все независимые попытки
  выполняются; первичный Completed/Failed/Cancelled/UnconfirmedCommit не заменяется.
  `save` completion публикует `saved` через finally даже при cleanup failure.
  Дополнительный `AuthoringState.cleanupProblem = IO_FAILURE` виден в UI без exception
  text/paths и без уничтожения основного `problem`/durability phase.
- Сами IO effects перенесены на Dispatchers.IO. Actor и публичные command/constructor
  seams не менялись, unlimited mailbox capacity не переопределялась.

Регрессии используют публичную AuthoringSession, управляемый CoroutineDispatcher,
настоящие file-backed SQLite/MediaStore и контролируемые lease probes. Actor continuation
блокируется, некооперативный port завершается, его worker реально заканчивает работу,
оставляя owned result в mailbox; затем отменяется owner scope. Проверяются нулевые
живые leases, один delegate close и освобождение media для настоящего GC. Private
session helper не вызывается. Отдельно проверены успешное adoption без double-close,
throwing save cleanup с продолжением latest save, финальный cleanup current/pending/
active pins, бросающие stop/discard, сохранение primary unknown commit и >1024 intents.

### Security-семантика, явно принятая COORD

Вход в собственный system picker/microphone permission flow отзывает старые core
capabilities/commit guards, **но не является AuthoringAction.Background**. Root
по-прежнему владеет этой классификацией. Authoring не сохраняет capability живой
ради provider/permission return и не ослабляет старый guard.

Если Completed prepared media возвращается для той же формы/generation без real
Background/Close, но старый guard даёт UNAUTHORIZED, session:

1. Переходит в PAUSED + UNAUTHORIZED; удерживает единственную owned prepared lease
   в приватном ephemeral pending-adoption слоте. Ни form result, ни SQL asset refs
   в этот момент не публикуются. Слот не находится в StateFlow/DTO/SQL/saved state.
2. Только явный Resume с новой, действительно принятой gate capability вправе
   проверить epoch, исходную форму и generation, adopt-ить готовый asset и запустить
   save с **новым** guard. Открытие gate само по себе ничего не replay-ит. Старый
   unauthorized Resume не уничтожает результат, ожидающий действительного входа.
3. Resume не повторяет picker/record/synthesis и не возобновляет запись. Для audio
   удерживается только уже готовый prepared asset, не microphone/request handle.
4. Real Background/Revoke, Close/discard, отмена/смена формы, смена epoch, conflict,
   unknown outcome или owner-scope cancellation освобождают именно own pending lease.
   Source/canonical media не удаляется. Поколение real Background инвалидируется
   уже в send; queued Resume не может принять более ранний pending result.

Перевод в media-reauth PAUSED использует compare-and-set исходного lifecycle token:
одновременный real Background не превращается в новое разрешённое поколение для
pending result. Failed/Cancelled не создают pending adoption и не ждут asset reauth.

Чистые регрессии доказывают no form/no SQL refs до Resume, GC pin до входа и release
после Background/Close/scope cancellation, однократное adoption новой capability,
отказ при другом epoch, победу Background над уже queued Resume и отсутствие второго
record вызова после permission-style gate invalidation.

Это **COORD security decision + core behavior proof**, а не доказательство настоящих
native foreground/provider/permission transitions. Root/lifecycle implementation и
runtime OS proof остаются у primary.

Correction gate 2026-11-05: **43/43 PASS** — 40 AuthoringSessionTest и прежние
3 presentation, все прежние 27 сохранены, добавлены 16 regressions; 0 failures,
errors и skipped. Реальный SQLite/MediaStore, lease probes и fake native boundaries.
Обе Native compilation (`iosArm64`, `iosSimulatorArm64`) и Android shared debug
Kotlin compilation **PASS** после common-коррекции; combined command выше завершился
BUILD SUCCESSFUL за 29 s. Decoder producer — VERIFIED_FASTPATH, без повторного OS
proof/симулятора. `git diff --check`/`git diff --cached --check` — PASS.
