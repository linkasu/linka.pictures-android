package su.linka.pictures.activity

import android.app.Activity
import android.view.ViewGroup
import android.view.View
import android.view.inspector.WindowInspector
import android.os.Looper
import android.os.SystemClock
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.runtime.snapshots.Snapshot
import org.junit.Assert.assertTrue
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowChoreographer
import java.time.Duration
import su.linka.pictures.awaitHostWork

internal fun assertComposeHost(activity: Activity) {
    awaitHostWork(activity)
    val content = activity.findViewById<ViewGroup>(android.R.id.content)
    assertTrue("Activity must mount the real shared Compose screen", content.getChildAt(0) is ComposeView)
    if (!(content.getChildAt(0) as ComposeView).hasComposition) composeNodes(activity)
    assertTrue("Shared screen composition must be created", (content.getChildAt(0) as ComposeView).hasComposition)
}

internal fun composeNodes(activity: Activity): List<SemanticsNode> {
    awaitHostWork(activity)
    val content = activity.findViewById<ViewGroup>(android.R.id.content)
    ComposeHostClock.active?.attach(content.getChildAt(0) as ComposeView)
    fun roots(view: View): List<RootForTest> = if (view is RootForTest) listOf(view)
        else if (view is ViewGroup) (0 until view.childCount).flatMap { roots(view.getChildAt(it)) }
        else emptyList()
    fun nodes(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::nodes)
    Snapshot.sendApplyNotifications()
    val looper = shadowOf(Looper.getMainLooper())
    looper.idle()
    ComposeHostClock.active?.advance()
    looper.idle()
    val nextFrameMillis = (ShadowChoreographer.getNextVsyncTimeNanos() + 999_999L) / 1_000_000L
    if (nextFrameMillis > SystemClock.uptimeMillis()) {
        looper.idleFor(Duration.ofMillis(nextFrameMillis - SystemClock.uptimeMillis()))
    }
    content.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY))
    content.layout(0, 0, 1080, 1920)
    return (roots(content) + WindowInspector.getGlobalWindowViews().flatMap(::roots)).distinct().flatMap { root ->
        root.measureAndLayoutForTest()
        nodes(root.semanticsOwner.rootSemanticsNode)
    }
}

internal fun composeTextsForTag(activity: Activity, tag: String): List<String> {
    val node = composeNodes(activity).first { it.config.getOrNull(SemanticsProperties.TestTag) == tag }
    fun texts(node: SemanticsNode): List<String> =
        node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } + node.children.flatMap(::texts)
    return texts(node)
}

internal fun awaitComposeTextsForTag(activity: Activity, tag: String, expected: List<String>) {
    var actual = emptyList<String>()
    repeat(120) {
        actual = composeTextsForTag(activity, tag)
        if (actual == expected) return
    }
    org.junit.Assert.assertEquals("Compose text in '$tag'", expected, actual)
}

internal fun composeTexts(activity: Activity): List<String> = composeNodes(activity).flatMap { node ->
    node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
}

internal fun clickComposeText(activity: Activity, text: String) {
    var target: SemanticsNode? = null
    repeat(120) {
        if (target == null) target = composeNodes(activity).firstOrNull { node ->
            node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == text } &&
                node.config.getOrNull(SemanticsActions.OnClick) != null
        }
    }
    val node = target ?: error("No clickable Compose text '$text'; rendered: ${composeTexts(activity)}")
    assertTrue("Action must not be disabled", !node.config.contains(SemanticsProperties.Disabled))
    assertTrue(node.config.getOrNull(SemanticsActions.OnClick)!!.action!!.invoke())
    shadowOf(Looper.getMainLooper()).idle()
}
