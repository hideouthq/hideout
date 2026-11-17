package gg.hideouthq.core.designsystem.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import gg.hideouthq.core.designsystem.interaction.collectInteractionStates
import gg.hideouthq.core.designsystem.interaction.focusRing
import gg.hideouthq.core.designsystem.interaction.hideoutSelectable
import gg.hideouthq.core.designsystem.theme.HideoutDimens
import gg.hideouthq.core.designsystem.theme.HideoutRadius
import gg.hideouthq.core.designsystem.theme.HideoutShapes
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import gg.hideouthq.core.designsystem.theme.HideoutWeights

@Composable
fun NavItem(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    trailing: @Composable () -> Unit = {},
    compact: Boolean = false,
    labelAlpha: Float = 1f,
) {
    val source = remember { MutableInteractionSource() }
    val states = source.collectInteractionStates()
    val scheme = MaterialTheme.colorScheme
    val motion = HideoutTheme.motion
    val hover by animateFloatAsState(if (states.hovered) 1f else 0f, motion.effectsFast(), label = "navItemHover")
    val press by animateFloatAsState(if (states.pressed) 1f else 0f, motion.effectsFast(), label = "navItemPress")
    val active by animateFloatAsState(if (selected) 1f else 0f, motion.effectsDefault(), label = "navItemSelected")
    val labelColor = lerp(scheme.onSurfaceVariant, scheme.onSurface, active)
    val activeFill = scheme.surfaceContainer
    val hoverFill = scheme.surfaceContainerLow

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(if (compact) 44.dp else HideoutDimens.NavItem.Height)
            .drawBehind {
                val radius = CornerRadius(if (compact) 15.dp.toPx() else HideoutRadius.Row.toPx())
                if (active > 0f) drawRoundRect(activeFill, cornerRadius = radius, alpha = active)
                val uncovered = 1f - active
                if (hover > 0f) drawRoundRect(hoverFill, cornerRadius = radius, alpha = hover * uncovered)
                if (press > 0f) drawRoundRect(activeFill, cornerRadius = radius, alpha = press * uncovered)
            }
            .focusRing(states.focusVisible, HideoutShapes.Row)
            .hideoutSelectable(selected, source, role = Role.Tab, onClick = onClick)
            .then(if (compact) Modifier.semantics { contentDescription = label } else Modifier)
            .padding(horizontal = if (compact) 0.dp else HideoutDimens.NavItem.HorizontalPadding),
        horizontalArrangement = if (compact) Arrangement.Center else Arrangement.spacedBy(HideoutDimens.NavItem.IconGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) HideoutIcon(icon, contentDescription = null, tint = labelColor)
        if (compact) return@Row
        Box(Modifier.weight(1f).graphicsLayer { alpha = labelAlpha }) {
            val style = HideoutTheme.typography.labelNav
            Text(
                text = label,
                style = style,
                color = labelColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.graphicsLayer { alpha = 1f - active },
            )
            Text(
                text = label,
                style = style.copy(fontWeight = HideoutWeights.Label),
                color = labelColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.graphicsLayer { alpha = active },
            )
        }
        trailing()
    }
}
