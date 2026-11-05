# Development iOS decoder bridge — primary review / root binding pending

Base `457365579d3aba3c7f4d006d607da704de06019e`, branch
`implementation/p07-ffmpeg-preflight`. User explicitly approved development LGPL2.1+
integration, not shipping. Existing preflight457 script/harness/report are unchanged;
this separate bridge is not substituted for that original evidence.

## Final actual evidence

`build/ios-ffmpeg-preflight/0689189a-55e6-4c3a-b788-05f0acf22631/result.json`
is **DEVELOPMENT_PROOF_PASS**,102.51s including scoped Kotlin compile.

```sh
python3 -B scripts/build-ios-audio-decoder.py \
  --reuse-build-run 011b2be6-3303-4d22-b95e-3abfb68d9815 --prove --compile-kotlin
```

This resumes existing verified artifacts, does not repeat preflight457 or extract source.
It verifies official tar SHA/PGP, old config and FFmpeg library SHA, copies only small
headers/libs into fresh targets, rebuilds the changed bridge and retains old outputs.
For reproducible fresh target builds use `--verified-source-run <owned intake UUID>`.
No runtime launch occurs unless `--prove` is explicit. Make concurrency is at most-j4.

The interrupted `c556…` run failed during extraction with ENOSPC; no permission or policy
workaround was applied. After parent confirmed space, work resumed from cached outputs.
Any further disk/database failure must stop, not trigger a retry/build loop.

Owner checked immediately before native executions:
`DD0F9A3F-DC32-4373-BA08-400FECE3A1CC`, `linka_platform_probe_ios17`, Booted,
iOS17.0/21A328. Only public simctl spawn of the own test executable; no normal app,
AVAudioSession/mic/playback/permissions or Keychain/AMFI bypass. Restricted-entitlement
AMFI−424 is a separate BLOCKED gate and is not bypassed by this work.

## Sources and ABI

- `shared/src/nativeInterop/cinterop/include/linka_audio_decoder.h`
- `shared/src/nativeInterop/c/linka_audio_decoder.c`
- `shared/src/iosMain/kotlin/su/linka/pictures/platform/audio/IosAudioDecoder.kt`
- `scripts/build-ios-audio-decoder.py`, `scripts/test-ios-audio-decoder.c`

C API returns structured status/duration/format with an optional per-request cancellation
callback. No raw path/native error logging. Callback only checks cancellation and never
throws Kotlin across C. Inspect fully decodes/discards; compatibility decode writes
PCM16/stereo16000Hz WAV. Maximum output **38,400,044 bytes** (44byte header +600s PCM),
never canonical media/export source. Immutable input SHA remains unchanged.

Native writer accepts a caller-owned, fresh empty regular single-link FD, checks distinct
source inode, duplicates/closes only its own descriptor, never truncates/unlinks caller
paths. Test caller uses O_CREAT|O_EXCL|O_NOFOLLOW; cleanup starts only after successful own
open. Kotlin's trusted PlatformFilesPort factory is authority for a new empty reservation;
Kotlin opens O_NOFOLLOW, validates regular/empty/single-link/distinct inode before claiming
cleanup ownership. Foreign/nonempty/symlink/same-source reservations are not claimed or
discarded. Late/cancelled owned output is discarded only if the path still has the claimed
device/inode. A factory lying about ownership is outside this capability contract; primary
must not wire a broker that returns canonical/foreign paths.

Statuses are separate OK/UNSUPPORTED/INVALID/LIMIT/IO/CANCELLED. Kotlin maps them to current
OperationResult/ProblemCode, propagates coroutine cancellation, performs blocking work on IO
and serializes native calls process-wide. No save/picker/storage/Access behavior is added.

## Fixed bounded native policy

- source size + total read budget64MiB, no-follow regular input, bounded seeks;
- monotonic30s deadline + host35s process deadline;
- probe1MiB, analyze3s,128 probe packets, max8 format streams before find_stream_info;
- exactly one audio stream, not best-of-multiple; unsupported audio codec fails whole call;
- source channels<=8, rate<=192000 before own avcodec_open; probe threads option1;
- native codec/probe threading disabled in build (HAVE_PTHREADS=0), own decoder thread_count1;
- frame<=32768 samples, packet<=2MiB, checked64-bit plane offsets, source/output<=600s;
- fixed512KiB float +256KiB PCM16 chunk buffers, F32 finite validation before quantization;
  silence accepted, no full-file PCM allocation;
-16MiB per av_malloc allocation, initialized once, **not a total/RSS guarantee**;
- one native decoder gate, private process FFmpeg owner, no user-adjustable allocator knob;
- global quiet av_log policy for that private instance, no raw paths/metadata in logs;
- deny-all supplemental io_open regardless of protocol whitelist; no external path authority.

Changing audio parameters are explicitly unsupported, not skipped/reinitialized silently.
No general adversarial/RSS/latency/OS-background safety claim is made.

## BMFF rejection policy: actual external RED → GREEN

Initial run `c940…` was RED: ISO dref/url flags0 was ignored by FFmpeg8.1.2 and produced
embedded AMR220ms, `supplemental_denied=0`. Upstream mov.c771-776 discards unknown dref
types, so io_open alone cannot enforce cross-platform immutable-source policy. That
original external URL negative remains; it is not reclassified as success/passive data.

COORD approved a small before-FFmpeg rejection validator:

- pread only actual atom headers/minimal dref fields; checked64-bit parent/source extents;
- source64MiB, moov8MiB,4096 atoms including dref entries, depth8;
- extended64 and size0 within parent handled, short/overflow/refcount mismatch invalid;
- only known structural containers under moov are traversed; unknown/mdat payload is skipped,
  never searched for strings. Direct moov/rmra is unsupported;
- dref FullBox version0, count>0, every entry must be url-space/version0/flags1 with no
  location payload. External URL/URN/alias/unknown type is UNSUPPORTED, malformed INVALID,
  resource exhaustion LIMIT. Not just the selected first dref entry;
- native demuxer is forced after bounded container classification, so an audio-looking
  prefix cannot fall back to MOV and bypass the validator;
- this is not a sample-description/movie parser: STSD/audio data_reference_index mapping is
  left to native validation, not claimed as independently parsed. All declared drefs are local.

Final native corpus passes: external-file/http, URN, alias, unknown-ref, second-external,
self-location, dref version/count0/truncated/count4097, rmra, extended overflow/short header,
short box,4097 atoms, depth10, oversized sparse moov. Original external cases now fail
UNSUPPORTED with format0/frames0 before decoder open, with no output.
Supplemental io_open remains deny-all defense-in-depth; those early rejects do not need
to reach that callback. No upstream patch/private struct access/general movie parser.

Positive variants extended-moov, size0-moov, opaque-mdat and size0 opaque-mdat all produce
the exact genuine3GP compatibility WAV hash; mdat contains fake dref/rmra/URL bytes and is
not falsely inspected. Genuine fixture bytes are never modified.

## Actual output matrix / other negatives

| Own source | Frames | Duration ms | WAV bytes | WAV SHA256 |
|---|---:|---:|---:|---|
| Vorbis |4096|256|16428|`1ae31e8e23743f5b40167d6e6177df6e9c3a7f6d7d006bccf3b1a754ac46a5b1`|
| MP3 |3200|200|12844|`6fe3e7aac243c362a4147b1080f6c4e5e885a4340ec0219afed0343347da2403`|
| AMR-NB |3520|220|14124|`2cd3019a23c76f8316b4786dc0f902dbffb87d4e43001979e3c78a0bc1f2e0e4`|
| 3GP AMR-NB |3520|220|14124|same|
| PCM16 WAV |3200|200|12844|`f408f45e38a040910c40ac3851fafa8ed5248d04fc074d841b8ecbe0b93f2e51`|

Both full inspect and WAV decode agree on duration/frames for each. Actual file PCM16 is
stereo16000Hz, nonzero finite energy for these tones; independent silence case is accepted.
Original5 SHA unchanged. Truncated INVALID;9channels/192001Hz/601s/64MiB+1 LIMIT; PCM_U8
UNSUPPORTED; duplicate AMR audio tracks UNSUPPORTED. Per-request callback cancellation
returns CANCELLED and own partial WAV is removed. Existing nonempty foreign output returns
IO and keeps its prior hash. No host decoding is counted as native proof.

## Build correspondence / compile boundary

Official8.1.2 tar remains pinned:
`464beb5e7bf0c311e68b45ae2f04e9cc2af88851abb4082231742a74d97b524c`,
PGP FCF986EA15E6E293A5644F10B4322F04D67658D8. Original preflight report retains license
notices/source/legal facts. New assembly removes CLI/filters/encoders/muxers and threading;
only4 decoders,5 demuxers,file protocol, avcodec/avformat/avutil/swresample. GPL/NONFREE/
VERSION3/NETWORK/LIBVORBIS/LIBMP3LAME/LIBX264 all0; no autodetect/hwaccels/AudioToolbox.

Current C source SHA `cdc4ddd8612da4ab489288be605cbfcf6086070fc1c12dda98094f31d50aba46`.
Native test executable SHA `fd6152f5fec41696fe0bfa0ce93b05142fdcd73af146240547715253277c31db`,
1,266,696bytes. Simulator bridge archive15,056bytes SHA
`3a642f2da6778d15c1ef038ffc4e778c16a39224ce1726cc6e38758ccb72ebff`;
device bridge archive15,056bytes SHA `1deb5b6694136fdd979dbf6636ad1a6008b5dd6769eb0e7d20db3a983db8d955`.
All objects match arm64/platform IOSSimulator7 or IOS2/min17.0. Full configs/argv/SHA in result.
Device bridge compiled against the cached matching device FFmpeg libraries, never simulator libs.

**Scoped Kotlin+cinterop compilation PASS**, independent generated project containing
unchanged actual source snapshots (hashes recorded): OperationResult, LocalFile,
PlatformFilesPort, MediaInspectionPort, NativeText and IosAudioDecoder. No fake declarations.
It includes current2.2.21/coroutines1.10.2/Okio3.10.2 and generated cinterop; not a claimed
main binding. Late init-script root attempts failed diagnostics and were not bypassed.
Android SDK env now `/Volumes/data/developer-state/android-sdk`; no SDK/root files edited.

## Exact primary root proposal — not applied here

No tracked root Gradle/cinterop/def wiring was edited. Primary must review this security-
sensitive validator and fixtures, then choose explicit per-target verified prefixes.
Do NOT cherry-pick the iOS source alone without the binding below: ordinary main Native
compilation intentionally has no generated decoder package until primary supplies it.

Generated per-target def (under build/, never tracked machine-specific absolute paths):

```text
headers = linka_audio_decoder.h
headerFilter = linka_audio_decoder.h
package = su.linka.pictures.platform.audio.decoder
compilerOpts = -I<target-prefix>/include
staticLibraries = liblinka_audio_decoder.a libavformat.a libavcodec.a libswresample.a libavutil.a
libraryPaths = <target-prefix>/lib
linkerOpts = -lm -pthread -framework CoreFoundation -framework CoreVideo -framework CoreMedia
```

In shared/build.gradle, configure during normal kotlin target setup, NOT a late
projectsEvaluated hook (which failed KGP diagnostics here):

```groovy
['iosArm64', 'iosSimulatorArm64'].each { name ->
    def nativeTarget = kotlin.targets.getByName(name)
    nativeTarget.compilations.getByName('main').cinterops.create('linkaAudioDecoder') {
        definitionFile.set(file("${buildDir}/ios-audio-decoder/${name}/linka_audio_decoder.def"))
    }
}
```

Primary adds explicit build/generate task dependency for each cinterop, matching SDK17
target/minimum and target library prefix; no automatic runtime launch. Framework transitive
static-link/source/relink packaging remains a separate review. Compile MAIN+TEST both Native
targets, then integrate with real owned files broker/audio backend. Factory must exclusively
create fresh empty owned files; decoder's compatibility outputs must not be published/exported
as canonical recordings. The old save port is unused. This lane implements no files/pickers/
AVAudioBackend/Access and no product navigation or root composition.

OPEN: main binding/runtime Kotlin lifecycle tests, physical device execution, RSS/large corpus,
full allocation/fuzz review, background/route/audio-session integration, and all source/notices/
relink/EULA/patent/AppStore/shipping gates. No Keychain or restricted-entitlement AMFI workaround.

## Primary bug-review correction (separate commit after5f874af)

PCM16 conversion now clamps **after rounding** as well as bounding finite float input:
the near-full-scale positive interval cannot wrap32768 into−32768. `done` guards NULL
probe before per-entry av_dict_free when probe av_calloc fails. Kotlin explicitly maps
WAV and treats an unknown successful native format as INVALID_DATA, not coerced WAV.

`scripts/test-ios-audio-decoder-unit.c` includes the actual bridge.c in a test-only TU,
overrides only that TU's av_calloc, calls real static emit, injects probe allocation failure,
then verifies a subsequent real decode succeeds. Native assertions cover near-fullscale,
nextafter(1,0), ±1, overshoot, silence and NaN/±Inf; no production knob/upstream patch.
Actual owned iOS17 run `build/ios-ffmpeg-preflight/07666875-ebfb-4c5b-96e1-095d192671b4`
is DEVELOPMENT_PROOF_PASS: same-TU unit PASS, same5-format matrix/25 negative corpus,
BMFF variants/cancel/foreign checks, current scoped Kotlin compile PASS; original5 SHA unchanged.

Current C SHA `68007a6207c9c7a0765cbf91d5a780173ef87bb9ee4088e302f7c38c9c8de236`.
Corrected bridge archive simulator SHA `d6884a4f2f816552409a1d4f4d6e61faeec9315d98545234521e82ba4c47417e`,
device SHA `66f53b802c97dc9512be26dfd9892836eb863e24d8fa872438d70b7a96e6bf6d`.
Earlier hashes/results above remain baseline evidence, not silently replaced.
