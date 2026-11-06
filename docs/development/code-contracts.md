# P02: контракты ядра и handoff

Статус: **G2 зафиксирован 5 ноября 2026 года**, не готовая библиотека. Модельные/Unicode контракты проходят JVM и Native iOS17 (14/14), Android и оба frameworks/unsigned iOS shells собираются; [evidence](verification.md#p02-contract-freeze--5-november-2026). Условные P02–P14 разрешены пользователем; G0/G1, ARMv7, codec/physical и выпускные проверки не становятся PASS. Авторизация и миграция остаются у COORD; реальные данные до G4 не подключаются.

## Источник истины и границы

- **UI/навигация/темы:** [UI-паритет 1.4](ui-1.4-parity.md) обязателен для P10/P11/P13/P14. Прежние house tokens и скомпилированные screens не visual approval. Сохраняются session/application/guard seams; меняется представление, не storage/authorization policy. Реализация на паузе по просьбе пользователя; следующий gate — одинаковые заполненные доски рядом с 1.4 и визуальное подтверждение, не новая backend-волна.
- Поведение: K1–K9 в согласованном `linka-pokazhi-contracts.md`; исправленные `docs/c4/`.
- Точные типы: `shared/src/commonMain/kotlin/su/linka/pictures/domain/`, `application/ports/`, `application/communication/`, `application/portability/`.
- `OperationResult.Completed`, `Cancelled`, `Failed(Problem)`, `UnconfirmedCommit` различаются. Последний разрешён только при недоказанном durable исходе storage mutation; не означает успех/ошибку/откат. Coroutine cancellation не превращается в IO_FAILURE; cleanup выполняется и исключение отмены распространяется до точки COMMIT. Команда отмены пользователем до commit возвращает Cancelled. Не передавать тексты сообщения, пароль, путь или исключение в Problem/logs.
- Wire JSON `.linka` и backup не сериализует внутренний BoardDocument напрямую. `extensionsJson` — пассивный JSON object, проверяемый codec до публикации; он не может переопределить известные поля, включить активный mode или содержать локальные URI.
- Domain не импортирует платформу, Compose, filesystem или JSON DOM. Сериализация моделей — внутренняя schema, а не совместимость с Electron.
- `LocalFile` — sandbox handle, не постоянные данные. Только `TemporaryFile` разрешён для `discard`; immutable media нельзя удалить cleanup picker-а.

## Handoff P08 и native external files

### История: возобновление полного Build, 5 ноября (до UI STOP)

Пользователь поручил выполнить release coding plan до готовых Android/iOS. COORD держит native access/lifecycle, migration и root bindings. Независимые P11/P12 application workflows можно реализовывать по существующим AssistantGate/LibraryStore/native ports на synthetic данных параллельно этим bindings: это разрешённый conditional coding P02–P14, не принятие P08/G4 и не обход проверки real adapters.

P11 frozen seam: `application/authoring/AuthoringSession` с зависимостями `(library: LibraryStore, media: MediaStore, audio: AudioPort, files: PlatformFilesPort, images: ImagePreparationPort, inspection: MediaInspectionPort, gate: AssistantGate, ids: IdSource, scope: CoroutineScope)`. Один session/owner, immutable StateFlow, сериализованные commands; команды содержат explicit owner/capability, новая загрузка отменяет прежние pending media. Create/open/resume, board metadata, pages/reorder/resize preserving overflow, card form и content/space/empty, image pick/text generation/record/synthesis/preview, saveCard/cancelForm, apply/delete/close — полный scope. Конкретные command/state declarations принадлежат P11 внутри его пакета; существующие ports/domain/SQL не меняются без COORD. Autosave один in-flight+latest pending, Saved только durable result; Apply проверяет epoch/draft version/base board revision, открытая form запрещает apply. Конфликт сохраняет draft. Revoke/background отменяет media/audio/новые mutations; уже admitted commit может завершиться. Неуспешно сохранённое state остаётся видимым до reauth/явного решения; process restart восстанавливает лишь durable draft, не активные request/capability. UNKNOWN COMMIT quarantines, без auto-apply/retry. Close с несохранённым draft — явный результат, не silent discard; force-discard только отдельная команда с названием. House UI использует этот session, не собственный reducer.

### U1.4: тип незавершённой карточки — принято COORD до реализации

Для radio CONTENT/SPACE/EMPTY как в1.4 добавить trailing `EditorForm.Card.kind:
CellKind = CONTENT` и `AuthoringAction.ChangeCardKind(kind)`. CONTENT default
не сериализуется даже при encodeDefaults=true (`EncodeDefault.NEVER`): новый binary
читает старые forms, обычная CONTENT form остаётся в прежнем формате. Старый strict
reader отвергает новую non-CONTENT form без перезаписи; downgrade экспериментальных
бинарников с такими forms не поддерживается. DB user_version/backup format/внешний
LINKA whitelist не меняются; неизвестное значение enum отклоняется целиком.

Writers: только actor AuthoringSession → существующий saveDraft writer; snapshot/
backup/restore переносят тот же serializable draft. `assetIds/assetRoles` и backup
validation удерживают image/recording открытой формы независимо от kind — hidden
поля нужны для отмены/возврата к CONTENT. Новый writer/SQL/lock не вводится.

| Команда / состояние | Переход и сохранность |
|---|---|
| OpenCard | source kind, если ячейка существует; отсутствующая позиция → CONTENT. SetCell(CONTENT) явно открывает CONTENT form, не две публичные команды |
| ChangeCardKind в EDITING/CONFLICT | Отменяет свои media/preview/pending adoption, сохраняет kind в autosaved form. Документ и опубликованная доска не меняются; label/media/opaque extensions формы остаются |
| SPACE/EMPTY form | Новые media/synthesis/record/preview не запускаются; возврат к CONTENT сохраняет введённое. Background/revoke/late results действуют по прежней generation/lease policy |
| SaveCard | Одна actor-команда меняет ячейку и закрывает form в одном draft snapshot. CONTENT требует прежней speech/media validation; SPACE/EMPTY создают card=null без требования скрытой записи. Cell extensions сохраняются |
| CancelCard / crash / ResumeDraft | Cancel не меняет исходную ячейку; crash восстанавливает последний durable kind/form. Никакого CancelCard→SetCell как обхода атомарности |
| Apply / конфликт / unknown commit | Прежние epoch/version/guard и quarantine; kind не даёт обхода, cancel/failed/completed не объединяются |

Нужны serialization old/new/unknown-kind tests, actual SQLite autosave/reopen/resume,
Cancel/Save всех трёх видов, late-media cleanup и сохранённые metadata/опубликованная
доска/message snapshot. Native compatibility выполняется после coherent интеграции.
`withoutSpace` уже выражен `BoardBehavior.inputStyle` и codec mapping LITERAL/CARDS;
второе persisted поле ради визуального checkbox не добавляется.

### U1.4: native Back — принято COORD до binding

LinkaApp может зарегистрировать принадлежащий текущей композиции эфемерный handler
через trailing optional `onBackHandlerChanged: (((() -> Unit)?) -> Unit)? = null`.
Это presentation seam, не auth authority; Android development root обязательно
связывает его с OnBackPressedDispatcher, iOS без hardware Back не подставляет fake.
Dispose/закрытие окна отнимает handler до отмены scope. Новый handler не сохраняется
в SQL/saved state; один владелец — текущее окно, вызов на Main.

| Контекст | Back |
|---|---|
| Старт/переход/cleanup | Поглотить повтор, не закрывать Activity посреди передачи owner |
| Библиотека | Handler null: штатный выход из Activity, root останавливает звук |
| Общение | Вернуться к библиотеке, сохраняя фразу/playback по navigation contract |
| Редактор | Та же Close/подтверждение выхода, что toolbar; PAUSED/несохранённая работа не обходятся force-discard |
| Настройки/access/transfer | Вернуться по существующему пути с отзывом owner/cleanup; не обещать rollback внешнего partial |
| Модальная форма/диалог | Штатный dismiss/CancelCard/CancelGrid/закрытие конкретного диалога, не уничтожение всего draft |

Повторная регистрация/старый callback после dispose не действует на новое окно.
Для Android нужен actual key-event сценарий board→library без очистки сообщения;
pure mapping tests не заменяют проверку OnBackPressedDispatcher/модального приоритета.

P12 frozen seam: `application/portability/TransferCoordinator(library: LibraryStore, messages: MessageStore, linka: LinkaCodec, backup: BackupCodec, files: PlatformFilesPort, gate: AssistantGate, ids: IdSource, scope: CoroutineScope)`. Owner-bound operation state; prepareImport/prepareRestore/prepareExport/prepareBackup возвращают preview состояния с opaque operationId, не resources/capability; confirm/cancel/close работают только для живой той же операции/owner. Для import confirm явно NEW либо REPLACE(boardId, expectedRevision); для restore captured library revision+message stamp+epoch, existing recovery=>CONFLICT, recovery swap/delete отдельные protected+confirmed операции. Один pending/running transfer на owner: новая конкурирующая операция CONFLICT, не queue. Prepare/staging/codec IO вне Main/DB writer. Picker/destination возвращение не authority: после foreground требуется новая явная confirm с актуальной capability и свежим guard; до неё external contents не выдаются. Source/snapshot/lease/temp/destination принадлежат operation до terminal cleanup; resources не сохраняются в StateFlow/saved state, late results закрываются. Pending preparation отменяется без resume после process death/background; manual retry создаёт новую операцию и авторизацию. Terminal COMPLETED/CANCELLED/FAILED/UNCONFIRMED не объединяются; counters не суммируют cancel как failed/success. Public ports/formats/SQL не изменять, checked ZIP engine остаётся один.

В обоих workflows guard синхронный без IO и создаётся в application, не только UI; acquire/release всех leases на terminal/replacement/late result, никакого удаления foreign/nonempty temp. Root wire/real OS/provider UI и physical acceptance — отдельные последующие gates. UI/тесты не называют controlled fake actual OS proof.

COORD policy: [access-policy.md](access-policy.md). `AssistantGate.check/guard(action, capability, ownerId)` требуют explicit owner, capability другого owner не переносится. `guard` захватывает owner context+credential generation и отзывается даже без lock. Секреты/новый verifier не входят в SQLite schema1 или restore. PBKDF2-HMAC-SHA1/1300000/salt16/output32, exact versioned UTF16-hex encoding и legacy-input-only trim; алгоритм встроенный Java/CommonCrypto, не собственная криптография. Core policy реализуется отдельно от native credential storage/реальной OS owner verification; G4 до них OPEN. Unknown credential commit — Unavailable/quarantine, не silent Unprotected.

`PlatformFilesPort.save(picker+write)` удалён до native binding: `chooseDestination(suggestedName, mediaType, ownerId): OperationResult<ExportDestination>`. Destination предоставляет ownerId, single-use `suspend write(file: LocalFile, guard: CommitGuard): OperationResult<Unit>` и idempotent `close()`. Выбор может создать пустой файл, но **не передаёт содержимое**. Workflow после picker на foreground показывает конкретную выдачу и получает свежую авторизацию; сам destination не capability. Последний guard перед внешним open/truncate — irreversible authority boundary; повторная проверка между bounded chunks. После неё cancel/IO/revoke могут оставить внешнюю частичную копию, rollback чужого provider не обещается, provider file cleanup-ом не удаляется. Handle/URI не persisted и не попадает в JSON, закрытый/чужой/stale owner не пишет. Native adapters ещё не реализованы, все существующие test fakes обновляются вместе с port.

Sync files thread contract: createTemporaryFile/source/sink и операции возвращённых Source/Sink выполняет worker caller (P12 workflow переносит codec work на IO, как уже требовалось ниже). Нативный adapter не отправляет sync IO на worker с блокирующим ожиданием на Main. Исключение — `discard`: AudioWork.resume-on-cancellation может вызвать его на любой thread, поэтому native implementation синхронно только отнимает logical ownership/делает handle недоступным, физическое удаление передаёт worker, принадлежащему process/root scope. Scope передаёт root, не GlobalScope; failure/process death может оставить private cache temp для последующего own cleanup, не повреждая library и не маскируя первичный результат. Fake может удалять сразу. Suspend pick/destination.write сами выполняют streaming IO внеMain, UIKit/Activity presentation — Main. При binding обязательно проверить каждого настоящего caller; сам port declaration не доказывает правильный dispatcher UI.

Destination FSM-lock защищает только state/guard/admission, **никогда provider metadata/open/read/write/flush**. Последний успешный guard/admission — точка авторизации уже in-flight syscall/chunk. Close после неё не может отменить допущенный syscall (возможна пустая/partial внешняя копия), но возвращается без ожидания IO и запрещает все последующие admissions. Worker проверяет closing/cancellation после каждого blocking unit, scope удерживается до закрытия streams. Новое write после close не допускается. Нельзя добиться линейности, удерживая lock на provider IO и тем самым блокируя Main/background revoke.

## Модели и режимы

ID не равны имени. AssetId — lowercase SHA256 фактических bytes; другие IDs генерирует `IdSource` (случайные UUID на платформах). Внешние numeric/повторяющиеся IDs хранятся пассивно; новые внутренние IDs выдаются один раз на import, не на каждый save.

Native text boundary: IDs, board/retained names и MediaAsset.mediaType должны быть well-formed UTF16 без NUL, иначе INVALID_DATA до native bind/публикации. [Probe и rationale](native-text-boundary.md): SQLite2.5.2 на owned iOS17 реально обрезал bound text на NUL и изменил lone surrogate. Это не SQL escaping: значения по-прежнему bound parameters. Codec JSON проверяет также **decoded** строки/keys (включая escaped lone surrogates); обычный Unicode не нормализуется/не trim-ится. Opaque JSON data с корректным `\u0000` сохраняется, глобального удаления NUL из пользовательских metadata нет. Speech с NUL/ill-formed UTF16 отказывается до первого segment/native synthesis, видимая фраза остаётся; тихое произнесение только префикса запрещено. Storage дополнительно проверяет actual serialized/bound Strings на последнем bind/write boundary, не полагается только на model constructors.

BoardPage: 1..64 rows/columns, sparse BoardCell.position в `[0, capacity)`, позиции уникальны. Отсутствующая позиция — пусто. CONTENT имеет CardDocument; SPACE/EMPTY не имеют его. CardId уникален в документе; PageId уникален. Внутренний документ имеет 1..256 страниц. Layout-нормализация переносит overflow на дополнительные страницы, не отбрасывает карточки; превышение предела — явный отказ всего набора.

Каноническая внутренняя сетка **uniform, одна клетка = одна позиция**, без spans из opaque JSON. R04 разрешает нормализацию: импортированные width/height валидируются и нормализуются к1; covered/overflow content не теряется и не скрывается. Экспорт описывает эту фактическую сетку с width=height=1, не реанимирует старый span из metadata после editor resize/move. UI/domain/editor не парсят extensionsJson ради геометрии. Никакого автоматического смыслового сортирования; сохраняется входная последовательность/пустоты в пределах разрешённой нормализации. Исходный внешний архив остаётся неизменным; остальные пассивные unknown metadata сохраняются.

SpeechSource — Recording(assetId, audioText/audioVoice), Tts(text, languageTag), None. Параметры редакторского синтеза не являются fallback записи. Snapshot выбранной карточки содержит происхождение и собственные label/image/speech, не перечитывает источник после edit/delete.

Один MessageDraft на процесс/библиотеку, а не на доску. Режим COMPOSE/DIRECT хранится вместе с фразой; текущие focus/audio/request/capability/undo не сохраняются.

| Контекст | Select / текст | Навигация | Speak / Backspace / Clear |
|---|---|---|---|
| COMPOSE idle | Добавить snapshot или дополнить последний literal run | Сохранить фразу | Speak снимок; backspace карточку целиком или одну графему; clear с одним undo |
| COMPOSE playing | Stop перед изменением фразы | Тот же playback snapshot продолжает звучать | Speak = Stop; изменение сначала Stop |
| DIRECT | Новый explicit выбор заменяет direct playback, фразу не меняет | Фраза остаётся | Не произносить/очищать общую фразу неявно |
| Редактор / фон / закрытие | Нет выбора как коммуникации | Stop при выходе из общения | Preview отдельный owner, поздний callback не захватывает звук |

SetMode останавливает речь и сохраняет фразу. OpenBoard не применяет defaultMode поверх уже существующего сеанса; defaultMode используется при первом выборе режима для новой пустой установки. InsertText действует только в COMPOSE; DIRECT выбирает буквальные клетки как отдельный direct-запрос. SPACE добавляет явный пробел, EMPTY ничего не делает. Литеральная последовательность не разрывается при смене страницы/доски. Повтор CommandId игнорируется в ограниченном окне сеанса; новый ID допускает осознанный повтор содержимого.

Автоматический разделитель применяется только на границе смысловых фрагментов: «Хочу» + «мёд» → «Хочу мёд»; ведущие пробелы и пунктуация literal не получают дополнительный пробел; внутри literal ничего не схлопывается. `text/GraphemeBoundaries.kt` закрепляет extended graphemes Unicode16, а не UTF16/code points.

## Handoff P03: CommunicationSession

D реализует `application/communication/CommunicationSession.kt` с конструктором `(library: LibraryStore, messages: MessageStore, media: MediaStore, audio: AudioPort, ids: IdSource, scope: CoroutineScope)`, `val state: StateFlow<CommunicationState>`, `fun dispatch(command: CommunicationCommand)`, `fun interrupt()`, `fun close()`.

Команды и state уже объявлены COORD; сигнатуры изменяет только COORD. Session сериализует reducer в одном coroutine actor. Restore сообщения не вызывает речь. Select читает уже открытый leased board; диск/платформенный callback не задерживает немедленное изменение отображаемой фразы. OpenBoard имеет request generation; поздний ответ не меняет новую навигацию. Версии saves возрастают, старый save не откатывает новое state. Save effects ограничены одним in-flight и одним latest pending snapshot: замена pending освобождает его lease, version gaps допускаются, последняя видимая фраза не теряется. Ошибка save остаётся видна и удерживает media текущего состояния. Close/interrupt останавливают звук, close освобождает leases после завершения собственных pending saves, не удаляет сообщение. UnconfirmedCommit даже старой версии или listing quarantine замораживает весь сеанс без отправки pending snapshot; explicit reopen не проигрывает команды заново.

Локальные чистые функции D: `renderMessage(MessageDraft): String`, `speechPlan(MessageDraft): OperationResult<SpeechPlan>`. Plan immutable, последовательный, без recording+TTS дубля одной карточки. None не превращается сам в TTS: если смысловой фрагмент не может быть произнесён, возвращается SPEECH_UNAVAILABLE, а не успешная частичная фраза. Копия фразы не зависит от lifecycle источника. Undo удерживает собственную lease; один clear-context, освобождение на следующем clear/close, не persisted. CommandId dedup ограничен последними 1024 командами; это защита повторной доставки в текущем сеансе, не вечный журнал. Сообщение ограничено 65536 UTF16 units отображаемого текста/4096 parts; превышение даёт LIMIT_EXCEEDED без изменения/обрезки прежнего сообщения.

## Ownership файлов и аудио

MediaStore.prepare потребляет и закрывает Source на всех путях, вычисляет hash по потоку, проверяет лимит и сохраняет immutable bytes. Completed передаёт caller-у PreparedMedia.lease; metadata без lease не гарантирует наличие файла. `acquire` — all-or-nothing; `retain` создаёт независимый ref, а не тот же close-handle. `close` lease идемпотентен; file/source разрешены только пока id входит в открытую lease. Source закрывает получатель.

Storage per-asset byte budget: IMAGE/AUDIO <=64MiB, ARCHIVE <=2GiB (Long, потоково, без decoder/unzip). ARCHIVE — также raw retained-unreadable source, не вложенное активное `.linka`; его нельзя ограничить media64MiB и потерять старый набор. Import `.linka` всё равно ограничен500MiB, backup целиком2GiB включая overhead; превышение export budget — явный LIMIT_EXCEEDED без урезания и без удаления исходника. Источники legacy сверх этих resource limits сохраняются private migration recovery до решения, не исчезают под видом пустой доски.

Доска/draft read возвращает document+lease атомарно относительно GC. PreparedBoard владеет своей lease; публикация не потребляет её, caller закрывает после завершения/отказа. Успешный commit создаёт постоянные refs до освобождения staging leases. Failed/cancelled не публикуют частичный document.

AudioPort.play **забирает** PlaybackRequest.lease, в том числе при отказе/замене/пустом плане; освобождает по терминальному исходу. Caller передаёт retain, не единственную message lease. Preflight проверяет все media и локальные voices до первого звука. Shared AudioCoordinator владеет очередью и NativeAudioBackend; платформы не создают вторую очередь. Native методы suspend до терминального callback и не запускают звук после cancellation. Один owner/request на процесс, новый запрос сначала останавливает старый. Фон/interrupt не включает autoreplay. Synthesize/record Completed передаёт caller-у TemporaryFile; на ошибке незавершённый файл удаляет backend. Ready означает фактический валидный готовый файл, не разрешение микрофона.

Точная shared реализация для P06/P07: `audio/AudioCoordinator.kt`, `class AudioCoordinator internal constructor(backend: NativeAudioBackend, media: MediaStore, files: PlatformFilesPort, scope: CoroutineScope) : AudioPort`. Внутренний backend не экспортируется shell-ам; экземпляр создаётся shared platform binding позднее. Coordinator preflight-ит весь plan, разрезает длинный TTS по графемным границам не выше подтверждённого voices.maxTextLength (одна графема больше лимита → LIMIT_EXCEEDED до речи), записей не дублирует. Recording-only plan не требует TTS voice; local-only выбор голоса/языка окончательно подтверждает native backend, пользовательский preferredVoiceId не заменяется молча другим голосом. Empty/whitespace-only text segment не запускает TTS.

Все play/record/synthesize проходят одного owner/request арбитра; новый explicit запрос отменяет прежний, stop(owner,id) не касается чужого/нового запроса. Native callbacks и file results старой generation игнорируются, поздние owned temp удаляются. Interrupt помечает AudioPhase.INTERRUPTED и Failed(INTERRUPTED), явный stop — CANCELLED/Cancelled; синтез/запись после отмены не становятся Ready и не возобновляются. Готовый результат record/synthesize проверяется inspectClip перед Ready/Completed; caller получает только прошедший проверку TemporaryFile. Ошибка/отмена уничтожает только незавершённые собственные temp, не LocalFile immutable media. Shared tests с backend fakes не считаются доказательством OS audio session/codec/permissions; native gates P06/P07 сохраняются.

SynthesisRequest длиннее подтверждённого voices.maxTextLength отклоняется LIMIT_EXCEEDED до native synthesize: frozen backend не объединяет несколько файлов, текст не обрезается. Exceptions stop/finishRecording/lease.close/discard не должны убивать actor, пропускать wakeup/отмену оставшихся jobs или оставлять caller навечно ожидающим. Cleanup best-effort завершается для всех собственных handles, сохраняет primary Cancelled/UnconfirmedCommit/Failed и показывает IO_FAILURE без raw exception/path. Физическое удаление при отказе OS не гарантируется; файл, уже переданный caller-у, cleanup координатора не удаляет.

Android audio prerequisite P06: `platform/audio/AndroidAudioBackend.kt`, internal constructor `(context: android.content.Context, files: PlatformFilesPort, inspection: MediaInspectionPort, requestMicrophonePermission: suspend (RequestId) -> OperationResult<Unit>, scope: CoroutineScope) : NativeAudioBackend`; `platform/capabilities/AndroidMediaInspectionPort.kt : MediaInspectionPort` — internal class. Нет Activity в common. Requester callback связывает будущий shell с native permission request, не выдаёт разрешение сам и не используется для AssistantAccess. Context — applicationContext; один TTS engine/AudioManager/active native request, callbacks привязаны к request generation. Android native AudioAttributes — media/speech, stream music; не менять глобальную громкость/настройки/доступность. Focus loss/noisy/lifecycle interruption не вызывает autoreplay. Engine voices только local, preferred ID/язык проверяются фактически; отсутствующий/сетевой voice даёт отказ, не server fallback.

Запись нового media — streaming PCM16 mono16kHz/WAV через AudioRecord после фактического permission callback, с явным finish/stop и finalized header перед Ready; без частичного/пустого файла. SynthesizeToFile отдаёт собственный temporary только после terminal callback и inspect. Нативный inspector использует реальные bytes/container + MediaExtractor/MediaCodec для bounded PCM decode (WAV/MP3/Vorbis/AMR-NB/3GPP), не только metadata/MediaPlayer.prepare; images — bounds до decode и ограниченная настоящая decode validation. Source <=64MiB, duration<=10min, dimensions<=8192/16MP, PCM читается/discard потоково без whole-clip allocation. Unsupported/error/timeout/cancel явны, исходные media не изменяются/не перекодируются на месте. Физические codecs/focus/route/mic/permission/ARMv7 gates остаются OPEN до device proof.

Shared Android instrumented tests получают уже используемые в проекте JUnit4.13.2/androidx.test:core1.6.1/runner1.6.2/coroutines-test1.10.2, без новых production dependencies. Они компилируются в отдельный library test APK, **не** normal `su.linka.pictures`; запуск/установка и actual native результаты принадлежат COORD на проверенной owned AVD. Synthetic permission callback не считается реальным разрешением микрофона. Binding Activity callbacks/manifest/startup и native files/image preparation — последующие COORD-owned handoffs, не скрытые fake bindings в normal app. Compile-only результат без device run не становится native acceptance; отсутствие local voice/permission/ABI остаётся явным OPEN.

До запуска instrumented APK test-only manifest исключает унаследованный FirebaseInitProvider и явно отключает analytics; это не перенос production privacy cleanup из P14. Никаких mic grants/RECORD_AUDIO ради тестов. Self-test target34 не подменяет foreground/focus доказательство normal app target35. Непустой неожиданный temp на входе native writer даёт CONFLICT без truncation/**без удаления его прежних bytes**; cleanup ownership регистрируется после проверки. Float PCM decoder output проверяется на finite values потоково перед принятием кадров, silence допустима.

## SQLite schema v1 (только новая библиотека)

S реализует `storage/StorageRuntime.kt`: `companion object { suspend fun open(root: okio.Path, fileSystem: okio.FileSystem, driver: androidx.sqlite.SQLiteDriver): OperationResult<StorageRuntime> }`, свойства `library: LibraryStore`, `messages: MessageStore`, `media: MediaStore`, `suspend fun close()`. Root передаёт shell, он не выводится из пользовательского JSON. Для tests инъецируются реальные временные директории/контролируемые FileSystem и SQLiteDriver с fault injection; новая production-конфигурация/глобальный singleton в common не вводятся. Close завершает принятые операции и закрывает собственные DB handles, не удаляет библиотеку; после него новые операции отклоняются. Shell держит один runtime на процесс; точные sandbox roots Android/iOS остаются COORD startup binding P14.

Blocking SQL/FS/hash/copy работы suspend storage methods и snapshot flows выполняются внутри storage на Dispatchers.IO (или эквивалентном owned worker), не на caller Main. Coroutine actor общения может жить на Main: один launch сам по себе не превращает синхронный SQLite/Okio вызов в background IO. Caller не должен знать внутренний dispatcher и оборачивать каждый save. Синхронные lease/file-handle методы не копируют/не декодируют/не хэшируют содержимое. P12 workflows аналогично запускают codec streaming/JSON work вне UI dispatcher.

Реализация P04: bundled SQLite2.5.2, Okio filesystem. `PRAGMA user_version=1`, `foreign_keys=ON`, `journal_mode=WAL`, `synchronous=FULL`. Все значения bound parameters; нет dynamic SQL из внешних имён. До создания схемы читается user_version: 0 в новой/пустой DB — создать новую, 1 — проверить обязательные таблицы и открыть, больше 1 — NEWER_SCHEMA без записи/delete/fallback. Непустая неизвестная DB с version0 или неполная schema1 — INVALID_DATA, не пересоздание. Проверять версию до смены journal mode. Не менять legacy 1.4 файлы.

### Initial schema probe: исходные файлы не открываются SQLite

P04 доказал, что generic SQLiteDriver.open(existingPath) при close может checkpoint-нуть WAL даже после отказа NEWER_SCHEMA. Bundled2.5.2 имеет публичный flags overload, но frozen generic SQLiteDriver его не передаёт; PRAGMA query_only не делает исходное открытие физически read-only. Immutable URI также не используется для live source: он отключает locking/change detection и не является универсальной crash-recovery гарантией.

Принятый COORD протокол без изменения factory API:

1. На первоначальном open ещё нет writer-а: один StorageRuntime на sandbox/process, никаких других app processes/extensions/writers на этот root. Shell обеспечивает это владение; пользовательская SQLite path не принимается. Это явная предпосылка, не обещание safe concurrent backup чужой живой DB.
2. Если DB существует, скопировать её и присутствующие `-wal`/`-journal` побайтово в свежий уникальный **owned probe directory**, mustCreate без overwrite. `-shm` не копировать/не изменять: SQLite восстановит индекс только у копии. Владелец scratch — этот open, не исходная библиотека; можно использовать новый уникальный child под runtime root, не переиспользовать неизвестный каталог. До принятия schema не запускать source GC/staging cleanup.
3. Копировать потоково, проверять размеры/ошибки и стабильность source file set+bytes до/после копии (stream hashes), без загрузки всей DB в память. Изменившийся source/нехватка места/ошибка чтения/копии → отказ IO_FAILURE/CONFLICT, **не** fallback к source open. Никакой своей интерпретации SQLite header/WAL/journal.
4. Generic driver открывает **только probe-copy**; штатный SQLite восстанавливает её WAL/rollback journal, читает user_version и валидирует известную schema. Любые checkpoint/SHM/journal writes относятся только к копии. NEWER_SCHEMA/INVALID_DATA/corrupt source → закрыть probe и удалить только собственный scratch; исходные DB/WAL/SHM/journal/прочие файлы остаются byte-for-byte и не удаляются.
5. Только после признания schema0-empty/schema1 и повторного подтверждения стабильности source закрыть/очистить probe и открыть live writer обычным путём; повторно проверить user_version/known schema до прикладных mutations. Live crash recovery допустим только для уже принятой схемы. Смена источника между проверками при нарушенной single-writer предпосылке не маскируется как поддерживаемый сценарий.

Цена — streaming copy/hash **metadata DB**, не media library, на startup; shortcut допускается до измеренного startup bottleneck и отдельного flags-capable read-only binding с теми же invariant tests. Проверка generic-driver URI без URI flags не считается доказательством поддержки URI. Регрессии обязаны проверять unknown schema в WAL crash leftovers, отсутствие source checkpoint/SHM write, rollback-journal fixture, источник с `?`/`#`/Unicode path как обычный filename, copy failure и запрет любого driver.open на live source до принятия schema. Известный источник при неуспешной cleanup probe не превращается в destructive fallback.

Минимальная логическая схема (имена столбцов обязательны, вспомогательные индексы допускаются):

| Таблица | Столбцы / constraints |
|---|---|
| library_meta | singleton INTEGER PK CHECK=1, epoch INTEGER>=0, revision INTEGER>=0, settings_json TEXT NOT NULL |
| entries | id TEXT PK, name TEXT, kind TEXT BOARD/RETAINED_ARCHIVE, revision INTEGER>0, order_index INTEGER UNIQUE, payload_json TEXT NOT NULL |
| drafts | id TEXT PK, board_id TEXT NULL, base_revision INTEGER NULL, version INTEGER>0, payload_json TEXT NOT NULL |
| message | singleton INTEGER PK CHECK=1, epoch INTEGER>=0, version INTEGER>=0, payload_json TEXT NOT NULL |
| assets | id TEXT PK, kind TEXT, media_type TEXT, byte_count INTEGER>=0 |
| asset_refs | owner_kind TEXT, owner_id TEXT, asset_id TEXT REFERENCES assets(id), PK(owner_kind,owner_id,asset_id) |
| recovery | singleton INTEGER PK CHECK=1, id TEXT UNIQUE, created_at INTEGER, library_revision INTEGER, settings_json TEXT |
| recovery_entries | id TEXT PK, name TEXT, kind TEXT, revision INTEGER, order_index INTEGER UNIQUE, payload_json TEXT |
| recovery_drafts | id TEXT PK, board_id TEXT NULL, base_revision INTEGER NULL, version INTEGER, payload_json TEXT |

Payload entries = BoardDocument или RetainedArchive по kind; draft = EditorDraft; message = MessageDraft. JSON включает defaults и имеет явную внутреннюю schema v1, не unknown-field ignore для опасного изменения схемы. Draft board_id НЕ FK на entries: удаление исходной доски не удаляет работу редактора. Message provenance тоже не FK. Card counters считаются по CONTENT всех pages, у retained cardCount=null, не 0.

asset_refs owner_kind: BOARD, DRAFT, MESSAGE, RECOVERY_BOARD, RECOVERY_DRAFT, RETAINED, RECOVERY_RETAINED. owner_id — соответствующий внутренний id; у MESSAGE — singleton. Ref-набор всегда полностью заменяется той же транзакцией, что payload. Recovery не включает message/undo/секреты. Staging/playback/undo используют process leases, а не бессрочные SQL roots. Путь файла выводится из hash (`media/<first2>/<hash>`), никогда не берётся из JSON.

Asset refs проверяют и наличие, и **роль** metadata.kind: image в board/form/message → IMAGE, recording → AUDIO, retained archive → ARCHIVE. Один ID в конфликтующих ролях/несовпадающий kind отклоняется до публикации payload/refs, в том числе при builder.seal/restore. Storage не декодирует media: actual byte/decoder validation — native inspector, declared-kind coherence — обязательный storage invariant, а не надежда на UI.

## Писатели, порядок lock и atomicity

Единственный StorageRuntime на sandbox, на него ссылаются LibraryStore, MessageStore и MediaStore. Multi-window — те же writer/leases; separate Android process/extension не создаётся. Schema/open/миграции новых schema — storage writer, auth DB/legacy migration — отдельная COORD область.

1. Validate/копирование/хэширование/decoder/file picker/TTS — вне writer lock.
2. Immutable файл полностью записать/flush через FileHandle и закрыть в staging. Короткая финализация prepare под writer lock: атомарно переименовать в hash-path на той же FS и зарегистрировать metadata+lease **до** освобождения lock. Существующие bytes сверить и не перезаписывать; GC не может вклиниться между rename и lease. Ошибка до этого — нет публикации.
3. Serialized writer lock → SQLite BEGIN IMMEDIATE → проверить epoch/expected revision → вызвать permit.check прямо перед mutation → проверить media+refs → обновить payload/refs/счётчики → повторить guard перед COMMIT. Последний успешный guard — точка авторизации; последующий отзыв не отменяет уже разрешённый commit. До неё отзыв даёт ROLLBACK.
4. Только после commit обновить StateFlow и вернуть Completed. Callback guard синхронный, не берёт storage lock, не ждёт OS prompt и не вызывает storage. Нет обратного lock-order.
5. Cleanup после commit отдельно. Ошибка GC не отменяет успешный commit и не выдаётся за failed publication. До commit error/cancel → ROLLBACK, старые refs/file untouched. Ошибка результата после успешного commit не должна побуждать слепой повтор: перечитать revision/id. Недоказанный исход обрабатывается по следующему правилу, не угадывается по собственным незакоммиченным данным writer-а.

### Неопределённый COMMIT: обязательный fail-closed протокол

P04 воспроизвёл оба случая на bundled SQLite: writer видит собственную незакоммиченную revision после ошибок COMMIT+ROLLBACK; либо COMMIT выполнен, а readback недоступен. Поэтому frozen протокол уточнён COORD: чтение через тот же writer не доказывает durability, а недоступная проверка не даёт права объявить Failed/Cancelled или Completed.

- COMMIT вернулся успешно → Completed, даже если refresh/cleanup затем не удался. Результат mutation захвачен до commit; refresh не нужен для доказательства этого успеха.
- COMMIT/ROLLBACK бросил исключение → закрыть/больше не использовать сомнительное соединение. Одна bounded reconciliation через **новое независимое** соединение, после закрытия writer, может доказать exact old/new durable state по ожидаемым version/epoch/payload/refs; это не автоматический повтор mutation. Если доказан committed → Completed; доказан rollback → первоначальный Failed/Cancelled. У независимого observer нет незавершённой транзакции writer-а.
- Если writer не удалось закрыть, observer недоступен/неоднозначен либо error мог произойти после фактического commit, но подтвердить результат невозможно → **OperationResult.UnconfirmedCommit**, никогда не притворяться rollback. Никаких бесконечных retry или отключения этих fault tests.
- Runtime становится quarantined: новые mutations и GC запрещены, сомнительное соединение не переиспользуется; listing помечается `refreshProblem=COMMIT_OUTCOME_UNKNOWN`. Последний подтверждённый UI state не называется актуальным сохранённым результатом. Текущие in-memory leases остаются у владельцев; cleanup не удаляет потенциально опубликованные media.
- Сообщение остаётся видимым, `MessageSavePhase.UNCONFIRMED`, дальнейшие команды, save effects и undo приостанавливаются до явного восстановления. TransferState — UNCONFIRMED с COMMIT_OUTCOME_UNKNOWN; не COMPLETED/FAILED/CANCELLED. UI: «Результат сохранения не подтверждён. Проверьте библиотеку после повторного открытия». Никаких автоматических речи/повторов/очистки.
- Восстановление — explicit close/open StorageRuntime и новый CommunicationSession после устранения IO проблемы. SQLite crash recovery/read authoritative state определяет фактическую библиотеку; pending commands из памяти/диска не проигрываются повторно. Это не обещание exactly-once receipts, которых API не имеет. Unknown исход остаётся отдельным observable результатом, пока достоверный read невозможен.

Наличие fault injection после успешного underlying step не сужает гарантии теста: подтвердить Completed можно только наблюдаемым durable результатом. Additive declarations этого уточнения принадлежат COORD; D/S/P получают один и тот же контракт. `UnconfirmedCommit` не возвращается обычными pure/audio/picker read операциями и не используется вместо обработки известной IO ошибки.

GC под тем же writer lock выбирает только отсутствие persistent refs **и** process lease. Сначала удаляет только проверенный owned hash-file; затем metadata. Ошибка удаления оставляет metadata для повторного cleanup и считает failedAssets. Staging отдельна от media, startup удаляет только распознаваемые свои незавершённые temp-объекты; не обходит произвольные пути/чужие файлы. Media.assetIds closure обязателен для board, draft document+open form, message и recovery.

Process pins — per-asset refcounts под tiny standard ReentrantLock/NSLock, без map cloning всех живых leases на каждом retain/close. Lock order writerMutex→pinsLock, под pins lock нет SQL/FS/runtime callbacks. Stage ownership использует O(1) linked-stack attach и линейный close. Capture consistent metadata может O(metadata) ставить writers в очередь, но работает на IO и проверяет caller cancellation между documents/assets/writes; это явный shortcut, не обещание nonblocking reader pool.

Committed epoch/revision захватываются внутри транзакции до COMMIT. Если после подтверждённого commit listing refresh не прочитал items, StateFlow всё равно публикует **доказанные новые stamp** + cached items + IO_FAILURE. Старый epoch нельзя оставлять после успешного restore: это могло бы разрешить stale communication/direct playback. При неопределённом commit COMMIT_OUTCOME_UNKNOWN имеет приоритет; никакая postcommit-read ошибка не выдумывает новый stamp.

`expectedVersion=null` — создание draft только при отсутствии id; иначе CONFLICT. SaveDraft версия +1; PublishDraft проверяет draft version, baseRevision/target revision, отсутствие unfinished form; меняет board+refs и удаляет именно применённый draft одной транзакцией. Конфликт не теряет draft. AddBoard только новый ID. Replace/Rename/Delete проверяют revision; удаление board не удаляет snapshots или черновики.

## Epoch, message и restore

`WritePermit.epoch` проверяется для **каждого** library mutation. Любое полное переключение повышает epoch монотонно; board/draft revision может совпасть с прежней после restore, поэтому одной revision недостаточно. Stale epoch → STALE_REQUEST без guard/mutation. Внутри одного epoch library revision возрастает на каждый mutation (включая draft/settings), message version отдельно.

SaveMessage: epoch должен совпасть с meta/message; новая version строго больше сохранённой, gaps допустимы (coalescing). Совпавшие epoch/version/value — idempotent Completed; та же версия с другим payload или меньшая версия — CONFLICT. Обновление message и его refs одной транзакцией writer-а. Caller удерживает lease до завершения save. Старый pending save после restore получает STALE_REQUEST, не оживляет старую фразу.

SnapshotReader — собственный repeatable immutable staging-снимок metadata+lease, revision+epoch захвачены одним consistent read. Большие библиотеки читаются по одному документу/asset row, не через giant JSON. Settings/counts без message и секретов. Flow при чтении повреждённого/недоступного staging бросает SnapshotReadException(Problem); получатель превращает в Failed, не пропускает остаток. close идемпотентно освобождает staging/lease; повторное чтение после close запрещено.

SnapshotBuilder.add* принимает готовые immutable объекты, проверяет уникальные IDs/лимиты, pin-ит каждый addAsset до возврата Completed. seal проверяет точное равенство media manifest и transitive roots boards/drafts/retained (без message-exclusive), размеры/hash/существование assets и counts; возвращает reader с переданным владением staging. Failed seal не отдаёт полуснимок; close builder очищает незавершённое. Общего агрегата всех документов/медиа в памяти нет: runtime использует только reader/builder, а BackupManifest содержит только заголовок/настройки/счётчики. Тест заголовка не считается доказательством runtime closure — это обязательный P04/P12 contract test.

ReplaceLibrary требует expectedLibraryRevision, expectedMessage stamp и permit epoch; staged reader полностью валиден до writer. Если уже есть recovery — CONFLICT: пользователь сначала явно удаляет его либо возвращается, без тихого overwrite. В одной SQL-транзакции: сохранить предыдущие entries/drafts/settings+refs как recovery, установить staged library+refs, повысить epoch и library revision, очистить message с version=0 нового epoch и удалить старые message refs. Lock/пароль/voice device preference не трогать. Доказанная ошибка recovery/rollback → старая библиотека и фраза остаются. Cancellation до commit не меняет live; подтверждённый commit → Completed, неподтверждённый исход → UnconfirmedCommit/quarantine, не Cancelled/ложный Failed.

ReturnToRecovery с теми же checks атомарно меняет current/recovery местами и очищает message/повышает epoch. Старый current становится единственным новым recovery, бесконечной цепочки нет. DeleteRecovery — собственное protected+confirmed действие, убирает только recovery roots, GC отдельно. Переключение сеанса в новый epoch на UI — отдельный explicit reload без speech и без переноса undo.

Совместимость: SQLite v1 не открывается исходным Android1.4; безопасный binary downgrade не обещан. Legacy source/recovery не является актуальным rollback после новой работы. Будущие версии сохраняют monotonic epoch; unknown schema блокируется без destructive fallback. P04 не меняет startup/реальную библиотеку. Migration copy/validate/publish/marker и старый password — P09/COORD после safety review.

## Handoff P05: LinkaCodec и wire policy

P реализует `portability/linka/LinkaCodec.kt` с конструктором `(media: MediaStore, files: PlatformFilesPort, inspection: MediaInspectionPort, ids: IdSource)`, методами `suspend fun read(file: LocalFile, name: String): OperationResult<PreparedBoard>` и `suspend fun write(board: PublishedBoard, lease: MediaLease): OperationResult<TemporaryFile>`. Read не изменяет source/live library. Write не потребляет входную lease. Source/sink внутри закрывает codec; Completed отдаёт caller-у PreparedBoard/TemporaryFile. До выдачи результата images/audio прошли inspect реальных bytes; unsupported codec → UNSUPPORTED_MEDIA, не пропуск и не TTS вместо записи.

Whitelist reader: строки `1.0`, `1.2` legacy flat по доказанным bundled fixtures; `2.0` legacy flat — только после synthetic test с той же схемой и evidence peer test; `3.0` только pages. Другие версии отвергаются UNSUPPORTED_VERSION, не «все1.x». Legacy numeric-string dimensions принимаются лишь canonical decimal positive integer (проверенный producer), не coercion boolean/NaN/fraction. `quiz=true`, любой mode кроме standard на любой странице, active неизвестная схема → reject whole. Проверка raw JSON до нормализации.

Root fields: version/withoutSpace/directSet/description/pages (legacy rows/columns/cards). Page: id/mode/rows/columns/cards. Card: id/cardType/title/imagePath/audioPath/width/height/audioText/audioVoice. Типы0/1/2/3 = content/space/empty/placeholder; placeholder нормализуется в EMPTY без новых ID при каждом save. Known quiz fields в standard сохраняются пассивно, не активируют обучение. Остальные неизвестные пассивные JSON fields сохраняются на соответствующем root/page/cell/card, не исполняются; сомнительная активная функция отклоняется, не игнорируется.

**Уточнение, явно принято пользователем 5 ноября:** на непосредственном структурном уровне root/page/card имена `action`, `actions`, `command`, `commands`, `event`, `events`, `script`, `scripts`, `navigation` зарезервированы под неподдерживаемые функции. Само присутствие любого такого ключа, даже null/пустого, даёт UNSUPPORTED_FEATURE и отказ всего архива до media preparation. Сравнение точное case-sensitive, как у JSON schema; не искать подстроки/синонимы и не угадывать назначение неизвестного поля. Остальные дополнительные поля — непрозрачные пассивные данные, сохраняемые без исполнения. Вложенные значения opaque metadata не обходятся для поиска этих слов: `custom.actions` — данные, непосредственный `root.actions`/`page.actions`/`card.actions` — отказ. Version/mode/quiz по-прежнему проверяются отдельно; неизвестная обязательная версия или unsupported mode не превращаются в standard. Это конечное правило совместимости, не заявление, что произвольные JSON-данные автоматически классифицированы по намерениям автора.

withoutSpace=true → LITERAL title/space; normal card playback при audioPath → Recording, audioText/audioVoice только metadata. Без audioPath literal использует title как текст; в CARDS отсутствие аудио не подменяется авторским audioText (None; новый явный TTS хранится только после materialization при экспорте). Writer3.0 не обещает Electron runtime-TTS без audioPath: Tts source без prepared recording → SPEECH_MATERIALIZATION_REQUIRED. P12 перед экспортом явно материализует локальный TTS во временную export-copy, не меняет опубликованный board и не теряет текст.

Budgets по фактическим bytes, все суммы checked Long: archive file <=500MiB; <=2000 entries; сумма max(compressed,uncompressed) <=500MiB как у peer; сумма реального inflate <=500MiB, config <=8MiB, отдельное media <=64MiB, отдельная картинка <=16 megapixels и side<=8192, отдельное audio <=10min. ZIP path <=4096 UTF8 bytes, сумма name bytes всего central index <=1MiB: entry-count не даёт права выделить сотни MiB под длинные имена до проверки config. JSON depth<=64, string<=65536 UTF16, field-name<=256; страницы<=256, grid<=64x64, общее content cells<=100000. Resource limit даёт LIMIT_EXCEEDED, не silent truncate. Decoders получают эти пределы до allocation/PCM decode. Ограничения должны быть видны пользователю при отказе; larger library backup потоковый и не использует `.linka` 2000-entry budget на всю библиотеку.

ZIP methods только store/deflate, no encryption/symlink/special/ZIP64/multidisk до отдельной поддержки. Проверять central/local sizes, offsets, CRC и реальные потоковые bytes, не extract-all-then-check. Безопасные относительные slash paths, запрет traversal-компонентов/drive/backslash/NUL/device names/trailing dot-space. `UUID..wav` допустим. Collision key = NFC затем lowercase, directory slash убран; prefix file-directory conflicts тоже rejected. Один canonical lookup map для media references, без filesystem extraction по именам ZIP. Sniff контейнер, не расширение `.ogg` и не force audio/mp3. Wire DTO/ZIP алгоритм P05 принадлежат P, но изменять эту политику без COORD нельзя.

COORD review принимает bounded ZIP decoding, **не** доказательство canonical DEFLATE bitstream: публичный common Okio3.10.2 не сообщает bytesRead inflater-а, prefetch может поглотить trailing bytes внутри объявленной compressed region. Весь compressed region всё равно учитывается budget-ом, выдаваемый payload ограничен реальными bytes/size/CRC и хэшируется; нативный media decoder получает только проверенный inflated payload, а не исходный ZIP. Такие trailing compressed bytes не исполняются/не становятся файловыми путями. Это известное ограничение каноничности; новый parser/internal API/one-byte slow feed ради него не добавляются. Нельзя заявлять, что все noncanonical DEFLATE representations отвергаются; новые counterexamples, обходящие budgets/CRC/payload trust boundary, остаются блокирующими дефектами.

## Владельцы и проверки

### P10 early tokens/primitives (не продуктовые экраны)

Этот ранний handoff описывает существующий кандидат компонентов, **не visual baseline**.
По решению 6 ноября layout/default tokens сверяются с [1.4](ui-1.4-parity.md).
Интерфейсы/семантика остаются; изменения фактических размеров и связанных tests —
будущая реализация после отдельной команды, не выполненная часть docs-only шага.

Допустимая до полной G3 независимая область C: `ui/theme/**`, `ui/components/**`, их pure common tests. Единственный writer C; никаких navigation/controllers/storage/audio/files/access effects. `LinkaTheme(darkTheme: Boolean = isSystemInDarkTheme(), highContrast: Boolean = false, textScale: Float = 1f, content)` использует существующий Material2 и системный fontScale × portable textScale0.5..3, не фиксирует/не обрезает системный масштаб. Все4 комбинации dark/highContrast — только presentation; смена темы не сбрасывает message/draft/queued operations и не возобновляет речь. Новый сохраняемый theme mode не добавляется.

Ранее реализованные regular light — C4 candidate colors; dark candidate: primary66D9D9/onPrimary003737, background0E1919/surface172424, textE3F1F1/secondaryB2C8C8, border8DAAAA, dangerFFB4AB/onDanger690005, focusFFD666. High-contrast light: white background/surface, primary004747/onPrimaryFFFFFF, text000000/secondary1A1A1A, border000000, danger8F120A/onDangerFFFFFF, focus002F6C. High-contrast dark: black background/surface, primary8FFFFF/onPrimary000000, textFFFFFF/secondaryE6E6E6, borderFFFFFF, dangerFFCAC4/onDanger000000, focusFFE082. Light focus002F6C. Disabled surface/text: regular lightE5EEEE/435B5B, regular dark243737/B2C8C8; high-contrast сохраняет основной surface/secondary text без бледной alpha. Checked opaque pairs: dark text/actions>=7.7 (secondary9.1), high-contrast text/actions>=9.3; это расчёт пар, не visual1.4/physical accessibility acceptance.

Минимальные house primitives: `CommunicationCard(label, accessibleName, activationLabel, image: ImageBitmap?, selected, enabled, onActivate, modifier)` и `LabeledActionButton(label, enabled, destructive, onClick, modifier)`. Изображение ContentScale.Fit/no crop/no bitmap-caption; text wrap без ellipsis/уменьшения font. Существующие card96dp/action56dp — прежние implementation defaults, не обязательная новая плотность: целевое представление повторяет1.4 с платформенными touch/a11y минимумами и крупным текстом. Явный selected checkmark+border, отдельный focus ring с неизменной геометрией (3dp, отделён от selected), доступные label/role/action/disabled state без двойного объявления child image/text. Native/foundation click/key/touch semantics прежде ручных gestures; focus/hover сами не активируют, disabled не оставляет accessibility action. AccessibleName/activationLabel задаёт caller по реальному mode; не угадывать смысл фотографии.

Pure tests проверяют4 palette pairs (text4.5, high-contrast7, borders/focus3), disabled/pressed composited colors и targets/font multiplier без platform state. Actual semantic tree, keyboard autorepeat/traversal, pointer cancellation, screenshots/VoiceOver/TalkBack/large-font на native остаются P10/P15 gates; compile/palette math не объявляются этим proof. Родительский layout должен соблюдать min targets, theme tokens не чинят произвольный clipping ancestor.

### Backup schema1 для P12

P12 codec prerequisite, без workflow/UI/user-data binding: `portability/backup/BackupCodec(media: MediaStore, files: PlatformFilesPort, inspection: MediaInspectionPort, library: LibraryStore)`, `suspend fun write(snapshot: SnapshotReader): OperationResult<TemporaryFile>`, `suspend fun read(file: LocalFile): OperationResult<SnapshotReader>`. Write не потребляет caller reader/lease; берёт собственный retain на время операции, read возвращает sealed owned reader через LibraryStore.createSnapshotBuilder, не заменяет live библиотеку. Fail/cancel/UnconfirmedCommit не возвращают частично готовый reader; все свои temp/leases/builder закрываются, immutable media не удаляются вручную. Передача результата после последнего suspend проверяет cancellation. Codec — не authorization boundary для внешней выдачи: protected workflow и native destination guard принимаются отдельно до PlatformFilesPort.save binding.

Отдельное расширение `.linkabackup`, ZIP, root `manifest.json` с `format="su.linka.pictures.backup"`, `version="1.0"`, captured library revision, PortableSettings и SnapshotCounts. Нет epoch текущего процесса, message/undo, credential/lock/device voice, paths или временных операций. Metadata documents — внутренние version1 **PublishedBoard(document,revision)** / StoredDraft(version,draft) / RetainedArchive, **не** `.linka` wire DTO; обновление этих моделей требует совместимого versioned reader, не молчаливой смены schema1.

Entries: `boards/00000000.json`, `drafts/00000000.json`, `retained/00000000.json` (последовательные индексы, не непроверенные ID в пути), `assets/<64 lowercase hex>`. Manifest asset metadata — отдельный поток `assets.jsonl`, одна MediaAsset на строку, exact hash/bytes/closure. Порядок boards — порядок entries; IDs/revisions сохраняются. Дубликаты IDs/entries, неизвестные обязательные поля/версия/несовпадающие counts или лишние assets отклоняют весь backup. Tts source сохраняется как Tts, без materialization/потери текста, в отличие от Electron export.

ZIP reader/writer переиспользует один проверенный bounded engine из P05, не второй parser. Два закрытых внутренних профиля LINKA/BACKUP: LINKA limits неизменны; BACKUP<=60000 entries/2GiB file+aggregate actual bytes, path<=4096 UTF8 bytes и central names<=8MiB, metadata entry<=8MiB, assets.jsonl<=64MiB с bounded отдельными JSON-строками. Asset file budget определяется MediaKind после manifest: IMAGE/AUDIO64MiB+native inspector budgets, ARCHIVE2GiB raw/hash-only без попытки исполнять/декодировать retained архив. Неподдерживаемые compression/flags/path/CRC/prefix/collision/ZIP64 правила и известный deflate canonicality limit те же. Profiling — только два требуемых формата, не пользовательские knobs/плагины; общие helpers можно перенести в portability/zip с обновлением всех P05 callers/tests.

Чтение и запись по одному документу/asset потоку; разрешены только заявленные entries. Снимок metadata заранее зафиксирован, exporter не архивирует живой media-каталог или DB. Перед restore предупреждение об открытом файле и полная замена с recovery. Пределы backup P12: до 60000 entries/2GiB фактических bytes (без ZIP64), per-JSON8MiB/depth64/string65536; media с теми же decoder budgets. Предварительная оценка места включает одновременно staged backup, текущую библиотеку и recovery; место не гарантируется одним начальным free-space check — реальные IO ошибки обрабатываются до commit. Превышение не создаёт урезанную «полную» копию. Эти budgets не сокращают содержимое молча; расширение формата для более крупных библиотек — отдельная проверяемая версия.

| Область | Единственный writer |
|---|---|
| Root build, domain declarations, ports, text normalization, security/migration, integration | COORD |
| domain pure rules и application/communication implementation+tests | D / P03, существующие declarations не менять без handoff |
| storage + application/library, platform storage bindings+tests | S / P04 |
| portability/linka, wire DTO/fixtures/tests | P / P05 |
| audio/files/platform inspection bindings | A/P06, I/P07; root/project/permission изменения только COORD |
| shared UI primitives | C / P10; другие UI используют их, не дублируют |

P03/P04/P05 запускаются в отдельных branches/worktrees после commit/freeze G2, не в общем mutable index. Нужная новая сигнатура/режим/таблица/зависимость — вернуть COORD, не импровизировать. Перед интеграцией focused tests, coherent correctness review, затем один общий gate.

P02 acceptance: common model round trips/позиции/refs/form pins/epoch/cancel, path NFC tests JVM+Native17, полный Unicode16 corpus, обе ARM native compilation, оба frameworks и unsigned iOS shell после rename. P04 доказывает stale epoch/двух writers/unknown schema/GC pins/restore fault atomicity до любых user-data bindings. G2 снимается только после результатов, не наличия этого файла.
