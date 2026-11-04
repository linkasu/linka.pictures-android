package su.linka.pictures.ui

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsActionTest {
    private val otherActions = listOf(
        SettingsAction.ToggleParent(false),
        SettingsAction.ToggleParent(true),
        SettingsAction.ToggleAnalytics(false),
        SettingsAction.ToggleAnalytics(true),
        SettingsAction.Back,
    )

    @Test
    fun legacyRecoveryOnlyAllowsPasswordAssignment() {
        assertTrue(SettingsAction.ChangePassword.isAllowed(passwordRecoveryRequired = true))
        otherActions.forEach { assertFalse(it.isAllowed(passwordRecoveryRequired = true)) }
    }

    @Test
    fun passwordAssignmentRemainsAvailableBeforeEnablingParentControl() {
        assertTrue(SettingsAction.ChangePassword.isAllowed(passwordRecoveryRequired = false))
        otherActions.forEach { assertTrue(it.isAllowed(passwordRecoveryRequired = false)) }
    }
}
