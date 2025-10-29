package su.linka.pictures.components

import android.content.Context
import android.text.InputType
import androidx.preference.PreferenceManager
import su.linka.pictures.Callback
import su.linka.pictures.R

object ParentPasswordDialog {

    fun showDialog(context: Context, listener: Callback<Any?>) {
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        if (!preferences.getBoolean("parent", false)) {
            listener.onDone(null)
            return
        }

        val password = preferences.getString("parent_password", "").orEmpty()
        InputDialog.showDialog(
            context,
            R.string.parent_password,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
            null,
            object : Callback<String>() {
                override fun onDone(result: String) {
                    if (result == password) {
                        listener.onDone(null)
                    } else {
                        listener.onFail(Exception("Invalid parent password"))
                    }
                }

                override fun onFail(error: Exception?) {
                    listener.onFail(error)
                }
            }
        )
    }
}
