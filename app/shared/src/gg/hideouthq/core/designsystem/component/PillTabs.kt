package gg.hideouthq.core.designsystem.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import gg.hideouthq.core.designsystem.interaction.collectInteractionStates
import gg.hideouthq.core.designsystem.interaction.focusRing
import gg.hideouthq.core.designsystem.interaction.hideoutSelectable
import gg.hideouthq.core.designsystem.theme.HideoutDimens
import gg.hideouthq.core.designsystem.theme.HideoutShapes
import gg.hideouthq.core.designsystem.theme.HideoutTheme

@Composable
fun <T : Any> PillTabs(
    tabs: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: (T) -> String,
    icon: (T) -> ImageVector,
    groupLabel: String,
    modifier: Modifier = Modifier,
    count: (T) -> String? = { null },
) {
    val scheme = MaterialTheme.colorScheme
    val indicator = rememberSelectionIndicatorState(selected, squash = true)
    Row(
        modifier
            .semantics { contentDescription = groupLabel }
            .drawBehind { drawRoundRect(scheme.surfaceContainer, cornerRadius = CornerRadius(size.height / 2)) }
            .selectionIndicator(indicator, scheme.primary, HideoutShapes.Full)
            .padding(HideoutDimens.PillTabs.Padding)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(HideoutDimens.PillTabs.Gap),
    ) {
        tabs.forEach { tab ->
            PillTab(label(tab), icon(tab), count(tab), tab == selected, { onSelect(tab) }, Modifier.indicatorTarget(indicator, tab))
        }
    }
}

@Composable
private fun PillTab(text: String, icon: ImageVector, count: String?, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    val source = remember { MutableInteractionSource() }
    val states = source.collectInteractionStates()
    val motion = HideoutTheme.motion
    val type = HideoutTheme.typography
    val content by animateColorAsState(if (selected) scheme.onPrimary else scheme.onSurfaceVariant, motion.effectsDefault(), label = "tabContent")
    val hover by animateColorAsState(if (states.hovered && !selected) scheme.surfaceContainerHigh else scheme.surfaceContainerHigh.copy(alpha = 0f), motion.effectsDefault(), label = "tabHover")
    Row(
        modifier
            .height(HideoutDimens.PillTabs.Height)
            .drawBehind { drawRoundRect(hover, cornerRadius = CornerRadius(size.height / 2)) }
            .focusRing(states.focusVisible, HideoutShapes.Full)
            .hideoutSelectable(selected, source, role = Role.Tab, onClick = onClick)
            .padding(start = HideoutDimens.PillTabs.StartPadding, end = HideoutDimens.PillTabs.EndPadding),
        horizontalArrangement = Arrangement.spacedBy(HideoutDimens.PillTabs.LabelGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HideoutIcon(icon, null, size = HideoutDimens.PillTabs.Icon, tint = content)
        Text(text, style = type.labelTab, color = content, maxLines = 1)
        if (count != null) {
            Text(count, style = type.labelTabCount, color = content, maxLines = 1, modifier = Modifier.alpha(CountAlpha))
        } else {
            Spacer(Modifier)
        }
    }
}

private const val CountAlpha = 0.66f
