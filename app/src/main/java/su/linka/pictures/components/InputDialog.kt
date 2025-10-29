package su.linka.pictures.components

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.view.LayoutInflater
import android.widget.EditText
import androidx.annotation.StringRes
import su.linka.pictures.Callback
import su.linka.pictures.R

object InputDialog {

    fun showDialog(context: Context, @StringRes title: Int, listener: Callback<String>) {
        showDialog(context, title, InputType.TYPE_CLASS_TEXT, null, listener)
    }

    fun showDialog(context: Context, @StringRes title: Int, type: Int, listener: Callback<String>) {
        showDialog(context, title, type, null, listener)
    }

    fun showDialog(
        context: Context,
        @StringRes title: Int,
        currentValue: String?,
        listener: Callback<String>
    ) {
        showDialog(context, title, InputType.TYPE_CLASS_TEXT, currentValue, listener)
    }

    fun showDialog(
        context: Context,
        @StringRes title: Int,
        type: Int,
        currentValue: String?,
        listener: Callback<String>
    ) {
        val promptsView = LayoutInflater.from(context).inflate(R.layout.input_prompt, null, false)
        val userInput = promptsView.findViewById<EditText>(R.id.input_prompt).apply {
            setText(currentValue.orEmpty())
            inputType = type
            setSelection(text?.length ?: 0)
        }

        val dialog = AlertDialog.Builder(context)
            .setView(promptsView)
            .setTitle(title)
            .setPositiveButton(R.string.ok) { dialogInterface, _ ->
                val text = userInput.text?.toString()?.trim().orEmpty()
                if (text.isNotEmpty()) {
                    listener.onDone(text)
                } else {
                    listener.onFail(null)
                }
                dialogInterface.dismiss()
            }
            .setNegativeButton(R.string.cancel) { dialogInterface, _ ->
                dialogInterface.dismiss()
                listener.onFail(null)
            }
            .setOnCancelListener {
                listener.onFail(null)
            }
            .create()

        dialog.setOnShowListener {
            userInput.requestFocus()
        }
        dialog.show()
    }
}
