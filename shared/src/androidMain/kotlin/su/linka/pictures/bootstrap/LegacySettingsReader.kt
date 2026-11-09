package su.linka.pictures.bootstrap

import android.content.Context
import su.linka.pictures.application.ProblemCode
import su.linka.pictures.migration.LegacyUpgradeSettings
import su.linka.pictures.migration.reject

/** Reads the original AndroidX names without retaining its UI or any preference writer. */
internal class LegacySettingsReader(context: Context) {
    private val defaults = context.getSharedPreferences("${context.packageName}_preferences", Context.MODE_PRIVATE)
    private val grid = context.getSharedPreferences("my", Context.MODE_PRIVATE)

    class Snapshot(val settings: LegacyUpgradeSettings, val password: String?, val hasPreferences: Boolean)

    fun read(): Snapshot {
        val values = defaults.all
        val layout = grid.all
        val parent = values["parent"]
        if (parent != null && parent !is Boolean) reject(ProblemCode.INVALID_DATA)
        val password = values["parent_password"]
        if (password != null && password !is String) reject(ProblemCode.INVALID_DATA)
        val initialized = layout["ASSETS_LOADER"]
        if (initialized != null && initialized !is Boolean) reject(ProblemCode.INVALID_DATA)
        val boards = layout.filterKeys { it != "ASSETS_LOADER" }.mapValues { (_, value) ->
            value as? Int ?: reject(ProblemCode.INVALID_DATA)
        }
        return Snapshot(LegacyUpgradeSettings(boards, parent == true), password as? String,
            values.isNotEmpty() || layout.isNotEmpty())
    }
}
