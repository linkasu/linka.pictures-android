# F: isolated iOS17 FFmpeg8.1.2 decoder candidate

**Experimental capability PASS, not production P07/G1/release acceptance.**
COORD approved the audio-only C harness after the initial CLI dependency gap;
no extra video filters or upstream patches were introduced. Shipping/linking/legal
decisions remain COORD-owned and OPEN.

## Scope / reproducible evidence

- Base `152cbb9b06f5fafcdbff86071224791bb4744cd2`, branch `implementation/p07-ffmpeg-preflight`.
- Only source changes: `scripts/preflight-ios-ffmpeg.py`,
  `scripts/preflight-ios-ffmpeg-decode.c`, this report.
- Root `/Users/aacidov/.local/share/opencode/worktree/67846f/pokazhi-p07-ffmpeg-preflight`.
- Final evidence directory (ignored):
  `build/ios-ffmpeg-preflight/1c1cd004-e6c7-41e5-bfed-3c4c39933762/`.
  `result.json` contains every actual argv/cwd/log/deadline, full configure lines,
  library/config hashes, runtime info, native decoder results and host PCM measurements.
- Verified intake retained at `build/ios-ffmpeg-preflight/4ff2c26f-2438-4bb1-bdd0-97a3ebd92fcf/`;
  simulator libraries retained at `build/ios-ffmpeg-preflight/4ae587d3-d3ca-4de2-ad36-0ee3bf370728/iphonesimulator/install/`.
  Existing dirty script and all derived outputs were preserved across the user pause.

Executed final command:

```sh
python3 -B scripts/preflight-ios-ffmpeg.py \
  --verified-source-run 4ff2c26f-2438-4bb1-bdd0-97a3ebd92fcf \
  --simulator-libs-run 4ae587d3-d3ca-4de2-ad36-0ee3bf370728
```

For a fresh source/target build, omit both cache arguments. Cache reuse is limited
to UUID directories under this worktree's ignored preflight root; the tar SHA and
PGP signature are reverified, source is freshly extracted, configs and every archive
object are inspected, targets are not overwritten/reused for the other platform.

`python3 -B scripts/preflight-ios-ffmpeg.py --self-test` PASS: PCM alignment/finite/energy,
traversal/absolute-root/symlink escape and forbidden feature flag checks. Source size,
signature, actual feature lists, Mach-O metadata, owner/runtime and decoded PCM assertions
are mandatory in the full run. No host/Homebrew/SDK/global config/dependency changes,
Docker/CI/push, installs/normal apps, AVAudioSession/mic/playback/permission actions.

## Official source / signature / licensing facts

- Source: <https://ffmpeg.org/releases/ffmpeg-8.1.2.tar.xz>, HTTP200,
  **11,710,924 bytes**, capped at50MiB. Computed archive SHA256:
  `464beb5e7bf0c311e68b45ae2f04e9cc2af88851abb4082231742a74d97b524c`.
- Detached signature `.tar.xz.asc`, HTTP200,520bytes;
  SHA256 `0a0963fccd70597838073f3e31b20f4a4d8cc2b5e577472c9a5a1f22624246f8`.
- `gpgv` VALIDSIG **FCF986EA15E6E293A5644F10B4322F04D67658D8** PASS,
  official key <https://ffmpeg.org/ffmpeg-devel.asc>, fingerprint pinned against
  <https://ffmpeg.org/download.html>. Fresh owned public keyring, not user's GPG home.
- Requested `.tar.xz.sha256` returned404; **no published SHA is asserted**.
  Computed SHA plus verified detached signature are the actual provenance evidence.
- Initial Python TLS handshakes had EOF/proxy timeouts; system curl with TLS verification,
  HTTPS-only, no redirects, size/deadline caps and disabled user curlrc succeeded.
  No insecure TLS, alternate version/tag/fork or binary download was used.
- Before extraction/build, LICENSE.md, COPYING.LGPLv2.1 and headers
  `libavcodec/vorbisdec.c`, `amrnbdec.c`, `libavformat/amr.c`, `mov.c` were inspected
  in memory: LGPL2.1-or-later notices. Full notices/source retained in the archive/tree.
  Native Vorbis/AMR-NB implementations are built-ins; configure's AMR-NB selection is
  `lsp celp_math`, not external libopencore/libvorbis. Actual config disables external codecs.
- Native executable `--info` reports `version=8.1.2`,
  **`license=LGPL version 2.1 or later`**, and actual avcodec/avformat/swresample configuration.
  CONFIG_GPL/NONFREE/VERSION3/NETWORK and LIBX264/LIBMP3LAME/LIBVORBIS are all0.

These are source/build facts, **not a legal opinion or distribution authorization**.
Corresponding-source delivery/notices, relink/replacement material, EULA/reverse-engineering
terms, static vs dynamic production linking, patents and App Store compatibility remain OPEN.
Official legal guidance: <https://ffmpeg.org/legal.html>. No artifact is added to production deps.

## Exact assembly / toolchain

Xcode **27.2 / 27B52**, `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer` per command.
SDKs iPhoneSimulator27.2 / iPhoneOS27.2, clang targets
`arm64-apple-ios17.0-simulator` and `arm64-apple-ios17.0`.
Each configure uses the matching xcrun clang/ar/ranlib/nm/sysroot, explicit owned prefix,
`--pkg-config=/usr/bin/false`, matching `--extra-cflags=-target …` and `--extra-ldflags=-target …`.
No deployment/minimum metadata or linker checks were suppressed. Upstream-generated warning
defaults remain in config.mak; no extra `-Wno` was added to pass this experiment.

Common actual configure arguments (complete paths/argv are in result.json):

```text
--enable-cross-compile --target-os=darwin --arch=aarch64
--disable-autodetect --disable-everything --disable-gpl --disable-nonfree
--disable-version3 --disable-network --disable-devices --disable-hwaccels
--disable-audiotoolbox --disable-videotoolbox --disable-securetransport
--disable-zlib --disable-bzlib --disable-lzma --disable-iconv
--disable-xlib --disable-libxcb --disable-avdevice --disable-swscale
--disable-doc --disable-debug --enable-small --enable-static --disable-shared
--disable-programs --enable-ffmpeg --enable-protocol=file
--enable-demuxer=ogg,amr,mov,wav,mp3
--enable-decoder=vorbis,amrnb,pcm_s16le,mp3
--enable-muxer=pcm_f32le,wav --enable-encoder=pcm_f32le,pcm_s16le
--enable-swresample --enable-avfilter
--enable-filter=abuffer,abuffersink,aresample,aformat,anull
--disable-filter=atrim,crop,format,hflip,null,rotate,transpose,trim,vflip
```

The earlier `f32le` configure name was mechanically corrected to `pcm_f32le`
(runtime raw format name is f32le). `CONFIG_FFMPEG=0` deliberately remains: upstream CLI
auto-select requires video/trim filters outside the whitelist. No CLI executable is claimed.
The accepted harness links only avformat/avcodec/swresample/avutil, not avfilter.
Existing optional PCM encoders/muxers/three audio filters in static libs were not expanded.
Upstream pkgconfig system requirements are `-lm`, `-pthread`, CoreFoundation/CoreVideo/CoreMedia;
no AudioToolbox/AVFoundation/session API is used by the harness.

Actual enabled components, identical simulator/device:

| Category | Values |
|---|---|
| Decoders | AMRNB, MP3, VORBIS, PCM_S16LE |
| Encoders | PCM_F32LE, PCM_S16LE |
| Demuxers | AMR, MOV, MP3, OGG, WAV |
| Muxers | PCM_F32LE, WAV |
| Protocol | FILE only |
| Configured filters | AFORMAT, ANULL, ARESAMPLE (abuffer/buffersink are internal) |
| HW accelerators | none |

Build correspondence hashes:

| File | Simulator SHA256 | Device SHA256 |
|---|---|---|
| config.h | `706cefe7ca82b305d8ed2625df5d24c8d74a3061f4dae9563d22bc73392fa41f` | `1cf8c049c022ced5340d3a24c7c471f66904b130a3ab586da0db16aea39d7cf8` |
| config_components.h | `c91a4de2713c386787481a24c34e566ef2472c7faf0e092d30bfd5b6a4c1eb58` | same |
| ffbuild/config.mak | `8b71a48e11e36a7b56644425acd3393c55a7cb722e5973e988bfaed48d935c04` | `4758a3f6c0cf1f365217d5b9b8c062647ace9a8b70c11df4562070d4b67bb9e5` |

Harness source SHA256 `e03503aac268004f28f0c2e2525fbada769865fce2d021a85ddf8a1493350c9d`.
Compiled with matching clang/sysroot/target, C17/O2/Wall/Wextra/Werror/partial-availability;
actual native binary SHA256 **`21749c4434485f0fa4e1318bb5f5ee2f2baa715839110dc2214ed30a7c2b22b0`**,
**1,289,488 bytes**, arm64, LC_BUILD_VERSION IOSSimulator7/min17.0.

## Actual iOS17 execution / PCM proof

Before spawn, owner check confirmed UDID **DD0F9A3F-DC32-4373-BA08-400FECE3A1CC**,
name `linka_platform_probe_ios17`, Booted, iOS17.0/**21A328**. Only public
`xcrun simctl spawn <ownedUDID> <owned-binary> …` ran. No normal app launch/install/audio output.

Inputs are the same hash-pinned OWN P06/P01 fixtures. Original SHA is verified before/after;
copied inputs are must-create. Vorbis5063/MP32612/AMR358/3GP962 bytes match the accepted SHA values.
WAV is own0.2s stereo16000Hz PCM16 sine,12844bytes,
SHA `f408f45e38a040910c40ac3851fafa8ed5248d04fc074d841b8ecbe0b93f2e51`.
It is independently reproduced through the existing own generator's pure wav_bytes function;
the manifest's `control_wave_sha256=2cff…` is a DIFFERENT mono square-wave control, not tone.wav.
No original fixture was rewritten and no host decode is counted as native proof.

Output is streaming **16000Hz/stereo/interleaved f32le**, measured from native-produced files.
All samples finite, frames>0, duration0.1..0.5s, nonzero RMS, native/host frames and RMS agree:

| Input / actual codec | Frames | Seconds | RMS | PCM SHA256 |
|---|---:|---:|---:|---|
| OGG / vorbis |4096|0.256|0.11443795279332822|`a4384e0afd0f7426b51149136358c0294c6d9ff9a0bf18f84e6a5b3aa7411cea`|
| MP3 / mp3 |3200|0.200|0.12293887709087434|`0bda878789166a2abc20a97d844bc3b31fb32ca673574ae4972fab1edd5a521a`|
| Raw AMR / amr_nb |3520|0.220|0.06831205032986142|`7b02caa8b6236697cde013447fdf69f37d111ac0638d24a7738c7611ec832c69`|
| 3GP / amr_nb |3520|0.220|0.06831205032986142|same|
| WAV / pcm_s16le |3200|0.200|0.12946113623004205|`bb7932f148d7867d6b01ebd1c3589a95406c8ecaddbeef30253669c85cf877a0`|

MP3 uses the approved built-in fixed-point `mp3`, not host default mp3float/external codec;
PCM SHA differs slightly from host producer validation, not a silent decoder swap.
AMR mono is resampled/upmixed to stereo by swresample (hence different RMS from mono source).

Boundaries exercised:

- Native custom AVIO: regular no-follow file, source size/total read cap64MiB, bounded seeks,
 30s monotonic interrupt callback and host process deadline. Decoder thread_count1.
- Metadata and decoded source/output duration capped600s; packed output uses fixed512KiB
 buffer and bounded input chunks, finite validation before each streamed write.
- Truncated own OGG64bytes: exit2,0frames, no output; one negative case, not a security corpus.
- Self-raised SIGINT after first1024 PCM frames: exit130/cancelled, partial output deleted.
 This covers cooperative/signal handler cleanup, not OS background/route lifecycle integration.
- Sparse64MiB+1 input: exit2/input-size-limit, read_bytes0, no output; no disk filling.
- Tiny601-second metadata WAV: exit2/metadata-duration-limit, no output.
- Existing owned output sentinel: exit2/File exists, original bytes not overwritten/deleted.
- All **5 original fixtures unchanged** after both complete runs.

## Device artifacts / cost / remaining gates

Only after simulator matrix PASS, same config cross-compiled static device libs, no device run/signing:

| Library | Simulator bytes | Device bytes |
|---|---:|---:|
| libavcodec.a |627368|628976|
| libavformat.a |724056|724080|
| libavutil.a |849880|849344|
| libswresample.a |106632|106584|
| libavfilter.a (unused by harness) |210064|209904|
| Total |2518000|2518888|

Every object (285 per target) has matching arm64 Mach-O platform/min17.0:
IOSSimulator7 vs IOS2, no mixed architecture. Per-library SHA values are retained in result.json.
Device prefix is fresh and separate; only `make -j4 install-libs install-headers` installs there.

Final cached-source run30.873s: harness compile0.455s, device configure13.257s,
device compile/install7.725s. Earlier simulator compile/install7.110s+0.361s;
each native tiny decode approximately0.17..0.24s. These are one-machine observations,
not production latency/RSS/size calibration. No RSS claim is made.

OPEN: full malformed/adversarial corpus and allocation/probe limits, unusual channel/rate
and parameter-changing streams (harness explicitly refuses changes), real external cancellation
and background/route/interruption handling, physical device execution, KMP/framework/product
binding, immutable-media vs compatibility-copy policy and all shipping/relink/source/notices/
App Store/patent/legal gates. Source seeks count against the same64MiB read budget, so this is
a conservative preflight bound, not a promise to accept every64MiB file. Source/target caps and
one decoded matrix do not constitute general untrusted-media safety or complete P07/G1 acceptance.
