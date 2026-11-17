package gg.hideouthq.feature.settings.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import gg.hideouthq.core.designsystem.component.ButtonSize
import gg.hideouthq.core.designsystem.component.HideoutButton
import gg.hideouthq.core.designsystem.component.HideoutIcon
import gg.hideouthq.core.designsystem.component.HideoutSwitch
import gg.hideouthq.core.designsystem.icon.HideoutIcons
import gg.hideouthq.core.designsystem.theme.HideoutDimens
import gg.hideouthq.core.designsystem.theme.HideoutRadius
import gg.hideouthq.core.designsystem.theme.HideoutShapes
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import gg.hideouthq.feature.settings.RowControl
import gg.hideouthq.feature.settings.SettingAction
import gg.hideouthq.feature.settings.SettingId
import gg.hideouthq.feature.settings.SettingRow
import gg.hideouthq.feature.settings.SettingsActions
import gg.hideouthq.resources.Res
import gg.hideouthq.resources.hideout_logo_small
import org.jetbrains.compose.resources.painterResource

@Composable
internal fun SettingsGroup(
    rows: List<SettingRow>,
    onAction: (SettingAction) -> Unit,
    actions: SettingsActions,
    titleRanges: (SettingId) -> List<IntRange> = { emptyList() },
    descriptionRanges: (SettingId) -> List<IntRange> = { emptyList() },
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(HideoutDimens.Settings.GroupGap)) {
        rows.forEachIndexed { index, row ->
            SettingRowView(row, groupShape(index, rows.size), titleRanges(row.setting.id), descriptionRanges(row.setting.id), onAction, actions)
        }
    }
}

@Composable
internal fun GroupHeading(text: String, first: Boolean) {
    Text(
        text = text,
        style = HideoutTheme.typography.labelGroup,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .padding(
                start = HideoutDimens.Settings.HeadingHorizontalPadding,
                end = HideoutDimens.Settings.HeadingHorizontalPadding,
                top = if (first) HideoutDimens.Settings.FirstHeadingTopPadding else HideoutDimens.Settings.HeadingTopPadding,
                bottom = HideoutDimens.Settings.HeadingBottomPadding,
            )
            .semantics { heading() },
    )
}

@Composable
private fun SettingRowView(
    row: SettingRow,
    shape: Shape,
    titleRanges: List<IntRange>,
    descriptionRanges: List<IntRange>,
    onAction: (SettingAction) -> Unit,
    actions: SettingsActions,
) {
    val scheme = MaterialTheme.colorScheme
    val type = HideoutTheme.typography
    val under = Modifier.padding(start = HideoutDimens.Settings.IconTile + HideoutDimens.Settings.CardGap)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(scheme.surfaceContainer, shape)
            .padding(horizontal = HideoutDimens.Settings.RowHorizontalPadding, vertical = HideoutDimens.Settings.RowVerticalPadding),
        verticalArrangement = Arrangement.spacedBy(HideoutDimens.Settings.PickerTopPadding),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(HideoutDimens.Settings.CardGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SettingTile(row.setting.icon, scheme.surfaceContainerHigh, scheme.onSurface)
            Column(Modifier.weight(1f)) {
                Row(horizontalArrangement = Arrangement.spacedBy(HideoutDimens.Settings.TitleGap), verticalAlignment = Alignment.CenterVertically) {
                    Text(highlighted(row.text.title, titleRanges), style = type.titleSetting, color = scheme.onSurface)
                    row.chip?.let { StatusChip(it.label, it.on) }
                }
                row.text.description?.let { description ->
                    Text(
                        text = highlighted(description, descriptionRanges),
                        style = if (row.mono) type.monoPath else type.notice,
                        color = scheme.outline,
                        maxLines = if (row.mono) 1 else Int.MAX_VALUE,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = HideoutDimens.Settings.DescriptionTopPadding),
                    )
                }
                row.note?.let { NoteMessage(it, Modifier.padding(top = HideoutDimens.Settings.NoteTopPadding)) }
            }
            when (val control = row.control) {
                is RowControl.Switch -> HideoutSwitch(
                    checked = control.checked,
                    onCheckedChange = { actions.toggle(row.setting.id, it) },
                    contentDescription = row.text.title,
                    enabled = control.enabled,
                )
                is RowControl.Button -> HideoutButton(
                    text = control.label,
                    onClick = { onAction(control.action) },
                    variant = control.variant,
                    size = ButtonSize.Small,
                    enabled = control.enabled,
                    contentPadding = PaddingValues(horizontal = HideoutDimens.Settings.ButtonHorizontalPadding),
                )
                is RowControl.Accent, is RowControl.CardStyle, RowControl.None -> Unit
            }
        }
        when (val control = row.control) {
            is RowControl.Accent -> AccentPicker(control.selected, actions::pickAccent, row.text.title, under)
            is RowControl.CardStyle -> ModCardPicker(control.selected, actions::pickModCards, row.text.title, under)
            else -> Unit
        }
    }
}

@Composable
private fun highlighted(text: String, ranges: List<IntRange>): AnnotatedString {
    if (ranges.isEmpty()) return AnnotatedString(text)
    val mark = SpanStyle(background = MaterialTheme.colorScheme.primary.copy(alpha = HighlightAlpha))
    return buildAnnotatedString {
        var at = 0
        for (range in ranges) {
            append(text.substring(at, range.first))
            withStyle(mark) { append(text.substring(range.first, range.last + 1)) }
            at = range.last + 1
        }
        append(text.substring(at))
    }
}

private fun groupShape(index: Int, size: Int): Shape {
    val top = if (index == 0) HideoutRadius.SettingsGroupEdge else HideoutRadius.SettingsGroupInner
    val bottom = if (index == size - 1) HideoutRadius.SettingsGroupEdge else HideoutRadius.SettingsGroupInner
    return RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)
}

@Composable
internal fun SettingTile(icon: ImageVector?, container: Color, content: Color) {
    Box(
        modifier = Modifier.size(HideoutDimens.Settings.IconTile).background(container, HideoutShapes.SettingsTile),
        contentAlignment = Alignment.Center,
    ) {
        if (icon == null) {
            Image(painterResource(Res.drawable.hideout_logo_small), contentDescription = null, modifier = Modifier.size(HideoutDimens.Icon.Size))
        } else {
            HideoutIcon(icon, contentDescription = null, tint = content)
        }
    }
}

@Composable
internal fun StatusChip(text: String, on: Boolean = true) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .height(HideoutDimens.Settings.StatusChipHeight)
            .background(if (on) scheme.secondaryContainer else scheme.surfaceContainerHigh, HideoutShapes.Full)
            .padding(start = if (on) HideoutDimens.Settings.StatusChipStartPadding else HideoutDimens.Settings.StatusChipEndPadding, end = HideoutDimens.Settings.StatusChipEndPadding),
        horizontalArrangement = Arrangement.spacedBy(HideoutDimens.Settings.StatusChipGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (on) HideoutIcon(HideoutIcons.Check, contentDescription = null, size = HideoutDimens.Settings.StatusChipIcon, tint = scheme.secondary)
        Text(text, style = HideoutTheme.typography.labelStatus, color = if (on) scheme.secondary else scheme.onSurfaceVariant)
    }
}

@Composable
internal fun NoteMessage(text: AnnotatedString, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(HideoutDimens.Settings.MessageGap),
    ) {
        HideoutIcon(
            HideoutIcons.Warning,
            contentDescription = null,
            size = HideoutDimens.Settings.MessageIcon,
            tint = scheme.error,
            modifier = Modifier.padding(top = HideoutDimens.Settings.MessageIconTopPadding),
        )
        Text(text, style = HideoutTheme.typography.notice, color = scheme.onSurfaceVariant)
    }
}

private const val HighlightAlpha = 0.24f
