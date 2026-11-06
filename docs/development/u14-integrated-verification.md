# U1.4 integrated verification — 2026-11-06

## Result

**Host checks PASS; device and visual verification BLOCKED. `userApproved=false`.**
This covers the approved first-filled-board direction, not full U1.4 acceptance.

Build source HEAD: `93b42626fd47aff4b42c8b0422455e81d2458090`, including
`5b021c9` (inline grid), `9336521` (communication geometry), and `93b4262`
(selection contrast). The worktree was clean before verification. No source,
Gradle, Manifest, CI, iOS, signing, account, Keychain, or framework changes were made.
The verification commit contains documentation/evidence only.

Evidence: [`2026-11-06-ui14-calibrated`](../progress-screenshots/2026-11-06-ui14-calibrated/).

## Host verification

Existing wrapper: Gradle 8.11.1, existing default cache (no
`GRADLE_USER_HOME` override). Existing JDK 17.0.19 at
`/opt/homebrew/Cellar/openjdk@17/17.0.19/libexec/openjdk.jdk/Contents/Home`.
Existing SDK: `/Volumes/data/developer-state/android-sdk`, Android 35 present.
No local AGENTS.md or local.properties exists in this worktree; repository
gradle.properties was read before running. Automatic Java downloads were disabled
for this invocation; no source configuration was changed.

```sh
JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.19/libexec/openjdk.jdk/Contents/Home \
./gradlew --offline --max-workers=1 --console=plain \
  -Porg.gradle.java.installations.auto-download=false \
  :shared:jvmTest \
  --tests 'su.linka.pictures.ui.*' \
  --tests 'su.linka.pictures.application.authoring.*' \
  --tests 'su.linka.pictures.domain.EditorFormKindSerializationTest' \
  :app:assembleDebug :app:assembleDebugAndroidTest
```

Exit 0, BUILD SUCCESSFUL in 1m 27s, 98 executed tasks. No native/iOS decoder,
framework, FFmpeg, Docker, or release build was requested or run.
Current-run JUnit XML: **23 suites, 158 tests, failures=0, errors=0, skipped=0**.
Per-suite counts are in `jvm-results.json`; raw XML copies are under `jvm-xml/`.
This replaces neither device acceptance nor visual approval; previous 140-test
and old-APK native-back results are not reused as this run's evidence.

| Artifact | Bytes | SHA-256 |
| --- | ---: | --- |
| `app/build/outputs/apk/debug/app-debug.apk` | 26346023 | `bdbb277474cbcdc61ca03318e6bd53fcb16c12dd26363df66c4e9c57cd1183df` |
| `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk` | 3458990 | `4e7b0ea9f60b13d9d5ec49fd8812a7ed05d3b9ea0bad7310acd20e0a5300e487` |

APK binary manifests/resources inspected with existing SDK aapt2:

- App package: `su.linka.pictures.development`.
- Test package: exactly `su.linka.pictures.development.test`; instrumentation
  targets `su.linka.pictures.development` using AndroidJUnitRunner.
- FileProvider authority: `su.linka.pictures.development.parity-fixtures`,
  `exported=false`; resource `xml/parity_fixture_paths` contains only
  `cache-path`, name `u14-fixtures`, path `u14-fixtures/`.
- `FirebaseInitProvider` absent. Other Firebase/measurement dependency components
  remain in the APK; this is not a claim that all Firebase code is absent.
- No APK installation was attempted.

## Device blocker and preserved scope

ADB MCP `list_devices` was the first tool call and was repeated after the build.
Both responses were exactly `List of devices attached` with no device entries.
The exclusive `emulator-5556` lease therefore could not be exercised. There was
no multiple-device selection to make; no substitute device or raw adb fallback
was used. Primary must restore visibility of the existing leased emulator to
MCP (and restart OpenCode if MCP needs reloading), without resetting its data.

No device-changing calls were issued: neither emulator-5554 nor any iOS device
was touched, no package was installed/uninstalled/cleared, no settings or
orientation changed, and no import, save, apply, delete, text insertion, or
phrase clearing occurred. Normal `su.linka.pictures` was not updated or modified
by this lane. Actual on-device baseline hashes/state and the development
library's current state could not be independently observed.

| Acceptance item | This run |
| --- | --- |
| `DevelopmentParityTest#reopenFilledBoardWithoutImportOrMessageEdits` on new APK | BLOCKED; not started, no instrumentation terminal result |
| Fixture already imported; reopen only | Not observed; no fresh import attempted |
| Native back on new code | BLOCKED; old APK's 1/1 result not carried forward |
| 9 cards and phrase `ХОЧУ КУШАТЬ` survive Cancel/Close | Not observed; no claim of PASS |
| Existing own `ХОЧУ` v6 draft resumed without duplication | Not observed |
| Library FAB, card modal, inline-grid fields, numeric IME/footer | Not observed |
| Stable final development library/filled board | Not established; device untouched |

Required resume inputs (do not import a new fixture):

- serial: **emulator-5556 only**, expected `linka_audit_tablet`, API 35;
- expected baseline: density 320, fontScale 1.0, landscape raw 2560×1600;
- test: `su.linka.pictures.activity.DevelopmentParityTest#reopenFilledBoardWithoutImportOrMessageEdits`;
- `u14FixtureName=LINKa-U14-HOCHU-cfb199d.linka`;
- `u14FixtureSha256=a0589a029f77bfff7307d6ee3c4dbd41e1b2badd52ca90896ca5c835ccfb293b`.

Before installing, confirm leased device identity and package provenance, then
use only non-destructive replace installation of these development app/test
APKs (no downgrade). Await terminal instrumentation result, not a fixed sleep;
do not dump UI hierarchy while instrumentation owns UiAutomation. Preconditions
must fail closed, never be repaired by reimport, reset, or message editing.

## Visual evidence and open acceptance

No new PNG was captured, read, resized, synthesized, or approved:
`rawPNGs=[]`, `newPngRead=false`, `userApproved=false`.
The existing baseline
`docs/progress-screenshots/2026-11-06-ui14/android-api35-baseline-compose-filled.png`
was not compared against a new image. Actual old/new toolbar geometry is
**unmeasured**, including the expected 64 dp toolbar; there is no pixel PASS.
The intended border `#696969`, functional teal selection, and brand-yellow FAB
with black foreground were not visually verified by this lane.

Phone portrait and large-font matrix remain OPEN; no global font-scale change
was made. G4, AAC/TTS, microphone, production root migration, visual user
approval, and full U1.4 acceptance remain outside this verification result.
