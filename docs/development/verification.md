# P02 focused verification map

## Текущие gates после P08/P10 и переноса AVD

Ниже сохранена историческая P02 карта. Для текущего дерева **core Native и app-host tests — разные обязательные compartments**, не один total:

- `:shared:jvmTest` — common/JVM. Последний принятый main aggregate **257/257**,0 failures/errors/skips.
- `:shared:linkDebugTestIosSimulatorArm64` — весь core Native, включая `iosTest`; запуск только на явно проверенном owned simulator, без default `allTests`/device selection. Следующие image/file adapters изменяют count, поэтому считать фактический зарегистрированный/executed набор.
- `:shared:linkAppHostDebugTestIosSimulatorArm64` — **два обязательных Keychain app-host tests**, не включённые в core. Source `shared/src/iosAppHostTest/`, associated main compilation `appHostTest`; executable `shared/build/bin/iosSimulatorArm64/appHostDebugTest/AppHostTests.kexe`. Link/`--ktest_list_tests` — не execution.

Runtime app-host gate сейчас **BLOCKED**: CLI получил−34018, отдельно разрешённый ad-hoc `.app` отклонён macOS AMFI−424 restricted entitlements/launchd153. Не снимать Data Protection/entitlements/AMFI, не использовать чужой привилегированный runner и не повторять ad-hoc попытки. Нужен штатный Xcode app/test-host с подходящей signing identity; доступ к signing credentials/profiles отдельно разрешается. [Полный evidence](ios-keychain-host.md). Зелёный core не закрывает P08/G4/native security gate.

5 ноября пользователь перенёс SDK/AVD и Gradle caches/wrapper на data. Свежие shells получают SDK `/Volumes/data/developer-state/android-sdk`, AVD `/Volumes/data/developer-state/android-avd`; старые aliases и `.gradle` symlinks валидны. Использовать новые environment paths, не удалять старые каталоги/данные от имени агента и не делать wipe/reset. Boot owned21arm64/tablet35 после переноса — отдельный runtime check, не повод повторять уже выполненные63/63. Те результаты до переноса: [freeze/summaries](evidence/p06-dref-2026-11-05/).

Per-command JDK17/Xcode остаются прежними. Не задавать старый ANDROID_HOME поверх нового environment. Текущий explicit core command после успешного link и повторной проверки owner/name/runtime:

```sh
xcrun simctl spawn DD0F9A3F-DC32-4373-BA08-400FECE3A1CC \
  "$PWD/shared/build/bin/iosSimulatorArm64/debugTest/test.kexe"
```

Это не запускает app-host tests и не заменяет их BLOCKED gate. Normal Android APK с прежним `su.linka.pictures` до G4 на реальные данные не устанавливать.

### Последний integrated gate (5 ноября)

Один aggregate после review corrections:

```sh
./gradlew :shared:jvmTest :shared:linkDebugTestIosSimulatorArm64 \
  :shared:linkAppHostDebugTestIosSimulatorArm64 \
  :shared:linkDebugFrameworkIosSimulatorArm64 :shared:linkDebugFrameworkIosArm64 \
  :app:assembleDebug :shared:assembleDebugAndroidTest --console=plain
```

**PASS,3m46s**; затем весь linked core executable на проверенном owned iOS17: **361/361,52502ms**, Keychain2 отдельно BLOCKED. Точные логи/SHA — [последний срез журнала](implementation-status.md#последний-принятый-срез--native-integration-после-переноса). `vtool -show-build` подтвердил `IOSSIMULATOR/minos17.0/sdk26.0`; это реальный compiler/link target, не переписанный header.

Root `prepareIosAudioDecoder` → два обычных per-target CInterop, manifest verifier без Gradle outputs (producer владеет seeded cache), static `.a` явно входят в inputs. Native runtime включает `IosAudioDecoderIntegrationTest`: real inspector callback/broker, finalized PCM16 stereo16000 WAV, original SHA, early IO/LIMIT/UNSUPPORTED, cancellation cleanup/foreign alias. Это не real TTS/mic/foreground/provider UI proof. Known ld-driver flag исправлен с отдельным `python3 -B scripts/test-ios-audio-decoder-def.py`; полный producer selfcheck16/16 ранее принят отдельно.

Android changed image path проверен готовым main APK, без пересборки в lane: [**24/24 API21 и24/24 API35**](p06-main-image-envelope-runtime.md). Files [**19/19 API35 и2/2 changed API21**](evidence/p06-files-path-2026-11-05/) дополняют прежние19 API21. API35 только approved runtime `-feature -WiFiPacketStream`: [crash/workaround](api35-netsim-crash.md), не default emulator/network acceptance. Прежние63/54 не повторялись и не складываются в новый total.

После явного compatibility decision для21/22 добавлена одна common policy regression. Только invalidated checks: `:shared:jvmTest --tests su.linka.pictures.access.LocalAssistantAccessTest :shared:linkDebugTestIosSimulatorArm64` PASS; затем explicit owned iOS17 spawn с `--ktest_filter=su.linka.pictures.access.LocalAssistantAccessTest.*`: **12/12 JVM,12/12 Native,9762ms**. Native availability/root/OS prompts этим не доказаны. Full257/361 выше — предыдущий полный gate; новый полный258/362 не исполнялся и не заявляется.

---

This verifies ordinary bootstrap builds and dependency interoperability,
**without a probe flag**, not product parity or a full quality gate. Base
`f7589be1dd5a5be26ff3d4991c84734d3f5dac9d`; approved versions and scope are in
[toolchain.md](toolchain.md). Worktree:
`/Users/aacidov/.local/share/opencode/worktree/67846f/pokazhi-p02-foundation`.

Host actually used: macOS27.0.1/build26A434, arm64; Homebrew JDK17.0.19,
Gradle8.11.1, stable Xcode27.2/27B52, installed SDK35 and Apple SDK27.2.
Environment selection is per-command; no global tool setting is changed:

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export ANDROID_HOME=/Users/aacidov/Library/Android/sdk
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
```

## Tasks and schemes

| Check | Command / output |
| --- | --- |
| JVM shared common test | `./gradlew :shared:jvmTest --console=plain` |
| Legacy Android compile/APK smoke, no install | `./gradlew :app:assembleDebug --console=plain` → `app/build/outputs/apk/debug/app-debug.apk` |
| Static simulator framework | `./gradlew :shared:linkDebugFrameworkIosSimulatorArm64 --console=plain` → `shared/build/bin/iosSimulatorArm64/debugFramework/Shared.framework` |
| Static device framework | `./gradlew :shared:linkDebugFrameworkIosArm64 --console=plain` → `shared/build/bin/iosArm64/debugFramework/Shared.framework` |
| Native common tests, link only | `./gradlew :shared:linkDebugTestIosSimulatorArm64 --console=plain` → `shared/build/bin/iosSimulatorArm64/debugTest/test.kexe` |
| Normal iOS app | Xcode project `iosApp/LinkaPokazhi.xcodeproj`, shared scheme `LinkaPokazhi`, Debug |

The Xcode project links the normal frameworks above; it does not fabricate or
copy a probe framework. Build the matching framework first. Simulator/device
outputs are selected by SDK, not by a runtime feature flag. Supported native
architectures are arm64; no Intel simulator product is claimed.
Do not install the normal Android APK for this smoke: its deliberately unchanged
applicationId could update an existing real-user app. Assembly is compile-only.

Unsigned generic simulator app build:

```sh
xcodebuild build -project iosApp/LinkaPokazhi.xcodeproj \
  -scheme LinkaPokazhi -configuration Debug \
  -destination 'generic/platform=iOS Simulator' \
  -derivedDataPath /private/var/folders/l4/r3plyjwj7pz_nq7zz50x04880000gn/T/opencode/p02-ios-simulator \
  CODE_SIGNING_ALLOWED=NO
```

Unsigned generic device app build (not installation/signing/physical execution):

```sh
xcodebuild build -project iosApp/LinkaPokazhi.xcodeproj \
  -scheme LinkaPokazhi -configuration Debug \
  -destination 'generic/platform=iOS' \
  -derivedDataPath /private/var/folders/l4/r3plyjwj7pz_nq7zz50x04880000gn/T/opencode/p02-ios-device \
  CODE_SIGNING_ALLOWED=NO
```

### Native test execution: exact owned iOS17 device only

Do **not** invoke a default `iosSimulatorArm64Test`/`allTests` task that might
choose another personal simulator. Tests were not disabled/skipped; the normal
Native test executable was linked and its entire registered suite executed
using this explicit public command, after confirming the owned device is
available, named `linka_platform_probe_ios17`, booted under runtime iOS17.0:

```sh
xcrun simctl spawn DD0F9A3F-DC32-4373-BA08-400FECE3A1CC \
  /Users/aacidov/.local/share/opencode/worktree/67846f/pokazhi-p02-foundation/shared/build/bin/iosSimulatorArm64/debugTest/test.kexe
```

This is a compiled Kotlin/Native common test on the **owned iOS17.0** runtime,
not an Android/JVM test standing in for native. No app was installed. The test
executable carries K/N metadata minos14.0/sdk26.0; the selected Xcode is27.2,
and both final Xcode app executables carry minos17.0/sdk27.2. These are distinct
artifacts; no native SDK/minimum metadata was rewritten to make them match.

## What the common test actually exercises

`shared/src/commonTest/kotlin/su/linka/pictures/shared/BootstrapDependenciesTest.kt`:

1. Real common `platformName()` actual and coroutine `runTest`/`async`.
2. Compiler-generated serializer for a **private test-only payload**, Unicode/
   apostrophe/emoji/newline JSON encode/decode. No product model/API wrapper.
3. Fresh `FileSystem.SYSTEM_TEMPORARY_DIRECTORY` child created with
   `mustCreate=true`; actual Okio buffered file sink/source, streamed SHA-256
   checked against an independent fixed UTF-8 vector:
   `de65987ee7e25b5910ba398715cba5745769314796ceb57e5bf7f8959d00d4f3`.
4. Real bundled SQLite file, bound text write, close, **reopen and read**,
   with exact Unicode equality. This is not an in-memory/cache echo.
5. `finally` closes/deletes only its owned files/directory, then asserts the
   directory is absent. No platform types or production temp-path wrappers,
   no fake filesystem, real library/source/migration/access or user data.

## Actual executed results and correction history

After coherent-diff review, initial focused command:

```sh
./gradlew :shared:jvmTest :app:assembleDebug \
  :shared:linkDebugFrameworkIosSimulatorArm64 :shared:linkDebugFrameworkIosArm64 \
  :shared:linkDebugTestIosSimulatorArm64 --console=plain
```

The earlier JVM/framework implementation check passed, but this command exposed
a **new-test import issue** during native test compilation: Okio Native streams
do not implement Kotlin AutoCloseable, unlike JVM Closeable. Adding the common
`okio.use` import fixed that receiver mismatch; no dependency/version/library
check or product source was changed. Android packaging had not completed when
that test compilation failed. Both Xcode generic app builds already succeeded.

Only invalidated/unfinished Gradle checks were rerun:

```sh
./gradlew :shared:jvmTest :app:assembleDebug \
  :shared:linkDebugTestIosSimulatorArm64 --console=plain
```

**Exit0**, `BUILD SUCCESSFUL in 13s`,79 tasks (15 executed,64 up-to-date).
JVM XML: **1 test,0 failures,0 errors,0 skipped**, actual0.330s. Native executable
was then run on the owned iOS17 device: **1 test passed,13ms**, exit0; no skip,
filter or alternative device. Both real library-framework links succeeded
earlier (`BUILD SUCCESSFUL in 2m20s`) and remained valid/up-to-date.

Removed a redundant source `UIDeviceFamily` plist entry because Xcode owns that
field via `TARGETED_DEVICE_FAMILY=1,2`; both generic Xcode builds were rerun,
**BUILD SUCCEEDED**. All other unchanged JVM/framework/runtime checks were not
repeated after that plist-only correction. No normal iOS/Android app launch.

Read-only artifact assertions confirmed:

- Packaged Android binary manifest: **su.linka.pictures**, version **1.4/code5**,
  minSdk **21**, target/compileSdk35. ARMv7 and ARM64 remain packaged (x86/x86_64
  are also retained); required SQLite libraries were checked by actual ELF
  class/machine, not just filenames. No app ID, version or ABI substitution.
- Both Xcode app plists: **su.linka.pictures.development**, exact min17.0,
  families **[1,2]**, no permission usage strings or embedded provisioning
  profile. Both actual MH_EXECUTE LC_BUILD_VERSION values: min17.0/sdk27.2,
  respectively IOSSIMULATOR and IOS. No release version keys were guessed.
- The real exported `Platform_iosKt.platformName()` in the generated normal
  Shared header is used by Swift; no placeholder framework is linked.
- App sources/build, original shared Android sources, settings and CI files
  have no package changes. `plutil -lint`, shared-scheme XML assertions and
  `git diff --check` passed.

Warnings were not suppressed: baseline deprecated MediaRecorder constructor,
AGP native-library strip diagnostic, and on the first iOS simulator link the
known CMP ICU17.2 data-object warning. Xcode's initial no-AppIntents-dependency
metadata message does not imply a new dependency is required. See toolchain
notes for the exact ICU member hash. The duplicate family warning was fixed
at its actual configuration source, not bypassed.

## Final local artifacts / SHA-256

| Artifact | SHA-256 |
| --- | --- |
| app/build/outputs/apk/debug/app-debug.apk | `f6ecfd147ab18922c195e623e03a75daf1574c472855b5a5ab18735c50a3619e` |
| shared/build/bin/iosSimulatorArm64/debugFramework/Shared.framework/Shared | `839f594d28ec932d07ffcce52550d7b06cdf84a0b2826df0891a7332e5087de9` |
| shared/build/bin/iosArm64/debugFramework/Shared.framework/Shared | `0293af2e814f87f3010c581e72c5f829e96656e45b3219325c1b555b478db89b` |
| shared/build/bin/iosSimulatorArm64/debugTest/test.kexe | `ee5cf93ce71f8b5e31a5e6eae5e23b9fb8edf25d568bf051c12c721eae6f1b1d` |
| p02-ios-simulator/Build/Products/Debug-iphonesimulator/LinkaPokazhi.app/LinkaPokazhi | `e6a6db29bbf0251f9fe31c3860e8a4f70fc7add4270af16f9e91d590f7b1f89a` |
| p02-ios-device/Build/Products/Debug-iphoneos/LinkaPokazhi.app/LinkaPokazhi | `6f17f3343f6719a7f0293433a251cf3358cbedf330683740c96a5b0492c039d1` |
| JVM test XML | `ac1a9857232685a776980542087d7860fbdfb2ef4f77569a62d55d8f5061db69` |
| p02-native-common-test-ios17.log | `232579b18129b243916e12a8f72cd4e1e6ed2527c5948e87fe1d3b800f578b8d` |

`p02-ios-*` paths and raw logs are under the approved session temp root:
`/private/var/folders/l4/r3plyjwj7pz_nq7zz50x04880000gn/T/opencode/`.
Retained logs: `p02-shared-initial.log`, `p02-focused-gradle.log` (import
failure), `p02-focused-corrected.log`, `p02-ios-simulator-build.log`,
`p02-ios-device-build.log`, `p02-ios-simulator-final.log`,
`p02-ios-device-final.log`, `p02-native-common-test-ios17.log`,
`p02-apk-badging.txt`, `p02-gradle-version.log`. Binaries/outputs are not
committed, only the approved generated wrapper JAR is source-controlled.

## Not executed / gates still open

No product regression suite, Android unit/instrumentation/device run, normal
app installation/launch, physical iOS run, production library/source/access/
migration integration, codec/Unicode policy proof, parity, release/archive/
store validation or publication. No tests were disabled to obtain this result.
No new permissions or signing/provisioning/profile configuration, SDK/license/global tool
change, production secret/real-user-data access, CI edit, push or integration.
The previous experimental probe worktree was not modified.

This is **P02 normal bootstrap only**, conditionally authorized while G1 is
open. Domain contracts/models are still COORD's work, not invented here.
**No G1/G2/P14/P16 PASS or release-readiness claim.**

## P02 contract freeze — 5 November 2026

This section supersedes only the bootstrap-only G2 status above. Foundation
`bb4f920` was integrated as `a371877`; normal shell renamed to `iosApp/`.
Contracts and SQL/ownership handoff are in [code-contracts.md](code-contracts.md),
and all five C4 documents/schemes now reflect the agreed full scope. This is
**G2 declaration acceptance**, not implementation of storage, communication,
access, migration, codecs, production UI or any release gate.

After coherent review, the aggregate in-memory library snapshot model was
removed: snapshot ports stream metadata, while the backup manifest only
contains an explicit header/settings/counts. Model tests do not pretend to
prove the future storage closure or transactional restore implementation.

Actual final command from `/Volumes/data/linka/linka.pictures-android`:

```sh
./gradlew :shared:jvmTest :shared:linkDebugTestIosSimulatorArm64 \
  :shared:linkDebugFrameworkIosSimulatorArm64 :shared:linkDebugFrameworkIosArm64 \
  :app:assembleDebug --no-daemon
```

Pinned environment above. Exit0, BUILD SUCCESSFUL in36s,82 tasks
(19 executed,63 up-to-date). JVM XML totals: **14 tests,0 failures/errors/skipped**:
8 CoreContractsTest,1 BootstrapDependenciesTest,1 ArchivePathNormalizationTest,
4 GraphemeBoundariesTest. The latter executes all1093 official Unicode16
vectors, with expected boundaries and last-cluster deletion for every vector;
it also checks malformed UTF16, emoji/Indic/flags and long contextual runs.
Generator exact-byte `--check` passed with official source hashes; see
[unicode.md](unicode.md) and the retained Unicode license.

Native command uses the same owned iOS17.0/21A328 UDID, verified Booted:

```sh
xcrun simctl spawn DD0F9A3F-DC32-4373-BA08-400FECE3A1CC \
  /Volumes/data/linka/linka.pictures-android/shared/build/bin/iosSimulatorArm64/debugTest/test.kexe
```

Final result: **14/14 passed,42ms**, no filter/skip, including real SQLite
close/reopen/Unicode IO, NFC/lowercase and the whole grapheme suite.

The first launch timed out at120s before tests ran. Sampling found the process
blocked in dyld's `__open`, not a Kotlin test. A narrow tccd log query identified
`kTCCServiceSystemPolicyRemovableVolumes` / AUTHREQ_PROMPTING for
SimulatorTrampoline reading this exact executable on `/Volumes/data`.
COORD neither approved the prompt nor relocated the executable to bypass it;
the user explicitly answered **«Разрешил вручную»**. The exact same binary and
command then passed. No full-disk permission or OS-settings change by the agent.
Earlier Foundation compile failure was fixed with the actual category-property
import; BetaInteropApi opt-in documents the factory API used, not a disabled
compiler/SDK check. One redundant always-false test assertion was removed;
the cancellation-type assertion remains.

Both generic Xcode build commands above were repeated against `iosApp/`, using
fresh `p02-contracts-ios-simulator` / `p02-contracts-ios-device` derivedData dirs
under approved temp. Both **BUILD SUCCEEDED**, CODE_SIGNING_ALLOWED=NO.
Both plist assertions: minimum17.0, provisional development bundle, iPhone+iPad,
no permission usage strings/profile. Known data-only ICU17.2 simulator warning
and no-AppIntents metadata message remain unsuppressed. Android artifact still
has `su.linka.pictures`,1.4/code5,min21,ARMv7+ARM64; it was **not installed**.

| Final contract artifact | SHA256 |
|---|---|
| Android debug APK | `54ed1f936c10eab3f2fd1aeb235d130868e3d20108c8ef731157ed4fe7ed97b2` |
| Simulator Shared framework | `94a0b91996ed7aff2e1e20bbde25b51b28be6fabc701684e2c2d1f5cc7c31c20` |
| Device Shared framework | `d17a9c846e70b5143277abb203c211369702ea0c3191e92f3b6139f286a960ed` |
| Native test.kexe | `5a94e425d0a29c7a28900e3f9370a0836e2acba45bce7ed0fa62eaf3784b7756` |
| Generic simulator app executable | `e32c6c00f18f3c7fec1b10dca55e4cec2c716a9d5e421a330e78fa750c10fd6f` |
| Generic device app executable | `ba6f4a819cc7d761ba1984fadf5e8f3e3c810acf569a5df33ce335417e29cdea` |

No production data binding, real migration/restore, normal app launch,
ARMv7 runtime, physical/AAC, shipping codecs or release acceptance was performed.
G0/G1 remain partial under the user's already authorized conditional order.

## Android native-adapter test target preparation

After shared AudioCoordinator integration, main common suites are **102/102**
on JVM and owned iOS17; see [P06 common evidence](p06-audio-coordinator.md#coord-integration-verification).
COORD added only shared `androidInstrumentedTest` dependencies already used in
the project: JUnit4.13.2, androidx.test core1.6.1/runner1.6.2, coroutines-test1.10.2,
and the standard AndroidJUnitRunner setting. No production dependency/version
or normal app identity changed.

`:shared:assembleDebugAndroidTest --no-daemon` **BUILD SUCCESSFUL in30s**,
47 tasks. APK is `shared/build/outputs/apk/androidTest/debug/shared-debug-androidTest.apk`.
Actual aapt manifest: package **su.linka.pictures.shared.test**, min21, target
instrumentation package **the same test package**, runner AndroidJUnitRunner;
packaged ARMv7/ARM64 retained. This is a self-instrumented library test app,
not an update of `su.linka.pictures`. It was **not installed or launched**;
at preparation there are no new native-adapter tests yet. Compile success is
not native MediaCodec/TTS/recording/permission/device evidence.
