# P09 synthetic legacy admission

Implemented against `legacy-upgrade-protocol.md` (COORD, 2026-11-05), starting from `9548d04`. This change owns only `migration` common code/tests. **Not full P09, native P08 proof, root integration, or G4 PASS.** No real preferences, sets, passwords, device or normal APK were read/installed.

## Root binding

The internal constructor is exactly:

```kotlin
LegacyUpgrade(
    root: Path,
    legacySets: Path,
    files: FileSystem,
    driver: SQLiteDriver,
    createFiles: (Path) -> PlatformFilesPort,
    inspection: MediaInspectionPort,
    ids: IdSource,
    readSettings: suspend () -> LegacyUpgradeSettings,
    prepareCredentials: suspend () -> OperationResult<Unit>,
    validateCredentials: suspend () -> OperationResult<Unit>,
    readBoard: suspend (LocalFile, String, MediaStore, PlatformFilesPort) -> OperationResult<PreparedBoard>,
)
```

`LegacyUpgradeSettings(boardGrid: Map<String, Int>, parentEnabled: Boolean)` carries no password, verifier or password digest. Platform readers/credential material remain private and caller-owned. The mandatory reader seam is for parallel codec integration, not a production fallback: root must bind `LinkaCodec(media, files, inspection, ids).readLegacyUpgrade(file, name)` using the supplied staging media/files. There is no unsupported/fake default. The retained `inspection` constructor argument does not replace this binding; inspection is performed by that actual codec instance.

Root must supply the real bundled driver, registered native file port, a single coordinator per private root, and exclude all legacy writers before inventory. Root owns and closes the returned `StorageRuntime` before opening another. `open(resumeInterrupted = false)` returns `OperationResult<LegacyOpenedLibrary>` with runtime, native files and report; its `StateFlow` is passive, never an authorization mechanism.

On an existing valid `active`, only `validateCredentials` is called: no source inventory, settings reader or credential preparation. Validation must require an existing initialized valid record, including disabled records, without comparing it to legacy after activation. Before activation, root's credential callbacks must enforce the frozen protocol's exact legacy-state correspondence, idempotence, KDF and secret lifecycle. Callback fakes in these tests do not establish those properties.

## Persisted state and writers

* New storage remains schema 1 inside unique `root/libraries/<safeMigrationId>`. New board IDs are `legacy-<migrationId>-<sortedIndex>`; names are case-sensitive, content equality does not merge identities.
* Bounded 4 KiB journal/active records contain schema, safe migration ID, SHA256 inventory/nonsecret-settings digest, phase, counts and credential-initialization flag. No source names, absolute paths, URIs, secrets or password digests are persisted there.
* Each source is checked/stream-hashed, then stream-copied into a registered temporary before the codec sees it. Unsupported/corrupt ordinary files retain their exact SHA256/original bytes as ARCHIVE. I/O/unstable/oversized/nonregular/symlink failures block activation. Media leases survive until writer references are published; ports consume/close their sources.
* Writes use the actual runtime's migration-generation/epoch guard, not a successful UI boolean; no overwrite. Resume requires explicit opt-in and matching fingerprints; reopened SQLite listing, not journal counters, decides which stable IDs are already committed. Settings are compared before saving.
* A persisted UNCONFIRMED marker precedes credential preparation. Unknown callback/runtime/journal outcomes freeze that coordinator instance. An explicit new coordinator/reopen is required; no implicit retry.
* Before activation, all staging entries/counts/settings/media are checked, media bytes are hash-verified, and staging closes. READY_TO_PUBLISH precedes the active write. Its last guard checks journal generation/liveness, credentials and a fresh source/settings fingerprint.
* Journal/active writes use must-create owned temporary files, flush and same-root atomic rename. Rename exceptions get exactly one independent old/new byte readback: exact new succeeds; exact old propagates failure/cancellation; neither freezes as unknown. A confirmed active survives late cancellation/progress-write failure. Active is stronger than stale/corrupt auxiliary progress.
* Legacy and foreign generations/sentinels are never deleted. A bounded matching owned journal leftover is preserved, requires explicit resume and is never promoted over reopened SQLite. Unknown/foreign leftovers block. A crash before the first durable journal or before creating the generation's database can require manual diagnosis: no unproven directory/database is automatically adopted or created on resume.

`MigrationReport` keeps imported and retained counts separate (retained is not successful import), plus structured per-entry problems. Exact fresh codec problems are available in that run; subsequent opens recover the existing finite retained-reason mapping, without adding report fields to library schema. Receipt counts describe original admission, not current user-edited library totals.

Grid values 1/3 map to COMPOSE; other values map to DIRECT. Values 2/3 show page buttons. Missing keys default to 3; codec `inputStyle` is preserved. Board settings use stable IDs, with no PortableSettings/schema additions.

## Verification and integration gates

Focused command:

```sh
./gradlew :shared:jvmTest --tests 'su.linka.pictures.migration.*' --offline --console=plain
```

Tests use real bundled SQLite, real synthetic filesystem/media, actual external codec 1.0/1.2 and unsupported 2.99, original retained SHA/bytes, grids 0..3/invalid/default, controlled case-sensitive inventory on a case-insensitive host, generation guards, disk/oversize/nonregular/symlink failures, source/settings mutation, credential termination before/after callback, unknown outcome freeze, journal interruption boundaries, archive preparation/publication/settings pre/postcommit failures, unknown actual SQL commit followed by authoritative reopen, active rename old/new/unknown outcomes, postcommit progress loss, late cancellation, no reimport after new work and foreign/unknown state preservation.

Both `compileKotlinIosArm64` and `compileKotlinIosSimulatorArm64` passed. The already-generated primary checkout decoder producer was independently checked with its read-only `inspect` API (`exact=true`); this worktree used an ignored build-directory symlink and excluded the producer task. No native sources/Gradle were edited and no decoder rebuilt. This is compile evidence only.

Final combined gate passed: `./gradlew :shared:jvmTest :shared:compileKotlinIosArm64 :shared:compileKotlinIosSimulatorArm64 -x :shared:prepareIosAudioDecoder --offline --console=plain`: 294 JVM tests, including 16 migration tests, zero failures; both Native main compilations. Only existing expect/actual beta warnings remain.

Root/primary still must bind and test internal flat 1.3 against the parallel codec change (including original Git/APK assets and external-read whitelist), actual Android/iOS registered native files/inspection, real credential-store callbacks and startup writer exclusion. Actual OS/process kill, power-loss/directory durability and device behavior are not proven by synthetic exception injection. No real-source admission, G4 or release/normal-APK authorization follows from these checks.
