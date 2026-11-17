package gg.hideouthq.core.designsystem.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import gg.hideouthq.core.designsystem.icon.HideoutIcons
import gg.hideouthq.core.designsystem.theme.HideoutDimens
import gg.hideouthq.core.designsystem.theme.HideoutTheme

/**
 * The 20dp check box: a 2dp subtle ring when clear, a primary fill with a 14dp check when ticked. It only draws; the
 * row it sits in is the toggle, so the whole row is the target. A larger [size] scales the corner and the check with it.
 */
@Composable
fun HideoutCheckbox(
    checked: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = HideoutDimens.Checkbox.Size,
    ringColor: Color = MaterialTheme.colorScheme.outline,
) {
    val scheme = MaterialTheme.colorScheme
    val motion = HideoutTheme.motion
    val fill by animateColorAsState(if (checked) scheme.primary else scheme.primary.copy(alpha = 0f), motion.effectsFast(), label = "checkboxFill")
    val ring by animateColorAsState(if (checked) ringColor.copy(alpha = 0f) else ringColor, motion.effectsFast(), label = "checkboxRing")
    val scale = size / HideoutDimens.Checkbox.Size
    val shape = RoundedCornerShape(HideoutDimens.Checkbox.Radius * scale)
    Box(
        modifier = modifier
            .size(size)
            .background(fill, shape)
            .border(HideoutDimens.Checkbox.Ring, ring, shape),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) HideoutIcon(HideoutIcons.Check, contentDescription = null, tint = scheme.onPrimary, modifier = Modifier.size(HideoutDimens.Checkbox.Check * scale))
    }
}
