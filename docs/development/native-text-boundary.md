# Native text boundary: lossless values before publication

COORD reproduced a bundled SQLite2.5.2 text-bind limitation on owned
iOS17.0/21A328. A temporary common test opened only `:memory:`, bound `SELECT ?`,
and compared UTF16 units; no library file/user data was read or written.

- Normal Cyrillic, decomposed accent and emoji round-tripped exactly.
- `left\u0000right` came back as `left`.
- `left\uD800right` came back with a different UTF16 sequence
  (`...55296,56434,105...` instead of `...55296,114,105...`).

Command: public `simctl spawn` on owned UDID
`DD0F9A3F-DC32-4373-BA08-400FECE3A1CC`, linked Kotlin/Native common test executable,
filter `su.linka.pictures.shared.SqlTextBoundaryProbe.*`. The diagnostic test was
removed after the probe. This is evidence for this binding/runtime, not a claim
that every SQLite driver has identical behavior.

The failure path matters for restore: an ID/name accepted from backup could be
silently changed in native SQL columns, disagree with its serialized document,
and fail startup invariants after a seemingly successful publication. Values
must be rejected before that write, not trimmed/replaced/repaired afterward.

`text/NativeText.kt` therefore provides pure UTF16 well-formedness and a native
text predicate excluding NUL. Persistent identifiers, board/retained names and
MediaAsset.mediaType enforce the native predicate. Existing length/blank rules
are unchanged; ordinary Unicode is not normalized, lowercased or trimmed.
This is content validity, **not SQL escaping**; every SQL value is still bound.

Interchange JSON additionally checks raw and decoded strings/keys, including
escaped lone surrogates. NUL in opaque JSON data is not globally removed or
rejected: standard JSON escaping can preserve it, unlike native-bound IDs/names.
Speech requests reject malformed/NUL text before native playback/synthesis,
leaving the visible message untouched instead of claiming a truncated utterance.
The grapheme algorithm still preserves malformed UTF16 as documented; validation
at native/interchange boundaries is a separate concern.

Regression first ran red: `NativeTextContractTest` had 2 failures/3 tests because
IDs/names/media types accepted lossy values. After model guards, the focused
class passed 3/3 on JVM. Full codec/audio/native integration checks are recorded
by their owning gates; this focused result alone is not G4/release acceptance.
