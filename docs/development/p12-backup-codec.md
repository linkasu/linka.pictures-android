# P12 prerequisite: backup codec only

Base `65717296e1868a6121ce30042d61a0efeaac030f`, branch
`implementation/p12-backup-codec`, write-root
`/Users/aacidov/.local/share/opencode/worktree/67846f/pokazhi-p12-backup-codec`.
Реализован frozen `BackupCodec(media, files, inspection, library)` с
`write(SnapshotReader): OperationResult<TemporaryFile>` и
`read(LocalFile): OperationResult<SnapshotReader>`.

**Не P12/G4 restore acceptance, не real storage integration.** Нет
StorageRuntime/user DB binding, protected workflow, UI, authorization, picker,
PlatformFilesPort.save, live library/message/settings/recovery mutation или GC.
P04 реализация не подключена: runtime SnapshotReader/Builder здесь не создаются
самописным production fallback; read вызывает только frozen factory
`LibraryStore.createSnapshotBuilder()` и add*/seal. Fakes находятся только в tests,
неиспользуемые live методы бросают ошибку, а не возвращают фиктивный успех.

## Schema1 и данные

Отдельный open ZIP `.linkabackup`:

| Entry | Payload |
|---|---|
| `manifest.json` | frozen BackupManifest: format/version1.0, revision, PortableSettings, SnapshotCounts |
| `boards/00000000.json` | **PublishedBoard(document, revision)** — точный payload выбран COORD, published revision не теряется |
| `drafts/00000000.json` | StoredDraft(version, draft), включая document и open form |
| `retained/00000000.json` | RetainedArchive, включая revision/reason/hash |
| `assets.jsonl` | Одна MediaAsset на строку, serializer escapes, без массива всех rows |
| `assets/<64 lowercase hex>` | Фактические asset bytes с SHA256 и точным byteCount |

Индексы каждой metadata-группы последовательны с0, порядок boards соответствует
snapshot flow. IDs/revisions/settings/opaque extensions сохраняются, Tts остаётся
Tts без materialization. Root closure включает все boards, draft document + open
form и retained archives. Ни message-exclusive assets, ни credential/device voice/
epoch/undo/cache/LocalFile paths в format не включаются. Нет обхода sandbox или
media folder: читаются только explicit snapshot flows и их exact roots. Даже если
ошибочный snapshot.assets добавляет незаявленный message-only asset, write целиком
отклоняется до передачи output.

JSON декодируется типизированным Kotlin serialization с ignoreUnknownKeys=false,
без version guessing. Raw manifest format/version проверяются до конструктора
BackupManifest. Shared JSON guard до DOM ограничивает bytes/depth64/string65536/
key256 и отвергает duplicate keys. Extensions остаются JSON data, не commands;
direct LINKA reserved-feature policy к внутреннему backup JSON не применяется.
Extensions проверяются как passive JSON object без sandbox/provider URI schemes,
как требует общий контракт. Unknown schema fields, duplicate entry/draft IDs,
asset IDs/rows, counts/closure/kind/hash/size mismatch отвергают весь backup.

## Один ZIP engine, два профиля

P05 engine перенесён, а не скопирован: `portability/zip/CheckedZip.kt`.
Общие bounded JSON/path/result/IO/inspection helpers также в `portability/zip/`;
LINKA feature-key policy осталась в `portability/linka/LinkaFeatures.kt`.
Callers используют только закрытый internal enum `ZipProfile.LINKA/BACKUP`, без
публичных options/plugins. LINKA facade и uniform1×1 policy не изменены.

| Budget | LINKA (прежний) | BACKUP |
|---|---:|---:|
| entries | 2000 | 60000 |
| archive / sum max(compressed,size) / actual inflate | 500MiB | 2GiB |
| filename UTF8 bytes | 4096 | 4096 |
| central names sum | 1MiB | 8MiB |
| config/metadata JSON | 8MiB | 8MiB |
| assets.jsonl | не отдельный формат | 64MiB, каждая JSON row <=8MiB |
| image/audio asset | 64MiB | 64MiB |
| retained ARCHIVE | не media path LINKA | 2GiB raw/hash-only |

Typed media limits назначаются после metadata validation; retained archive не
открывается внутренним ZIP parser и не передаётся image/audio inspector.
IMAGE/AUDIO проходят injected inspector и прежние 16MP/8192/10min проверки;
inspection fake **не** доказывает реальный native decoder/до-allocation budgets.

Shared engine сохраняет local/central sizes/flags/method/name/offset/CRC checks,
UTF8 rule, NFC+lower collision key существующего text helper, prefix conflicts,
truncation и запрет ZIP64/multidisk/encryption/symlink/special. Name budget
проверяется до allocation/read имени. Writer проверяет file budget также до
записи header/payload chunk/central records, не создаёт сверхлимитный temporary
ради последующего отказа. PROFILE limits не зависят от caller knobs.

Известное ограничение не усилено и не скрыто: public Okio inflater prefetch может
поглотить trailing compressed bytes внутри заявленного региона. Exact canonical
DEFLATE end не доказан; compressed region учитывается в budget, output ограничен
actual size/CRC и хэшируется. Internal Okio API/one-byte feeding/самописный deflate/
новая зависимость не добавлены. Trailers не исполняются/не становятся paths.

## Streaming, memory и ownership

Write берёт собственный retain, caller snapshot/lease не закрывает. Metadata
пишется по одному документу. Первый assets flow стримится в owned JSONL temporary
с validation/count/closure/digest; второй stream пишет payload и проверяется на
repeatability. MediaStore metadata, actual hash/bytes/type проверяются перед
успешной выдачей. Готовый output ZIP повторно проходит shared validation.

Read стримит bounded payload в созданные PlatformFilesPort temporaries с
фиксированными suggested names, **не извлекает ZIP paths в filesystem**. Первый
проход проверяет namespace, manifest, каждый document, строки asset metadata,
все actual asset hashes/sizes/kinds и exact closure **до первого prepare/builder**.
Затем factory создаёт builder: assets готовятся/добавляются с pins, metadata
перечитывается по одному документу, seal отдаёт reader. Подготовленные leases
не освобождаются до успешного seal и независимой reader lease.

Нет агрегата всех board JSON или media bytes. Resident working data:

- chunk buffers 8KiB и EOCD tail <=65557 bytes;
- один metadata document/asset row (accepted encoded bytes <=8MiB), typed
  model/JSON DOM; writer измеряет cells/pages до полной сериализации, чтобы
  malformed in-memory model не собирался в неограниченный JSON (preflight 2×8MiB,
  окончательный encoded budget всё равно8MiB);
- bounded central index (<=60000, sum raw names<=8MiB), owned handle/hash table,
  ID/kind/duplicate sets <=entry budget, **не список MediaAsset objects/bytes**;
- JSONL целиком в RAM не загружается: file<=64MiB, одна строка<=8MiB (+ bounded
  source read-ahead). Decode/encode временные copies и object overhead существуют.

Это структурные bounds, **не измеренный максимальный RSS/heap на Android21**.
Worst-case heap при предельных metadata/index остаётся native/device resource
gate, а не заявленный PASS. Codec disk staging: read до2GiB total inflated;
write output до2GiB + JSONL temporary до64MiB; MediaStore/Builder могут требовать
собственное дополнительное staging/immutable место. Нет попытки заменить ENOSPC
одним optimistic preflight или урезать «полную» копию.

Все собственные source/sink/lease/temp/builder закрываются на отказе/отмене;
cleanup пытается обработать все handles. Normal cleanup завершается до handoff,
при его ошибке sealed reader закрывается. Failure cleanup не маскирует primary
UnconfirmedCommit/CancellationException, GC/immutable delete отсутствуют. Реальный
OS отказ discard не гарантирует удаление файла — native cleanup gate остаётся.
SnapshotReadException.problem сохраняется. UnconfirmedCommit не превращается в
Failed/Cancelled/success, retries нет. Late noncooperative prepare/seal Completed
регистрируется как owned до cancellation check; late reader/lease закрывается,
до handoff повторно проверяется cancellation. Caller snapshot и source ZIP не
меняются. Передача готового temporary — только caller-у, не внешнему provider.

## Проверки и границы доказательства

`BackupCodecTest` — actual synthetic ZIP + minimal frozen port fakes:
empty/full round-trip, published/draft/retained revisions, form roots, Tts,
settings/metadata/escaped JSONL, message-only/secrets exclusion, incorrect flows,
exact roots/kinds/IDs/hash/counts, unsupported version/unknown fields/namespaces,
JSON/key/string/depth/line/file bounds (включая exact8MiB row без финального LF),
prepare/add/seal/create Failed/Cancelled/Unconfirmed, source/write/inspection/
cleanup faults, actual cancellation и поздние noncooperative prepare/seal.

`CheckedZipProfileTest` запускает один parser на обоих профилях: actual fixed
deflate/store/descriptors, flags/encryption/method/CRC/false-size/offset/ZIP64/
symlink/traversal/collision/prefix/truncation matrix; >2000-entry archive отличает
BACKUP от LINKA. ARCHIVE>64MiB/2GiB typed budget проверяется bounded helper/index
fixtures без записи гигабайтов. Intentionally absent payload по-прежнему
отвергается full visit: index acceptance не объявляется чтением такого backup.
Прежние 28 LINKA regressions и остальной baseline не удалены/не ослаблены.

Команда final gate (с принятыми JAVA_HOME/ANDROID_HOME/DEVELOPER_DIR):

```sh
./gradlew :shared:jvmTest :shared:compileKotlinIosSimulatorArm64 \
  :shared:compileTestKotlinIosSimulatorArm64 --console=plain
```

После gate прежний pure peer oracle читает четыре фактических LINKA writer-output
на Node v22.22.2 / E `c540646e1bf06d5f4ea76d6db6b2ab1b82bfe89b`, без Electron app,
media playback, peer source edits или переноса media. Synthetic fixture temporary
создаётся только внутри own test directory и удаляется. Native executor, actual
P04 runtime/snapshot closure under GC/SQL faults, live restore/protected external
export, physical codec/device proofs не запускались: это отдельные COORD gates.

## Фактический результат этой lane

- После focused checks и correctness review полный `:shared:jvmTest` —
  **123/123**, failures/errors/skipped=0 по XML: прежние **105** (LINKA28 +
  остальные77) + BackupCodec15 + shared ZIP profiles3.
- `:shared:compileKotlinIosSimulatorArm64` и
  `:shared:compileTestKotlinIosSimulatorArm64` — **PASS**. Это compile evidence,
  не запуск Native executor.
- Повторно исполненный pure Node oracle — **PASS** на всех четырёх actual
  LINKA writer ZIP после engine refactor: strict flat2.0, standard3.0, uniform1×1
  import/edit/sparse visibility, recording metadata/bytes, compact padding.
- Нет новых dependencies, root/native/ports/domain/BackupManifest edits,
  лицензий/SDK/signing/device/CI/push операций. Исходный peer не изменён.

Oracle воспроизводится после JVM tests:

```sh
python3 -c 'import json,xml.etree.ElementTree as E; r=E.parse("shared/build/test-results/jvmTest/TEST-su.linka.pictures.portability.linka.LinkaCodecTest.xml").getroot(); s=r.findtext("system-out", "").splitlines(); markers={"writer":"P05_WRITER_ZIP_HEX=","sparse":"P05_SPARSE_ZIP_HEX=","edited":"P05_EDITED_ZIP_HEX=","holes":"P05_HOLES_ZIP_HEX="}; print(json.dumps({k:next(x.split("=",1)[1] for x in s if x.startswith(v)) for k,v in markers.items()}))' \
  | /Users/aacidov/.nvm/versions/node/v22.22.2/bin/node \
    shared/src/commonTest/kotlin/su/linka/pictures/portability/linka/peer_oracle.cjs
```
