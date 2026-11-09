# Shared bounded image envelope

COORD-approved supplement to native image bounds/pixel decode, not an image
codec, supported-format whitelist, canonicality check or MIME classifier.

```kotlin
internal fun validateImageEnvelope(
    byteCount: Long,
    readAt: (Long, ByteArray, Int) -> Unit,
    check: () -> Unit,
): OperationResult<Unit>
```

Implementation: `shared/src/commonMain/kotlin/su/linka/pictures/media/ImageEnvelope.kt`.
The caller supplies the **actual** length of an immutable owned source.
`readAt(offset, buffer, count)` must fully fill `buffer[0 until count]` or throw
IOException; the helper never owns or closes its source. The caller's coroutine
ensureActive is checked before/after reads, between chunks and before returning
a validation/IO problem. Cancellation propagates, not INVALID_DATA/IO_FAILURE.

- More than 64 MiB → LIMIT_EXCEEDED; fewer than 12 bytes → INVALID_DATA.
- Exact PNG magic → first IHDR length 13, unsigned big-endian chunk lengths,
  subtraction-checked extents, CRC over every chunk's type+data, nonempty IDAT
  before zero-length IEND at exact EOF. No extraction/inflation/pixel decoding.
- JPEG SOI → FFD9 at EOF, with only prefix and tail reads; no body scan.
- Android's existing GIF8 family (including GIF87a/GIF89a) → 3B at EOF. Version
  validity remains the native decoder's job; this preserves Android parity.
- RIFF → unsigned little-endian declared size + 8 equals actual length.
- Unknown signature → envelope Completed, **not supported-image proof**. Every
  caller still must perform real native bounds and bounded pixel decoding.

All reads reuse one 8 KiB array; no whole-file allocation or payload copies.
PNG CRC reuses the **existing** ZIP CRC32 algorithm, moved without changing its
table/state/finalization to `su.linka.pictures.binary.Crc32`. The added
`update(bytes, offset = 0, count = bytes.size - offset)` validates slices and
allows partial buffer updates without copies. All old ZIP callers/tests now
import that class; one-argument semantics are unchanged. Android production's
older envelope implementation is intentionally not changed in this lease;
parent/A will switch it separately.

Known limit: correct envelope/CRC is not compressed-stream or full-container
canonicality. For example, recomputed CRC can conceal damaged PNG compressed
data, and ImageIO can recover pixels from it. JPEG/GIF/RIFF checks are deliberately
only the existing cheap Android envelope checks. No universal strict PNG/JPEG
parser, all-animation-frame verification or cross-platform format acceptance is
claimed. Native decode and MIME discovery are mandatory after envelope success.

Tests cover independent CRC vectors/IEND and IHDR constants, slices and legacy
ZIP use, bounded buffer reuse, chunk CRC corruption/type coverage, all truncated
prefixes/trailing bytes, IHDR/IDAT/IEND conditions, unsigned/extents limits,
random-read trailer cost, unknown-signature behavior, IO failure and cancellation.
Full JVM after coherent-diff review: **257 tests, 0 failures/errors/skips** across
21 suites, including 3 CRC and 14 envelope tests. Those same 17 focused common
tests also passed on owned iOS 17 as part of the 87-test focused Native run.
Raw JVM log: approved temp `p07-envelope-full-jvm.log`.
