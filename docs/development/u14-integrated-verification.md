# U1.4 integrated verification — 2026-11-06

## Resumed device verification — after commit 626b2f0

**Native reopen/back: PASS (1 test); requested landscape captures obtained.
Visual acceptance remains OPEN; `userApproved=false`.**

This is a later continuation, not a rewrite of the blocked attempt below.
Primary restored the existing AVD before this lane resumed. No JVM/build was
repeated, no source upgrade was taken: installed APKs are the frozen artifacts
built from `93b42626fd47aff4b42c8b0422455e81d2458090` above the initial
evidence commit `626b2f015a66bfa82caf4a6b79525b6278b26e6c`.

First resumed tool call was MCP `adb.list_devices`: only `emulator-5556` was
listed, state `device`. MCP `emu avd name` returned `linka_audit_tablet / OK`.
Boot complete=1, API 35, arm64-v8a, density 320, fontScale 1.0 were read.
Physical display is portrait 1600×2560; one authorized MCP `emu rotate` made
the current display landscape 2560×1600, rotation 3, before the test launch.
No persistent settings, permissions, networking, or emulator flags were changed.

Both full local SHA-256 values were checked against `artifacts.json` before
installing. MCP replace installs (`reinstall=true`, `allowDowngrade=false`)
both returned Success. Installed `base.apk` SHA-256 was then read on device:

- `su.linka.pictures.development`:
  `bdbb277474cbcdc61ca03318e6bd53fcb16c12dd26363df66c4e9c57cd1183df`;
- `su.linka.pictures.development.test`:
  `4e7b0ea9f60b13d9d5ec49fd8812a7ed05d3b9ea0bad7310acd20e0a5300e487`.

These hashes were checked again at the stable end. No normal/baseline package
was installed, launched, cleared, queried for data, or otherwise changed.
No uninstall, downgrade, new fixture import, duplicate board, save, apply,
delete, phrase edit/clear, microphone or playback action occurred.

### Actual native result

`DevelopmentParityTest#reopenFilledBoardWithoutImportOrMessageEdits` ran once
with the fixed fixture name/hash recorded below. Remote `am instrument -w -r`
ran in the background with output in its own `/data/local/tmp` evidence file;
the completion criterion was polled through MCP without fixed sleeps.
Terminal output: **Time 39.949; OK (1 test); INSTRUMENTATION_CODE: -1**.
Counts: tests=1, passed=1, failures=0; no ignored/skipped test was reported.

The test verified existing library presence and fixture identity, opened ХОЧУ,
confirmed nine visible named cells with explicit 3×3 AX metadata and the filled
message, sent native Back, reopened the same board, and confirmed them again.
Evidence reports `u14InputPath=reopen-native-back-regression`,
`u14MessageParts=ХОЧУ|КУШАТЬ`, `u14State=board-filled`.
No UI hierarchy dump was requested while instrumentation was active. A safe
in-progress screenshot shows library loading; it is not a filled-board proof.

### Read-only UI follow-up and stable end

After the terminal result, DevelopmentActivity was launched normally:

1. Captured library FAB: yellow body/black plus, one published ХОЧУ tile.
2. Opened the existing board and captured filled COMPOSE with all nine cards,
   two message parts, and functional teal outlines on КУШАТЬ and ХОЧУ.
3. Opened board settings without changing controls: COMPOSE radio is teal.
   Used **Закрыть**, not Save or reload settings.
4. Entered the existing ХОЧУ editor through its library action, not Create.
   Captured actual inline `3`/`3` fields above the editor grid. No new value was
   entered and no resize/apply/save action was pressed. Durable draft version
   v6 was supplied by primary, but was not independently inspected in storage.
5. Focused the existing numeric field without changing its value. The first
   PNG was before keyboard animation settled; `editor-focus-settled.png` shows
   the actual numeric IME and resized editor with its `1 / 1` footer above it.
   The keyboard was dismissed with native Back; global settings were not changed.
6. Opened the existing КУШАТЬ card modal, captured its teal selected standard
   card radio and Cancel/Done footer. No media, type, text, or audio was changed;
   used **Отмена**, not Done. Native Back left the editor without publishing.
7. Reopened the published board. Final hierarchy and PNG show all nine named
   cards and `Общее сообщение: ХОЧУ КУШАТЬ`. Foreground remained DevelopmentActivity
   in filled COMPOSE, landscape; density 320 and fontScale 1.0 unchanged.

Post-terminal MCP hierarchy requests transiently returned `null root node`
once in board settings and twice during editor/focus transitions. No reset,
restart, SQL bypass, or authorization workaround was used. Screenshots were
read to establish actual screen state; final board hierarchy was obtained.
No conflict/invalid-form error was displayed in the captured flow. This does
not test intentionally conflicting or invalid submissions.

### Actual baseline comparison, not pixel parity approval

Existing baseline and new `compose-filled.png` were both read at raw
**2560×1600**, same density 320/fontScale 1.0/landscape and same filled message.
PNG bytes were not resized or edited. `pixel-observations.json` records direct
RGB samples from original PNG decoding, not inferred layout constants:

- At x=10, baseline teal spans y=[0,176), including the status bar. New white
  status bar spans [0,48), teal toolbar [48,176). Excluding the 48 px status
  inset, both toolbar bands are **128 px = 64 dp**. New status-bar styling
  visibly differs; this is not full pixel parity.
- Divider sample at (16,479) is teal only in baseline; (16,467) is teal only
  in new: message/grid boundary is 12 px (6 dp) higher in the new capture.
  Final new AX grid card bounds are y=[488,818), [833,1163), [1178,1508).
- New message thumbnails and grid images visibly differ in size; new bottom
  content stays above the navigation region. No whole-screen similarity score
  or visual-user PASS is claimed. Teal selection outlines are intentional,
  not a claim that every edge is the neutral `#696969` border.

All ten raw PNGs, their SHA-256 and individual `read=true` flags are recorded in
[`device-resume/png-manifest.json`](../progress-screenshots/2026-11-06-ui14-calibrated/device-resume/png-manifest.json).
Terminal output, final device/installed hashes, final board AX XML, pixel samples,
and a separate SHA256SUMS are alongside them. The original blocked JSON/logs
and their original SHA256SUMS remain unchanged.

Remaining OPEN: phone portrait/large-font matrix, visual user approval, G4,
AAC/TTS/microphone and production migration. This does not close full U1.4.

## Initial host-only result (preserved)

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
