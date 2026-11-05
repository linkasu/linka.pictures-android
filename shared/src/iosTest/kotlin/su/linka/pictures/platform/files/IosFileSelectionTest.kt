@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package su.linka.pictures.platform.files

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import okio.IOException
import platform.Foundation.NSNotificationCenter
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import su.linka.pictures.application.*
import kotlin.coroutines.resume
import kotlin.test.*

class IosFileSelectionTest {
    @BeforeTest fun mainDispatcher() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @AfterTest fun resetDispatcher() { Dispatchers.resetMain() }

    @Test fun boundedLifecyclesDetachTokensAndCallbacksWithoutGcAssumptions() {
        val center = NSNotificationCenter()
        var removed = 0
        repeat(32) {
            var callbacks = 0
            val selection = IosFileSelection("owner", { true }, center) { token ->
                center.removeObserver(token)
                removed++
            }
            selection.onBackground = { callbacks++ }
            assertEquals(3, selection.retainedObserverCount)
            center.postNotificationName(UIApplicationDidEnterBackgroundNotification, null)
            assertEquals(1, callbacks)
            selection.close(); selection.close()
            assertEquals(0, selection.retainedObserverCount)
            assertNull(selection.onBackground)
            center.postNotificationName(UIApplicationDidEnterBackgroundNotification, null)
            assertEquals(1, callbacks)
        }
        assertEquals(96, removed)
    }

    @Test fun failingRemovalStillDetachesAndAttemptsEveryTokenOnlyOnce() {
        val center = NSNotificationCenter()
        var attempts = 0
        val selection = IosFileSelection("owner", { true }, center) { token ->
            center.removeObserver(token)
            attempts++
            throw IOException("Test removal failure")
        }
        selection.close(); selection.close()
        assertEquals(3, attempts)
        assertEquals(0, selection.retainedObserverCount)
    }

    @Test fun dismissedCancelledResultKeepsItsPrimaryOutcome() = runTest {
        primarySurvivesCleanup(FileFailure(OperationResult.Cancelled))
    }

    @Test fun dismissedFailedResultKeepsItsPrimaryOutcome() = runTest {
        primarySurvivesCleanup(FileFailure(problem(ProblemCode.UNAUTHORIZED)))
    }

    @Test fun cancellationExceptionSurvivesDismissAndRemovalFailures() = runTest {
        primarySurvivesCleanup(CancellationException("Primary cancellation"))
    }

    private suspend fun primarySurvivesCleanup(primary: Throwable) {
        val center = NSNotificationCenter()
        var removals = 0
        var dismissals = 0
        val selection = IosFileSelection("owner", { true }, center) { token ->
            center.removeObserver(token)
            removals++
            throw IOException("Test removal failure")
        }
        try {
            val actual = assertFailsWith<Throwable> {
                selection.await(
                    present = {
                        if (primary is CancellationException) it.cancel(primary)
                        else it.resumeWith(Result.failure(primary))
                    },
                    dismiss = { dismissals++; throw IOException("Test dismiss failure") },
                )
            }
            assertSame(primary, actual)
        } finally { selection.close() }
        assertEquals(1, dismissals)
        assertEquals(3, removals)
        assertEquals(0, selection.retainedObserverCount)
    }

    @Test fun dismissFailureWithoutPrimaryIsReportedForIoMapping() = runTest {
        val selection = IosFileSelection("owner", { true }, NSNotificationCenter())
        val cleanup = IOException("Test dismiss failure")
        try {
            assertSame(cleanup, assertFailsWith<IOException> {
                selection.await(present = { it.resume(Unit) }, dismiss = { throw cleanup })
            })
        } finally { selection.close() }
        assertEquals(0, selection.retainedObserverCount)
    }
}
