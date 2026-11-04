package su.linka.pictures.activity

import android.os.Bundle
import android.text.InputType
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.*
import androidx.preference.PreferenceManager
import su.linka.pictures.*
import su.linka.pictures.R
import su.linka.pictures.components.InputDialog
import su.linka.pictures.components.ParentPasswordDialog
import su.linka.pictures.ui.*

class SettingsActivity : AppCompatActivity() {
    internal var authenticated by mutableStateOf(false)
        private set
    internal var parentEnabled by mutableStateOf(false)
        private set
    internal var analyticsEnabled by mutableStateOf(false)
        private set
    internal var passwordRecoveryRequired by mutableStateOf(false)
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        // Reauthenticate before exposing settings; never restore an editable password prompt.
        super.onCreate(null)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = onAction(SettingsAction.Back)
        })
        setContent {
            PicturesTheme {
                if (authenticated) SettingsScreen(parentEnabled, analyticsEnabled, passwordRecoveryRequired, ::onAction)
            }
        }
        ParentPasswordDialog.showDialog(this, object : Callback<Any?>() {
            override fun onDone(result: Any?) {
                if (!isDestroyed) { authenticated = true; refreshState() }
            }
            override fun onFail(error: Exception?) { if (!isDestroyed) finish() }
        }, allowRecovery = true)
    }

    private fun refreshState() {
        val control = ParentControl(this)
        parentEnabled = control.enabled
        passwordRecoveryRequired = control.needsRecovery
        analyticsEnabled = Telemetry.privacyState() == TelemetryCollectionState.ENABLED
    }

    internal fun onAction(action: SettingsAction) {
        if (isDestroyed || !authenticated) return
        val control = ParentControl(this)
        if (!action.isAllowed(control.needsRecovery)) return
        val preferences = PreferenceManager.getDefaultSharedPreferences(this)
        when (action) {
            is SettingsAction.ToggleParent -> {
                if (action.enabled && !control.hasPassword) {
                    Toast.makeText(this, R.string.parent_password_required, Toast.LENGTH_LONG).show()
                } else preferences.edit().putBoolean("parent", action.enabled).apply()
            }
            SettingsAction.ChangePassword -> InputDialog.showDialog(this, R.string.parent_password,
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD, null, object : Callback<String>() {
                    override fun onDone(result: String) {
                        if (isFinishing || isDestroyed) return
                        if (result.isBlank()) {
                            Toast.makeText(this@SettingsActivity, R.string.parent_password_required, Toast.LENGTH_LONG).show()
                        } else {
                            // Keep literal leading/trailing whitespace: legacy passwords may include it.
                            preferences.edit().putString("parent_password", result).apply()
                        }
                        refreshState()
                    }
                    override fun onFail(error: Exception?) = Unit
                })
            is SettingsAction.ToggleAnalytics -> Telemetry.setAnalyticsEnabled(action.enabled)
            SettingsAction.Back -> finish()
        }
        refreshState()
    }
}
