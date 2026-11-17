package gg.hideouthq.feature.crosshairs.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import gg.hideouthq.core.crosshair.Adjustable
import gg.hideouthq.core.crosshair.Crosshair
import gg.hideouthq.core.crosshair.CrosshairColor
import gg.hideouthq.core.crosshair.SavedCrosshair
import gg.hideouthq.core.data.SavedNote
import gg.hideouthq.core.designsystem.component.ButtonSize
import gg.hideouthq.core.designsystem.component.ButtonVariant
import gg.hideouthq.core.designsystem.component.ChipTone
import gg.hideouthq.core.designsystem.component.HideoutButton
import gg.hideouthq.core.designsystem.component.HideoutSlider
import gg.hideouthq.core.designsystem.component.HideoutSwitch
import gg.hideouthq.core.designsystem.component.PageCard
import gg.hideouthq.core.designsystem.component.StatusChip
import gg.hideouthq.core.designsystem.component.TextInput
import gg.hideouthq.core.designsystem.component.TextInputSize
import gg.hideouthq.core.designsystem.component.TextInputStyle
import gg.hideouthq.core.designsystem.icon.HideoutIcons
import gg.hideouthq.core.designsystem.interaction.collectInteractionStates
import gg.hideouthq.core.designsystem.interaction.focusRing
import gg.hideouthq.core.designsystem.interaction.hideoutSelectable
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import gg.hideouthq.core.designsystem.theme.HideoutWeights
import gg.hideouthq.core.ui.SavedNoteLine
import gg.hideouthq.feature.crosshairs.CrosshairsActions
import gg.hideouthq.feature.crosshairs.CrosshairsUiState
import gg.hideouthq.resources.Res
import gg.hideouthq.resources.crosshairs_border_color
import gg.hideouthq.resources.crosshairs_border_hex
import gg.hideouthq.resources.crosshairs_center_dot
import gg.hideouthq.resources.crosshairs_collapse
import gg.hideouthq.resources.crosshairs_collapse_hint
import gg.hideouthq.resources.crosshairs_color
import gg.hideouthq.resources.crosshairs_dot_border
import gg.hideouthq.resources.crosshairs_dot_border_gap
import gg.hideouthq.resources.crosshairs_dot_border_opacity
import gg.hideouthq.resources.crosshairs_dot_border_size
import gg.hideouthq.resources.crosshairs_dot_opacity
import gg.hideouthq.resources.crosshairs_dot_size
import gg.hideouthq.resources.crosshairs_fixed_gap
import gg.hideouthq.resources.crosshairs_fixed_gap_hint
import gg.hideouthq.resources.crosshairs_gap
import gg.hideouthq.resources.crosshairs_hex
import gg.hideouthq.resources.crosshairs_in_use
import gg.hideouthq.resources.crosshairs_length
import gg.hideouthq.resources.crosshairs_line_border
import gg.hideouthq.resources.crosshairs_line_border_gap
import gg.hideouthq.resources.crosshairs_line_border_opacity
import gg.hideouthq.resources.crosshairs_line_border_size
import gg.hideouthq.resources.crosshairs_lines
import gg.hideouthq.resources.crosshairs_name
import gg.hideouthq.resources.crosshairs_opacity
import gg.hideouthq.resources.crosshairs_reset
import gg.hideouthq.resources.crosshairs_size
import gg.hideouthq.resources.crosshairs_swatches
import gg.hideouthq.resources.crosshairs_thickness
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringArrayResource
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun CrosshairEditor(
    selected: SavedCrosshair,
    inUse: Boolean,
    nameDraft: String?,
    state: CrosshairsUiState,
    actions: CrosshairsActions,
    modifier: Modifier,
    name: FocusRequester,
    renames: Int,
) {
    val crosshair = selected.crosshair
    LaunchedEffect(renames) { if (renames > 0) name.requestFocus() }
    PageCard(modifier, contentPadding = PaddingValues(start = 8.dp, top = 8.dp, end = 8.dp, bottom = 10.dp)) {
        Row(Modifier.padding(start = 4.dp, top = 4.dp, end = 4.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextInput(
                nameDraft ?: selected.name, actions::setName, stringResource(Res.string.crosshairs_name),
                Modifier.weight(1f).focusRequester(name), style = TextInputStyle.Title, onSubmit = actions::commitName,
            )
            if (inUse) StatusChip(stringResource(Res.string.crosshairs_in_use), ChipTone.Good, leadingIcon = HideoutIcons.Check)
            else UseInGameButton(state.cannotUse, actions::useSelected, size = ButtonSize.Medium)
        }
        Section(stringResource(Res.string.crosshairs_color)) {
            ColorRow(crosshair.color, actions::setColor)
            BorderColorRow(crosshair.borderColor, actions::setBorderColor)
        }
        Section(stringResource(Res.string.crosshairs_lines)) {
            Sliders(SliderSections.getValue(EditorSection.Lines), crosshair, actions)
            ToggleRow(stringResource(Res.string.crosshairs_fixed_gap), stringResource(Res.string.crosshairs_fixed_gap_hint), crosshair.fixedGap, actions::setFixedGap)
            // The game greys its Collapse static reticle out while the reticle moves, so it waits for Fixed gap here too.
            ToggleRow(stringResource(Res.string.crosshairs_collapse), stringResource(Res.string.crosshairs_collapse_hint), crosshair.collapse, actions::setCollapse, enabled = crosshair.fixedGap)
        }
        for (section in listOf(EditorSection.LineBorder, EditorSection.CenterDot, EditorSection.DotBorder)) {
            Section(stringResource(section.title)) { Sliders(SliderSections.getValue(section), crosshair, actions) }
        }
        Column(Modifier.padding(top = 4.dp).rule().padding(start = 8.dp, top = 13.dp, end = 8.dp, bottom = 2.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                HideoutButton(stringResource(Res.string.crosshairs_reset), actions::reset, variant = ButtonVariant.Text, size = ButtonSize.Small, leadingIcon = HideoutIcons.Reset, leadingIconSize = 18.dp)
                Spacer(Modifier.weight(1f))
                if (inUse) SavedNoteLine(state.note, actions::retrySave)
            }
            if (!inUse && state.note is SavedNote.Failed.Yours) SavedNoteLine(state.note, actions::retrySave, Modifier.align(Alignment.End))
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.rule().padding(start = 8.dp, top = 11.dp, end = 8.dp, bottom = 6.dp)) {
        Text(title, Modifier.padding(bottom = 8.dp), style = headingStyle(), color = MaterialTheme.colorScheme.onSurface)
        content()
    }
}

@Composable
private fun ColorRow(color: CrosshairColor, onColor: (CrosshairColor) -> Unit) {
    val names = stringArrayResource(Res.array.crosshairs_swatches)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        CrosshairColor.Swatches.forEachIndexed { index, swatch ->
            Swatch(swatch, swatch == color, names.getOrElse(index) { swatch.hex }) { onColor(swatch) }
        }
        Spacer(Modifier.weight(1f))
        HexField(color, stringResource(Res.string.crosshairs_hex), onColor)
    }
}

@Composable
private fun BorderColorRow(color: CrosshairColor, onColor: (CrosshairColor) -> Unit) {
    val label = stringResource(Res.string.crosshairs_border_color)
    Row(Modifier.fillMaxWidth().height(40.dp).padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = HideoutTheme.typography.captionSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        HexField(color, stringResource(Res.string.crosshairs_border_hex), onColor)
    }
}

@Composable
private fun HexField(color: CrosshairColor, label: String, onColor: (CrosshairColor) -> Unit) {
    var hex by remember(color) { mutableStateOf(color.hex) }
    TextInput(
        hex,
        { text ->
            hex = text.uppercase()
            CrosshairColor.parseHex(text)?.let(onColor)
        },
        label, Modifier.width(114.dp), size = TextInputSize.Compact,
        leading = { Box(Modifier.size(18.dp).swatch(color.toColor(), RoundedCornerShape(6.dp))) },
    )
}

@Composable
private fun Swatch(color: CrosshairColor, picked: Boolean, name: String, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val states = source.collectInteractionStates()
    val ring by animateColorAsState(
        if (picked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primary.copy(alpha = 0f),
        HideoutTheme.motion.effectsFast(), label = "swatchRing",
    )
    Box(
        Modifier
            .size(30.dp)
            .outerRing(ring, 15.dp)
            .swatch(color.toColor(), CircleShape, edgeAlpha = 0.18f)
            .focusRing(states.focusVisible, CircleShape)
            .semantics { contentDescription = name }
            .hideoutSelectable(picked, source, role = Role.RadioButton, onClick = onClick),
    )
}

private enum class EditorSection(val title: StringResource) {
    Lines(Res.string.crosshairs_lines),
    LineBorder(Res.string.crosshairs_line_border),
    CenterDot(Res.string.crosshairs_center_dot),
    DotBorder(Res.string.crosshairs_dot_border),
}

private class SliderSpec(val label: StringResource, val name: StringResource, val setting: Adjustable)

private val SliderSections: Map<EditorSection, List<SliderSpec>> = mapOf(
    EditorSection.Lines to listOf(
        SliderSpec(Res.string.crosshairs_length, Res.string.crosshairs_length, Adjustable.Length),
        SliderSpec(Res.string.crosshairs_thickness, Res.string.crosshairs_thickness, Adjustable.Thickness),
        SliderSpec(Res.string.crosshairs_gap, Res.string.crosshairs_gap, Adjustable.Gap),
        SliderSpec(Res.string.crosshairs_opacity, Res.string.crosshairs_opacity, Adjustable.Opacity),
    ),
    EditorSection.LineBorder to listOf(
        SliderSpec(Res.string.crosshairs_size, Res.string.crosshairs_line_border_size, Adjustable.LineBorder),
        SliderSpec(Res.string.crosshairs_gap, Res.string.crosshairs_line_border_gap, Adjustable.LineBorderGap),
        SliderSpec(Res.string.crosshairs_opacity, Res.string.crosshairs_line_border_opacity, Adjustable.LineBorderOpacity),
    ),
    EditorSection.CenterDot to listOf(
        SliderSpec(Res.string.crosshairs_size, Res.string.crosshairs_dot_size, Adjustable.DotSize),
        SliderSpec(Res.string.crosshairs_opacity, Res.string.crosshairs_dot_opacity, Adjustable.DotOpacity),
    ),
    EditorSection.DotBorder to listOf(
        SliderSpec(Res.string.crosshairs_size, Res.string.crosshairs_dot_border_size, Adjustable.DotBorder),
        SliderSpec(Res.string.crosshairs_gap, Res.string.crosshairs_dot_border_gap, Adjustable.DotBorderGap),
        SliderSpec(Res.string.crosshairs_opacity, Res.string.crosshairs_dot_border_opacity, Adjustable.DotBorderOpacity),
    ),
)

@Composable
private fun Sliders(sliders: List<SliderSpec>, crosshair: Crosshair, actions: CrosshairsActions) {
    sliders.forEach { SliderRow(stringResource(it.label), stringResource(it.name), it.setting, crosshair, actions) }
}

@Composable
private fun SliderRow(label: String, name: String, setting: Adjustable, crosshair: Crosshair, actions: CrosshairsActions) {
    val scheme = MaterialTheme.colorScheme
    val value = setting.get(crosshair)
    val shown = if (setting.percent) percent(value) else value.toString()
    Row(Modifier.fillMaxWidth().height(40.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(92.dp), style = HideoutTheme.typography.captionSmall, color = scheme.onSurfaceVariant, maxLines = 1)
        HideoutSlider(
            value.value.toFloat(), { actions.setValue(setting, it.toDouble()) },
            setting.range.start.toFloat()..setting.range.endInclusive.toFloat(), setting.step.toFloat(), name, Modifier.weight(1f),
        )
        Text(shown, Modifier.width(44.dp), style = valueStyle(), color = scheme.onSurface, textAlign = TextAlign.End, maxLines = 1)
    }
}

@Composable
private fun ToggleRow(title: String, hint: String, checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    val scheme = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(vertical = 4.dp).alpha(if (enabled) 1f else DisabledAlpha), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = HideoutTheme.typography.labelMenuItem.copy(fontWeight = HideoutWeights.Label), color = scheme.onSurface)
            Text(hint, Modifier.padding(top = 1.dp), style = HideoutTheme.typography.caption, color = scheme.outline)
        }
        HideoutSwitch(checked, onChange, contentDescription = title, enabled = enabled)
    }
}

private const val DisabledAlpha = 0.38f

@Composable
private fun Modifier.rule(): Modifier {
    val line = MaterialTheme.colorScheme.outlineVariant
    return drawBehind { drawLine(line, Offset(0f, 0.5f), Offset(size.width, 0.5f), 1.dp.toPx()) }
}
