# Android GIF alpha probe — 2026-11-06

## Verdict

**Current implementation update:** the API21 loss is fixed with the existing
Glide `gifdecoder:4.16.0`, now declared by its shared Android owner. Only the first
frame is decoded; Movie is not used by product code. API28+ thumbnails retain the
software ImageDecoder path. GIF inspection validates all frame metadata and uses
the same bounded first-frame decoder before accepting media.

Native regression went **RED on API21 (0.068s, 0 transparent pixels)** before the
fix. Afterward **5/5 tests passed on API21 (0.202s) and API35 (0.644s)**: the three
hash-pinned GIFs retain alpha, out-of-canvas second frames and 4097-frame metadata
bombs are rejected, a 1×8192 GIF samples to nonzero bounded buffers, and PNG/JPEG
orientation/byte preservation still pass. API21 transparent pixel counts are now
**14374 / 11697 / 17076**; API35 remains **29684 / 20880 / 37717**.

Before Glide's header parser/allocations, a source snapshot is structurally
validated: existing 64MiB/8192-dimension/16M-canvas-pixel limits, all frame
rectangles inside the canvas, at most4096 frames/16384 metadata commands/1000000
sub-blocks, bounded reads, complete trailer and valid LZW minimum code size.
Decode is serialized; estimated source/index/scratch/output/previous-frame
buffers must fit half of max heap and current heap headroom. Low-memory/resource
failures report `LIMIT_EXCEEDED`, not opaque-black success or content rewriting.
Large valid GIFs may need a streaming decoder on low-memory devices; this explicit
working-memory limit is not a claim that every64MiB GIF fits every phone.

Why not Movie: Android5.0's original
[SkMovie_gif.cpp](https://android.googlesource.com/platform/external/skia/+/android-5.0.0_r1/src/images/SkMovie_gif.cpp)
calls `DGifSlurp` for the animation and allocates two full-canvas bitmaps.
A small Canvas target alone does not constrain those allocations. No source or
canonical media bytes are re-encoded, and no dependency download was needed.
Actual product screenshots/font-scale matrix and physical memory acceptance
remain separate checks. Measurements below are the preserved pre-fix history.

The same three public GIF byte sequences lose transparency during native decoding on the API 21 arm64 emulator. The actual `AndroidThumbnailDecoder(AndroidMediaInspectionPort())` returns opaque black corner pixels; the full-resolution direct `BitmapFactory` control does too. API 35 preserves transparency in both controls and actual thumbnails. `Bitmap.hasAlpha() == true` on **both** platforms is not proof of transparent pixels.

This establishes a decode-level API/device difference without UI imitation, storage writes, or a production change. It does **not** establish a new regression: an old 1.4/API 21 baseline is still missing. It does not prove the physical-phone screenshot has the identical cause.

## Measured pixels

Counts are pixels with alpha < 255, scanned across the entire actual `ImageBitmap.toPixelMap()` (direct control uses `Bitmap.getPixel`). The source is the GIF's native still decode, not an animation renderer.

| Fixture / card | Source size | API 21 thumbnail / alpha count | API 35 thumbnail / alpha count | Direct BitmapFactory alpha count, API 21 / 35 |
|---|---|---|---|---|
| `unnamed (4).gif` / ГУЛЯТЬ | 364×228 | 182×114 / **0** | 256×160 / **29684** | 0 / 58506 |
| `unnamed (2).gif` / ИГРАТЬ | 390×222 | 195×111 / **0** | 256×145 / **20880** | 0 / 47503 |
| `unnamed (3).gif` / ОБНИМАТЬСЯ | 350×350 | 175×175 / **0** | 256×256 / **37717** | 0 / 69096 |

All four corners in every API 21 bitmap/control: alpha 255, ARGB `ff000000`. All four corners in every API 35 bitmap/control: alpha 0, ARGB `00000000`. `nativeHasAlpha=true` everywhere. Thumbnail request side is **256**, the actual supported contract (`1..256`), not 512. API 21 follows production BitmapFactory power-of-two sampling; API 35 follows production software ImageDecoder with sRGB target. Different dimensions are expected, not an inspection failure.

## Fixture provenance

Only test assets were added, extracted unchanged from `app/src/androidTest/assets/u14/ХОЧУ.linka` into `shared/src/androidInstrumentedTest/assets/u14-alpha/`.

Archive SHA-256 before extraction, after extraction, and final verification:
`a0589a029f77bfff7307d6ee3c4dbd41e1b2badd52ca90896ca5c835ccfb293b`.

| GIF | Bytes | SHA-256 |
|---|---:|---|
| `unnamed (4).gif` | 528975 | `4bbd53973ea39c9df2dc14bee3a8e45bc36e79414efd204225d1688f009a6aeb` |
| `unnamed (2).gif` | 326240 | `18cfd53a71a7c1881a60bb43314106d2cff1c7505ac88ab56502208ba9d0a396` |
| `unnamed (3).gif` | 213437 | `b98b30f483099c0f132c2245dfeec98fb5ac53284ad2495c121cccf8e6ee53c6` |

The test checks asset SHA, actual inspector dimensions/type, direct decoded dimensions, bounded thumbnail dimensions, and byte-for-byte/SHA immutability of its owned cache file. After measuring both devices, one regression assertion was added for the measured API 35 reference: each actual thumbnail must contain transparent pixels. API 21 absence is emitted as `TRANSPARENCY_ABSENT`, **not** asserted as correct transparency and not hidden by a fake/native-unsupported workaround. Other SDKs remain diagnostic only for alpha.

## Reproduction and execution evidence

Build with the existing wrapper/cache and JDK 17, without dependency downloads:

```sh
JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew :shared:assembleDebugAndroidTest --offline --max-workers=1
```

Inspect `shared/build/outputs/apk/androidTest/debug/shared-debug-androidTest.apk` before installing. Its package **and instrumentation target** must both be `su.linka.pictures.shared.test`; runner is `androidx.test.runner.AndroidJUnitRunner`. Only this self-instrumenting APK was installed with MCP `install_app(reinstall=true, allowDowngrade=false)`; no normal application APK was installed.

MCP device listing and AVD identity checks preceded changes:

| Serial | Confirmed AVD | SDK / ABI | Final run |
|---|---|---|---|
| `emulator-5554` | `linka_platform_probe_api21_arm64` (`emu avd name`; boot property empty) | 21 / arm64-v8a | `OK (1 test)`, code -1, 0.066 s; alpha **absent** |
| `emulator-5556` | `linka_audit_tablet` (boot property) | 35 / arm64-v8a | `OK (1 test)`, code -1, 1.328 s; alpha **present** |

Devices ran sequentially. Each final run used only this class, via MCP `execute_shell_command` with an explicit serial:

```text
am instrument -w -r -e class su.linka.pictures.bootstrap.AndroidGifAlphaProbeTest su.linka.pictures.shared.test/androidx.test.runner.AndroidJUnitRunner
```

Each fixture emits a `GIF_ALPHA_PROBE` JSON status with SDK, ABI, source SHA, native `hasAlpha`, all corner coordinates/alpha/ARGB, dimensions, pixel counts and measured transparency status. Structured scalar records transcribed from the MCP output, provenance, manifest inspection, and the final build log are in [`evidence/android-gif-alpha-probe/`](evidence/android-gif-alpha-probe/). The initial diagnostic run gave identical pixel counts before adding the API 35 regression assertion.

## Boundaries and cleanup

### Additional API 21 standard-OS Movie comparison

An additional **test-only** run on `emulator-5554` (`linka_platform_probe_api21_arm64`, SDK 21, arm64-v8a) compared the same already validated fixtures with `android.graphics.Movie.decodeFile`, `setTime(0)`, and `Movie.draw` onto a newly allocated, explicitly transparent ARGB_8888 bitmap. A software `Canvas` scales each frame to the actual production thumbnail dimensions above. No image/screenshot files are generated. Movie is deprecated on modern Android; the helper has a local test-only deprecation suppression and runs **only on SDK 21**. There is no API 35 Movie claim.

| Fixture | Movie target | Movie alpha < 255 | All four corners | Decode + allocation + draw elapsed |
|---|---|---:|---|---:|
| ГУЛЯТЬ / `unnamed (4).gif` | 182×114 | **14629** / 20748 | alpha 0 / `00000000` | 20484166 ns (~20.484 ms) |
| ИГРАТЬ / `unnamed (2).gif` | 195×111 | **11894** / 21645 | alpha 0 / `00000000` | 12194250 ns (~12.194 ms) |
| ОБНИМАТЬСЯ / `unnamed (3).gif` | 175×175 | **17263** / 30625 | alpha 0 / `00000000` | 6501333 ns (~6.501 ms) |

`setTime(0)` returned true and output `hasAlpha=true` for all three. Movie source dimensions matched the original inspector dimensions. Timing ends after drawing, before scanning pixels; it is a single diagnostic measurement, not a benchmark. The direct BitmapFactory and actual thumbnail comparisons were rerun unchanged and still had **0/0/0** alpha<255 pixels with opaque black corners. The top-level JSON `status` continues to describe the **actual production thumbnail**, not Movie.

**Answer to the narrow question: standard OS Movie preserves transparent pixels for these three first frames on this API 21 emulator.** This is not a production safety recommendation: a bounded output canvas does not bound Movie's parser, native frame decode, or animation memory. Validation, frame/memory bounds for arbitrary or hostile GIFs, cancellation, and production ownership remain separate primary decisions. The test only uses the three hash-pinned, previously inspected small fixtures and independently owns/recycles its output bitmap; it does not pass the thumbnail bitmap to Movie. Movie exposes no explicit recycle/close API.

The additional build used the same offline/JDK17/single-worker task. Only the verified self-target test APK was reinstalled (`-r`, no downgrade) on 5554. The same sole probe class returned `OK (1 test)`, instrumentation code -1, **0.116 s**. API 35 was not installed or run again. `finally` deleted all owned temporary sources and recycled the new output bitmaps; the post-run cache check returned `NO_OWNED_PROBE_TEMP_FILES`. Test APK remains installed; no grants, app UI, production changes, dependencies, or AVD changes. Archive SHA and all three asset SHA/ZIP byte equalities were reverified unchanged. New evidence is `movie-api21-measurements.json`, `movie-api21-build.log`, `movie-apk-manifest.txt`, `movie-apk-sha256.txt`, and `movie-fixture-verification.json` in the existing evidence directory; original API 21/35 evidence remains intact.

Only the test's uniquely named `gif-alpha-probe-*.gif` cache files were written on devices; `finally` deletes each with an assertion. A post-run `run-as su.linka.pictures.shared.test` cache check returned `NO_OWNED_PROBE_TEMP_FILES` on both devices. The self-instrumenting test package remains installed; no uninstall/clear-data, runtime grants, microphone tests, development-app UI actions, screenshots, or AVD changes occurred. Existing application datasets were not opened or modified by the probe. No production decoder, preparation, storage, UI, Gradle, manifest, CI, iOS, or audio-host source was edited.

Actual stored library bytes were **not measured** here. The previously known hash-preserving import/preparation contract is separate from the cache-file immutability measured by this test. On API 35, preserved bitmap alpha gives no grounds to blame canonicalization/decoder for any separate render failure. Physical-device confirmation, actual Compose rendering/compositing, old 1.4/API 21 parity, intermediate Android versions, and AAC/audio parity remain **OPEN**. No Glide/Movie/global image dependency or production workaround is proposed by this lane.
