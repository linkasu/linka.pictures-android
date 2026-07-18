package su.linka.pictures

import android.content.Context
import android.os.Bundle
import com.google.firebase.analytics.FirebaseAnalytics

/**
 * Centralized wrapper for Firebase services used across the app.
 */
object Telemetry {
    @Volatile
    private var collectionEnabled = false

    private var analytics: FirebaseAnalytics? = null
    private var privacyPreferences: TelemetryPrivacyPreferences? = null
    private var collectionAllowed = false

    fun init(context: Context, collectionAllowed: Boolean) {
        synchronized(this) {
            val appContext = context.applicationContext
            analytics = analytics ?: FirebaseAnalytics.getInstance(appContext)
            privacyPreferences = TelemetryPrivacyPreferences(appContext)
            this.collectionAllowed = collectionAllowed
            applyCollectionState(privacyState())
        }
    }

    fun privacyState(): TelemetryCollectionState =
        privacyPreferences?.state() ?: TelemetryCollectionState.UNKNOWN

    fun setAnalyticsEnabled(enabled: Boolean) {
        privacyPreferences?.setAnalyticsEnabled(enabled)
        applyCollectionState(
            if (enabled) TelemetryCollectionState.ENABLED else TelemetryCollectionState.DISABLED
        )
    }

    fun logEvent(name: String, params: Bundle? = null) {
        if (!collectionEnabled) return
        analytics?.logEvent(name, params)
    }

    fun isCollectionEnabledForCurrentProcess(): Boolean = collectionEnabled

    private fun applyCollectionState(state: TelemetryCollectionState) {
        collectionEnabled = state.collectionEnabledForBuild(collectionAllowed)
        analytics?.setAnalyticsCollectionEnabled(collectionEnabled)
    }
}
