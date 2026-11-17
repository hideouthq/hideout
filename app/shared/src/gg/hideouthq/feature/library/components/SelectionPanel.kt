package gg.hideouthq.feature.library.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import gg.hideouthq.core.designsystem.component.ButtonSize
import gg.hideouthq.core.designsystem.component.ButtonVariant
import gg.hideouthq.core.designsystem.component.FactItem
import gg.hideouthq.core.designsystem.component.HideoutButton
import gg.hideouthq.core.designsystem.component.HideoutDivider
import gg.hideouthq.core.designsystem.component.KeyboardHint
import gg.hideouthq.core.designsystem.component.PaneHeader
import gg.hideouthq.core.designsystem.icon.HideoutIcons
import gg.hideouthq.core.designsystem.theme.HideoutDimens
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import gg.hideouthq.core.library.Profile
import gg.hideouthq.core.library.ProfileId
import gg.hideouthq.core.ui.ModLabels
import gg.hideouthq.core.ui.ProfileChips
import gg.hideouthq.feature.library.Coverage
import gg.hideouthq.feature.library.SelectedMods
import gg.hideouthq.resources.Res
import gg.hideouthq.resources.action_uninstall
import gg.hideouthq.resources.details_title
import gg.hideouthq.resources.fact_size_on_disk
import gg.hideouthq.resources.fact_status
import gg.hideouthq.resources.library_clear_selection
import gg.hideouthq.resources.library_selected_count
import gg.hideouthq.resources.library_selection_status
import gg.hideouthq.resources.library_turn_off
import gg.hideouthq.resources.library_turn_on
import gg.hideouthq.resources.profile_library_membership
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun SelectionPanel(selection: SelectedMods, profiles: List<Profile>, onTurn: (Boolean, ProfileId?) -> Unit, onClear: () -> Unit, onUninstall: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize()) {
        PaneHeader(stringResource(Res.string.details_title))
        Column(
            Modifier.weight(1f).fillMaxWidth().padding(start = HideoutDimens.SidePanel.Padding, end = HideoutDimens.SidePanel.Padding),
            verticalArrangement = Arrangement.spacedBy(HideoutDimens.Library.PanelGap),
        ) {
            Text(pluralStringResource(Res.plurals.library_selected_count, selection.count, selection.count), style = HideoutTheme.typography.titleSection, color = scheme.onSurface)
            HideoutDivider()
            FactItem(icon = if (selection.on > 0) HideoutIcons.Check else HideoutIcons.Minus, label = stringResource(Res.string.fact_status), value = stringResource(Res.string.library_selection_status, selection.on, selection.count - selection.on))
            FactItem(icon = HideoutIcons.Folder, label = stringResource(Res.string.fact_size_on_disk), value = ModLabels.size(selection.sizeBytes))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(HideoutDimens.Button.Gap)) {
                HideoutButton(stringResource(Res.string.library_clear_selection), onClear, variant = ButtonVariant.Text, size = ButtonSize.Small)
                KeyboardHint("Esc")
            }
            if (profiles.size > 1) {
                HideoutDivider()
                Text(stringResource(Res.string.profile_library_membership), style = HideoutTheme.typography.caption, color = scheme.outline)
                ProfileChips(
                    profiles = profiles,
                    selected = selection.coverage.filterValues { it == Coverage.All }.keys,
                    mixed = selection.coverage.filterValues { it == Coverage.Some }.keys,
                    onToggle = { profile, enabled -> onTurn(enabled, profile) },
                    enabled = selection.canToggle,
                )
            }
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = HideoutDimens.SidePanel.Padding,
                    end = HideoutDimens.SidePanel.Padding,
                    top = HideoutDimens.SidePanel.FooterTopPadding,
                    bottom = HideoutDimens.SidePanel.Padding,
                ),
            verticalArrangement = Arrangement.spacedBy(HideoutDimens.Button.Gap),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(HideoutDimens.Button.Gap)) {
                HideoutButton(stringResource(Res.string.library_turn_on), { onTurn(true, null) }, Modifier.weight(1f), variant = ButtonVariant.Tonal, enabled = selection.canToggle && selection.on < selection.count)
                HideoutButton(stringResource(Res.string.library_turn_off), { onTurn(false, null) }, Modifier.weight(1f), variant = ButtonVariant.Subtle, enabled = selection.canToggle && selection.on > 0)
            }
            HideoutButton(stringResource(Res.string.action_uninstall), onUninstall, Modifier.fillMaxWidth(), variant = ButtonVariant.Subtle, enabled = selection.canUninstall)
        }
    }
}
