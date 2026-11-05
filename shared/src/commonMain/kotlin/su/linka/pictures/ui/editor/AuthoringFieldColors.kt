package su.linka.pictures.ui.editor

import androidx.compose.material.TextFieldColors
import androidx.compose.material.TextFieldDefaults
import androidx.compose.runtime.Composable
import su.linka.pictures.ui.theme.LocalLinkaPalette

@Composable
internal fun authoringFieldColors(): TextFieldColors {
    val palette = LocalLinkaPalette.current
    return TextFieldDefaults.outlinedTextFieldColors(
        textColor = palette.text, disabledTextColor = palette.disabledText,
        backgroundColor = palette.surface, cursorColor = palette.focus,
        focusedBorderColor = palette.focus, unfocusedBorderColor = palette.border,
        disabledBorderColor = palette.border, errorBorderColor = palette.danger,
        focusedLabelColor = palette.text, unfocusedLabelColor = palette.secondaryText,
        disabledLabelColor = palette.disabledText, errorLabelColor = palette.danger,
    )
}
