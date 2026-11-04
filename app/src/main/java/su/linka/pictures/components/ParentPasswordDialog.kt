package su.linka.pictures.components

import android.content.Context
import android.text.InputType
import android.widget.Toast
import su.linka.pictures.Callback
import su.linka.pictures.ParentControl
import su.linka.pictures.R

object ParentPasswordDialog {

    fun showDialog(context: Context, listener: Callback<Any?>, allowRecovery: Boolean = false) {
        val control = ParentControl(context)
        if (!control.enabled || (allowRecovery && control.needsRecovery)) {
            listener.onDone(null)
            return
        }

        if (control.needsRecovery) {
            Toast.makeText(context, R.string.parent_password_recovery, Toast.LENGTH_LONG).show()
            listener.onFail(IllegalStateException("Parent password requires recovery"))
            return
        }
        InputDialog.showDialog(
            context,
            R.string.parent_password,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
            null,
            object : Callback<String>() {
                override fun onDone(result: String) {
                    if (control.enabled && control.accepts(result)) {
                        listener.onDone(null)
                    } else {
                        Toast.makeText(context, R.string.parent_password_invalid, Toast.LENGTH_SHORT).show()
                        listener.onFail(Exception("Invalid parent password"))
                    }
                }

                override fun onFail(error: Exception?) {
                    listener.onFail(error)
                }
            }
        )
    }

    fun run(context: Context, onDenied: () -> Unit = {}, action: () -> Unit) {
        showDialog(context, object : Callback<Any?>() {
            override fun onDone(result: Any?) = action()
            override fun onFail(error: Exception?) = onDenied()
        })
    }
}
