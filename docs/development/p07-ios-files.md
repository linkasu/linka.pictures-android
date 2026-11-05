# P07 iOS files — conditional native adapter

This lane adds `IosPlatformFilesPort` only; no composition-root, entitlement,
permission, access-policy or decoder wiring. This is not P07/G4 acceptance.

## Ownership and threads

The internal constructor takes app-private `temporaryRoot`, immutable `mediaRoot`,
`FileSystem`, a Main-only presenter, thread-safe `isOwnerActive`, and the process-owned
`CoroutineScope`. Roots must be absolute, normalized, native-valid, existing,
canonically disjoint app-owned directories. The adapter never chooses a root
from a provider URL. Configuration of genuinely private roots is the root lane's
responsibility, not inferred from a string prefix.

Synchronous creation/source/sink and all stream operations have the accepted
worker-caller contract. They do not dispatch synchronously from Main. P12 must
wrap outer workflows in IO; calling `LinkaCodec` directly from UI is unsupported.
Suspend import/export perform their own IO dispatcher hop. Presenter, UIKit
delegate, presentation and dismissal run on Main and affect only our controller.

`createTemporaryFile` must-creates a UUID path, unrelated to the passive display
name. Registry membership uses handle identity, not its path. Sources accept
only registered temporary handles or regular immutable files contained in the
configured media root after normalization and canonicalization; temporary
lookalikes, traversal, symlink escapes and credential/DB paths outside that root
are rejected. A sink is single-open and requires an owned empty regular file;
it writes via an existing file handle, without truncation.

`discard` is the deliberate any-thread exception: it invalidates registry
ownership synchronously without filesystem work, then schedules best-effort
unlink in the injected worker scope. A revoked handle is immediately NOT_FOUND.
No GlobalScope or public drain API is used. Process shutdown, cancelled scope or
filesystem refusal may leave private cache bytes; cleanup cannot touch immutable
media or turn an operation's primary outcome into a deletion error.

## Picker and destination

All kinds use `UIDocumentPickerViewController` in open mode, not automatic
export. IMAGE restricts to system image types, AUDIO to audio, archives to data;
there is no broad photo-library grant. A selected security-scoped URL is held
through NSFileCoordinator's completed streaming copy. Source bytes are never
modified. Import budgets are IMAGE/AUDIO 64 MiB, LINKA 500 MiB, BACKUP 2 GiB:
metadata is only a precheck and actual 64 KiB chunks are counted independently.
No whole-file NSData buffer is used. External URLs are never persisted.

Folder selection uses UTType.folder, does not copy content, and creates an
owner/request-bound `ExportDestination`. Leaf names, MIME syntax and native
Unicode are validated without trimming, normalizing, renaming or auto-suffixes.
Starting a newer request for the owner revokes the earlier one. Actual background
revokes a pending request; active/inactive notifications gate foreground checks.
The shell must also close handles on owner close; the owner predicate is checked
at callback/copy/write boundaries, not polled by an invented timer.

Destination state is NEW → WRITING → terminal; close during IO marks CLOSING,
retains scope until streams close, and prevents subsequent admissions. Close is
idempotent and never waits for provider IO. The tiny state lock covers only
FSM/current-request/foreground/owner checks and the synchronous guard/admission,
never metadata, open, reads, writes, flush or close. The last successful admission
authorizes that one in-flight syscall/chunk: close after admission may leave an
empty or partial provider file, but forbids every later admission. State and
cancellation are checked again after blocking units; revoked work cannot return
Completed. After IO finishes, the terminal outcome is linearized against close
under that same tiny state lock. This lock is independent of `discard` and the
handle registry.
After choosing, the workflow must reauthorize and explicitly call write with its
fresh guard. Owner/current request/foreground, cancellation and guard are checked
immediately before must-create external open and between bounded chunks. Output
has the overall 2 GiB archive ceiling. Existing targets are conflicts, not
overwrite requests. Source stays owned by the caller until terminal completion.
Completed follows successful write, flush and both closes.

Once provider open has happened, cancel/revoke/IO can leave an external partial
file. The adapter does not delete provider files or claim rollback. Destination
scope is released at terminal/close; nothing resumes automatically after restart.

## Focused verification and remaining gates

Native tests use real files in a per-test UUID private directory, real local
NSURL/NSFileCoordinator calls, and a synthetic owned-directory destination.
Controlled wrappers test lying metadata and lifecycle/guard failure without
pretending that those controls are OS authorization. Cleanup tests await an
injected filesystem deletion latch, not a public test-only drain API. Existing
22 ImageIO image tests remain, with only the fake's new destination method updated.

Correction regressions hold provider open/write on injected filesystem latches,
then require close to return while those latches remain held, keep scope alive
until stream closure and permit no later unit. Both tests failed before the fix
with `close waited for provider IO`. Close is exercised on a worker so even a
regression cannot freeze Main; waits are criterion-based and bounded, not sleeps.

Selection close detaches and clears observer tokens, continuation and scope
under its tiny lock, then removes/releases them outside it. Cleanup attempts all
tokens even if one removal fails. A 32-cycle isolated native notification-center
check verifies explicit retained-token count zero, exactly 96 removals and no
callbacks after close, not GC/ARC timing or a measured leak-free process heap.
The existing internal await seam injects presentation/dismiss callbacks in tests;
throwing dismissal/removal preserves original Cancelled/Failed/cancellation,
and dismissal failure without a primary is propagated for IO_FAILURE mapping.
UI dismissal remains Main-only; best-effort Foundation scope/observer close is
any-thread and no longer adds a Main dispatcher hop to port cleanup finally.
Await cleanup tests inject callbacks and replace the Main dispatcher; they are
not tests of a real UIKit presentation, security scope or OS foreground gate.

Build (JDK 17, existing Android SDK, Xcode 27.2):

```sh
./gradlew :shared:compileKotlinIosArm64 :shared:linkDebugTestIosSimulatorArm64
```

After verifying the existing available booted device's exact UDID, name
`linka_platform_probe_ios17` and iOS-17-0 runtime, execute only:

```sh
xcrun simctl spawn DD0F9A3F-DC32-4373-BA08-400FECE3A1CC \
  "$PWD/shared/build/bin/iosSimulatorArm64/debugTest/test.kexe" \
  '--ktest_filter=su.linka.pictures.platform.files.*:su.linka.pictures.platform.capabilities.IosImagePreparationPortTest.*'
```

No default allTests/runtime selection, user files, permission prompts, device
changes or normal-app installation are used. **Still OPEN:** actual picker UI
and cancellation callbacks, real security-scoped grants, Files/iCloud provider
coordination, UIApplication foreground/background behavior with a live app,
owner teardown/root wiring, and physical device behavior. Local synthetic tests
prove none of those gates. Full/JVM suites and real provider acceptance are not
claimed.

Pre-correction focused run on the owner-checked UDID above: **44/44 passed** (1033 ms):
22 files tests plus all 22 pre-existing image tests. Native runtime reported
`Version 17.0 (Build 21A328)`. Both iosArm64 and iosSimulatorArm64 main compilations
and the simulator test link succeeded; new adapter files have no opt-in warnings.
Logs in approved temp: `p07-files-final-build.log`, `p07-files-final-native.log`.

Final correction run: **53/53 passed** (694 ms): 25 files, 6 selection-lifecycle
and all 22 image tests, on the same owner-checked iOS 17.0 (21A328) UDID. Both
Native main targets compiled and the simulator tests linked. The admitted-open
and admitted-write close tests passed while their provider latches remained held;
they allow exactly the already-admitted empty-file/chunk effect and no subsequent
write, and verify release only after streams close. A native source can return
less than the requested 64 KiB; the test checks the actual admitted byte count.
Correction logs in approved temp: `p07-files-correction-red-native.log`,
`p07-files-correction-final-build.log`, `p07-files-correction-final-native.log`.
No ad-hoc Keychain host, AMFI workaround or real picker/iCloud/UI acceptance run
was attempted.
