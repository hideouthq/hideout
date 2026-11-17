package gg.hideouthq.core.designsystem.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import gg.hideouthq.core.designsystem.icon.HideoutIcons
import gg.hideouthq.core.designsystem.interaction.collectInteractionStates
import gg.hideouthq.core.designsystem.interaction.focusRing
import gg.hideouthq.core.designsystem.interaction.hideoutClickable
import gg.hideouthq.core.designsystem.theme.HideoutShapes
import gg.hideouthq.core.designsystem.theme.HideoutTheme

@Composable
fun BackLink(text: String, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val source = remember { MutableInteractionSource() }
    val states = source.collectInteractionStates()
    val fill by animateColorAsState(if (states.hovered) scheme.surfaceContainer else scheme.surfaceContainer.copy(alpha = 0f), HideoutTheme.motion.effectsDefault(), label = "backLink")
    Row(
        Modifier
            .height(36.dp)
            .clip(CircleShape)
            .background(fill)
            .focusRing(states.focusVisible, HideoutShapes.Full)
            .hideoutClickable(source, onClick = onClick)
            .padding(start = 6.dp, end = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HideoutIcon(HideoutIcons.ChevronLeft, null, size = 16.dp, tint = scheme.onSurface)
        Text(text, style = HideoutTheme.typography.labelPill, color = scheme.onSurface, maxLines = 1)
    }
}
