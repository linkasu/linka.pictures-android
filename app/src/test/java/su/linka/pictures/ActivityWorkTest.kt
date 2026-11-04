package su.linka.pictures

import android.os.Looper
import androidx.activity.ComponentActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class ActivityWorkTest {
    @Test fun backgroundWorkIsSingleFlightAndDeliversOnMain() {
        val owner = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val work = ActivityWork(owner.get())
        val release = CountDownLatch(1)
        var delivered = false
        work.run({
            assertNotEquals(Looper.getMainLooper(), Looper.myLooper())
            check(release.await(5, TimeUnit.SECONDS))
            42
        }, {
            assertEquals(Looper.getMainLooper(), Looper.myLooper())
            assertEquals(42, it)
            delivered = true
        }, { throw AssertionError(it) })
        assertTrue(work.busy)
        work.run({ error("Repeated operation must be rejected") }, {}, { throw AssertionError(it) })
        release.countDown()
        awaitWork { delivered }
        assertFalse(work.busy)
        owner.pause().stop().destroy()
    }

    @Test fun destroyedHostGetsNoCompletionButStartedCommitFinishesAndReleasesResult() {
        val owner = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val work = ActivityWork(owner.get())
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val committed = AtomicBoolean(false)
        val cleaned = AtomicBoolean(false)
        work.run({
            started.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            committed.set(true)
            "workspace"
        }, { fail("Destroyed Activity must not receive a result") }, { throw AssertionError(it) },
            abandoned = { assertEquals("workspace", it); cleaned.set(true) })
        assertTrue(started.await(5, TimeUnit.SECONDS))
        owner.pause().stop().destroy()
        release.countDown()
        awaitWork { cleaned.get() }
        assertTrue(committed.get())
    }
}

internal fun awaitWork(ready: () -> Boolean) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
    while (!ready() && System.nanoTime() < deadline) {
        shadowOf(Looper.getMainLooper()).idle()
        Thread.yield()
    }
    check(ready()) { "Background operation did not complete" }
    shadowOf(Looper.getMainLooper()).idle()
}

internal fun awaitHostWork(host: Any) {
    val field = runCatching { host.javaClass.getDeclaredField("work") }.getOrNull() ?: return
    field.isAccessible = true
    val work = field.get(host) as ActivityWork
    awaitWork { !work.busy }
}
