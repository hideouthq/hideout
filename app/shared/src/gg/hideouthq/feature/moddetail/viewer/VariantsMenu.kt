package gg.hideouthq.feature.moddetail.viewer

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import gg.hideouthq.core.designsystem.component.Eyebrow
import gg.hideouthq.core.designsystem.component.HideoutIcon
import gg.hideouthq.core.designsystem.component.HideoutMenu
import gg.hideouthq.core.designsystem.component.MenuEntry
import gg.hideouthq.core.designsystem.icon.HideoutIcons
import gg.hideouthq.core.designsystem.interaction.collectInteractionStates
import gg.hideouthq.core.designsystem.interaction.focusRing
import gg.hideouthq.core.designsystem.interaction.hideoutClickable
import gg.hideouthq.core.designsystem.interaction.hideoutSelectable
import gg.hideouthq.core.designsystem.theme.HideoutDimens
import gg.hideouthq.core.designsystem.theme.HideoutShapes
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import gg.hideouthq.core.designsystem.theme.HideoutWeights
import gg.hideouthq.core.designsystem.theme.HideoutDimens.Variants as Menu
import gg.hideouthq.feature.moddetail.FileSelection

@Composable
internal fun VariantsMenu(variants: List<StageVariant>, selected: FileSelection?, title: String, onPick: (FileSelection) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    val source = remember { MutableInteractionSource() }
    val states = source.collectInteractionStates()
    val trigger = remember { FocusRequester() }
    val content by animateColorAsState(
        if (states.hovered || open) scheme.onSurface else scheme.onSurfaceVariant,
        HideoutTheme.motion.effectsFast(),
        label = "variantsTrigger",
    )
    Box(modifier) {
        Row(
            Modifier
                .widthIn(max = Menu.TriggerMaxWidth)
                .height(HideoutDimens.Pill.Height)
                .background(scheme.surfaceContainer, HideoutShapes.Row)
                .focusRing(states.focusVisible, HideoutShapes.Row)
                .focusRequester(trigger)
                .hideoutClickable(source, role = Role.DropdownList) { open = true }
                .semantics { contentDescription = title }
                .padding(start = Menu.LabelStart, top = HideoutDimens.Segmented.Padding, end = HideoutDimens.Segmented.Padding, bottom = HideoutDimens.Segmented.Padding),
            horizontalArrangement = Arrangement.spacedBy(Menu.LabelGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Eyebrow(title, style = HideoutTheme.typography.eyebrowSmall, color = scheme.outline)
            Row(
                Modifier
                    .weight(1f, fill = false)
                    .fillMaxHeight()
                    .background(scheme.surfaceContainerHighest, HideoutShapes.Row)
                    .padding(horizontal = HideoutDimens.Segmented.SegmentHorizontalPadding),
                horizontalArrangement = Arrangement.spacedBy(HideoutDimens.Pill.Gap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                variants.firstOrNull { it.file == selected }?.let { current ->
                    Text(
                        current.label,
                        Modifier.weight(1f, fill = false),
                        style = HideoutTheme.typography.labelChip,
                        color = content,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                HideoutIcon(HideoutIcons.ChevronDown, contentDescription = null, size = HideoutDimens.Icon.Small, tint = content)
            }
        }
        if (open) {
            Box(Modifier.matchParentSize().wrapContentWidth(Alignment.Start, unbounded = true).width(HideoutDimens.Menu.Width)) {
                HideoutMenu(
                    entries = variants.map { variant ->
                        MenuEntry.Content {
                            VariantRow(variant.label, variant.file == selected) {
                                open = false
                                trigger.requestFocus()
                                onPick(variant.file)
                            }
                        }
                    },
                    onClose = { byKey -> open = false; if (byKey) trigger.requestFocus() },
                    label = title,
                )
            }
        }
    }
}

@Composable
private fun VariantRow(label: String, selected: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val source = remember { MutableInteractionSource() }
    val states = source.collectInteractionStates()
    val shape = RoundedCornerShape(Menu.RowRadius)
    val motion = HideoutTheme.motion
    val fill by animateColorAsState(
        when {
            selected -> scheme.primary
            states.hovered -> scheme.surfaceContainerHighest
            else -> scheme.surfaceContainerHighest.copy(alpha = 0f)
        },
        motion.effectsFast(),
        label = "variantFill",
    )
    Row(
        Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = Menu.RowMinHeight)
            .background(fill, shape)
            .focusRing(states.focusVisible, shape)
            .hideoutSelectable(selected, source, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = Menu.RowHorizontal, vertical = Menu.RowVertical),
        horizontalArrangement = Arrangement.spacedBy(Menu.RowGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(HideoutDimens.Icon.Small), contentAlignment = Alignment.Center) {
            if (selected) HideoutIcon(HideoutIcons.Check, contentDescription = null, size = HideoutDimens.Icon.Small, tint = scheme.onPrimary)
        }
        Text(
            label,
            Modifier.weight(1f),
            style = HideoutTheme.typography.labelPill.copy(fontWeight = if (selected) HideoutWeights.Title else HideoutWeights.Value),
            color = if (selected) scheme.onPrimary else scheme.onSurfaceVariant,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
