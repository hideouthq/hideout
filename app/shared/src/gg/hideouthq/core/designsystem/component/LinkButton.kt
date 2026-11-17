package gg.hideouthq.core.designsystem.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.Modifier
import gg.hideouthq.core.designsystem.icon.HideoutIcons
import gg.hideouthq.core.designsystem.interaction.collectInteractionStates
import gg.hideouthq.core.designsystem.interaction.focusRing
import gg.hideouthq.core.designsystem.interaction.hideoutClickable
import gg.hideouthq.core.designsystem.theme.HideoutDimens
import gg.hideouthq.core.designsystem.theme.HideoutShapes
import gg.hideouthq.core.designsystem.theme.HideoutTheme

@Composable
fun LinkButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val source = remember { MutableInteractionSource() }
    val states = source.collectInteractionStates()
    val scheme = MaterialTheme.colorScheme
    val background by animateColorAsState(
        targetValue = when {
            states.pressed -> scheme.surfaceContainerHigh
            states.hovered -> scheme.surfaceContainer
            else -> scheme.surfaceContainer.copy(alpha = 0f)
        },
        animationSpec = HideoutTheme.motion.effectsDefault(),
        label = "linkBackground",
    )
    Row(
        modifier = modifier
            .height(HideoutDimens.Link.Height)
            .drawBehind { drawRoundRect(background, cornerRadius = CornerRadius(size.height / 2)) }
            .focusRing(states.focusVisible, HideoutShapes.Full)
            .hideoutClickable(source, onClick = onClick)
            .padding(start = HideoutDimens.Link.StartPadding, end = HideoutDimens.Link.EndPadding),
        horizontalArrangement = Arrangement.spacedBy(HideoutDimens.Link.Gap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = text, style = HideoutTheme.typography.labelPill, color = scheme.onSurfaceVariant, maxLines = 1)
        HideoutIcon(HideoutIcons.ChevronRight, contentDescription = null, size = HideoutDimens.Icon.Small, tint = scheme.onSurfaceVariant)
    }
}
