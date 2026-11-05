# P07 iOS image preparation — conditional implementation

`IosImagePreparationPort` implements the existing port with injected files and
inspection. This is **not P07/G4 PASS**, and the real iOS inspector binding from
lane F is not integrated here. No app wiring, picker, file adapter, decoder,
permissions, build files or public port signatures are changed.

## Policy

- `prepareImage` calls `inspection.image` before opening the source, then streams
  an exact copy with an inclusive 64 MiB limit. It never writes the source,
  resizes, re-encodes or strips EXIF. Both streams close on every exit.
- Outputs come only from `createTemporaryFile`. A source-path alias, nonempty
  allocation or unreadable allocation is rejected without opening its sink or
  deleting it. Ownership relies on the injected files adapter's factory contract;
  this lane does not invent filesystem ownership or canonical-path security.
- Only a successfully verified empty allocation is eligible for cleanup.
  Failure/cancellation discards that owned output. Publication occurs outside
  the dispatcher hop, so prompt cancellation also cleans a late successful result.
  Cleanup is best-effort: a throwing `discard` never replaces the primary failure,
  cancelled result or cancellation exception; a file can remain if deletion fails.
- `imageFromText` is explicitly requested user media, not a UI caption bitmap.
  Input is unchanged: no trim, normalization, shrinking or ellipsis. Empty input,
  NUL, malformed UTF-16 and colors outside unsigned 32-bit ARGB are invalid.
  NUL/UTF-16 checks reuse the shared `isNativeText` validator.
  More than 4096 UTF-16 code units or a computed canvas over 4096 px high is a
  limit failure, not truncation.
- CoreText's native system font is 64 points on a scale-1 1024-pixel-wide canvas,
  with 32-pixel padding and native wrapping. Height is the ceiling of the native
  suggested text height plus padding. The canvas cap is stricter than 16 MP.
  The frame must report the full UTF-16 range visible. CoreText/CoreGraphics work
  runs on `Dispatchers.Default`; no UIKit/UIFont main-thread API is called.
- The scoped bitmap is encoded by ImageIO to an owned PNG. Its closed output is
  checked by injected `inspection.image` before `Completed`; cancellation never
  publishes a late file. Native CF/CG resources are released in `finally` blocks.

## Focused native checks

Use JDK 17, the existing Android SDK and Xcode 27.2:

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export ANDROID_HOME=/Users/aacidov/Library/Android/sdk
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
./gradlew :shared:compileKotlinIosArm64 :shared:linkDebugTestIosSimulatorArm64
```

Do not run the Gradle simulator test task with its default runtime selection.
First inspect `xcrun simctl list devices --json` and verify the existing available,
booted device is exactly `DD0F9A3F-DC32-4373-BA08-400FECE3A1CC`, named
`linka_platform_probe_ios17`, under `com.apple.CoreSimulator.SimRuntime.iOS-17-0`.
No simulator boot/shutdown, app install or UI changes are needed:

```sh
xcrun simctl spawn DD0F9A3F-DC32-4373-BA08-400FECE3A1CC \
  "$PWD/shared/build/bin/iosSimulatorArm64/debugTest/test.kexe" \
  '--ktest_filter=su.linka.pictures.platform.capabilities.IosImagePreparationPortTest.*'
```

The tests use an in-memory files adapter and a controlled inspection boundary.
Generated PNGs are separately opened **and decoded by real ImageIO**, including
pixel data, dimensions, visible nonempty glyphs, wrapping, Unicode, whitespace,
color bounds, UTF-16 and canvas limits. Copy checks cover exact bytes, real-PNG
SHA-256 preservation, the 64 MiB boundary, alias/nonempty ownership guards,
read failures, stream closure, cleanup and cancellation/late results.

These tests are not evidence for the real iOS picker, filesystem ownership,
audio decoder, physical hardware or app integration. Common code is unchanged;
JVM/full-suite tests are outside this lane's focused verification.

## Runtime evidence

The owned-device check observed the exact name/UDID above, booted and available
on iOS 17.0. The native executable reported:

```text
P07-I runtime: Version 17.0 (Build 21A328); UDID=DD0F9A3F-DC32-4373-BA08-400FECE3A1CC
```

The first native run exposed an Apple font-name fallback: requesting the private
`.SFUI-Regular` name yields Times. The implementation now uses
`CTFontCreateUIFontForLanguage(kCTFontUIFontSystem, 64, null)` directly instead.
Final focused correction run: **22 tests passed, 0 failed** (229 ms), without the font-name
fallback warning. `compileKotlinIosArm64` and the simulator compile/test link
completed successfully with Xcode 27.2 (27B52), JDK 17 and the existing SDK.
The lane handoff records the commit SHA and local build/test log locations.
The four correction regressions cover throwing cleanup for a known problem,
cancelled result, original cancellation exception and cancelled late success.
Font, color-space and color ownership is scoped before subsequent allocation;
native allocation failure paths were reviewed, not forced by exhausting memory.
