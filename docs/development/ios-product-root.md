# R2 — iOS product root

Ветка `implementation/r2-ios-product-root`, база `036b102`. Собственный lease:
новые `shared/src/iosMain/.../bootstrap/**`, `shared/src/iosTest/.../bootstrap/**`,
`iosApp/main.swift`, `iosApp/Info.plist`, этот документ. Xcode project, Gradle,
CI, access/ports/native implementations и common UI не изменены.

Dependency-only cherry-picks (не собственные root changes):
`92ce9de` → `731062a`, `52f37df` → `542a7bc`, `bbba81d` → `87a019b`,
`8fdb5ce` → `1d76f82`. Correction dependencies:
`7c1cb7e` → `af87623`, `82e852e` → `fd24b24`, `f8032ec` → `c0b450b`,
`55e94b6` → `4754644`. Их Android/evidence файлы не являются собственным iOS diff.
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
Snapshot берёт current UIView.window либо cached weak window **только того же root
window token**: fullscreen own modal может временно отсоединить presenting view.
Actual app/scene activation и hidden всё равно проверяются. Inactive не background;
foreign current window не принимается, close/disconnect очищают cache/admission.

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

Loading/startup error показываются до `LinkaApp`. До первого Ready ошибка закрывает
непринятый runtime. После Ready ошибка **не уничтожает graph/session/window scopes**:
настоящий `LocalAssistantAccess.block(problem)` синхронно закрывает gate, root отменяет
свои files/audio и показывает latched banner. Та же communication session и mounted
editor остаются видимыми; read-only слой перехватывает pointer/keyboard и скрывает
accessibility actions, сохраняя доступное чтение существующей фразы. Actual foreground
не подменяется background из-за ошибки. Root-owned exception handler также доставляет
ошибки cancellation/completion handlers на Main в этот же fail-closed путь.
Автоматического retry, отключения защиты и второго writer нет.

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

`onLibraryReplaced(epoch)` проверяет настоящий committed listing epoch. Root немедленно
скрывает старый context, закрывает protected/native/audio admission, читает новые
settings и готовит candidate session **до** разрушения старой. Публикует candidate
только после фактического SAVED, проверки stamp.epoch/current listing epoch и
закрытия/cancel/join старой session. До этого RootAssistantAccess отвергает новые
commands/guards; guard, созданный в blocked phase, не становится валидным позже.
Это тот же StorageRuntime, не новый writer. Старые undo/session/speech не replay.
При подтверждённом replacement и reload failure баннер явно говорит: «Библиотека
заменена, прежнее сообщение очищено. Требуется перезапуск»; старый context не
выдаётся за новый и commit не описывается как rollback. Поздний confirmed commit
инвалидирует старую фразу даже после ранее latched error, но не запускает auto retry.

Root release отдельно владеет cleanup scope. `CleanupAttempts` сначала закрывает
admission/revokes access, затем независимо пытается stop/revoke каждого native handle,
отменить каждого child, отменить/join parent, detach каждого observer, закрыть SQLite
и удалить только собственный UUID cache. Ошибка одной попытки не пропускает следующую;
первичный Problem не заменяется вторичным cleanup IO. После ошибки SQLite.close root
остаётся closed/blocked, нового writer/retry нет. `OwnedFiles` не claim/close foreign
destination: owner проверяется до создания wrapper/регистрации cleanup; revoke
пытается отменить каждый job и закрыть каждый handle, возвращая root реальный failure.
Открытие нового writer поверх незакрытого старого не предусмотрено. iOS process
termination может не дать завершиться async cleanup: это не доказательство graceful
OS termination и не обещание нового background execution entitlement.

## Thumbnails

Root использует **один common `ui.images.ThumbnailLoader` COORD**, а не свой cache.
Составленный UI передаёт demand отдельно для каждого окна; remove вызывается при
его уничтожении. Настоящий loader problem виден над UI, ошибка не становится Loaded.

`IosThumbnailDecoder` сначала вызывает настоящий image inspector, затем ImageIO
thumbnail API с maxSide 1…256 и immediate cache/pixel decode. Actual dimensions
проверяются; CoreGraphics принудительно рисует RGBA pixels, Skia получает реальный
ARGB8888-compatible display bitmap. Raw RGBA ≤256 KiB, без full-original UIImage,
64-MiB NSData или PNG-перекодирования canonical media. Все CF/CG handles освобождаются
в finally. Native canonical file никогда не модифицируется.

На момент **исторических первых** проверок common helper был WIP, подключался read-only:
`/private/var/folders/l4/r3plyjwj7pz_nq7zz50x04880000gn/T/opencode/r2-thumbnail-source.gradle`.
SHA256 inspected helper: `2a3e852dbc3200afe307c089b171f714c27e40f492e0d92603df4aedccd398e2`.
Теперь принят реальный dependency `82e852e`; обычные main/framework build не нуждаются
в init script или чужих source directories. Helper не дублируется. `9f3fa7b` добавляет
embedded стандартный 2×2 PNG: верх RED/GREEN, низ BLUE/WHITE. `readPixels` проверяет
точные ARGB/topology, source SHA, config и dimensions. На owned iOS17: **3/3 raster
tests PASS,251ms**. Vertical flip/transpose не обнаружены; production CTM/bridge не
менялись, JPEG EXIF thumbnail transform сохранён.

## Нерешённые внешние gates

- **Incoming document COORD gap:** нынешний PlatformFilesPort предоставляет explicit
  picker, но не root-owned pending inbound URL adoption/token после openURL. Поэтому
  нет document-type/openURL advertisement, unsafe автокопирования или автоимпорта.
  Нужен отдельный согласованный common/native owned-inbound seam и explicit UI import.
- P13 cleanup/browse/readiness dependencies приняты; parent интегрирует visible-demand fixes
  communication/editor; root не угадывает assets всех страниц.
- Positive Keychain/LA/Face ID/microphone/picker prompts, normal app install/run,
  real store signing, production identifiers/entitlements и physical device gates
  этим diff не доказаны. Identity/profile/certificate/AMFI/permission settings не менялись.
- App-host Keychain остаётся отдельным заблокированным gate при отсутствии разрешённой
  Apple Development identity; unsigned compile не является app-host runtime proof.

## Историческая базовая проверка (до correction review)

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

## Review corrections — отдельные коммиты, без amend baseline

- `9f3fa7b`: реальный asymmetric PNG pixel proof, не только dimensions/SHA.
- `16f7137`: frozen root lifecycle/failure/cleanup и ownership correction, без amend
  `9be7e7f`/`5a72b3f`/`1212d8b`.
- Modal snapshot, post-Ready preservation, latched concrete access block, candidate
  restore preparation и independent cleanup описаны выше. Pure policy regression
  отдельно проверяет startup vs Ready error, поздний confirmed epoch после error,
  отсутствие auto retry, запрет guard creation во время replacement, сохранение
  primary Problem и все cleanup attempts после controlled throws.
- Foreign native export destination отвергается `UNAUTHORIZED` до root registration;
  neither revoke(A) nor revoke(B) его не закрывает. Два своих destinations получают
  оба close attempts даже при throw первого; IO failure возвращается root.
- Native modal runtime/signing/app-host/prompt gates остаются **OPEN/BLOCKED**, не
  доказаны policy tests. ICU warning здесь повторно не исследовался.

После `82e852e` normal Native main compile больше не заблокирован `block`/thumbnail.
Но normal `linkDebugTestIosSimulatorArm64` пока заблокирован отдельным dependency
`55e94b6`: в common `TransferCoordinatorTest.kt:49,50,60,61` отсутствует `import okio.use`
(Native видит только `AutoCloseable.use`). Common test вне lease не менялся.
Для фактической focused Native проверки временный init script
`/private/var/folders/l4/r3plyjwj7pz_nq7zz50x04880000gn/T/opencode/r2-bootstrap-native-tests.gradle`
выбирает только **собственные bootstrap test sources**, без common tests и без
чужих source directories. Это не объявляется успехом normal/full test gate.
Обычные framework builds выполняются без init script.

Предварительно pure helper JVM suite: **7/7 PASS**. Actual owned17 raster suite:
**3/3 PASS,251ms**, включая точный opaque ARGB row order RED/GREEN; BLUE/WHITE.
Final correction gate (`16f7137`): **18/18 Native PASS,154ms**,8 test cases,
owned17 UDID тот же. Это исходные8 + asymmetric raster1 + foreign/throwing files2 +
snapshot2 + recovery/cleanup4 + access-admission1. Fullscreen modal остаётся policy
regression, не positive UIKit presentation proof. Тесты не вызывают настоящий
Keychain/LA/microphone/picker prompt и не запускают normal app.

Focused test executable SHA256:
`bf9cbcee85f7e52511a9c9d90c7e8975a1f2e9cd392e5103c4582ee8cd9a34f3`.
Committed common ThumbnailLoader SHA256:
`5bae8ad7d033c1dd71bc07e110313fec61a22c1e764c46f429f1748d93815a26`.

```sh
./gradlew :shared:linkDebugTestIosSimulatorArm64 \
  :shared:linkDebugFrameworkIosSimulatorArm64 :shared:linkDebugFrameworkIosArm64 \
  -I /private/var/folders/l4/r3plyjwj7pz_nq7zz50x04880000gn/T/opencode/r2-bootstrap-native-tests.gradle \
  --console=plain
xcrun simctl spawn DD0F9A3F-DC32-4373-BA08-400FECE3A1CC \
  "$PWD/shared/build/bin/iosSimulatorArm64/debugTest/test.kexe" \
  '--ktest_filter=su.linka.pictures.bootstrap.*'
./gradlew :shared:linkDebugFrameworkIosSimulatorArm64 \
  :shared:linkDebugFrameworkIosArm64 --console=plain
```

Focused compile/link: `BUILD SUCCESSFUL`,1m55s. Последняя команда **без init script**:
`BUILD SUCCESSFUL`,3s, оба Native main/framework UP-TO-DATE для frozen correction.
Оба unsigned приложения повторно `BUILD SUCCEEDED` (те же xcodebuild commands/flags,
никакого signing/install/run). Full normal Native test gate остаётся BLOCKED
отдельным common-test import, не выдан за PASS.
Managed logs: `sh_1ad576b92001kZIo3JgBTIfnBo.out` (focused build),
`sh_1ad59ca1e001RmF9UIxXQdRH4Y.out` (normal frameworks),
`sh_1ad59ca2c001h4cnSt77dNi2XV.out` (unsigned simulator),
`sh_1ad59ca3a001LxJuARJ160B9Kq.out` (unsigned device).
