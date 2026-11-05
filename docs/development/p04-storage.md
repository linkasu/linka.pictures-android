# P04 storage: implementation handoff

Условная реализация P04, **не G4/migration/runtime user-data readiness**. Перед интеграцией
нужен COORD safety/data-loss review; реальные пользовательские данные не подключались.

## База и границы

- Исходная база: `0816394017adefd3fc77aeebca2a1a052493206e`.
- Ветка: `implementation/p04-storage`.
- Порученные cherry-picks: `601291e` → `98ce03a`, `01e2adb` → `8b05662`.
- База собственного implementation diff: `8b05662463661189c2a96dd11f465331420e3aac`.
- SAFE PROBE-COPY принят COORD в `0f2f821`; его contracts прочитаны через git show,
  commit не cherry-picked, другие lanes не интегрировались.
- Собственный diff: только commonMain/commonTest `storage/**` и этот отчёт.
  Нет изменений ports/models/dependencies/root build/UI/startup/access/migration,
  legacy/protection DB, installations, permissions, secrets, push или CI.

## Initial schema probe: source не открывается SQLite до acceptance

Точный factory API сохранён: `StorageRuntime.open(root, fileSystem, driver)`;
`library`, `messages`, `media`, suspend `close()`. Native wrappers не потребовались.

Для существующей DB `InitialSchemaProbe` создаёт новый UUID child под root с mustCreate.
Потоково копируются main DB и присутствующие WAL/journal. SHM не копируется. Потоковые
SHA256 + size/mtime и file-set checks выполняются до/после копирования, native validation,
закрытия/cleanup scratch; media library не копируется. SQL recovery/checkpoint/SHM writes
разрешены только у копии. Corrupt/unknown0/incomplete1/future schema отказывают до live open.
Schema1 дополнительно проверяется существующими payload/ref/media invariants на probe.

Только после принятой schema, стабильности source и успешной cleanup собственного probe
открывается live writer; version/schema проверяются повторно перед mutations. Никаких URI,
immutable mode, custom SQLite/WAL parser или обхода native handles. Факт: bundled2.5.2
generic open не принимает `file:…?mode=ro` без дополнительных flags; это не API-контракт.

В published SQLite2.5.2 common `SQLiteException` содержит только message, без structured
result code. Native exceptions probe трактуются как IO_FAILURE (невозможно проверить),
без парсинга приватного текста и ложного INVALID_DATA при реальном native IO failure.
Явно доказанные schema/model/ref violations сохраняют INVALID_DATA/INTEGRITY_FAILURE.
Для binary corruption, которую сам SQLite не может прочитать, гарантия — отказ без
source open/изменений, а не догадка о причине по exception text.

Single runtime/sandbox/process без внешних writers — явная предпосылка shell. Протокол
обнаруживает проверяемые изменения, но не обещает atomic cross-file backup чужой живой DB.
При cleanup failure — IO_FAILURE без live open. Может остаться только собственный новый
probe directory; следующий open создаёт другой UUID и не присваивает/не чистит старые folders.

### Source-mutation proof

Реальная bundled SQLite, только owned synthetic directories, без убийства процессов:

- Future schema2 в WAL при **main-only user_version=0**, native SHM, opaque future file
  и чужой staging sentinel: NEWER_SCHEMA; hashes/file-set всего original tree совпадают;
  driver открывал только probe, до его open в копии не было SHM.
- Реальный hot rollback journal после cache spill: SQLite восстанавливает/удаляет journal
  **только у probe**, обнаруживает исходную schema2; source DB/journal/tree неизменны.
- Known schema1 WAL принимается до live recovery и загружает authoritative latest state.
  Непосредственно перед первым live driver.open original tree всё ещё byte-for-byte исходный.
- Обычный root filename с `?`, `#`, Unicode проходит open/reopen без URI-интерпретации.
- Injected ENOSPC/copy error не вызывает driver вообще; source changes во время копирования
  и после native copy validation дают CONFLICT, без live open и без revert внешнего изменения.
- Cleanup failure не открывает source; unknown old probe folders и их sentinel остаются.
- Corrupt source, nonempty version0, incomplete/altered schema1 отказывают без source open.

## COMMIT outcome и ownership

Suspend ports, factory open/close, initial probe и reconciliation сами исполняют blocking
SQL/FS/hash/copy на Dispatchers.IO. Caller на Main не должен оборачивать saves в IO.
Snapshot producers используют IO + rendezvous buffer, без предзагрузки массива документов.
Синхронные file/retain — только handle/ref checks. Синхронное закрытие snapshot немедленно
закрывает lease/state; metadata cleanup выполняется на IO и учитывается close runtime.
Quarantine/closed runtime не запускает новую metadata cleanup.

Возврат с worker использует atomic continuation, не prompt-cancellable dispatcher return:
иначе cancellation Main job могла бы скрыть уже выполненный COMMIT. Исходный caller Job
проверяется вручную перед mutations/commit и при потоковом IO; uncertain reconciliation
не прерывается cancellation. Это покрыто реальной отменой caller Job до/после COMMIT.

Все SQL writers используют один runtime writer и транзакционный протокол. Результат
вычисляется до COMMIT. Guard проверяется непосредственно перед mutation и перед commit;
epoch/optimistic revisions проверяются заново после media preflight.

- Успешно наблюдаемый COMMIT → Completed, даже при последующем refresh/cleanup failure.
- COMMIT/ROLLBACK exception → suspect writer закрывается и никогда не переиспользуется.
- После успешного close — одна bounded independent reconciliation: новый connection,
  `busy_timeout=1000`, `query_only=ON`, consistent read. Streaming SHA256 fingerprints
  exact old/new schema1 state включают epoch/revisions/payloads/settings/message/assets/
  refs/recovery/schema. Exact new → Completed, exact old → исходный Failed/Cancelled.
- Observer остаётся read-only. После доказанного исхода следующий обычный access открывает
  свежий writer. Ошибка cleanup observer не отменяет уже наблюдавшееся durable proof.
- Close failure, недоступный observer или mismatch → UnconfirmedCommit и quarantine;
  mutations/GC возвращают UnconfirmedCommit. Library/message reads явно блокируются с
  COMMIT_OUTCOME_UNKNOWN; listing имеет тот же refreshProblem, не притворяется current state.
- Owner leases и potentially published media сохраняются. Internal uncertain pins живут до
  explicit close; uncertain staged files не удаляются до authoritative reopen. Replay нет.

## Остальной полный scope

- Frozen schema1, WAL/FULL/foreign_keys, bound values, strict versioned JSON marker.
- Board/draft optimistic writers; unfinished form refusal; конфликт/удаление source сохраняют draft.
- Message epoch/version coalescing/idempotence, atomic refs; stale epoch не оживляет фразу.
- Immutable actual SHA256 bytes, typed LocalFile, independent CAS leases, all-or-nothing acquire.
- `AssetRoles` проверяет declared metadata.kind: image refs → IMAGE, recording refs → AUDIO,
  retained raw archive → ARCHIVE, включая open forms/message snapshots/recovery. Один hash
  в конфликтующих ролях отвергается. Writers, startup validation, seal и restore используют
  тот же helper; kind не выводится из label/MIME, native decoder в storage не добавлялся.
- Exact prepare limits: IMAGE/AUDIO **67,108,864 bytes (64 MiB)**, ARCHIVE
  **2,147,483,648 bytes (2 GiB)**, streaming Long counter/hash-only без unzip/decoder.
  `.linka` 500 MiB и whole-backup 2 GiB включая overhead остаются codec/P12 budgets;
  legacy >2 GiB — private migration recovery/явный отчёт COORD, не silent drop в storage.
- Hash/copy/validation вне writer/SQL lock: staging pin, затем повторная metadata/ref проверка
  под writer. Final rename + SQL metadata + lease publication и GC согласованы одним lock.
- GC повторно проверяет refs/leases после hashing, оставляет durable owned deletion intent;
  после reopen retry завершает metadata cleanup. Unregistered hash files после uncertain prepare
  rollback удаляются только после authoritative reopen, проверки SHA и отсутствия refs/leases.
  Missing/corrupt files без intent не выдаются за успешный cleanup/пустую библиотеку.
- SnapshotReader/Builder: per-document owned staging, repeatable streams, exact closure
  boards + draft documents/open forms + retained, без message-exclusive assets.
- ReplaceLibrary: expected library/message + permit, occupied recovery conflict, атомарные
  recovery-before-switch/settings/refs/new epoch/message clear. Protection DB не затрагивается.
- Для replacement metadata — другая owned scratch DB с теми же frozen tables, затем
  ATTACH/INSERT SELECT. Scratch не является user library; новых live schema tables нет.
- Recovery swap держит две metadata rows и сохраняет overlapping IDs/order/drafts/refs;
  explicit delete снимает только recovery roots, GC отдельно.
- close ждёт accepted operations, закрывает handles, не удаляет библиотеку и запрещает новые вызовы.
  Не закрывшиеся handles сохраняются для следующего explicit close, без retry mutations.

## Verification и оставшиеся границы

Focused JVM suite: **69/69 storage tests PASS** на actual bundled SQLite; existing common14
не менялись. После correctness review выполнен финальный quality gate:

```text
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
ANDROID_HOME=/Users/aacidov/Library/Android/sdk
DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
./gradlew :shared:jvmTest :shared:compileKotlinIosSimulatorArm64 --console=plain
```

- `:shared:jvmTest` — **83/83 PASS**, 0 failures/errors/skipped (69 storage + existing14).
- `:shared:compileKotlinIosSimulatorArm64` — **PASS**, actual compilation, не Native execution.
- XML evidence: `shared/build/test-results/jvmTest/TEST-*.xml`.
- Явный `okio.use` сохраняет Closeable boundary на Native; JVM-only use не принимался за proof.

Fault matrix: BEGIN, entry/ref insert, COMMIT до/после actual step, ROLLBACK failure,
writer/observer close, observer unavailable, second guard revoke/cancel, recovery creation/
copy/switch/message clear, file finalize до/после rename, source close, GC file delete до/после,
GC metadata commit/reopen/retry, corrupt snapshot/assets/refs/schema, probe copy/source change/
cleanup failures. Две исходные uncertain-commit регрессии сохранены, не подавлены.
Дополнительно отдельный однопоточный Main caller остаётся responsive при заблокированных
factory/SQL/prepare/acquire/metadata/probe/snapshot-flow/close/cleanup, а отмена настоящего
Main Job после commit сохраняет Completed/UnconfirmedCommit вместо cancellation.
Kind regressions покрывают wrong board image/recording, draft document/open form,
message image/recording, retained archive, exact-manifest seal, cross-document role conflicts,
untrusted replace и corrupt recovery/startup. Exact old DB fingerprint/epoch/revision/refs
сохраняются при отказе; есть positive IMAGE+AUDIO/form persist/reopen/seal. Budget regression
проверяет 100 MiB retained archive, границы 64 MiB/2 GiB и Long overflow без больших файлов.

В коде явно отмечены performance shortcuts: startup copy/hash O(metadata DB bytes) и
streaming full-state fingerprints на commits O(metadata size). Large-library throughput
не измерялся; aggregate library model в RAM не строится. Existing active owners должны
завершаться при явной смене runtime/session; foreign/closed leases не принимаются.

Native integrated execution после интеграции, Android/device/codec/physical/power-loss gates,
G0/G1/G4, реальная legacy migration, startup roots и пользовательские данные остаются COORD.
Тесты не заполняли диск, не останавливали личные процессы и не устанавливали приложения.
