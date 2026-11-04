package su.linka.pictures

import android.app.Application

class LinkaPicturesApp : Application() {
    override fun onCreate() {
        super.onCreate()
        WorkspaceCleanup.onProcessStart(this)
        Telemetry.init(applicationContext, BuildConfig.TELEMETRY_COLLECTION_ALLOWED)
    }
}
