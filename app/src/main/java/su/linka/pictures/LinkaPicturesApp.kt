package su.linka.pictures

import android.app.Application

class LinkaPicturesApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Telemetry.init(applicationContext, BuildConfig.TELEMETRY_COLLECTION_ALLOWED)
    }
}
