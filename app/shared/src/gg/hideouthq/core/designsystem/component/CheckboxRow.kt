package gg.hideouthq.core.designsystem.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import gg.hideouthq.core.designsystem.interaction.collectInteractionStates
import gg.hideouthq.core.designsystem.interaction.focusRing
import gg.hideouthq.core.designsystem.interaction.hideoutToggleable
import gg.hideouthq.core.designsystem.theme.HideoutDimens
import gg.hideouthq.core.designsystem.theme.HideoutMotion
import gg.hideouthq.core.designsystem.theme.HideoutTheme

private val RowShape = RoundedCornerShape(HideoutDimens.CheckboxRow.Radius)

@Composable
fun CheckboxRow(
    title: String,
    detail: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    count: Int? = null,
    enabled: Boolean = true,
) {
    val source = remember { MutableInteractionSource() }
    val states = source.collectInteractionStates()
    val scheme = MaterialTheme.colorScheme
    val type = HideoutTheme.typography
    val container by animateColorAsState(
        if (states.hovered && enabled) scheme.surfaceContainerHigh else scheme.surfaceContainer,
        HideoutTheme.motion.effectsFast(),
        label = "checkboxRowContainer",
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = HideoutDimens.CheckboxRow.MinHeight)
            .alpha(if (enabled) 1f else HideoutMotion.DisabledAlpha)
            .drawBehind { drawRoundRect(container, cornerRadius = CornerRadius(HideoutDimens.CheckboxRow.Radius.toPx())) }
            .focusRing(states.focusVisible, RowShape)
            .hideoutToggleable(checked, source, enabled = enabled, role = Role.Checkbox, onValueChange = onCheckedChange)
            .padding(
                start = HideoutDimens.CheckboxRow.StartPadding,
                end = HideoutDimens.CheckboxRow.EndPadding,
                top = HideoutDimens.CheckboxRow.VerticalPadding,
                bottom = HideoutDimens.CheckboxRow.VerticalPadding,
            ),
        horizontalArrangement = Arrangement.spacedBy(HideoutDimens.CheckboxRow.Gap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HideoutCheckbox(checked, size = HideoutDimens.CheckboxRow.Box, ringColor = scheme.surfaceContainerHighest)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(HideoutDimens.CheckboxRow.DetailGap)) {
            Text(title, style = type.titlePanel, color = scheme.onSurface, maxLines = 1)
            if (detail != null) Text(detail, style = type.subtitle, color = scheme.outline, maxLines = 1)
        }
        if (count != null) {
            Text(
                count.toString(),
                style = type.monoCount.copy(fontSize = CountSize),
                color = scheme.onSurface,
                textAlign = TextAlign.End,
                modifier = Modifier.widthIn(min = HideoutDimens.CheckboxRow.CountMinWidth),
            )
        }
    }
}

private val CountSize = 13.sp
