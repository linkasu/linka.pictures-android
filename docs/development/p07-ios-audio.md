# P07 iOS audio — implementation evidence and COORD

Branch: `implementation/p07-ios-audio`, base `b5874cc`.
Scope: iOS native backend, its PCM/generation helpers and tests only. Root,
ports, files, access and decoder implementations were not modified.

## COORD — blocking compatibility decision

**P07 audio is not fully accepted: finalized TTS WAV on iOS 17 remains BLOCKED.**
`AVAudioFile.close()` is publicly available only on iOS 18+, as documented in
the installed Apple `AVFAudio.framework/Headers/AVAudioFile.h`:

```objc
- (void)close API_AVAILABLE(macos(15.0), ios(18.0), watchos(11.0), tvos(18.0), macCatalyst(18.0));
```

`AVAudioFile` updates the output header on close/release. Kotlin/Native object
scope/autoreleasepool does not prove deterministic release of a Kotlin-held
ObjC object. No forced GC, private release calls or fake inspection were used.
Until root resolves this gap, this lane explicitly refuses synthesis on iOS 17
with `UNSUPPORTED_MEDIA`, before allocation/native speech. This is a visible
blocked capability, **not an approved change to the minimum OS policy**.

Root must approve a deterministic lifetime shim/project integration, an
alternative native writer API, or a minimum-OS change. None was invented here.
The iOS 18+ AVSpeech.write → owned bounded PCM → AVAudioFile implementation
compiles but its positive runtime gate remains OPEN (no authorized 18+ host).

## COORD — integration owned by root

- Wire the internal constructor with real files, inspector,
  `decoder::decodeToWave`, microphone activity/OS completion callback,
  Main-thread native foreground callback, and owning scope. No production
  callback has a fake default. Do not use a saved UI foreground flag.
- Files factory must return an actually owned empty `UUID.wav` path for
  `audio/wav`. User-suggested filenames are metadata, never filesystem authority.
  Backend refuses extensionless native-writer paths. The base files factory
  still produces extensionless paths: that separate root change is required.
- Add `NSMicrophoneUsageDescription` to the real app/recording test host.
  TTS does not require `NSSpeechRecognitionUsageDescription`. No background
  audio capability, routing preference, VoiceOver or global volume change is
  requested. No project/usage-string changes were made in this lane.
- Run positive playback/TTS/recording/finalization/route/foreground checks in
  the approved real app host after integration. Do not infer those gates from
  fake permissions, fake foreground or fake inspection.

## Implemented boundaries

The shared coordinator remains the only queue/lease owner. The backend admits
one native generation, rejects overlap, revokes matching stop synchronously,
and ignores stale controls/delegates. Cancellation only cancels that call's
worker. NonCancellable Main release finishes before another generation can
acquire native handles. Temporary ownership transfers only after inspection,
final cleanup and the last suspension/cancellation check. Playback compatibility
temps are discarded, never canonical/export output. Cleanup exceptions cannot
replace Cancelled/Failed or strand arbitration.

Speech uses actual available voice metadata with a 4000 UTF-16-unit conservative
limit. NUL/unpaired surrogates/oversize text are rejected before utterance
construction. Missing explicit voice/language never silently falls back.
Foreground, background notification, interruption-began and old-device-unavailable
route transitions interrupt without replay. Speech/playback transiently use
`.playback/.spokenAudio`; recording uses `.playAndRecord/.default` and standard
route. Only an owned active session is deactivated.

Recording requires the explicit permission callback followed by the actual
AVAudioSession granted check; backend never prompts by itself. AVAudioRecorder
uses WAV PCM16 little-endian mono 16000 Hz with native 600 s duration cap.
At this format, even 600 s plus header is below 64 MiB. Manual finish emits
FINISHING before stop; spontaneous/cancel/error/background never returns Ready;
duration exhaustion reports LIMIT_EXCEEDED, not a successful partial.

File speech preserves first-buffer sample rate/channels/processing format,
uses standard AVAudioFile PCM16 conversion, rejects changed formats/nonfinite
float samples, caps frames/time/output bytes, and accepts zero terminal buffer
only after actual frames. Callback buffers are copied into independently owned
PCM with an eight-buffer channel (max 1 MiB each, max 16384 frames); overflow
fails the request instead of silently dropping data. All file/decode work is
on IO; session/engine controls are on Main. No full-clip allocation or extra
dependency was added.

## Focused verification

Before spawn, `simctl list devices available --json` and the existing owner
record in `docs/redesign-audit/ios-platform-runtime.md` confirmed exactly:

- UDID `DD0F9A3F-DC32-4373-BA08-400FECE3A1CC`
- name `linka_platform_probe_ios17`, iPhone SE (3rd generation)
- runtime `com.apple.CoreSimulator.SimRuntime.iOS-17-0`, Booted

No simulator was booted/reset/erased, no permission was granted, no microphone
was started, and no native host/AMFI/Keychain setting was bypassed.

```sh
./gradlew :shared:compileKotlinIosArm64 :shared:linkDebugTestIosSimulatorArm64 --console=plain
# BUILD SUCCESSFUL

xcrun simctl spawn DD0F9A3F-DC32-4373-BA08-400FECE3A1CC \
  /Volumes/data/linka/linka-pokazhi-p07-ios-audio/shared/build/bin/iosSimulatorArm64/debugTest/test.kexe \
  '--ktest_filter=su.linka.pictures.platform.audio.*'
```

Final native output:

```text
P07-A runtime: Version 17.0 (Build 21A328); UDID=DD0F9A3F-DC32-4373-BA08-400FECE3A1CC
[==========] 15 tests from 2 test cases ran. (353 ms total)
[  PASSED  ] 15 tests.
```

Tests cover native voice metadata and unavailable explicit selection, actual
AVAudioFormat settings/PCM buffers (copy ownership, channels/rate, interleaved
PCM16, NaN/size refusal, eight-buffer capacity), malformed text, the iOS 17
explicit refusal, permission denial and actual OS guard against a falsely
completed permission callback, matching/stale stop, late decoded-temp cleanup,
inspection/discard failures, scope cancellation and notification interruption.
Generation tests replace Dispatchers.Main and foreground/permission/inspection
callbacks: they are control-flow proof, **not native Main/foreground/mic/decoder
proof**. Notifications are posted deliberately, not real hardware route changes.

A separately owned tiny WAV is written through real IosPlatformFilesPort,
decoded by AVAudioFile, checked as PCM16 mono 16000 Hz/160 frames, and removed
through owned discard. This proves real file bytes/native metadata, not TTS
output or the root inspector/decoder. An initial fixture setup omission
(missing media directory) failed; setup was corrected, its empty owned test
directories removed with exact `rmdir`, and the final run passed.

No `iosSimulatorArm64Test`, `allTests`, positive TTS, recording, real foreground,
physical route, 10-minute wall-clock or iOS 18+ writer tests were run. These
runtime gates remain OPEN; the iOS 17 writer API gap remains BLOCKED.
