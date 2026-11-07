package su.linka.pictures.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import su.linka.pictures.ui.theme.LinkaMetrics
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
    kind: PrimitiveSurfaceKind = if (selected != null) PrimitiveSurfaceKind.CARD else PrimitiveSurfaceKind.ACTION,
    description: String? = null,
    content: @Composable () -> Unit,
) {
    val palette = LocalLinkaPalette.current
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val pressed by interactions.collectIsPressedAsState()
    val card = kind == PrimitiveSurfaceKind.CARD
    val shape = RoundedCornerShape(if (card) LinkaMetrics.cardRadius else 8.dp)
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
                if (description != null) stateDescription = description
                role = Role.Button
                if (selected != null) this.selected = selected
                if (!enabled) disabled()
            }
            .then(activation)
            // Focus is drawn inside the hit target, never charged to the grid's content budget.
            .border(LinkaMetrics.focusRing, if (enabled && focused) palette.focus else Color.Transparent, shape),
        propagateMinConstraints = true,
    ) {
        Surface(
            color = if (!enabled) palette.disabledSurface
                else if (pressed) palette.pressedSurface(surface, contentColor) else surface,
            contentColor = if (enabled) contentColor else palette.disabledText,
            shape = shape,
            border = PrimitiveSurfaceChrome.border(palette, selected, kind),
        ) {
            CompositionLocalProvider(LocalContentAlpha provides 1f) {
                Box(
                    Modifier.clearAndSetSemantics {}
                        .padding(if (card) PaddingValues(4.dp) else PaddingValues(horizontal = LinkaMetrics.panelPadding, vertical = LinkaMetrics.editorGap)),
                    contentAlignment = Alignment.Center,
                ) { content() }
            }
        }
    }
}
