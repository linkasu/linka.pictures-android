package su.linka.pictures

import android.content.Intent

abstract class ActivityResultListener {
    abstract fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?)
}
