package su.linka.pictures.platform

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import su.linka.pictures.application.OperationResult
import su.linka.pictures.application.ProblemCode
import su.linka.pictures.domain.RequestId

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class AndroidSystemRequestsTest {
    class IsolatedActivity : ComponentActivity()

    private val controller = Robolectric.buildActivity(IsolatedActivity::class.java)
    private lateinit var activity: IsolatedActivity
    private lateinit var scope: CoroutineScope
    private lateinit var requests: AndroidSystemRequests
    private val registry = RecordingRegistry()
    private var ownerOpen = true
    private var presentations = 0
    private var onPresentation: () -> Unit = {}
    private var result: OperationResult<*>? = null
    private val id = RequestId("first")

    private class RecordingRegistry : ActivityResultRegistry() {
        val codes = mutableListOf<Int>()
        override fun <I, O> onLaunch(
            requestCode: Int,
            contract: ActivityResultContract<I, O>,
            input: I,
            options: ActivityOptionsCompat?,
        ) { codes += requestCode }
    }

    @Before fun setup() {
        activity = controller.setup().visible().windowFocusChanged(true).get()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        requests = AndroidSystemRequests(activity, { ownerOpen }, {
            presentations++
            onPresentation()
        }, scope, registry)
        idle()
        assertTrue(activity.hasWindowFocus())
    }

    @After fun teardown() {
        requests.close()
        scope.cancel()
        idle()
        if (activity.lifecycle.currentState != Lifecycle.State.DESTROYED) controller.pause().stop().destroy()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun document(requestId: RequestId = id): Job = scope.launch {
        result = requests.requestDocument(Intent(Intent.ACTION_OPEN_DOCUMENT), requestId, "owner")
    }.also { idle() }

    private fun documentResult(code: Int = registry.codes.last(), ok: Boolean = true) {
        registry.dispatchResult(code, if (ok) Activity.RESULT_OK else Activity.RESULT_CANCELED,
            Intent().setData(Uri.parse("content://documents/item")))
        idle()
    }

    @Test fun documentReturnsTransientUriAndIgnoresDuplicate() {
        document()
        documentResult()
        assertEquals(OperationResult.Completed(Uri.parse("content://documents/item")), result)
        result = null
        documentResult()
        assertNull(result)
        assertEquals(1, presentations)
    }

    @Test fun lateOldCallbackCannotCompleteNewRequest() {
        document()
        val old = registry.codes.single()
        requests.cancel(id)
        idle()
        assertEquals(OperationResult.Cancelled, result)
        result = null
        document(RequestId("second"))
        assertNotEquals(old, registry.codes.last())
        documentResult(old)
        assertNull(result)
        documentResult()
        assertTrue(result is OperationResult.Completed)
    }

    @Test fun secondRequestConflictsWithoutReplacingFirst() {
        document()
        var second: OperationResult<Unit>? = null
        scope.launch { second = requests.requestCredential(Intent(), RequestId("second"), "owner") }
        idle()
        assertEquals(ProblemCode.CONFLICT, (second as OperationResult.Failed).problem.code)
        assertEquals(1, registry.codes.size)
        documentResult()
        assertTrue(result is OperationResult.Completed)
    }

    @Test fun positiveResultWaitsForResumedAndFocusedActivity() {
        document()
        controller.windowFocusChanged(false).pause()
        documentResult()
        assertNull(result)
        requests.onForeground()
        idle()
        assertNull(result)
        controller.resume().windowFocusChanged(true)
        requests.onForeground()
        idle()
        assertTrue(result is OperationResult.Completed)
    }

    @Test fun ownerClosedWhileWaitingForForegroundCancelsResult() {
        document()
        controller.windowFocusChanged(false)
        documentResult()
        ownerOpen = false
        controller.windowFocusChanged(true)
        requests.onForeground()
        idle()
        assertEquals(OperationResult.Cancelled, result)
    }

    @Test fun presentationCanRevokeOwnerBeforeLaunch() {
        onPresentation = { ownerOpen = false }
        document()
        assertEquals(OperationResult.Cancelled, result)
        assertTrue(registry.codes.isEmpty())
        assertEquals(1, presentations)
    }

    @Test fun presentationCanCancelOrCloseBeforeLaunch() {
        onPresentation = { requests.cancel(id) }
        document()
        assertEquals(OperationResult.Cancelled, result)
        assertTrue(registry.codes.isEmpty())
        onPresentation = { requests.close() }
        result = null
        document()
        assertEquals(OperationResult.Cancelled, result)
        assertTrue(registry.codes.isEmpty())
    }

    @Test fun cancellationFromAnotherThreadDuringPresentationPreventsLaunch() {
        onPresentation = {
            Thread { requests.cancel(id) }.apply { start(); join() }
        }
        document()
        assertEquals(OperationResult.Cancelled, result)
        assertTrue(registry.codes.isEmpty())
    }

    @Test fun deniedPermissionDoesNotWaitForForeground() {
        scope.launch { result = requests.requestMicrophonePermission(id, "owner") }
        idle()
        controller.windowFocusChanged(false).pause()
        registry.dispatchResult(registry.codes.single(), false)
        idle()
        assertEquals(ProblemCode.PERMISSION_DENIED, (result as OperationResult.Failed).problem.code)
        controller.resume().windowFocusChanged(true)
    }

    @Test fun recreationDoesNotAcceptOldTransportAsNewRequest() {
        document()
        val oldCode = registry.codes.single()
        controller.recreate()
        activity = controller.windowFocusChanged(true).get()
        idle()
        result = null
        requests = AndroidSystemRequests(activity, { ownerOpen }, {}, scope, registry)
        document(RequestId("recreated"))
        documentResult(oldCode)
        assertNull(result)
        documentResult()
        assertTrue(result is OperationResult.Completed)
    }

    @Test fun callerCancellationDiscardsOldResult() {
        val job = document()
        job.cancel()
        idle()
        documentResult()
        assertNull(result)
        assertTrue(job.isCancelled)
        document(RequestId("second"))
        documentResult()
        assertTrue(result is OperationResult.Completed)
    }

    @Test fun parentScopeCancellationRejectsPendingCallback() {
        // The caller need not be a child of the supplied root scope.
        val caller = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        caller.launch { result = requests.requestCredential(Intent(), id, "owner") }
        idle()
        scope.cancel()
        registry.dispatchResult(registry.codes.single(), Activity.RESULT_OK, null)
        idle()
        assertEquals(OperationResult.Cancelled, result)
        caller.cancel()
    }

    @Test fun closeAndDestroyRejectCallbacks() {
        document()
        controller.pause().stop().destroy()
        documentResult()
        assertEquals(OperationResult.Cancelled, result)
        result = null
        document(RequestId("after-destroy"))
        assertEquals(OperationResult.Cancelled, result)
    }

    @Test fun documentCancellationAndMissingUriAreCancelled() {
        document()
        documentResult(ok = false)
        assertEquals(OperationResult.Cancelled, result)
        result = null
        document(RequestId("second"))
        registry.dispatchResult(registry.codes.last(), Activity.RESULT_OK, Intent())
        idle()
        assertEquals(OperationResult.Cancelled, result)
    }

    @Test fun permissionAlreadyGrantedDoesNotPresentOrRevoke() {
        shadowOf(activity.application).grantPermissions(Manifest.permission.RECORD_AUDIO)
        scope.launch { result = requests.requestMicrophonePermission(id, "owner") }
        idle()
        assertEquals(OperationResult.Completed(Unit), result)
        assertEquals(0, presentations)
        assertTrue(registry.codes.isEmpty())
    }

    @Test fun permissionDenialIsPermissionDenied() {
        scope.launch { result = requests.requestMicrophonePermission(id, "owner") }
        idle()
        registry.dispatchResult(registry.codes.single(), false)
        idle()
        assertEquals(ProblemCode.PERMISSION_DENIED, (result as OperationResult.Failed).problem.code)
    }

    @Test fun credentialOnlyMapsTransportResult() {
        scope.launch { result = requests.requestCredential(Intent(), id, "owner") }
        idle()
        registry.dispatchResult(registry.codes.last(), Activity.RESULT_CANCELED, null)
        idle()
        assertEquals(OperationResult.Cancelled, result)
        scope.launch { result = requests.requestCredential(Intent(), RequestId("second"), "owner") }
        idle()
        registry.dispatchResult(registry.codes.last(), Activity.RESULT_OK, null)
        idle()
        assertEquals(OperationResult.Completed(Unit), result)
    }
}
