package gg.hideouthq.feature.library.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import gg.hideouthq.core.designsystem.component.ButtonSize
import gg.hideouthq.core.designsystem.component.ButtonVariant
import gg.hideouthq.core.designsystem.component.ChipSize
import gg.hideouthq.core.designsystem.component.ChipTone
import gg.hideouthq.core.designsystem.component.HideoutButton
import gg.hideouthq.core.designsystem.component.HideoutIconButton
import gg.hideouthq.core.designsystem.component.HideoutTooltip
import gg.hideouthq.core.designsystem.component.StatusChip
import gg.hideouthq.core.designsystem.icon.HideoutIcons
import gg.hideouthq.core.designsystem.theme.HideoutDimens
import gg.hideouthq.core.library.FilesDamage
import gg.hideouthq.core.ui.ModLabels
import gg.hideouthq.feature.library.DownloadPhase
import gg.hideouthq.feature.library.LibraryActions
import gg.hideouthq.feature.library.LibraryRow
import gg.hideouthq.feature.library.UpdateState
import gg.hideouthq.resources.Res
import gg.hideouthq.resources.install_dialog_set_files
import gg.hideouthq.resources.library_damage_changed
import gg.hideouthq.resources.library_damage_missing
import gg.hideouthq.resources.library_finding
import gg.hideouthq.resources.library_hidden_models
import gg.hideouthq.resources.library_reinstall
import gg.hideouthq.resources.library_reinstall_named
import gg.hideouthq.resources.library_update_action
import gg.hideouthq.resources.library_update_available
import gg.hideouthq.resources.library_update_cancel
import gg.hideouthq.resources.library_update_change_named
import gg.hideouthq.resources.library_update_keep
import gg.hideouthq.resources.library_update_keep_named
import gg.hideouthq.resources.library_update_named
import gg.hideouthq.resources.library_update_review
import gg.hideouthq.resources.library_update_unknown
import gg.hideouthq.resources.library_update_updating
import gg.hideouthq.resources.library_update_waiting_custom_game
import gg.hideouthq.resources.library_update_waiting_game
import gg.hideouthq.resources.profile_install_change_version
import gg.hideouthq.resources.profile_library_row_past_limit
import gg.hideouthq.resources.update_cancel
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun LibraryRow.statusLine(): String {
    val operation = operation
    if (operation != null) {
        return when (val phase = operation.phase) {
            DownloadPhase.WaitingForGame -> stringResource(Res.string.library_update_waiting_game)
            DownloadPhase.WaitingForCustomGame -> stringResource(Res.string.library_update_waiting_custom_game)
            else -> ModLabels.join(stringResource(Res.string.library_update_updating), phase.subtitle())
        }
    }
    if (pastLimit) return stringResource(Res.string.profile_library_row_past_limit)
    val label = when (update) {
        UpdateState.Available -> stringResource(Res.string.library_update_available)
        UpdateState.Review -> stringResource(Res.string.library_update_review)
        UpdateState.Unknown -> stringResource(Res.string.library_update_unknown)
        UpdateState.NotChecked, UpdateState.Current, null -> if (finding) stringResource(Res.string.library_finding) else null
    }
    val category = category
    val files = if (installedVpks > 1) pluralStringResource(Res.plurals.install_dialog_set_files, installedVpks, installedVpks) else null
    return ModLabels.join(label, if (category != null) ModLabels.join(ModLabels.singular(category), heroOrKind, author) else variant.orEmpty(), files)
}

@Composable
internal fun RowUpdateActions(row: LibraryRow, actions: LibraryActions, modifier: Modifier = Modifier, content: Color? = null) {
    val operation = row.operation
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(HideoutDimens.Library.NoteGap), verticalAlignment = Alignment.CenterVertically) {
        when {
            operation != null -> if (operation.canCancel) {
                val cancel = stringResource(Res.string.library_update_cancel, row.title)
                HideoutButton(
                    stringResource(Res.string.update_cancel), { actions.cancelDownload(row.modId) },
                    modifier = Modifier.semantics { contentDescription = cancel }, variant = ButtonVariant.Text, size = ButtonSize.Small, contentColor = content,
                )
            }
            row.held -> Unit
            row.damage != null && row.reinstall != null -> RowAction(
                stringResource(Res.string.library_reinstall), stringResource(Res.string.library_reinstall_named, row.title), HideoutIcons.Reset,
                { actions.reinstall(row.installId) }, enabled = row.canInstall && !row.busy, content = content,
            )
            row.updatePlan != null -> {
                val named = stringResource(Res.string.library_update_named, row.title)
                HideoutButton(
                    stringResource(Res.string.library_update_action), { actions.update(row.updatePlan) },
                    modifier = Modifier.semantics { contentDescription = named }, variant = ButtonVariant.Text, size = ButtonSize.Small,
                    enabled = row.canInstall && !row.busy, contentColor = content,
                )
            }
            row.update == UpdateState.Review && row.enabled -> {
                RowAction(
                    stringResource(Res.string.profile_install_change_version), stringResource(Res.string.library_update_change_named, row.title), HideoutIcons.SwapVersions,
                    { actions.changeFiles(row.installId) }, enabled = row.page != null && row.canInstall && !row.busy, content = content,
                )
                RowAction(
                    stringResource(Res.string.library_update_keep), stringResource(Res.string.library_update_keep_named, row.title), HideoutIcons.Check,
                    { actions.keepCurrentFiles(row.installId) }, enabled = row.canToggle, content = content,
                )
            }
        }
    }
}

@Composable
internal fun DamageChip(damage: FilesDamage) {
    val text = stringResource(if (damage == FilesDamage.Changed) Res.string.library_damage_changed else Res.string.library_damage_missing)
    StatusChip(text, ChipTone.Warning, leadingIcon = HideoutIcons.Warning, size = ChipSize.Small)
}

@Composable
internal fun HiddenModelsChip(row: LibraryRow, onClick: () -> Unit) {
    HideoutTooltip(row.hiddenBy.joinToString(", ") { it.name }) {
        StatusChip(
            pluralStringResource(Res.plurals.library_hidden_models, row.hiddenModels, row.hiddenModels), if (row.hiddenMeant) ChipTone.Neutral else ChipTone.Warning,
            size = ChipSize.Small, onClick = onClick,
        )
    }
}

@Composable
private fun RowAction(label: String, named: String, icon: ImageVector, onClick: () -> Unit, enabled: Boolean, content: Color?) {
    if (content == null) {
        HideoutButton(label, onClick, modifier = Modifier.semantics { contentDescription = named }, variant = ButtonVariant.Text, size = ButtonSize.Small, enabled = enabled)
    } else {
        HideoutTooltip(label) { HideoutIconButton(icon, named, onClick, enabled = enabled, tint = content) }
    }
}
