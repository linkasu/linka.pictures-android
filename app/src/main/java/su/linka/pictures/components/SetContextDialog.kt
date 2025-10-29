package su.linka.pictures.components

import android.app.AlertDialog
import android.content.Context
import su.linka.pictures.Callback
import su.linka.pictures.R

object SetContextDialog {
    const val OPEN = 0
    const val EDIT = 1
    const val RENAME = 2
    const val DELETE = 3

    fun show(context: Context, title: String, callback: Callback<Int>) {
        AlertDialog.Builder(context)
            .setTitle(title)
            .setItems(R.array.set_context_actions) { _, which ->
                callback.onDone(which)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
