# Core → backup → restore → recovery: synthetic integration

Base `152cbb9b06f5fafcdbff86071224791bb4744cd2`, branch
`implementation/p14-core-integration`, единственный write-root:
`/Users/aacidov/.local/share/opencode/worktree/67846f/pokazhi-p14-core-integration`.

**Test-only prerequisite, не полный P14/G4 и не product restore workflow.**
Production, frozen ports/models, Gradle, decoder policies не изменены. Test:
`shared/src/commonTest/kotlin/su/linka/pictures/integration/CoreBackupIntegrationTest.kt`;
own file/inspection helpers — `CoreBackupFiles.kt` в том же каталоге.

## Что реально исполнено

Один joint happy-flow использует **actual BundledSQLiteDriver, FileSystem.SYSTEM,
StorageRuntime, MediaStore, LibraryStore, SnapshotReader/Builder, BackupCodec и
shared checked ZIP engine**. Нет fake library/media/snapshot builder, no-op
storage или mock reopen.

1. Создаётся `SYSTEM_TEMPORARY_DIRECTORY/linka-core-backup-<UUID>` с mustCreate;
   внутри отдельные fresh source/target/files roots. Только synthetic bytes,
   `image/test`, `audio/test`, raw unreadable ARCHIVE. Реальный prepare проверяется
   по SHA256/byteCount. Source содержит published revision2 с image+recording+Tts,
   persisted draft version2 с ready image/recording form roots, retained revision7,
   settings с presentation/contrast/scale/locale и decomposed `café` в текстах.
2. Отдельные CURRENT_MESSAGE_ONLY_MARKER и image находятся только в StoredMessage
   epoch0/version1. Harmless credential sentinel — sibling вне source/target
   library roots, не настоящие credentials. Все creation leases закрываются после
   persistent refs; реальный source GC не удаляет ни одного rooted asset.
3. Actual snapshot экспортируется BackupCodec.write. Caller reader после write
   повторно читается, его lease допускает независимый retain. Source listing,
   revisions/boards/draft/settings/message не меняются. Actual ZIP entries и
   manifest проверяются: ровно пять transitive asset roots; message-only ID,
   marker, sentinel и test filesystem paths отсутствуют во всех payloads.
4. Target заранее содержит другую доску с собственным image, другие settings,
   message и один orphan asset. BackupCodec.read возвращает real sealed reader;
   counts/revision/settings/IDs/versions/form/Tts/assets совпадают с source,
   live target listing/message/data пока неизменны, input ZIP bytes сохранены.
5. Actual replaceLibrary вызывается только для synthetic target с захваченными
   expected library revision/message stamp и явно test-only Completed guard.
   Guard реально проверяется; epoch возрастает, message пуст, source data точно
   установлены. Old target становится recovery. Старые epoch message/permit
   получают STALE_REQUEST без изменения уже восстановленного состояния.
6. Sealed reader закрывается до GC: current refs и recovery, не staging lease,
   удерживают media. Target GC удаляет ровно orphan, не restored/recovery roots;
   message-only asset не появился в target. Source message-only asset живёт после
   закрытия snapshot до explicit clear; после clear version2 source GC удаляет
   ровно его. Source library data/revision и остальные bytes остаются прежними.
7. После закрытия readers/leases target runtime реально закрывается и снова
   открывается обычным StorageRuntime.open с actual existing-schema SAFE probe.
   Authoritative data, message epoch, recovery metadata, hashes/bytes всех current
   и recovery media сохранены.
8. Actual returnToRecovery с новым stamp/revision/permit возвращает старую цель,
   снова повышает epoch и очищает message. Imported library становится единственным
   новым recovery: новый ID/counts/assetBytes, старый ID отвергается CONFLICT.
   Старые epoch снова не могут оживить message/rename. GC сохраняет media обеих
   сторон. Sentinel и exported ZIP остаются byte-for-byte.

## Реальное и намеренно fake

- File port работает с реальными Source/Sink/TemporaryFile. Все физические names
  — fresh UUID с mustCreate, не пользовательские имена. Перед source/sink/discard
  проверяются membership в owned set, direct canonical parent filesRoot и symlink
  metadata. Pick/save бросают ошибку: внешнего export/provider/UI нет.
- Единственный media fake — MediaInspectionPort: читает реальный owned file,
  проверяет synthetic prefix и возвращает tiny 2×3 ImageInfo / 40ms AudioInfo
  с test MIME. Две image и две audio roots инспектируются в обе стороны; ARCHIVE
  и message-only image не декодируются. Это **не native codec/decoder proof**.
- WritePermit guard всегда Completed только по явному test-only заданию; это
  проверка участия настоящей транзакции, **не P08 authorization proof**.
- Никаких user data, app startup, credentials store, device permissions, install,
  platform logs, native executor, SDK/deps/CI/push операций.

## Владение и teardown

Read-only проверочные snapshots/acquire leases закрываются в finally собственных
helpers. Caller snapshot и imported reader явно закрыты до соответствующих GC и
runtime reopen. Все prepared leases/readers дополнительно входят в idempotent
ownership ledger. File port учитывает открытые FS handles и свои temporaries:
после codec операций остаётся только caller-owned exported ZIP, без partial files;
после его discard — ни одного.

Outer finally пытается закрыть все readers/leases, все runtime instances и file
handles/temporaries прежде, чем удалить **только fresh mustCreate UUID root**.
При ошибке close root не удаляется; cleanup failure не маскирует primary failure.
Родительские/system/unknown probe directories не удаляются. P04 rollback/fault
matrix не дублируется.

## Воспроизводимость и ограничения

Focused command:

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
ANDROID_HOME=/Users/aacidov/Library/Android/sdk \
DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer \
./gradlew :shared:jvmTest --tests '*CoreBackup*' --console=plain
```

Первый focused red был ошибкой test expectation NOT_FOUND: actual StoredMedia
для отсутствующего asset возвращает MISSING_ASSET. Исправлены только assertions,
production не менялся. После этого полный joint focused flow прошёл.

Первый общий gate подтвердил JVM, но Native compile выявил непереносимый
ForwardingSink в новом test-helper. Заменён обычной common `Sink by delegate`,
без production/Gradle/dependency изменений. Перепроверены оба затронутых checks.

Финальный результат по XML: **214/214 JVM**, failures/errors/skipped=0:
прежние **213** (прочие131 common tests +82 StorageRuntime tests) и **1** новый actual
joint integration test. `:shared:compileTestKotlinIosSimulatorArm64` — **PASS**
(Native main compilation также прошла как prerequisite, не runtime execution).
Focused `--tests '*CoreBackup*'` — **1/1 PASS**. Никакой production-дефект не
обнаружен/не исправлялся; проверяемый flow не ослаблен ради green.

Final gate:

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
ANDROID_HOME=/Users/aacidov/Library/Android/sdk \
DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer \
./gradlew :shared:jvmTest :shared:compileTestKotlinIosSimulatorArm64 --console=plain
```

Common test не использует JVM-only типов; Native compilation отдельно от execution.
Actual Native executor остаётся COORD после integration. Этот test не заменяет
physical codec/device/UI, protected external export/restore, P08/P09 migration,
G4/user-data safety и release acceptance. Existing deflate canonicality limitation
и native allocation/inspection gates не становятся PASS.
