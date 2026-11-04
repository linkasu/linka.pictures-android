package su.linka.pictures.ui

sealed interface SettingsAction {
    /** Recovery gate only; the controller must also enforce authentication and password validation. */
    fun isAllowed(passwordRecoveryRequired: Boolean): Boolean =
        !passwordRecoveryRequired || this == ChangePassword

    data class ToggleParent(val enabled: Boolean) : SettingsAction
    data object ChangePassword : SettingsAction
    data class ToggleAnalytics(val enabled: Boolean) : SettingsAction
    data object Back : SettingsAction
}
