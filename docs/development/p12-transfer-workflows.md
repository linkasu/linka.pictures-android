# P12: protected transfer workflows

UI-входы и представление — по [контракту 1.4](ui-1.4-parity.md): отдельные меню/
экраны переноса, не панель на доске или над плитками. Preview, fresh authorization,
подтверждения и запрет выдачи contents до confirm сохраняются. Код сейчас на паузе.

`application/portability/TransferCoordinator` использует существующие `LinkaCodec`,
`BackupCodec`, `CheckedZip`, порты и SQLite atomic publication. Новый parser,
UI, storage schema, credentials и native bindings не добавляются.

## API и состояния

Конструктор: `(library, messages, linka, backup, files, gate, ids, scope)` согласно
`code-contracts.md`. Coordinator принадлежит ограниченному transfer UI-owner/root
child scope, не является вечным process-global сервисом. Scope принадлежит root;
закрытые owner IDs не переиспользуются. `closedOwners` сохраняет tombstones до
освобождения coordinator, чтобы close никогда не открывал старый owner повторно.
Все codec/source/sink/cleanup работы
выполняются на `Dispatchers.IO`; короткий mutex хранит только admission/state.
Guard callbacks, picker, FS и SQL под этим mutex не выполняются.

- `prepareImport(ownerId, name)`, `prepareRestore(ownerId)`,
  `prepareExport(ownerId, boardId)`, `prepareBackup(ownerId)` возвращают opaque
  `OperationId`, по которому находится passive preview в `state`.
- `prepareReturnToRecovery(ownerId, recoveryId)` и
  `prepareDeleteRecovery(ownerId, recoveryId)` также сначала создают preview.
- `confirm(ownerId, operationId, capability, confirmation)` принимает только
  соответствующий `TransferConfirmation`: ImportNew, ImportReplace(id, revision),
  WriteFile, ReplaceWholeLibrary, ReturnToRecovery, DeleteRecovery.
- `cancel(ownerId, operationId, capability)` — safe stop, а не protected mutation
  или разрешение выдать данные. Достаточно live opaque operationId и того же owner;
  stale/null capability после picker/background revoke не блокирует отмену.
  Переданная capability чужого owner даёт UNAUTHORIZED; чужая операция или stale ID
  не допускаются. Cancel не вызывает gate/unlock/prompt, закрывает собственные
  private handles и запрещает следующие admissions/chunks, но не обещает rollback
  уже подтверждённого commit или внешней partial-копии.
- `close(ownerId, capability)` по-прежнему проверяет owner/capability через gate,
  освобождает операции владельца и закрывает
  этот owner для новых admissions; новый UI owner получает новый идентификатор.
- `revokePending(ownerId)` — lifecycle invalidation, а не авторизация. Root вызывает
  её при background/picker interruption. Порядок обязателен: сначала синхронный
  `access.revokeOwner(ownerId)` / `access.revokeAll()`, затем запуск suspend
  `revokePending` для затронутых owners; authority отнимается до ожидания mutex/join.
  Отозванная операция terminal, никогда автоматически не продолжается. Для нового
  explicit действия нужна новая prepare и затем fresh confirm.

`state: StateFlow<Map<OperationId, TransferOperationState>>` публикует отдельные
immutable снимки, без файлов, URI, leases, destinations и capabilities. Отсутствие
операции означает IDLE; существующий `TransferPhase` не изменён.
VALIDATING/PREPARING → AWAITING_CONFIRMATION → WRITING → COMPLETED.
CANCELLED, FAILED и UNCONFIRMED различаются. Для одного owner допускается только
одна pending/running операция, включая завершение cleanup; второй запрос возвращает
CONFLICT без скрытой очереди. Разные owners имеют независимые операции.
State не является историей: terminal остаётся видимым до успешного admission новой
операции того же owner, которое заменяет прежнюю terminal запись. Для каждого owner
хранится не более одной active/latest-terminal записи; чужие owners не удаляются,
а отказ admission не скрывает предыдущий результат.
In-flight waiter держит собственную terminal phase до возврата: замена публичной
записи новой операцией не превращает уже подтверждённый commit в Cancelled.

Счётчики обновляются после успешной codec-проверки архива: `processedEntries` —
реальное число ZIP entries, `processedBytes` — сумма их проверенных распакованных
размеров, включая metadata. Это не размер внешнего partial и не произвольный процент
progress. Для подсчёта повторно используется существующий CheckedZip (дополнительное
worker-чтение архива, без второго parser). До окончания проверки счётчики равны нулю.

## Границы подтверждения и публикации

Import держит owned picker temp и PreparedBoard lease до terminal. Входной внешний
архив не изменяется. NEW создаёт свежий BoardId; одинаковое имя не является ключом
overwrite. REPLACE использует только явно заданные id/revision и captured epoch.
Неподдерживаемые активные поля/режимы и resource limits остаются whole-archive
отказом существующего codec до публикации.

Export/backup сначала захватывают leased board/repeatable snapshot и записывают
полный private файл. Picker получает только `board.linka` / `library.linkabackup`
и `application/zip`, не содержимое. После picker требуется explicit foreground
confirm с актуальной capability. Только application создаёт свежий guard,
передаваемый в `destination.write(file, guard)`; guard дополнительно проверяет
отзыв операции и captured epoch на каждом admission. Сам picker не авторизует.
Существующий native adapter отвечает за foreground, bounded chunk checks и
Completed только после flush/close. После admission внешняя пустая/partial копия
может остаться; workflow не удаляет её и не обещает внешний rollback.

Export preview содержит BoardTransferReport из **того же** leased PublishedBoard:
pages и CONTENT cards, число уникальных image/recording AssetIds и сумма actual
validated ZIP media entry sizes из существующего CheckedZip. Повторные ссылки
не удваивают media counts/bytes. Canonical uniform export имеет
`layoutNormalized=false`. Report не перечитывает текущий listing/новую revision;
edit во время picker не меняет preview или уже подготовленный export. Library
документ и исходные media workflow не изменяет.

Restore захватывает expected library revision, MessageStamp и epoch при prepare;
confirm их не обновляет. ReplaceWholeLibrary вызывает существующий replaceLibrary,
который атомарно создаёт recovery и очищает сообщение нового epoch. Занятый recovery
даёт CONFLICT без overwrite. ReturnToRecovery имеет собственное explicit подтверждение
с RESTORE_BACKUP, DeleteRecovery — с DELETE_RECOVERY. Credentials не читаются и
не изменяются. Успешный полный switch публикует `stateCommittedEpoch`: root обязан
явно пересоздать/reload communication без речи/undo из прежнего epoch.

После каждого suspend принадлежащий результат сначала регистрируется для cleanup,
потом проверяется cancellation/generation. Поздние picker results закрываются/
discard-ятся. Foreign destination не присваивается и не закрывается. Cleanup
best-effort проходит все owned handles и не маскирует первичный исход. Он выполняется
на IO и не удаляет чужие provider файлы. Отмена до commit не становится IO_FAILURE;
подтверждённый storage commit остаётся COMPLETED даже при конкурентной отмене.
UnconfirmedCommit публикует UNCONFIRMED/COMMIT_OUTCOME_UNKNOWN и замораживает
новые admissions/guards до пересоздания coordinator после explicit storage reopen.
Автоматического retry нет. Чистые codec ошибки остаются FAILED, не unknown commit.

## Проверка и оставшиеся release gates

`TransferCoordinatorTest` использует настоящие SQLite/FS, LinkaCodec/BackupCodec,
а существующие CoreBackupFiles/SyntheticCoreInspection — для owned file handling
и намеренно synthetic media inspection. Проверяются preview-only, fresh guard после
picker, explicit target revision, captured message/library conflicts, occupied
recovery, swap/delete confirmation, late source cleanup, pending/running cancel,
partial external copy, commit-versus-cancel, failed-versus-unknown и archive bounds.
Fake gate/picker/destination/inspection **не доказывают** native owner verification,
реальные OS prompts/providers, codecs, physical Android/iOS или UI integration.

Проверка 2026-11-05, base `baafb41`, без изменений root/Gradle/native:

```sh
./gradlew :shared:jvmTest \
  --tests 'su.linka.pictures.application.portability.TransferCoordinatorTest' \
  --tests 'su.linka.pictures.portability.*' \
  --tests 'su.linka.pictures.integration.CoreBackupIntegrationTest' \
  --tests 'su.linka.pictures.storage.StorageRuntimeTest' \
  :shared:compileDebugKotlinAndroid \
  :shared:compileKotlinIosArm64 :shared:compileKotlinIosSimulatorArm64 --console=plain
```

PASS: 144 JVM tests / 7 suites, 0 failures/errors; Android shared compile и обе
Native KLIB compilations. После этого добавлены только два workflow-теста
(успешный captured backup→restore и quiz/match boundary), production code не менялся:

```sh
./gradlew :shared:jvmTest \
  --tests 'su.linka.pictures.application.portability.TransferCoordinatorTest' --console=plain
```

PASS: финальные 14/14 workflow tests. Совокупно 146 различных проверенных tests;
после test-only изменения Native/Android compile не повторялись. Предупреждения
сборки — существующие expect/actual Beta. Normal APK, allTests, default simulator,
Keychain, OS prompts, UI и physical devices для этой lane не запускались.

Correction после `7d74425`: bounded per-owner state, сохранение close tombstones и
уточнённый root lifecycle. Добавлен тест повторных prepare/confirm с двумя owners:
terminal виден до следующего admission, CONFLICT не удаляет state, чужой active/
terminal сохраняется, закрытый owner не открывается снова. Прежние 14 тестов не
ослаблялись. PASS: 15/15 focused workflow tests и обе incremental Native compilations:

```sh
./gradlew :shared:jvmTest \
  --tests 'su.linka.pictures.application.portability.TransferCoordinatorTest' \
  :shared:compileKotlinIosArm64 :shared:compileKotlinIosSimulatorArm64 --console=plain
```

Integration correction после `f54b8b9`: заполнен export board report из leased
snapshot/проверенного ZIP и уточнена safe-stop матрица cancel без новой authority.
Добавлены тесты repeated media refs + edit во время picker + unchanged source
bytes, stale/null capability после revoke, owner isolation и running partial stop.
Прежние 15 тестов сохранены. Confirm/close guards, protected actions и production
ports/storage/native/UI не изменены. PASS: 18/18 focused JVM tests, 0 failures/errors/
skips; затем той же командой correction gate выше собраны обе Native KLIB
(JVM task в combined run UP-TO-DATE после успешного focused запуска).
Android compile повторно не запускался; Native compile-only не является OS proof.

Root перед отменой своего child scope обязан сначала синхронно отозвать access,
затем дождаться `close` / `revokePending` для всех остающихся owners и лишь после
этого отменять scope. После access revoke gate может отклонить protected close;
в этом случае cleanup выполняется через lifecycle `revokePending`, без новой
авторизации. Завершённый prepare намеренно держит lease/temp до explicit terminal.
