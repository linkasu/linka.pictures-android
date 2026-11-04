package su.linka.pictures

import android.content.Context
import androidx.preference.PreferenceManager

class ParentControl(context: Context) {
    private val preferences = PreferenceManager.getDefaultSharedPreferences(context)

    val enabled: Boolean get() = preferences.getBoolean("parent", false)
    val hasPassword: Boolean get() = !preferences.getString("parent_password", "").isNullOrBlank()
    val needsRecovery: Boolean get() = enabled && !hasPassword

    fun accepts(password: String): Boolean =
        hasPassword && password == preferences.getString("parent_password", "")
}
