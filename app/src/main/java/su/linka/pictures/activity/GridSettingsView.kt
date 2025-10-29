package su.linka.pictures.activity

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.Switch
import su.linka.pictures.R

class GridSettingsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    private val isOutputSwitch: Switch
    private val isPageButtonsSwitch: Switch
    private var settings: GridSettings = GridSettings()

    init {
        LayoutInflater.from(context).inflate(R.layout.grid_settings, this, true)
        isOutputSwitch = findViewById(R.id.is_output_switch)
        isPageButtonsSwitch = findViewById(R.id.is_page_buttons_switch)
    }

    fun setSettings(settings: GridSettings) {
        this.settings = settings
        isOutputSwitch.isChecked = settings.isOutput
        isPageButtonsSwitch.isChecked = settings.isPagesButtons
    }

    fun getSettings(): GridSettings = settings

    fun commit() {
        settings.isOutput = isOutputSwitch.isChecked
        settings.isPagesButtons = isPageButtonsSwitch.isChecked
    }
}
