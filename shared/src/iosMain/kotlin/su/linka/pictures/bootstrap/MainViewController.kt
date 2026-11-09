@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package su.linka.pictures.bootstrap

import androidx.compose.foundation.layout.*
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.Button
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ComposeUIViewController
import platform.Foundation.NSThread
import platform.UIKit.*
import su.linka.pictures.application.Problem
import su.linka.pictures.ui.app.LinkaApp
import su.linka.pictures.ui.theme.LinkaTheme
import su.linka.pictures.ui.theme.LocalLinkaPalette
import androidx.compose.material.Surface
import org.jetbrains.compose.resources.stringResource
import su.linka.pictures.resources.*
import su.linka.pictures.identity.*

private object ProductProcess {
    val root by lazy { IosProductRoot() }
}

/** Public Swift entry point; Compose owns the product controls, never a parallel Swift UI. */
fun MainViewController(): UIViewController {
    check(NSThread.isMainThread)
    val root = ProductProcess.root
    val failure = root.state.value as? RootState.Failed
    return if (failure != null) ComposeUIViewController { LinkaTheme { RootError(failure.problem) } }
    else ProductViewController(root)
}

private class ProductViewController(private val root: IosProductRoot) : UIViewController(nibName = null, bundle = null) {
    private val window = root.openWindow(this)
    private val product = ComposeUIViewController {
        DisposableEffect(window) { onDispose { root.closeWindow(window) } }
        ProductContent(root, window)
    }

    override fun viewDidLoad() {
        super.viewDidLoad()
        addChildViewController(product)
        view.addSubview(product.view)
        product.view.translatesAutoresizingMaskIntoConstraints = false
        NSLayoutConstraint.activateConstraints(listOf(
            product.view.leadingAnchor.constraintEqualToAnchor(view.leadingAnchor),
            product.view.trailingAnchor.constraintEqualToAnchor(view.trailingAnchor),
            product.view.topAnchor.constraintEqualToAnchor(view.topAnchor),
            product.view.bottomAnchor.constraintEqualToAnchor(view.bottomAnchor),
        ))
        product.didMoveToParentViewController(this)
    }

    override fun viewDidAppear(animated: Boolean) {
        super.viewDidAppear(animated)
        root.appeared(window)
    }

    override fun viewDidDisappear(animated: Boolean) {
        super.viewDidDisappear(animated)
        // Covering the app with its own picker is not destruction/background.
        if (isBeingDismissed() || isMovingFromParentViewController()) root.closeWindow(window)
    }
}

@Composable
private fun ProductContent(root: IosProductRoot, window: IosProductRoot.Window) {
    val state by root.state.collectAsState()
    val active by window.foreground.collectAsState()
    LaunchedEffect(active) { root.identity.telemetry.foreground(window.id, active) }
    DisposableEffect(window) { onDispose { root.identity.telemetry.foreground(window.id, false) } }
    LinkaTheme {
      Surface(Modifier.fillMaxSize(), color = LocalLinkaPalette.current.background, contentColor = LocalLinkaPalette.current.text) {
        when (val current = state) {
            RootState.Loading -> Text(stringResource(Res.string.ios_opening_library), Modifier.padding(24.dp))
            RootState.StartupResume -> Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(24.dp)) {
                Text(stringResource(Res.string.ios_starter_setup_interrupted))
                Button(onClick = root::resumeStartup) { Text(stringResource(Res.string.ios_starter_continue_setup)) }
            }
            is RootState.Failed -> RootError(current.problem)
            is RootState.Ready -> {
                val graph = current.graph
                val session by graph.session.collectAsState()
                val foreground by window.foreground.collectAsState()
                val images by graph.thumbnails.images.collectAsState()
                val problem by graph.thumbnails.problem.collectAsState()
                val notice by graph.notice.collectAsState()
                Column(Modifier.fillMaxSize()) {
                    notice.problem?.let { Text(stringResource(Res.string.ios_access_closed_error, it.code.name)) }
                    problem?.let { Text(stringResource(Res.string.ios_image_load_error, it.code.name)) }
                    val communication = session
                    if (notice.replacementEpoch != null) {
                        Text(stringResource(if (notice.problem != null) Res.string.ios_replaced_library_restart
                            else Res.string.ios_replaced_library_opening_message))
                    } else if (communication == null) Text(stringResource(Res.string.ios_opening_message))
                    else {
                        val message by communication.state.collectAsState()
                        if (notice.problem != null) Text(stringResource(Res.string.ios_read_only_message, message.renderedText))
                        ReadOnlyProduct(notice.readOnly, message.renderedText) {
                            LinkaApp(graph.storage.library, graph.storage.media, graph.storage.messages,
                                graph.access, graph.audio, graph.files, graph.preparation, graph.inspection,
                                graph.ids, window.scope, communication, foreground, images,
                                { graph.thumbnails.request(window.id, it) },
                                 { root.openOwner(it, window) }, root::closeOwner, root::libraryReplaced, identity = root.identity)
                        }
                    }
                }
            }
        }
      }
    }
}

@Composable
private fun RootError(problem: Problem) = Surface(Modifier.fillMaxSize(),
    color = LocalLinkaPalette.current.background, contentColor = LocalLinkaPalette.current.text) {
  Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(24.dp)) {
    Text(stringResource(Res.string.ios_library_open_error))
    Text("${problem.code}" + (problem.systemCode?.let { " (iOS: $it)" } ?: ""))
    Text(stringResource(Res.string.ios_access_not_opened))
  }
}
