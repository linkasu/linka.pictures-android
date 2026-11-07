package su.linka.pictures.activity

import android.content.Intent
import android.os.Bundle
import android.os.Build
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.compose.runtime.*
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import su.linka.pictures.application.*
import su.linka.pictures.bootstrap.AndroidProductRuntime
import su.linka.pictures.platform.AndroidSystemRequests
import su.linka.pictures.ui.components.RootStatusScreen
import su.linka.pictures.R

/** This launcher exists only in the separate .development APK, never in the production package. */
class DevelopmentActivity : ComponentActivity() {
    private var windowHost by mutableStateOf<AndroidProductRuntime.Window?>(null)
    private var problem by mutableStateOf<Problem?>(null)
    private lateinit var requests: AndroidSystemRequests

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // API21 has no dark status-bar icons; its system strip keeps the readable brand colour.
        window.statusBarColor = if (Build.VERSION.SDK_INT >= 23) android.graphics.Color.rgb(246, 247, 244)
            else ContextCompat.getColor(this, R.color.colorPrimary)
        if (Build.VERSION.SDK_INT >= 23)
            window.decorView.systemUiVisibility = window.decorView.systemUiVisibility or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        if (Build.VERSION.SDK_INT >= 26) {
            window.navigationBarColor = android.graphics.Color.rgb(246, 247, 244)
            window.decorView.systemUiVisibility = window.decorView.systemUiVisibility or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val host = windowHost
                if (host == null && problem == null || host?.handleBack() == true) return
                isEnabled = false
                try { onBackPressedDispatcher.onBackPressed() } finally { isEnabled = true }
            }
        })
        requests = AndroidSystemRequests(this, { owner -> windowHost?.isOwnerOpen(owner) == true },
            { windowHost?.systemPresentationStarted() }, lifecycleScope)
        setContentView(ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val host = windowHost
                if (host != null) host.Content()
                else RootStatusScreen(problem)
            }
        })
        lifecycleScope.launch {
            when (val opened = AndroidProductRuntime.openDevelopment(applicationContext)) {
                is OperationResult.Completed -> {
                    windowHost = opened.value.attach(AndroidProductRuntime.Requests(requests::requestDocument,
                        requests::requestMicrophonePermission, requests::requestCredential, requests::cancel,
                        { lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && hasWindowFocus() && !isFinishing && !isDestroyed },
                        { lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) && !isFinishing && !isDestroyed }))
                    acceptIntent(intent)
                }
                is OperationResult.Failed -> problem = opened.problem
                OperationResult.UnconfirmedCommit -> problem = Problem(ProblemCode.COMMIT_OUTCOME_UNKNOWN)
                OperationResult.Cancelled -> problem = Problem(ProblemCode.INTERRUPTED)
            }
        }
    }

    override fun onStart() { super.onStart(); windowHost?.started() }
    override fun onStop() { windowHost?.stopped(); super.onStop() }
    override fun onPostResume() { super.onPostResume(); windowHost?.lifecycleChanged(); requests.onForeground() }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        windowHost?.lifecycleChanged()
        if (hasFocus && this::requests.isInitialized) requests.onForeground()
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); acceptIntent(intent) }
    private fun acceptIntent(value: Intent) {
        if (value.action == Intent.ACTION_VIEW) value.data?.let { uri ->
            val result = windowHost?.incomingDocument(uri)
            if (result is OperationResult.Failed) problem = result.problem
        }
    }
    override fun onDestroy() {
        windowHost?.close()
        windowHost = null
        if (this::requests.isInitialized) requests.close()
        super.onDestroy()
    }
}
