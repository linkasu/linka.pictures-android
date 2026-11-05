# P07 iOS audio — implementation evidence and COORD

Branch: `implementation/p07-ios-audio`, base `b5874cc`.
Scope: iOS native backend, its PCM/generation helpers and tests only. Root,
ports, files, access and decoder implementations were not modified.

## COORD — iOS 17 writer correction

Minimum OS remains **iOS 17**. Following root's explicit correction, the
`94a6751` AVAudioFile writer/refusal has been replaced with `IosSynthWriter`:
CoreAudio `ExtAudioFileCreateWithURL` / `ExtAudioFileSetProperty` /
`ExtAudioFileWrite` / **explicit `ExtAudioFileDispose`**. There is no iOS 18
branch or iOS 17 unsupported stub. AVAudioFile is used only as a native reader
in tests. No ARC shim, forced GC, private API or minimum-OS bump was added.

The C output ASBD is signed packed little-endian PCM16, with rate/channels
from the first actual owned PCM buffer. ClientDataFormat is that buffer's
`format.streamDescription`; the system converter handles Float32/Int32/Int16,
including interleaved/deinterleaved input. No manual quantizer or resampler.

EraseFile is used only after files-port ownership/empty-source validation and
`lstat` checks for empty regular file, no symlink and link count exactly one.
The path must be an absolute native-safe `.wav` reservation. The helper owns
its C reference, disposes it exactly once on success/error/cancel, and preserves
a primary create/write/cancellation failure if disposal also fails. Success-path
dispose errors propagate before inspection, so cannot become Ready. A CFURL is
created through the CFCreate C API and balanced with CFRelease; no bridged
Foundation/ObjC object is manually released.

## COORD — integration owned by root

- Wire the internal constructor with real files, inspector,
  `decoder::decodeToWave`, microphone activity/OS completion callback,
  Main-thread native foreground callback, and owning scope. No production
  callback has a fake default. Do not use a saved UI foreground flag.
- Files factory must return an actually owned empty `UUID.wav` path for
  `audio/wav`. User-suggested filenames are metadata, never filesystem authority.
  Backend refuses extensionless native-writer paths. The base files factory
  still produces extensionless paths. Root reports this change is already
  implemented there; it was intentionally not copied into this lane. Writer
  tests use a private owned `.wav` reservation fixture until parent integration.
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
uses standard CoreAudio PCM16 conversion, rejects changed formats/nonfinite
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
P07-A ExtAudioFile: Version 17.0 (Build 21A328); UDID=DD0F9A3F-DC32-4373-BA08-400FECE3A1CC
[==========] 22 tests from 3 test cases ran. (1323 ms total)
[  PASSED  ] 22 tests.
```

Tests cover native voice metadata and unavailable explicit selection, actual
AVAudioFormat settings/PCM buffers (copy ownership, channels/rate, interleaved
PCM16, NaN/size refusal, eight-buffer capacity), malformed synthesis text,
permission denial and actual OS guard against a falsely
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

Corrected writer tests additionally cover Float32/Int32/Int16 conversion (both
interleavings), explicit-dispose/idempotence, reopened native PCM16 rate/channels/
frame count/signed samples, RIFF declared length, little-endian samples, format
change/zero-frame/nonfinite refusal, cancellation disposal before owned discard,
foreign/nonempty/symlink/hardlink refusal without truncation, and actual streamed
600-second/64-MiB budget exhaustion before the extra frames reach the file.
The budget tests write bounded buffers repeatedly, not full-clip allocations.
An initial byte-budget fixture used the simple eight-channel AVAudioFormat
initializer, which returned nil on iOS 17 before reaching the writer. It was
corrected to stereo 192 kHz, still reaching 64 MiB well before 600 s. No native
format fallback or production limit was changed; the final 22-test run passed.

Native ExtAudioFileDispose failure (e.g. disk flush failure) and an OSStatus
ExtAudioFileWrite I/O fault were not artificially injected: no existing native
fault seam permits those without production knobs or unsafe filesystem/process
changes. The explicit native status checking/primary-error preservation path
is present; tests cover invalid-frame/format and write-after-dispose failure.

No `iosSimulatorArm64Test`, `allTests`, positive TTS, recording, real foreground,
physical route or 10-minute wall-clock tests were run. These runtime gates
remain OPEN. CoreAudio file finalization/conversion is proven separately on
iOS 17; it is not TTS, microphone, real foreground or root decoder proof.
