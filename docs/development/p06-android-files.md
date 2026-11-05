# Android native files handoff — transfer prerequisite / blocker

Новая ветка `implementation/p06-android-files` создана от clean **`219d46b6dcf8683fb15cdcccd0b1ad936c4a4b9c`** в собственном worktree. Старые 54/54 и 63/63 suites не повторялись; image/dref commits не amend/reset. AndroidPlatformFilesPort **пока не реализован**: ниже только transfer verification и конкретный native environment blocker перед новым runtime scope.

## Full boot после переноса

Read-only проверены ровно два owned ini:

- `/Volumes/data/developer-state/android-avd/linka_platform_probe_api21_arm64.ini` → `/Volumes/data/developer-state/android-avd/linka_platform_probe_api21_arm64.avd`.
- `/Volumes/data/developer-state/android-avd/linka_audit_tablet.ini` → `/Volumes/data/developer-state/android-avd/linka_audit_tablet.avd`.

Начальный MCP adb.list_devices был пустой; localhost5554/5555/5556/5557 свободны. Запущен существующий `/Volumes/data/developer-state/android-sdk/emulator/emulator` (37.2.12) только с `-avd <owned> -port {5554,5556} -no-snapshot-load -no-snapshot-save`, environment ANDROID_HOME/ANDROID_SDK_ROOT=`/Volumes/data/developer-state/android-sdk`, ANDROID_AVD_HOME=`/Volumes/data/developer-state/android-avd`. Никакого wipe/reset/config edit/snapshot deletion/новых grants. bproxy/другие AVD/ARMv7 не запускались.

Boot completion проверялся MCP по sys.boot_completed, без фиксированного sleep: API21 готов при первой проверке, API35 сначала пустой property, затем **1** при следующем; emulator log подтвердил API35 full boot за45.583s. Явные serial/owner/getprop результаты:

| Serial | AVD | sys.boot_completed | SDK / ABI |
|---|---|---|---|
| emulator-5554 | linka_platform_probe_api21_arm64 | 1 | 21 / arm64-v8a |
| emulator-5556 | linka_audit_tablet | 1 | 35 / arm64-v8a |

После boot выполнены только read-only owner/SDK/ABI и `pm path su.linka.pictures.shared.test`, затем MCP pull уже установленного test APK (не private app data). Обе копии **16,144,120 bytes**, SHA256 **`1c9d835cdcd98ffedcd853ded375445c659f8206c35d68599dec30c7d4f0faae`**, совпадают с прежним frozen dref test APK. Копии сохранены в approved temp `/private/var/folders/l4/r3plyjwj7pz_nq7zz50x04880000gn/T/opencode/p06-transfer-dd02be92-dbcc-4d07-93ab-08975f6964f0/api{21,35}-installed-test.apk`. Это transfer/data-preservation proof, **не новая полная native acceptance**. Normal app/credentials/library/private userdata не читались, APK не переустанавливались, instrumentation не запускалась.

## Конкретный blocker

Уже после sys.boot_completed=1 и успешного чтения установленного APK API35 host emulator завершился **SIGABRT**. Native stdout содержит `ERROR Failed to find ColorBuffer: 27`, `Failed to bind to texture: invalid color buffer`, после completed boot — `Netsim ... Stream removed` и `Killed by SIGABRT`. Причину abort не объявляем доказанной только по этим соседним строкам; наблюдаемый факт — native process crashed. Полный managed host log: `/Users/aacidov/.local/share/opencode/shell/67846f12c6312a5d2a234ce99442f75d17977218/sh_1ab95ada60016QfS17quv6ooIT.out` (не копируется целиком: там нерелевантные SDK boot properties/public-key diagnostics).

Повторный MCP list_devices видит только **emulator-5554**. API35 не перезапускался автоматически; renderer/GPU/AVD settings/locks/permissions не менялись, native prompts не подтверждались. Для обязательного focused files runtime на обоих AVD нужно COORD решение по восстановлению API35/допустимому обычному restart, либо отдельный handoff host-compile-only до стабильного device. Не маскируем это новым набором emulator flags или synthetic SAF PASS.

Файлы production/ports/root/manifest/access/migration не менялись. Новый files adapter и provider tests не пишутся до возвращения этого platform blocker primary; фактов SAF/device acceptance нет. Старые native suites остаются валидной сохранённой историей.

## Accepted conditional continuation: AndroidPlatformFilesPort

После принятия transfer proof primary явно разрешил продолжить implementation/compile и focused runtime **только на стабильном API21**, оставив API35 crash diagnosis себе. Первоначальный blocker выше — история этапа; теперь adapter реализован, API35 новый runtime gate по-прежнему **OPEN**, не PASS матрицы.

### Scope и contracts

Новые production файлы только `platform/files/{AndroidPlatformFilesPort,OwnedFileHandles,NativeFileRules}.kt`, собственные `platform/files/{AndroidPlatformFilesPortTest,FilesTestRig}.kt` + это evidence. Frozen constructor: `(Context, temporaryRoot: Path, mediaRoot: Path, FileSystem, requestDocument: suspend (Intent,RequestId,String)->OperationResult<Uri>, isOwnerActive:(String)->Boolean, CoroutineScope)`. Context только applicationContext, presenter callback только Main; root callback владеет current request/foreground/cancel-dismiss своего ID. Adapter не AssistantAccess и не реализация ActivityResult shell. Старые root/ports/manifest/Access/migration/audio/image/dref не менялись, main не вливался.

- Pick: ACTION_OPEN_DOCUMENT/CATEGORY_OPENABLE, IMAGE=image/*, AUDIO=audio/*; legacy ZIP LINKA/BACKUP — type*/* с EXTRA_MIME_TYPES application/zip + application/octet-stream (не broad storage grant). Только transient read flag, нет persistable permission/takePersistableUriPermission. Только content URI с authority; file/http/resource fallback отсутствует. OpenableColumns cursor закрывается; filename/MIME валидируются как passive display hints, никогда не становятся filesystem path. Неподдерживаемые/опасные leaf/MIME/UTF16/NUL отвергаются, не trim/rename.
- Copy фактических bytes в собственный must-create UUID temp потоково8KiB, независимо от provider SIZE metadata. Actual caps IMAGE/AUDIO64MiB, LINKA500MiB, BACKUP2GiB Long. IO/cursor/streams закрываются на всех штатных terminal paths; late/cancel/background result не передаётся. Последующий media/codec/backup validation остаётся у consumer, picker не объявляет arbitrary bytes валидным image/archive.
- Sync create/source/sink и Source/Sink операции имеют worker-caller contract, Main отвергается без blocking dispatch. Registry ключуется **identity TemporaryFile**, не строкой path/label. Source разрешён только зарегистрированному temp либо regular immutable asset `<mediaRoot>/<shaPrefix2>/<lowercaseSHA256>`; DB/credential/foreign/temp-path aliases и symlinks не читаются. Sink/discard только own registered temp; sink один раз, открывается non-truncating FileHandle, фактический size0 проверяется до записи. Existing nonempty не затирается; foreign identity не даёт доступ даже с тем же path.
- Discard **ANY thread**: на caller синхронно только logical invalidation/map removal + conflated wake. Source(open) сразу NOT_FOUND, физическое закрытие собственных streams/unlink выполняет один owned IO cleanup worker. Никакого GlobalScope/main-thread unlink; parent cancellation закрывает admission, инвалидирует registry и дренирует собственный cleanup в NonCancellable IO. Delete failure не оживляет handle и не убивает cleanup следующего; orphan physical recovery best-effort, не обещание удаления после OS ошибки. Sink/Source close защищены от двойного concurrent close.
- chooseDestination: ACTION_CREATE_DOCUMENT с исходным leaf title/type, transient flags, только URI handle — **никаких openOutputStream/content writes**. Создание пустого документа выполняет root-owned system chooser; adapter его contents не трогает до write. URI не persisted.
- Destination one-use FSM NEW→WRITING→TERMINAL/CLOSED, close идемпотентный и отменяет свой write job. Owner/job/root/guard проверяются до private source open и непосредственно **перед openOutputStream(uri,"wt")**. Private source валидируется/открывается и известный oversized source отказывается до irreversible external truncate. Далее fresh guard/live checks до/после bounded reads и перед chunks, cap2GiBLong. Completed только после flush/close и последнего authorization check. После open ошибка/cancel/revocation могут оставить **partial или уже полный** provider document; URI не удаляется, rollback не заявляется, слепой повтор того же destination запрещён.
- Coroutine cancellation и недоставленный Completed result закрывают/discard только свои handles. Resume cleanup допускается на Main, не блокирует FS. Primary Failed/Cancelled/UnconfirmedCommit не подменяется обычной cleanup ошибкой; Unknown — защитная ветка, не штатный результат SAF reads. Реальный unresponsive provider/OS IO не становится обещанием forcible thread termination: checks между chunks, root presenter обязан корректно завершать собственные cancelled UI requests.

### Checks и actual API21 evidence

Focused compile перед coherent-diff review прошёл с `JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home`, **ANDROID_HOME=/Volumes/data/developer-state/android-sdk**, прежним DEVELOPER_DIR. После review `:shared:compileDebugKotlinAndroid :shared:assembleDebugAndroidTest` — **PASS** (3s), compiled total80 = сохранённые63 + новые17. Старые63/54 не перезапускались, common/iOS не инвалидированы adapter diff и не повторялись.

Первый focused run13/13 PASS. Расширенный17-case run дал16 PASS/1 FAIL: test setup использовал `JvmSystemFileSystem.createSymlink`, не поддержанный Okio на API21. Не пропущено: fixture создаёт свою symlink публичным API21 android.system.Os.symlink; production path guard не ослаблен. После этого **17/17 PASS (0.269s), 0 failures/skips/assumptions** на owner-checked emulator5554/linka_platform_probe_api21_arm64/SDK21/arm64-v8a. Полные [initial13](evidence/p06-android-files-2026-11-05/initial-13-api21.txt), [red17](evidence/p06-android-files-2026-11-05/red-17-api21.txt), [green17](evidence/p06-android-files-2026-11-05/green-17-api21.txt), [per-case results](evidence/p06-android-files-2026-11-05/results.json) сохранены и прочитаны.

Tests используют actual own cache FS, path/canonicalization, Android cursor/PFD close callbacks и fake provider/requester, без grants. Для API21 публичный framework `android.test.mock.MockContentResolver` создан reflection только потому, что его stub в optional SDK jar (уже имеющаяся android.test.runner library); hidden API/deps/manifest не добавлены. API29+ test helper использует guarded ContentResolver.wrap. Это **не SAF picker/provider UI/permission acceptance**. Thread hook запрещает physical FS на Main; covered17: leaf/MIME/nativeUTF; registry identity; nonempty preservation; immutable/media/credential/symlink sandbox; Main discard immediate NOT_FOUND + async unlink; all kinds/actual64MiB cap; cursor/stream closure; cancel/background/late/undelivered callbacks; source/guard-before-truncate; one-use/close during open; chunk revocation/partial no-delete; oversized preflight; provider errors; failed unlink и subsequent cleanup. 500MiB/2GiB constants/Long preflight проверены, multi-GiB provider throughput не заявляется.

Final frozen test APK `/private/var/folders/l4/r3plyjwj7pz_nq7zz50x04880000gn/T/opencode/p06-files-green-26b159a3-a12e-49d4-a7cc-2238d55d99da/frozen-files-test.apk`, **SHA256 `0ae8a430a7a3b5c95ca1875c572df11200d6e19783800657c3f28f92cfc352f3`**. Actual aapt self-target su.linka.pictures.shared.test/min21/target34, no RECORD_AUDIO/FirebaseInitProvider, analytics disabled; [freeze/manifest/badging](evidence/p06-android-files-2026-11-05/). MCP install только test APK и `am instrument -w -r -e class ...platform.files.AndroidPlatformFilesPortTest`, без normal-app install/grants/settings/network actions. API35 **compile-only/runtime OPEN**, никаких его рестартов/renderer/lock/crash investigations в continuation. Real SAF UI, physical providers, foreground Activity binding, owner verification/migration/user data/release gates остаются у COORD.

### Точные уже полученные launch-tool факты для PRIMARY (без нового исследования)

Оба `functions.shell` launch вызваны с **background=true, timeout не передавался** (не0, не shell timeout); workdir `/Users/aacidov/.local/share/opencode/worktree/67846f/pokazhi-p06-android-audio`. Command prefix точно `env ANDROID_HOME=/Volumes/data/developer-state/android-sdk ANDROID_SDK_ROOT=/Volumes/data/developer-state/android-sdk ANDROID_AVD_HOME=/Volumes/data/developer-state/android-avd /Volumes/data/developer-state/android-sdk/emulator/emulator`, затем API21 `-avd linka_platform_probe_api21_arm64 -port 5554` / API35 `-avd linka_audit_tablet -port 5556`, оба с `-no-snapshot-load -no-snapshot-save`. Ни nohup/disown, ни иного timeout wrapper не было.

Managed output paths **как были возвращены tool response**, без утверждения текущего существования после restart:

- API21: `/Users/aacidov/.local/share/opencode/shell/67846f12c6312a5d2a234ce99442f75d17977218/sh_1ab95ad10001AJSQ4o8l0QEe70.out`.
- API35: `/Users/aacidov/.local/share/opencode/shell/67846f12c6312a5d2a234ce99442f75d17977218/sh_1ab95ada60016QfS17quv6ooIT.out`.

Launch response PID не сообщал. Только уже пришедший позднее API35 stdout содержал `detection is enabled for process: 85100`; PID API21 не зафиксирован. Native logs не перечитывались/не искались, hypothesized timeout/host-lifecycle/GPU причины не выбирались; PRIMARY расследует сам. Эти факты переданы в commentary ещё до files completion.
