# R2: default language before audio preflight

`AudioCoordinator` accepts a trailing `defaultLanguage: () -> String? = { null }`.
The composition root must bind it to a read-only cached value of the actual
`PortableSettings.languageTag`; the callback must not query SQL. Root wiring is
outside this change. Existing callers retain the existing OS-default semantics
when both the explicit language and callback result are null.

For each executing playback work containing implicit text language, the
coordinator reads the callback once and resolves all implicit text segments
before whole-plan voice checks, media inspection or first audio. Prepared chunks
retain that language even when the setting changes during playback. Explicit
segment language always wins, including in mixed-language plans. Recording-only
and empty plans, microphone recording, and explicit-only requests never query
the callback. Synthesis applies the same resolution to a copied request before
voice/length checks and before passing it to the backend.

NUL and malformed UTF-16 in explicit or resolved language are `INVALID_DATA`,
without trimming, coercion or fallback. An unsupported configured language is
`VOICE_UNAVAILABLE` before any recording segment can play; no other language is
silently selected. Existing chunking, voice snapshot, cancellation, lease and
temporary-file ownership behavior is unchanged.

## Verification

```sh
./gradlew :shared:jvmTest --tests su.linka.pictures.audio.AudioCoordinatorTest --offline --console=plain
git diff --check
```

The focused JVM suite has 44 tests (37 existing + 7 new), with zero failures,
errors or skipped tests. The new tests use existing `NativeAudioFake` and are
common-coordinator contract evidence, **not native speech proof**. They cover
unsupported mixed-plan preflight, supported/chunked defaults, explicit override,
mid-play setting changes, synthesis, malformed languages and recording without
TTS/default queries. No device audio, permissions, OS prompts or historical native
test totals are claimed. Both Native compilations were skipped: this worktree
has no prepared `shared/build/ios-audio-decoder` cache, so a safe cached-only
compile was unavailable.
