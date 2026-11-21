package gg.hideouthq.feature.moddetail.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import gg.hideouthq.core.designsystem.component.HideoutIconButton
import gg.hideouthq.core.designsystem.component.HideoutIconToggleButton
import gg.hideouthq.core.designsystem.component.HideoutTooltip
import gg.hideouthq.core.designsystem.component.TooltipSide
import gg.hideouthq.core.designsystem.component.HideoutMenu
import gg.hideouthq.core.designsystem.component.InstallButton
import gg.hideouthq.core.designsystem.component.MenuEntry
import gg.hideouthq.core.designsystem.icon.HideoutIcons
import gg.hideouthq.core.designsystem.theme.HideoutDimens
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import gg.hideouthq.core.library.OperationStage
import gg.hideouthq.core.library.choice
import gg.hideouthq.core.ui.CompatChip
import gg.hideouthq.core.ui.ModLabels
import gg.hideouthq.core.ui.ProfileChips
import gg.hideouthq.core.ui.rememberInstallOrigin
import gg.hideouthq.feature.moddetail.ModDetailActions
import gg.hideouthq.feature.moddetail.ModDetailUiState
import gg.hideouthq.resources.*
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun InstallBar(state: ModDetailUiState, actions: ModDetailActions, modifier: Modifier = Modifier) {
    val header = state.header ?: return
    val page = state.installation
    val installed = page.record != null
    val file = if (installed) header.previewVariants.firstOrNull { it.fileId == page.installedFileIds.firstOrNull() }
        else header.previewVariants.singleOrNull()
    val busy = page.operation != null && page.operation.stage !is OperationStage.Failed
    var menu by remember(header.modId) { mutableStateOf(false) }
    val base = ModLabels.install(page.status)
    val size = file?.sizeBytes?.let { ModLabels.size(it) }
    val asking = page.operation?.stage?.choice != null
    val labels = if (asking) base else base.copy(install = ModLabels.join(base.install, size))
    Column(
        modifier.padding(start = HideoutDimens.SidePanel.Padding, end = HideoutDimens.SidePanel.Padding, top = 18.dp, bottom = HideoutDimens.SidePanel.Padding),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        page.record?.let { record -> page.compat?.let { CompatChip(it, record.enabled, header.title, onClick = actions::openCompat) } }
        if (installed || busy && !asking && page.selectedProfiles.isNotEmpty() && page.profiles.size > 1) {
            SectionLabel(stringResource(if (installed) Res.string.profile_install_on_in else Res.string.profile_install_turn_on))
            ProfileChips(page.profiles, page.selectedProfiles, actions::setProfile, enabled = (if (installed) page.canEditChoices else page.canInstall) && !busy, waiting = page.applying)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val origin = rememberInstallOrigin(header.modId)
            Box(Modifier.weight(1f).then(origin.modifier)) {
                InstallButton(
                    state = ModLabels.installButton(page.status),
                    labels = labels,
                    onClick = when {
                        installed && !busy -> { { menu = true } }
                        page.canInstall && !busy -> { { origin.claim(); actions.install() } }
                        else -> null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    showInstalledMenu = installed && !busy,
                )
                if (menu) HideoutMenu(
                    entries = listOfNotNull(
                        MenuEntry.Item(stringResource(Res.string.profile_install_change_version), HideoutIcons.SwapVersions, { origin.claim(); actions.changeFiles() }, enabled = page.canInstall).takeIf { page.record?.edited != true },
                        MenuEntry.Item(stringResource(Res.string.profile_install_show_library), HideoutIcons.Layers, actions::showInLibrary),
                        MenuEntry.Item(stringResource(Res.string.menu_show_files), HideoutIcons.Folder, actions::showFiles),
                        MenuEntry.Separator,
                        MenuEntry.Item(stringResource(Res.string.menu_uninstall), HideoutIcons.Trash, actions::uninstall, danger = true, enabled = page.canUninstall),
                    ),
                    onClose = { menu = false },
                    label = labels.installed,
                )
            }
            if (page.canCancel) HideoutIconButton(
                HideoutIcons.Close, stringResource(Res.string.profile_install_cancel), actions::cancelInstall,
                modifier = Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape), size = 48.dp,
            )
            HideoutTooltip(stringResource(if (state.favourite) Res.string.favourite_remove else Res.string.favourite_add), side = TooltipSide.Above) {
                HideoutIconToggleButton(
                    if (state.favourite) HideoutIcons.StarFilled else HideoutIcons.Star, stringResource(Res.string.favourite_add), state.favourite, actions::setFavourite,
                    modifier = Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape), size = 48.dp,
                    hoverColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                )
            }
        }
        val transfer = (page.operation?.stage as? OperationStage.Downloading)?.progress
        val wait = ModLabels.installWait(page.status)
        if (wait != null) {
            Text(wait, style = HideoutTheme.typography.caption, color = MaterialTheme.colorScheme.outline)
        } else if (transfer != null) {
            val bytes = ModLabels.join(ModLabels.size(transfer.doneBytes), transfer.totalBytes.takeIf { it > 0 }?.let { ModLabels.size(it) })
            val remaining = transfer.remainingSeconds?.let { seconds ->
                if (seconds < 60) stringResource(Res.string.profile_install_seconds_left, seconds)
                else stringResource(Res.string.profile_install_minutes_left, (seconds - 1) / 60 + 1)
            }
            val hint = ModLabels.join(bytes, remaining)
            if (hint.isNotEmpty()) Text(hint, style = HideoutTheme.typography.caption, color = MaterialTheme.colorScheme.outline)
        }
    }
}
