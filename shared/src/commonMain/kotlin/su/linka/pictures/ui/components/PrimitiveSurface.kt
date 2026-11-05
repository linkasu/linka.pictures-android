package su.linka.pictures.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Surface
import androidx.compose.material.LocalContentAlpha
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import su.linka.pictures.ui.theme.LinkaTargets
import su.linka.pictures.ui.theme.LocalLinkaPalette

@Composable
internal fun PrimitiveSurface(
    accessibleName: String,
    activationLabel: String,
    enabled: Boolean,
    selected: Boolean?,
    surface: Color,
    contentColor: Color,
    onClick: () -> Unit,
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    val palette = LocalLinkaPalette.current
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val pressed by interactions.collectIsPressedAsState()
    val shape = RoundedCornerShape(12.dp)
    val activation = if (enabled) Modifier.clickable(
        interactionSource = interactions,
        indication = null,
        onClickLabel = activationLabel,
        role = Role.Button,
        onClick = onClick,
    ) else Modifier
    Box(
        modifier
            .semantics(mergeDescendants = true) {
                contentDescription = accessibleName
                role = Role.Button
                if (selected != null) this.selected = selected
                if (!enabled) disabled()
            }
            .then(activation)
            .border(LinkaTargets.focusRing, if (enabled && focused) palette.focus else Color.Transparent, shape)
            // Always reserve the ring and gap: focus never changes layout or the selected border.
            .padding(6.dp),
        propagateMinConstraints = true,
    ) {
        Surface(
            color = if (!enabled) palette.disabledSurface
                else if (pressed) palette.pressedSurface(surface, contentColor) else surface,
            contentColor = if (enabled) contentColor else palette.disabledText,
            shape = shape,
            border = BorderStroke(if (selected == true) 3.dp else 1.dp,
                if (selected == true) palette.primary else palette.border),
        ) {
            CompositionLocalProvider(LocalContentAlpha provides 1f) {
                Box(
                    Modifier.clearAndSetSemantics {}.padding(12.dp),
                    contentAlignment = Alignment.Center,
                ) { content() }
            }
        }
    }
}
