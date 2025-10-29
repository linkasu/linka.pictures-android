package su.linka.pictures.activity

data class GridSettings(
    var isOutput: Boolean = true,
    var isPagesButtons: Boolean = true
) {

    fun toInt(): Int {
        var value = 0
        if (isOutput) value += 1
        if (isPagesButtons) value += 2
        return value
    }

    companion object {
        fun fromInt(value: Int): GridSettings {
            val settings = GridSettings(isOutput = false, isPagesButtons = false)
            if (value == 1 || value == 3) {
                settings.isOutput = true
            }
            if (value == 2 || value == 3) {
                settings.isPagesButtons = true
            }
            return settings
        }
    }
}
