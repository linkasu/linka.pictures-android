# P12: protected transfer workflows

`application/portability/TransferCoordinator` использует существующие `LinkaCodec`,
`BackupCodec`, `CheckedZip`, порты и SQLite atomic publication. Новый parser,
UI, storage schema, credentials и native bindings не добавляются.

## API и состояния

Конструктор: `(library, messages, linka, backup, files, gate, ids, scope)` согласно
`code-contracts.md`. Scope принадлежит root. Все codec/source/sink/cleanup работы
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
- `cancel(ownerId, operationId, capability)` и `close(ownerId, capability)`
  явно проверяют owner/capability. Close освобождает операции владельца и закрывает
  этот owner для новых admissions; новый UI owner получает новый идентификатор.
- `revokePending(ownerId)` — lifecycle invalidation, а не авторизация. Root вызывает
  её при background/picker interruption и отдельно отзывает доступ через access.
  Отозванная операция terminal, никогда автоматически не продолжается. Для нового
  explicit действия нужна новая prepare и затем fresh confirm.

`state: StateFlow<Map<OperationId, TransferOperationState>>` публикует отдельные
immutable снимки, без файлов, URI, leases, destinations и capabilities. Отсутствие
операции означает IDLE; существующий `TransferPhase` не изменён.
VALIDATING/PREPARING → AWAITING_CONFIRMATION → WRITING → COMPLETED.
CANCELLED, FAILED и UNCONFIRMED различаются. Для одного owner допускается только
одна pending/running операция, включая завершение cleanup; второй запрос возвращает
CONFLICT без скрытой очереди. Разные owners имеют независимые операции.

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

Root перед отменой своего scope обязан вызвать `revokePending` для остающихся
owners: завершённый prepare намеренно держит lease/temp до explicit terminal.
