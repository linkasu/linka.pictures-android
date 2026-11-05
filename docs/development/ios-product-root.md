# R2 — iOS product root

Ветка `implementation/r2-ios-product-root`, база `036b102`. Собственный lease:
новые `shared/src/iosMain/.../bootstrap/**`, `shared/src/iosTest/.../bootstrap/**`,
`iosApp/main.swift`, `iosApp/Info.plist`, этот документ. Xcode project, Gradle,
CI, access/ports/native implementations и common UI не изменены.

Dependency-only cherry-picks (не собственные root changes):
`92ce9de` → `731062a`, `52f37df` → `542a7bc`, `bbba81d` → `87a019b`,
`8fdb5ce` → `1d76f82`. Pending P13 navigation correction интегрирует COORD отдельно.
Public `LinkaApp` seam сохранён.

## Runtime и владение

`MainViewControllerKt.MainViewController()` — public Kotlin → Swift factory.
Swift оставляет существующий UIKit app-delegate/window lifecycle, product controls
рисуются только общим `LinkaApp`. Deployment target 17.0, bundle/team identifiers
и Data Protection не изменены.

Процесс владеет одним `IosProductRoot`, `SupervisorJob`/Main scope, одним
`StorageRuntime` с `BundledSQLiteDriver`, одним `AudioCoordinator` и одним текущим
`CommunicationSession`. Каждое окно имеет отдельный scope, native window identity
и отдельный thumbnail-demand key. UI owners регистрируются до работы; thread-safe
`OwnerWindows` связывает owner только с его окном. Root хранит weak native references,
не создавая retain cycle singleton → controller/window → composition → root.
Native presenter не выбирает
произвольное key window. UIKit queries выполняются на Main; worker admission читает
синхронизированный snapshot actual UIKit lifecycle, не сохранённый UI boolean.

Предопределённый root: canonical app-private `Application Support/LINKa/library-v1`.
Canonical media: `root/media`. Native temporary files находятся в отдельном
canonical `Caches/LINKa/<NSUUID>`: must-create reservation, не библиотека. Порядок
binding: files → audio decoder → media inspector → native backend → coordinator →
communication. `IosImagePreparationPort(files, inspection)` настоящий. IDs — NSUUID.
Язык для audio preflight берётся из cached settings StateFlow, без SQL в callback;
cache обновляется после реального listing revision/epoch и до создания session
после restore. Ошибки чтения settings не подменяются новым default.

## Fail-closed startup

До открытия SQLite читается настоящий `KeychainCredentialStore`.
Любой native error/unknown сохраняет реальный `Problem`/systemCode и показывает
Compose error view; file credentials, fake Unprotected и повторное подписание
не используются. Existing library root + missing record — `INTEGRITY_FAILURE`.
Только отсутствующий root и подтверждённый `read(null)` допускают
`LegacyCredentialImporter.prepare(false, null, root-liveness guard)`, затем
`LocalAssistantAccess.open` и `refreshPrerequisites`. Guard использует owned job и
thread-safe closing StateFlow. Root не читает пользовательский legacy store.

Loading/error показываются до `LinkaApp`. Автоматического retry, очистки данных,
отключения защиты и открытия второго writer нет. После ошибки — исправление причины
и явный перезапуск процесса, не фальшивое «готово». Позднее создание controller в
уже failed процессе тоже показывает тот же error, не создаёт новый runtime.

## Native lifecycle и prompts

App/scene real background сначала синхронно отзывает access и прерывает audio,
затем публикует `isForeground=false`, затем отменяет собственные native requests.
Inactive/deactivation не изображается real background. Actual app activation и
scene activation обновляют admission и вызывают owner-specific
`IosDeviceOwnerVerifier.onForeground(owner)`. LAContext получает late-bound
`LocalAssistantAccess.isVerificationCurrent` и actual owner-window foreground.

Собственные picker/LA/microphone presentations вызывают
`suspendForSystemPrompt`, отбрасывая старые capabilities/guards, но не уничтожая
частный pending OS context через UI Background. `OwnedFiles` отслеживает только
свои picker jobs и export destinations; owner close/scene disconnect сначала
закрывают admission, потом отзывают access/handles, потом window scope. Window hidden
отзывает admission/handles/audio, но не уничтожает scope временно скрытого окна.
Поздний отменённый picker result уничтожается, а не публикуется как URI.

Микрофон запрашивается только из explicit recording через настоящий
`AVAudioSession.recordPermission/requestRecordPermission`. Already granted не
запускает prompt и не отзывает доступ; denied — `PERMISSION_DENIED`. `RecordingOwners`
переносит настоящий request owner через native RequestId-only callback. Stop,
interrupt и superseding audio operation синхронно инвалидируют callback; ответы
проверяют job, request identity и actual window/app foreground на Main.
Usage descriptions микрофона/Face ID имеют конкретные пользовательские причины.
Ни startup, ни тесты root policy не запрашивают разрешения.

## Restore и release

`onLibraryReplaced(epoch)` проверяет настоящий committed listing epoch, отзывает
access/interrupt audio, закрывает старую communication session и ожидает завершения
её owned child jobs, затем создаёт новую на том же StorageRuntime и публикует её
через StateFlow. Старые undo/session/speech не переигрываются. Повторные старые
notifications игнорируются; неожиданный reload failure становится root error и
требует restart, не исключением из UI notification с потерянным epoch.

Root release отдельно владеет cleanup scope: пытается закрыть session, cancel/join
все runtime workers, закрыть SQLite и удалить только собственный UUID cache.
Открытие нового writer поверх незакрытого старого не предусмотрено. iOS process
termination может не дать завершиться async cleanup: это не доказательство graceful
OS termination и не обещание нового background execution entitlement.

## Thumbnails и pending dependency

Root использует **один common `ui.images.ThumbnailLoader` COORD**, а не свой cache.
Составленный UI передаёт demand отдельно для каждого окна; remove вызывается при
его уничтожении. Настоящий loader problem виден над UI, ошибка не становится Loaded.

`IosThumbnailDecoder` сначала вызывает настоящий image inspector, затем ImageIO
thumbnail API с maxSide 1…256 и immediate cache/pixel decode. Actual dimensions
проверяются; CoreGraphics принудительно рисует RGBA pixels, Skia получает реальный
ARGB8888-compatible display bitmap. Raw RGBA ≤256 KiB, без full-original UIImage,
64-MiB NSData или PNG-перекодирования canonical media. Все CF/CG handles освобождаются
в finally. Native canonical file никогда не модифицируется.

На момент предварительных проверок common helper ещё WIP в основной рабочей копии;
он подключён **read-only** временным Gradle init script вне репозитория:
`/private/var/folders/l4/r3plyjwj7pz_nq7zz50x04880000gn/T/opencode/r2-thumbnail-source.gradle`.
SHA256 inspected helper: `2a3e852dbc3200afe307c089b171f714c27e40f492e0d92603df4aedccd398e2`.
Для интеграции COORD обязан сначала принять свой helper commit и повторить обычные
compile/link без init script. В эту ветку helper не скопирован и не закоммичен.

## Нерешённые внешние gates

- **Incoming document COORD gap:** нынешний PlatformFilesPort предоставляет explicit
  picker, но не root-owned pending inbound URL adoption/token после openURL. Поэтому
  нет document-type/openURL advertisement, unsafe автокопирования или автоимпорта.
  Нужен отдельный согласованный common/native owned-inbound seam и explicit UI import.
- Parent интегрирует P13 navigation cleanup correction и visible-demand fixes
  communication/editor; root не угадывает assets всех страниц.
- Positive Keychain/LA/Face ID/microphone/picker prompts, normal app install/run,
  real store signing, production identifiers/entitlements и physical device gates
  этим diff не доказаны. Identity/profile/certificate/AMFI/permission settings не менялись.
- App-host Keychain остаётся отдельным заблокированным gate при отсутствии разрешённой
  Apple Development identity; unsigned compile не является app-host runtime proof.

## Проверки

Frozen implementation: native/root/tests `9be7e7f`, Swift/plist `5a72b3f`.
Final gate с тем же read-only helper — **PASS**:

```sh
./gradlew :shared:linkDebugTestIosSimulatorArm64 \
  :shared:linkDebugFrameworkIosSimulatorArm64 :shared:linkDebugFrameworkIosArm64 \
  -I /private/var/folders/l4/r3plyjwj7pz_nq7zz50x04880000gn/T/opencode/r2-thumbnail-source.gradle \
  --console=plain

xcrun simctl spawn DD0F9A3F-DC32-4373-BA08-400FECE3A1CC \
  "$PWD/shared/build/bin/iosSimulatorArm64/debugTest/test.kexe" \
  '--ktest_filter=su.linka.pictures.bootstrap.*'

xcodebuild -project iosApp/LinkaPokazhi.xcodeproj -target LinkaPokazhi \
  -configuration Debug -sdk iphonesimulator -arch arm64 \
  CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO \
  SYMROOT="$PWD/build/r2-ios-simulator" build
xcodebuild -project iosApp/LinkaPokazhi.xcodeproj -target LinkaPokazhi \
  -configuration Debug -sdk iphoneos -arch arm64 \
  CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO \
  SYMROOT="$PWD/build/r2-ios-device" build
```

Both Native main compilations, test link и оба frameworks: `BUILD SUCCESSFUL`, 2m10s.
Owned booted `linka_platform_probe_ios17`, iOS17.0,
UDID `DD0F9A3F-DC32-4373-BA08-400FECE3A1CC`: **8/8 PASS, 122ms**, без skip.
Test executable SHA256:
`21418a27c05afd591d9d3524dec1bf5e596b0df99fea2fe364394bfd280b2f72`.
Это public standalone test executable, не installation/launch normal app.

Simulator/device unsigned apps: **BUILD SUCCEEDED**. SDK27.2, target остаётся17.0.
Simulator linker предупредил о dependency `libicu.icudtl_dat.o`, собранном для17.2;
deployment target не повышался, dependency не переписывалась. Это отдельная
проверка dependency provenance/minimum-runtime перед store release, не разрешение
скрыть warning или заявить полный product runtime на17.0. AppIntents metadata
warning на обеих платформах — отсутствует AppIntents dependency.

Final Native log: OpenCode managed shell `sh_1ad3a56bf001Bm4ZViFdVDPCFd.out`.
Xcode raw logs в `$HOME/Library/Application Support/rtk/tee/`:
`1793907798_xcodebui_9c3a5f.log` (simulator),
`1793907798_xcodebui_c818d4.log` (device).
`plutil -lint iosApp/Info.plist` и `git diff --check` — PASS.
Default `iosSimulatorArm64Test`/`allTests` не запускались.

Новые focused tests (только bootstrap): root credential admission (3), owner-window
isolation/close (1), cancelled picker delivery (1), revoked microphone callback без
AVAudioSession/prompt (1), actual owned PNG/thumbnail bounds/ARGB/source SHA и invalid
limit before IO (2). Они не вызывают настоящий Keychain и не открывают OS prompts.
Первая focused попытка была прервана только у своего test executable: standalone
runner не обслуживает UIKit Main queue. В callback-negative test Main заменён
test dispatcher, затем **все8** повторены успешно. Production dispatcher не менялся;
predicate запрещает в этом negative test даже обращение к UIKit/AVAudioSession.
