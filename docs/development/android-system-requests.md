# Android system request hand-off

`app/src/main/java/su/linka/pictures/platform/AndroidSystemRequests.kt` is an
Activity-owned OS requester, not `AssistantAccess`, a capability store, or proof
of owner authentication. It has no root wiring yet.

## Root integration

Construct one `AndroidSystemRequests(activity, isOwnerOpen,
onPresentationStarted, scope)` per `ComponentActivity`. Construct on Main and
use an Activity-owned coroutine scope; do not retain it across recreation.

- Pass `::requestDocument` to `AndroidPlatformFilesPort`. The caller supplies
  the MIME policy and intent. A successful result returns a transient `Uri`;
  this requester never takes persistable grants or adds storage permissions.
- Pass `::requestCredential` to `AndroidDeviceOwnerVerifier`. `RESULT_OK` only
  means successful OS transport. The adapter/root must still verify Keyguard,
  freshness and the current root gate before issuing any authority.
- Pass `::requestMicrophonePermission` to the microphone backend. Already
  granted permission completes without presentation/revocation; denial maps to
  `Failed(PERMISSION_DENIED)`.
- `onPresentationStarted()` runs once immediately before an admitted OS launch.
  Synchronously revoke prior capabilities/guards there. It grants nothing;
  the requester rechecks owner/request/scope after the hook before launch.
- Call `onForeground()` from `onPostResume` and from
  `onWindowFocusChanged(true)`. Positive callbacks wait for native RESUMED,
  window focus, and a non-finishing/non-destroyed Activity. The hook does not
  substitute for these native checks. Cancel/failed results need not wait.
- Call `cancel(id)` when closing/revoking that request owner. Merely changing
  `isOwnerOpen` is not a push notification: it is checked before launch and
  delivery, and by `onForeground`. Owner-close must synchronously invalidate
  the root owner and call `cancel` for its pending ID.
- Call `close()` on root disposal. Activity destruction and scope cancellation
  also invalidate the requester. `cancel`/`close` are idempotent and may be
  called on any thread; they atomically invalidate, then schedule owned Main
  cleanup without blocking the caller.

## State and cancellation

There is one pending request per requester/Activity. A second request receives
`Failed(CONFLICT)`. A null pending slot is IDLE; an admitted request moves from
LAUNCHING to WAITING_SYSTEM_RESULT, optionally WAITING_FOREGROUND, then
TERMINAL. Closing permanently prevents further admission.

Each launch registers a fresh random key directly with the actual
`ActivityResultRegistry`, without a lifecycle-owner registration (therefore
registration after STARTED is supported). Only that key is unregistered.
No key, pending handle, auth state, or proof is saved across recreation.
Restored Android OS transport for an old key cannot complete a new request.
Duplicate and late callbacks cannot deliver into a replacement request.

Caller cancellation invalidates immediately; suspend cleanup runs on Main in
`NonCancellable`. Scope cancellation independently invalidates requests even
when the caller belongs to another scope. Once a terminal completion CAS has
admitted delivery, later cancellation cannot retract that completed operation.
Root must enforce its own authority gate at the eventual point of use.

Cancellation does not finish provider/Settings activities, revoke external
state, or delete partial external files. An already admitted OS launch may
finish after cancellation, but its late result is discarded.

## Verification and limits

`AndroidSystemRequestsTest` uses Robolectric with `Application::class`, an
isolated `ComponentActivity`, and a controlled `ActivityResultRegistry`
subclass dispatching results through the real registry. Run:

```sh
./gradlew :app:testDebugUnitTest --tests su.linka.pictures.platform.AndroidSystemRequestsTest :app:compileDebugKotlin
```

These are transport/state-machine tests, not proof of real OS dialogs or
device behavior. They neither exercise normal legacy application/Firebase
startup nor require actual permissions or an APK/device.

This requester does not distinguish Home from an external credential/Settings
activity, decide what counts as a real background transition, preserve real
capabilities, or establish full R1/foreground-security acceptance. Those are
root responsibilities; positive transport completion alone is never authority.
