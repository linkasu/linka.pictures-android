# P07 iOS inspection — envelope validation and bounded native decode

This is **not P07/G4 PASS**. No root/port/files/access/decoder changes or normal
app wiring are included. Branch `implementation/p07-ios-inspection` was created
from `8ace115`, then `b5874cc` was merged without resetting the image/files history.
The initial blocked report remains in history at `5919573`. COORD subsequently
approved the shared bounded envelope validation described in
`image-envelope.md`, before (not instead of) actual ImageIO pixel decoding.

## Current implementation

`IosMediaInspectionPort` is internal and takes the required suspend audio
inspection callback. There is no production audio fake, default unsupported
stub or new factory; future root wiring supplies real `IosAudioDecoder::inspect`.

Image inspection runs on IO. Absolute/native-valid/no-NUL local paths go through
lstat, O_NOFOLLOW open and fstat for a readable regular file with actual size
in 1..64 MiB. The already-owned descriptor supplies full POSIX pread loops to
`validateImageEnvelope`; the helper's 8 KiB buffer is reused without copying.
The descriptor always closes, and a primary envelope problem is not replaced
by a secondary close failure. The caller must keep its source immutable.

Only then is ImageIO opened through URL (never whole-file NSData). Actual source
UTI/UTType preferred MIME is mandatory, independent of extension/hints; absent
MIME is UNSUPPORTED_MEDIA, not a PNG fallback. Original numeric dimensions must
be integral, positive, <=8192 per side and <=16,000,000 pixels before thumbnail
allocation. Thumbnail side 1000 bounds even a square to decimal 1MP and the
requested <=1024 ceiling. Source/index statuses, positive decoded bounds,
immediate caching and draw/flush into a bounded 8-bit RGBA bitmap remain required.
Checks surround native heavy calls, with every CF/CG resource scoped by finally.
No original write, transform/re-encode, UI, permissions or root binding is added.

Unknown envelopes still require the native decoder; native supported formats
are not arbitrarily whitelisted or claimed to match Android21/Electron. This is
not universal compressed-stream integrity: a damaged payload with a recomputed
CRC and valid envelope can still be recovered by ImageIO. That accepted limit is
explicit; envelope validation does not replace native bounds/pixel checks.

## Historical native gap before the accepted envelope supplement

On the owner-checked, existing booted device
`DD0F9A3F-DC32-4373-BA08-400FECE3A1CC`, named
`linka_platform_probe_ios17`, runtime **iOS 17.0 (21A328)**, ImageIO accepts both:

- a real native-generated 127×63 PNG whose first eight IDAT payload bytes were
  overwritten with zeroes (including its compressed-stream header);
- the same native PNG truncated from 413 bytes to 206 bytes.

The agreed pipeline used `CGImageSourceCreateWithURL`, actual ImageIO UTI →
UTType MIME, original-property bounds before pixel allocation, an always-created
1000-side/1MP thumbnail, immediate caching, and a synchronous draw/flush into an
owned bounded RGBA bitmap. Both source and index statuses remain **Complete (0)**.
This is not fixed by materializing the thumbnail provider: a temporary focused
probe returned real 32-bit pixel data (127×63, row stride 512, **32256 bytes**)
for both original and damaged PNG, but different pixel hashes:

```text
source original SHA256: 62264a4af754e9ce9bb4c728c3b30bd1f159cb6d602ee76c3b1f0407a04266a0
source damaged  SHA256: 70f094b638607bf8a503c4619d4b6c03c6726a0448515598d8c5ebf2468fad49
pixels original SHA256: 526cd170f6830b8e50a09c462b30ebb2016e17a165900ec776e5ab48c28ba95f
pixels damaged  SHA256: 47a22accc6fb0aedaf4a5cf8014bdd569ead0efad399fdb8de134235e7c9bb10
source status=0; index status=0 (both cases)
truncated PNG: Completed(ImageInfo(127, 63, image/png))
truncated JPEG (1157/2 bytes): Failed(INVALID_DATA)
```

Therefore this pipeline demonstrates **native decoded/recovered pixels**, not
strict container/compressed-stream integrity. Treating a black/blank recovered
bitmap as invalid would also reject legitimate images; it is not a safe fix.
Re-encoding the recovered bitmap would break the accepted exact-copy policy.
No such heuristic, transform or ad-hoc format whitelist has been added.

## Accepted resolution

COORD chose not to accept these observed CRC-corrupt/truncated PNGs as Completed.
The shared Android-parity envelope supplement now rejects them before ImageIO;
the same three rejection regressions pass without weakening their assertions.
This is a narrow container-envelope rule, not a new format whitelist or codec.

## Focused checks

Both Native main targets compile and the ordinary simulator test binary links
with JDK 17, the existing Android SDK and Xcode 27.2. The appHostTest/Keychain
binary is neither requested nor executed; no ad-hoc host or AMFI bypass is used.

```sh
./gradlew :shared:compileKotlinIosArm64 :shared:linkDebugTestIosSimulatorArm64
xcrun simctl spawn DD0F9A3F-DC32-4373-BA08-400FECE3A1CC \
  "$PWD/shared/build/bin/iosSimulatorArm64/debugTest/test.kexe" \
  '--ktest_filter=su.linka.pictures.platform.capabilities.IosMediaInspectionPortTest.*'
```

Historical pre-envelope focused result: **14 passed / 3 failed**. The failures were strict damaged
PNG rejection, truncated PNG rejection, and the real files/inspector/preparation
integration's strict damaged-source rejection. Native PNG/JPEG MIME and no-hint
checks, actual 8192-axis and decimal 16MP boundaries, no-follow regular-file and
UTF-16/NUL/path checks, actual sparse 64 MiB+1 size rejection, source hashes,
real exact-copy and generated-text PNG integration, cancellation, and required
audio-callback forwarding checks pass. Audio callbacks here are explicitly
controlled test values, **not FFmpeg/audio-decoder proof**.

Inspection is read-only, uses lstat + O_NOFOLLOW open + fstat before ImageIO,
and scopes descriptors and CF/CG resources with finally. The caller must keep
its owned local source immutable through inspection; this is not a provider URL
or an authority check against hostile concurrent path replacement. Bounded native
calls check cancellation before/after; a C call itself is not forcibly aborted.
Only image zero is inspected; no all-animation-frame validation is claimed.

The temporary pixel-materialization probe was removed after capturing evidence.
Raw logs in approved temp: `p07-inspection-gap-build.log`,
`p07-inspection-gap-native.log`; they preserve the measurements above.
After removing the probe, `p07-inspection-blocked-build.log` confirms both Native
main compilations and the ordinary simulator test link; the focused repeat in
`p07-inspection-blocked-native.log` is still **14 passed / 3 failed** (782 ms).
Previously
accepted 53 files/image tests are not relabelled as full P07 acceptance. JVM and
default allTests, real picker/iCloud/UI foreground, physical-device and all-format
cross-platform gates remain outside this lane's verification.

## Final envelope + Native verification

Shared helper/CRC commit: `4afa13121dfa1f134871c9925f99dd41fd2060b3`.
After coherent-diff review, the **full JVM suite passed 257 tests across 21
suites, 0 failures/errors/skips**. CRC32's previous ZIP call sites and tests keep
their one-argument semantics; Android production is unchanged for parent/A's
separate switch.

Final focused Native: **87/87 passed** (2130 ms) on the explicitly owner-checked
iOS 17.0 (21A328) UDID above: 3 CRC, 14 envelope, 17 inspector, and all 53 prior
files/image/selection tests. The three formerly red malformed PNG/truncation/
preparation regressions are green without weakening assertions. Real native
PNG/JPEG generation, ImageIO, the actual files adapter and image preparation are
connected in the new integration tests; only the required audio callback is
controlled test data. Both iosArm64 and iosSimulatorArm64 main targets compiled
and the ordinary simulator tests linked. No appHostTest/Keychain run or AMFI
workaround was attempted.

```sh
./gradlew :shared:jvmTest
./gradlew :shared:compileKotlinIosArm64 :shared:linkDebugTestIosSimulatorArm64
xcrun simctl spawn DD0F9A3F-DC32-4373-BA08-400FECE3A1CC \
  "$PWD/shared/build/bin/iosSimulatorArm64/debugTest/test.kexe" \
  '--ktest_filter=su.linka.pictures.binary.Crc32Test.*:su.linka.pictures.media.ImageEnvelopeTest.*:su.linka.pictures.platform.capabilities.IosMediaInspectionPortTest.*:su.linka.pictures.platform.capabilities.IosImagePreparationPortTest.*:su.linka.pictures.platform.files.*'
```

Final logs in approved temp: `p07-envelope-full-jvm.log`,
`p07-inspection-final-build.log`, `p07-inspection-final-native.log`.
P07/G4/root/audio binding, actual picker/iCloud/UI foreground, all-image-format
cross-platform acceptance and universal compressed-stream canonicality remain
unclaimed.
