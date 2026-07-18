package su.linka.pictures.activity

import android.os.Bundle
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import com.google.android.material.appbar.MaterialToolbar
import su.linka.pictures.R
import su.linka.pictures.Telemetry
import su.linka.pictures.TelemetryCollectionState

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.settings_activity)
        val toolbar: MaterialToolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setTitle(R.string.title_activity_settings)
        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.settings, SettingsFragment())
                .commit()
        }
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
