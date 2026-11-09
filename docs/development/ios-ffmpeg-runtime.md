# iOS audio compatibility runtime — LinkaFFmpeg.framework

The selected architecture keeps basic WAV PCM16/MP3 inspection/playback on native
CoreAudio/AVAudioPlayer paths. The bounded FFmpeg bridge remains for compatibility
OGG/AMR/3GP. No new codec/demux stack is introduced before release. The FFmpeg
configuration is unchanged: four decoders (`vorbis,amrnb,pcm_s16le,mp3`), five
demuxers (`ogg,amr,mov,wav,mp3`), file protocol, swresample, no encoders/muxers/
filters/hardware acceleration/network, GPL/nonfree/version3 disabled. Existing
basic-format FFmpeg capability remains for the vetted unsupported-native-variant
fallback; invalid/limit/cancel results do not fall back. Only the explicit
`native_playback` result flag permits borrowing the original source for playback;
successful legacy inspection alone does not.

## Boundaries and source delivery

`liblinka_audio_decoder.a` contains application bridge/native-inspector objects
only. CInterop's `staticLibraries` names **only this archive**. The four FFmpeg
archives are force-loaded into a separate real dynamic `LinkaFFmpeg.framework`;
no bridge/application object enters it. Its install name is
`@rpath/LinkaFFmpeg.framework/LinkaFFmpeg`. Device and simulator are distinct arm64
builds with actual compiler/linker minimum iOS17.0, not patched Mach-O metadata.

`third_party/ffmpeg/` supplies the exact unmodified 8.1.2 source tar, original
detached signature, public release keys, LGPL2.1 and project license notices.
The source SHA is `464beb5e7bf0c311e68b45ae2f04e9cc2af88851abb4082231742a74d97b524c`.
These are copied from the previously authenticated intake, not redownloaded or
relabelled source receipts. Source is now part of the checkout, not available
only through a personal cache/UUID. Source/notice availability to recipients and
application distribution terms remain the release owner's responsibility.
The framework's `Source.txt` identifies the exact upstream archive URL/SHA and
checkout source/build-instruction paths, without inventing a published repository
or release-artifact URL.

The dynamic mechanism is the selected technical route for LGPL2.1 §6(b)
replacement; no app-object/relink kit is required by this build. This document
does not grant an application license, amend an EULA, offer three-year source
delivery, decide patents, or assert legal/store approval. User modifications must
remain interface-compatible with the application's bridge.

Apple's [Standard EULA](https://www.apple.com/legal/internet-services/itunes/dev/stdeula/),
section(a), expressly excepts applicable open-source licensing terms from its
reverse-engineering/modification restriction. That is a relevant distribution
term, not a patent opinion or confirmation of compliance for every jurisdiction.
No custom EULA narrowing this exception is introduced here.

## Production build, including a fresh clone

Prerequisites: Xcode27.2 with both iOS SDKs and command-line tools, Python3 with
tarfile's data filter support, make, GnuPG's `gpgv` on PATH. Install/accept Xcode
through the normal user workflow; the producer never accesses Apple accounts or
provisioning. No Docker build or network source fetch is needed.

```sh
python3 -B scripts/test-ios-audio-decoder-def.py
python3 -B scripts/test-ios-ffmpeg-runtime.py
python3 -B scripts/build-ios-audio-decoder.py \
  --gradle-output-root "$PWD/shared/build/ios-audio-decoder"
```

The last command is also `:shared:prepareIosAudioDecoder`'s command. An empty
generated root bootstraps from the vendored authenticated source with the same
bounded flags and explicit `xcrun` SDK/tool paths. It builds both targets with
make at most `-j4`. It does not boot/run a simulator, sign or publish.

Existing exact schema1 publications are checked before a controlled schema2
transition: unknown bytes/configs/defs/libraries still fail closed. A schema2
publication inventories runtime binary, Info, privacy and notices as well as
headers/libs/bridge source fingerprints. Native inspection is inline in the
bridge TU; the public header's appended `native_playback` field joins the bridge
fingerprint. A changed bridge rebuilds its sources, not FFmpeg. The
unchanged FFmpeg runtime may be copied only from a verified schema2 prefix.
The isolated `gpgv` homedir and pinned public key never use a user's keyring.
Partial/foreign output is retained for explicit owner recovery, not overwritten.

The exact-current fast path verifies inventory, source/config fingerprints,
platform/minimum/install-name/privacy and toolchain without compilation. Default
builds do not require any historical UUID. Explicit legacy `--reuse-build-run`
and `--verified-source-run` remain evidence/debug options, not clone prerequisites.

## Packaging and runtime checks

- Product Xcode configurations search the SDK-conditional producer prefix and
  use standard Link Binary/Embed Frameworks phases, with CodeSignOnCopy and
  RemoveHeadersOnCopy. The app loader path is `@executable_path/Frameworks`.
- The XCTest bundle links the runtime; its **host .app** embeds it in Frameworks.
  No dynamic framework is nested inside a static Shared/AppHostTests framework.
- Each Kotlin Native `.kexe` link finalizes with a declared per-binary Sync task
  copying the runtime to that executable's `Frameworks/`. Test ordering waits for
  that staging; independent binaries have distinct destinations. Its rpath is
  `@executable_path/Frameworks`, not a personal absolute producer path.
  The copied Simulator test framework receives the normal local ad-hoc signature;
  the inventoried producer prefix remains unsigned. iOS17 dyld rejects an unsigned
  copy, so neither AMFI nor library validation is disabled for tests.
- Framework link verification rejects FFmpeg-defined symbols appearing in static
  Shared/SharedAppHostTests. The bridge can have undefined FFmpeg references.
- App-side bridge linking includes AudioToolbox for the native inspector. Runtime
  linking uses only the four FFmpeg archives, math/CoreFoundation/CoreVideo/
  CoreMedia and never passes clang's `-pthread` driver option directly to ld.

Runtime metadata is derived, unsigned, and checked against actual load commands.
Its privacy manifest covers imported `fstat/lstat` for app-container files using
`C617.1`. `clock_gettime/gettimeofday` are not Apple's listed SystemBootTime APIs;
they do not manufacture a declaration. If `mach_absolute_time` is imported,
timer reason `35F9.1` is emitted. New DiskSpace imports stop for review rather than
adding a fake reason. The app's separate timer/picker declarations remain its
own responsibility. Tracking/data-collection claims are not invented here.
Reference: Apple's NSPrivacyAccessedAPIType documentation.

After the build owner schedules root builds, verify both app/framework links,
core tests and the real app-host suite with normal sandbox/AMFI/entitlements.
`--prove` is still opt-in actual owned-simulator execution of dynamically linked
bridge/unit tests, including the legacy Vorbis allocation-failure fixture.
The same-TU unit includes that inline native helper, not a duplicate bridge
archive. `--compile-kotlin` uses the same dynamic CInterop definition as production.
The exact five synthetic inputs now live in `scripts/fixtures/ios-audio/` with
their original SHA256 pins/provenance; expired temporary paths are not required.
Missing/changed fixtures still fail closed. The proof checks native WAV/MP3 routes
separately from explicit FFmpeg WAV conversion: CoreAudio may include MP3 encoder
padding in source-frame counts while the compatibility decoder trims it.

```sh
python3 -B scripts/build-ios-audio-decoder.py \
  --gradle-output-root "$PWD/shared/build/ios-audio-decoder" --prove --compile-kotlin
```

The producer integrity selfcheck needs a current published root and its current
`artifact_run` UUID (not an unrelated old C-source build):

```sh
python3 -B scripts/test-ios-audio-decoder-outputs.py \
  --published-root "$PWD/shared/build/ios-audio-decoder" --reuse-build-run CURRENT_ARTIFACT_UUID
```

## Rebuilding a compatible replacement without production hash bypass

The separate recipient mode never writes a production manifest or CInterop def,
and cannot be combined with production/cache/proof flags. It applies the same
feature flags and SDK/target settings, while permitting source changes and new
resulting binary hashes. The production producer's authentication and cache
guards are not weakened or skipped.

```sh
mkdir -p build/ffmpeg-user-source
tar -xf third_party/ffmpeg/ffmpeg-8.1.2.tar.xz -C build/ffmpeg-user-source
# Modify compatible FFmpeg source as desired; preserve upstream notices.
python3 -B scripts/build-ios-audio-decoder.py \
  --compatible-source "$PWD/build/ffmpeg-user-source/ffmpeg-8.1.2" \
  --runtime-output "$PWD/build/ffmpeg-user-runtime"
```

Result: `build/ffmpeg-user-runtime/runtime/LinkaFFmpeg.framework` (unsigned), with
`recipient-build.json` recording the local recipe/inventory, not production
provenance or release approval. For simulator use `--sdk iphonesimulator` and a
different fresh output. The normal app needs an interface-compatible framework
at `.app/Frameworks/LinkaFFmpeg.framework`; no app object files or original private
key are needed to replace that library. Device installation still requires the
recipient's **own** normal Apple signing/provisioning and permitted bundle ID.
Do not bypass AMFI, provisioning or platform restrictions. No key/profile/SDK is
distributed by this source/runtime recipe.

## Direct-clang packaging evidence (not final app/runtime execution)

`scripts/test-ios-ffmpeg-runtime.py --verified-root ABSOLUTE_PREFIX --output
ABSOLUTE_FRESH_BUILD_PRERELEASE_DIR` verifies old libraries against their exact
manifest hashes/platform/config, then builds both dynamic runtimes in a fresh
owned directory. It checks real DYLIB/install-name/platform/minimum, expected
exports, absence of app bridge symbols, imported-API privacy and byte-identical
upstream notices. This does not run Gradle/xcodebuild, sign, launch a device or
assert that the final candidate app has been rebuilt. Parent-scheduled root
Native/app-host/archive checks remain necessary after all concurrent changes.

Actual direct-clang check on 2026-11-09 passed for both device and simulator:
`build/prerelease/dynamic-ffmpeg-runtime-final-proof-20261109/runtime-proof.json`.
The report includes actual local `gpgv` authentication, real load commands,
binary hashes and generated privacy declarations. It uses the previously
verified unchanged four FFmpeg archives, not a claimed final app archive.
