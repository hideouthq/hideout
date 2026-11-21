package gg.hideouthq.feature.settings.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import gg.hideouthq.core.designsystem.component.ButtonSize
import gg.hideouthq.core.designsystem.component.ButtonVariant
import gg.hideouthq.core.designsystem.component.HideoutButton
import gg.hideouthq.core.designsystem.component.ScrollColumn
import gg.hideouthq.core.designsystem.theme.HideoutDimens
import gg.hideouthq.core.designsystem.theme.HideoutShapes
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import gg.hideouthq.core.library.BackupInfo
import gg.hideouthq.core.library.BackupReason
import gg.hideouthq.core.library.RestoreConfirmation
import gg.hideouthq.core.model.CalendarDate
import gg.hideouthq.core.ui.AlertModal
import gg.hideouthq.core.ui.ModLabels
import gg.hideouthq.core.ui.WindowModal
import gg.hideouthq.feature.moddetail.catching
import gg.hideouthq.feature.settings.BackupsPane
import gg.hideouthq.feature.settings.LicenceNotice
import gg.hideouthq.feature.settings.loadLicences
import gg.hideouthq.feature.settings.loadNotice
import gg.hideouthq.resources.Res
import gg.hideouthq.resources.backups_before_autoexec
import gg.hideouthq.resources.backups_before_restore
import gg.hideouthq.resources.backups_before_updates
import gg.hideouthq.resources.backups_cancel
import gg.hideouthq.resources.backups_close
import gg.hideouthq.resources.backups_delete
import gg.hideouthq.resources.backups_delete_body
import gg.hideouthq.resources.backups_delete_title
import gg.hideouthq.resources.backups_mods
import gg.hideouthq.resources.backups_restore
import gg.hideouthq.resources.backups_restore_body
import gg.hideouthq.resources.backups_restore_settings_body
import gg.hideouthq.resources.backups_restore_first
import gg.hideouthq.resources.backups_restore_title
import gg.hideouthq.resources.backups_when
import gg.hideouthq.resources.licences_close
import gg.hideouthq.resources.licences_hide
import gg.hideouthq.resources.licences_intro
import gg.hideouthq.resources.licences_not_osi
import gg.hideouthq.resources.licences_show
import gg.hideouthq.resources.licences_title
import gg.hideouthq.resources.licences_unreadable
import gg.hideouthq.resources.restore_body
import gg.hideouthq.resources.restore_cancel
import gg.hideouthq.resources.restore_confirm
import gg.hideouthq.resources.restore_keep
import gg.hideouthq.resources.restore_title
import gg.hideouthq.resources.setting_backups_title
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import java.time.Instant
import java.time.ZoneId

@Composable
internal fun RestoreDialog(confirmation: RestoreConfirmation?, modal: WindowModal, onConfirm: () -> Unit, onCancel: () -> Unit) {
    var last by remember { mutableStateOf(confirmation) }
    if (confirmation != null) last = confirmation
    val shown = last ?: return
    val cancel = remember { FocusRequester() }
    val title = stringResource(Res.string.restore_title)
    AlertModal(visible = confirmation != null, modal = modal, title = title, onDismiss = onCancel, initialFocus = cancel) {
        val scheme = MaterialTheme.colorScheme
        val type = HideoutTheme.typography
        Column(verticalArrangement = Arrangement.spacedBy(HideoutDimens.AlertDialog.Gap)) {
            Text(title, style = type.titleDialog, color = scheme.onSurface)
            Confirmation(
                listOf(pluralStringResource(Res.plurals.restore_body, shown.enabledMods, shown.enabledMods), stringResource(Res.string.restore_keep)),
                stringResource(Res.string.restore_cancel), onCancel, cancel,
                stringResource(Res.string.restore_confirm), onConfirm, enabled = confirmation != null,
            )
        }
    }
}

@Composable
internal fun LicencesDialog(visible: Boolean, modal: WindowModal, onClose: () -> Unit) {
    val close = remember { FocusRequester() }
    val title = stringResource(Res.string.licences_title)
    val notices by produceState<List<LicenceNotice>?>(null) { value = catching({ emptyList() }) { loadLicences() } }
    AlertModal(visible = visible, modal = modal, title = title, onDismiss = onClose, initialFocus = close, width = HideoutDimens.Settings.LicencesWidth) {
        val scheme = MaterialTheme.colorScheme
        val type = HideoutTheme.typography
        Column(verticalArrangement = Arrangement.spacedBy(HideoutDimens.AlertDialog.Gap)) {
            Text(title, style = type.titleDialog, color = scheme.onSurface)
            Text(stringResource(Res.string.licences_intro), style = type.bodyDialog, color = scheme.onSurfaceVariant)
            ScrollColumn(Modifier.weight(1f, fill = false).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(HideoutDimens.Settings.LicencesGap)) {
                if (notices?.isEmpty() == true) Text(stringResource(Res.string.licences_unreadable), style = type.bodyDialog, color = scheme.error)
                notices?.forEach { LicenceItem(it) }
            }
            DialogFooter {
                HideoutButton(stringResource(Res.string.licences_close), onClose, modifier = Modifier.focusRequester(close), variant = ButtonVariant.Tonal)
            }
        }
    }
}

@Composable
private fun LicenceItem(notice: LicenceNotice) {
    val scheme = MaterialTheme.colorScheme
    val type = HideoutTheme.typography
    var open by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(HideoutDimens.Settings.TitleGap)) {
        Row(horizontalArrangement = Arrangement.spacedBy(HideoutDimens.Settings.CardGap), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(notice.component, style = type.titleSetting, color = scheme.onSurface)
                val licence = if (notice.osiApproved) notice.licence else "${notice.licence} · ${stringResource(Res.string.licences_not_osi)}"
                Text(licence, style = type.notice, color = scheme.outline)
            }
            HideoutButton(
                text = stringResource(if (open) Res.string.licences_hide else Res.string.licences_show),
                onClick = { open = !open },
                variant = ButtonVariant.Text,
                size = ButtonSize.Small,
            )
        }
        if (open) notice.files.forEach { file -> NoticeText(file) }
    }
}

@Composable
private fun NoticeText(file: String) {
    val text by produceState<String?>(null, file) { value = catching({ null }) { loadNotice(file) } }
    Text(
        text = text.orEmpty(),
        style = HideoutTheme.typography.mono,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, HideoutShapes.Row)
            .padding(HideoutDimens.Settings.LicenceTextPadding),
    )
}

@Composable
internal fun BackupsDialog(pane: BackupsPane?, backups: List<BackupInfo>, enabled: Boolean, modal: WindowModal, onShow: (BackupsPane?) -> Unit, onConfirm: () -> Unit) {
    var last by remember { mutableStateOf(pane) }
    if (pane != null) last = pane
    val shown = last ?: return
    val first = remember { FocusRequester() }
    val title = when (shown) {
        BackupsPane.List -> stringResource(Res.string.setting_backups_title)
        is BackupsPane.ConfirmRestore -> stringResource(Res.string.backups_restore_title, backupTime(shown.backup.createdAt))
        is BackupsPane.ConfirmDelete -> stringResource(Res.string.backups_delete_title, backupTime(shown.backup.createdAt))
    }
    val back = { onShow(BackupsPane.List.takeIf { shown != BackupsPane.List }) }
    AlertModal(visible = pane != null, modal = modal, title = title, onDismiss = back, initialFocus = first) {
        val scheme = MaterialTheme.colorScheme
        val type = HideoutTheme.typography
        LaunchedEffect(shown) { first.requestFocus() }
        Column(verticalArrangement = Arrangement.spacedBy(HideoutDimens.AlertDialog.Gap)) {
            Text(title, style = type.titleDialog, color = scheme.onSurface)
            if (shown == BackupsPane.List) {
                ScrollColumn(Modifier.weight(1f, fill = false).height(IntrinsicSize.Max).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(HideoutDimens.AlertDialog.BodyGap)) {
                    backups.forEach { backup -> BackupItem(backup, enabled, onRestore = { onShow(BackupsPane.ConfirmRestore(backup)) }, onDelete = { onShow(BackupsPane.ConfirmDelete(backup)) }) }
                }
                DialogFooter {
                    HideoutButton(stringResource(Res.string.backups_close), { onShow(null) }, modifier = Modifier.focusRequester(first), variant = ButtonVariant.Tonal)
                }
            } else {
                val restoring = shown is BackupsPane.ConfirmRestore
                Confirmation(
                    when (shown) {
                        is BackupsPane.ConfirmRestore -> listOf(if (shown.backup.reason.holdsMods) Res.string.backups_restore_body else Res.string.backups_restore_settings_body, Res.string.backups_restore_first)
                        else -> listOf(Res.string.backups_delete_body)
                    }.map { stringResource(it) },
                    stringResource(Res.string.backups_cancel), back, first,
                    stringResource(if (restoring) Res.string.backups_restore else Res.string.backups_delete), onConfirm,
                    enabled = pane != null && enabled,
                    variant = if (restoring) ButtonVariant.Primary else ButtonVariant.Danger,
                )
            }
        }
    }
}

@Composable
private fun BackupItem(backup: BackupInfo, enabled: Boolean, onRestore: () -> Unit, onDelete: () -> Unit) {
    val mods = pluralStringResource(Res.plurals.backups_mods, backup.mods, backup.mods)
    val summary = when (backup.reason) {
        BackupReason.Manual -> mods
        BackupReason.BeforeRestore -> stringResource(Res.string.backups_before_restore, mods)
        BackupReason.BeforeUpdates -> stringResource(Res.string.backups_before_updates, mods)
        BackupReason.BeforeGameSettings -> stringResource(Res.string.backups_before_autoexec)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(HideoutDimens.Settings.CardGap), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(backupTime(backup.createdAt), style = HideoutTheme.typography.titleSetting, color = MaterialTheme.colorScheme.onSurface)
            Text(summary, style = HideoutTheme.typography.notice, color = MaterialTheme.colorScheme.outline)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(HideoutDimens.Settings.ButtonGap)) {
            HideoutButton(stringResource(Res.string.backups_restore), onRestore, enabled = enabled, variant = ButtonVariant.Text, size = ButtonSize.Small)
            HideoutButton(stringResource(Res.string.backups_delete), onDelete, enabled = enabled, variant = ButtonVariant.Text, size = ButtonSize.Small)
        }
    }
}

@Composable
internal fun backupTime(createdAt: Long): String {
    val at = Instant.ofEpochMilli(createdAt).atZone(ZoneId.systemDefault())
    return stringResource(Res.string.backups_when, ModLabels.date(CalendarDate(at.year, at.monthValue, at.dayOfMonth)), ModLabels.clock(at.hour, at.minute))
}

@Composable
private fun Confirmation(
    body: List<String>,
    cancel: String,
    onCancel: () -> Unit,
    cancelFocus: FocusRequester,
    confirm: String,
    onConfirm: () -> Unit,
    enabled: Boolean,
    variant: ButtonVariant = ButtonVariant.Primary,
) {
    Column(verticalArrangement = Arrangement.spacedBy(HideoutDimens.AlertDialog.BodyGap)) {
        body.forEach { Text(it, style = HideoutTheme.typography.bodyDialog, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
    DialogFooter {
        HideoutButton(
            cancel,
            onCancel,
            modifier = Modifier.focusRequester(cancelFocus),
            variant = ButtonVariant.Text,
            contentPadding = PaddingValues(horizontal = HideoutDimens.AlertDialog.CancelPadding),
        )
        HideoutButton(confirm, onConfirm, enabled = enabled, variant = variant)
    }
}

@Composable
private fun DialogFooter(content: @Composable () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = HideoutDimens.AlertDialog.FooterTopPadding),
        horizontalArrangement = Arrangement.spacedBy(HideoutDimens.AlertDialog.FooterGap, Alignment.End),
    ) {
        content()
    }
}
