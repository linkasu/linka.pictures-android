package su.linka.pictures

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.StringDef
import kotlin.annotation.AnnotationRetention.SOURCE

class Cookie(context: Context) {

    @StringDef(ASSETS_LOADER)
    @Retention(SOURCE)
    annotation class FieldName

    private val preferences: SharedPreferences =
        context.getSharedPreferences(APP_PREFERENCES, Context.MODE_PRIVATE)

    fun get(@FieldName id: String, def: Boolean): Boolean = preferences.getBoolean(id, def)

    fun set(@FieldName id: String, value: Boolean) {
        preferences.edit().putBoolean(id, value).apply()
    }

    fun getSetSettings(id: String, def: Int): Int = preferences.getInt(id, def)

    fun setSetSettings(id: String, value: Int) {
        preferences.edit().putInt(id, value).apply()
    }

    companion object {
        const val ASSETS_LOADER = "ASSETS_LOADER"
        private const val APP_PREFERENCES = "my"
    }
}
