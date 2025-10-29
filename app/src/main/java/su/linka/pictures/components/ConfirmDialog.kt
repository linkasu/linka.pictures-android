package su.linka.pictures.components

import android.app.AlertDialog
import android.content.Context
import androidx.annotation.StringRes
import su.linka.pictures.Callback
import su.linka.pictures.R

object ConfirmDialog {

    fun showConfirmDialog(context: Context, @StringRes title: Int, callback: Callback<Any?>) {
        AlertDialog.Builder(context)
            .setTitle(title)
            .setNegativeButton(R.string.cancel) { _, _ ->
                callback.onFail(null)
            }
            .setPositiveButton(R.string.ok) { _, _ ->
                callback.onDone(null)
            }
            .show()
    }
}
