package su.linka.pictures

import android.content.Context

enum class TelemetryCollectionState(val storedValue: String) {
    UNKNOWN("unknown"),
    ENABLED("enabled"),
    DISABLED("disabled");

    val requiresNotice: Boolean
        get() = this == UNKNOWN

    fun collectionEnabledForBuild(collectionAllowed: Boolean): Boolean =
        collectionAllowed && this == ENABLED

    companion object {
        fun fromStoredValue(value: String?): TelemetryCollectionState =
            entries.firstOrNull { it.storedValue == value } ?: UNKNOWN
    }
}

class TelemetryPrivacyPreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    fun state(): TelemetryCollectionState = readState()

    fun setAnalyticsEnabled(enabled: Boolean) {
        writeState(enabled)
    }

    private fun readState(): TelemetryCollectionState =
        TelemetryCollectionState.fromStoredValue(preferences.getString(KEY_ANALYTICS, null))

    private fun writeState(enabled: Boolean) {
        val state = if (enabled) {
            TelemetryCollectionState.ENABLED
        } else {
            TelemetryCollectionState.DISABLED
        }
        preferences.edit().putString(KEY_ANALYTICS, state.storedValue).apply()
    }

    companion object {
        const val PREFERENCES_NAME = "telemetry_privacy"
        private const val KEY_ANALYTICS = "analytics_state"
    }
}
