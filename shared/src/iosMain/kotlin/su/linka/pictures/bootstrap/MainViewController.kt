@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package su.linka.pictures.bootstrap

import androidx.compose.foundation.layout.*
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ComposeUIViewController
import platform.Foundation.NSThread
import platform.UIKit.*
import su.linka.pictures.application.Problem
import su.linka.pictures.ui.app.LinkaApp

private object ProductProcess {
    val root by lazy { IosProductRoot() }
}

/** Public Swift entry point; Compose owns the product controls, never a parallel Swift UI. */
fun MainViewController(): UIViewController {
    check(NSThread.isMainThread)
    val root = ProductProcess.root
    val failure = root.state.value as? RootState.Failed
    return if (failure != null) ComposeUIViewController { MaterialTheme { RootError(failure.problem) } }
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
    MaterialTheme {
        when (val current = state) {
            RootState.Loading -> Text("Открываем библиотеку…", Modifier.padding(24.dp))
            is RootState.Failed -> RootError(current.problem)
            is RootState.Ready -> {
                val graph = current.graph
                val session by graph.session.collectAsState()
                val foreground by window.foreground.collectAsState()
                val images by graph.thumbnails.images.collectAsState()
                val problem by graph.thumbnails.problem.collectAsState()
                val notice by graph.notice.collectAsState()
                Column(Modifier.fillMaxSize()) {
                    notice.problem?.let { Text("Ошибка: ${it.code}. Доступ закрыт; перезапустите приложение после устранения причины.") }
                    problem?.let { Text("Не удалось загрузить изображение: ${it.code}") }
                    val communication = session
                    if (notice.replacementEpoch != null) {
                        Text(if (notice.problem != null) "Библиотека заменена, прежнее сообщение очищено. Требуется перезапуск."
                            else "Библиотека заменена. Открываем новое сообщение…")
                    } else if (communication == null) Text("Открываем сообщение…")
                    else {
                        val message by communication.state.collectAsState()
                        if (notice.problem != null) Text("Текущее сообщение (только чтение): ${message.renderedText}")
                        ReadOnlyProduct(notice.readOnly, message.renderedText) {
                            LinkaApp(graph.storage.library, graph.storage.media, graph.storage.messages,
                                graph.access, graph.audio, graph.files, graph.preparation, graph.inspection,
                                graph.ids, window.scope, communication, foreground, images,
                                { graph.thumbnails.request(window.id, it) },
                                { root.openOwner(it, window) }, root::closeOwner, root::libraryReplaced)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RootError(problem: Problem) = Column(Modifier.padding(24.dp)) {
    Text("Не удалось безопасно открыть библиотеку")
    Text("${problem.code}" + (problem.systemCode?.let { " (iOS: $it)" } ?: ""))
    Text("Доступ не открыт. Перезапустите приложение после устранения причины; защита не отключена.")
}
