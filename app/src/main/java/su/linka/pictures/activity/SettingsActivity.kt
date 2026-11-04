package su.linka.pictures.activity

import android.os.Bundle
import android.text.InputType
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import com.google.android.material.appbar.MaterialToolbar
import su.linka.pictures.Callback
import su.linka.pictures.ParentControl
import su.linka.pictures.R
import su.linka.pictures.Telemetry
import su.linka.pictures.TelemetryCollectionState
import su.linka.pictures.components.ParentPasswordDialog

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // Reauthenticate before restoring any editable preference dialogs.
        super.onCreate(null)
        setContentView(R.layout.settings_activity)
        val toolbar: MaterialToolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setTitle(R.string.title_activity_settings)
        ParentPasswordDialog.showDialog(this, object : Callback<Any?>() {
            override fun onDone(result: Any?) {
                supportFragmentManager.beginTransaction()
                    .replace(R.id.settings, SettingsFragment()).commit()
            }

            override fun onFail(error: Exception?) { finish() }
        }, allowRecovery = true)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    class SettingsFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.root_preferences, rootKey)

            val control = ParentControl(requireContext())
            val parent = requireNotNull(findPreference<SwitchPreferenceCompat>("parent"))
            val password = requireNotNull(findPreference<EditTextPreference>("parent_password"))
            parent.setOnPreferenceChangeListener { _, value ->
                if (value == true && !control.hasPassword) {
                    Toast.makeText(context, R.string.parent_password_required, Toast.LENGTH_LONG).show()
                    false
                } else true
            }
            password.setOnBindEditTextListener {
                it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            password.setOnPreferenceChangeListener { _, value ->
                if (control.enabled && (value as String).isBlank()) {
                    Toast.makeText(context, R.string.parent_password_required, Toast.LENGTH_LONG).show()
                    false
                } else true
            }
            if (control.needsRecovery) {
                parent.isEnabled = false
                password.summary = getString(R.string.parent_password_recovery)
                for (index in 1 until preferenceScreen.preferenceCount) {
                    preferenceScreen.getPreference(index).isEnabled = false
                }
            }

            val privacyState = Telemetry.privacyState()
            findPreference<SwitchPreferenceCompat>(KEY_ANALYTICS)?.apply {
                isChecked = privacyState == TelemetryCollectionState.ENABLED
                setOnPreferenceChangeListener { _, newValue ->
                    Telemetry.setAnalyticsEnabled(newValue as Boolean)
                    true
                }
            }
        }

        companion object {
            private const val KEY_ANALYTICS = "telemetry_analytics"
        }
    }
}
