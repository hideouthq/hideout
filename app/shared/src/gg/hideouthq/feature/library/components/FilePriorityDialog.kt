package gg.hideouthq.feature.library.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import gg.hideouthq.core.designsystem.component.ButtonSize
import gg.hideouthq.core.designsystem.component.ButtonVariant
import gg.hideouthq.core.designsystem.component.HideoutButton
import gg.hideouthq.core.designsystem.component.HideoutIcon
import gg.hideouthq.core.designsystem.icon.HideoutIcons
import gg.hideouthq.core.designsystem.theme.HideoutDimens
import gg.hideouthq.core.designsystem.theme.HideoutShapes
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import gg.hideouthq.core.designsystem.theme.HideoutWeights
import gg.hideouthq.core.library.FileIndexState
import gg.hideouthq.core.library.FilePriorities
import gg.hideouthq.core.library.FilePriorityPeer
import gg.hideouthq.core.library.FilePriorityRelation
import gg.hideouthq.core.library.FilePriorityReport
import gg.hideouthq.core.library.ModLibrary
import gg.hideouthq.core.ui.AlertModal
import gg.hideouthq.core.ui.WindowModal
import gg.hideouthq.feature.library.HiddenBy
import gg.hideouthq.resources.Res
import gg.hideouthq.resources.details_open_folder
import gg.hideouthq.resources.file_priority_close
import gg.hideouthq.resources.file_priority_currently_applied
import gg.hideouthq.resources.file_priority_foreign
import gg.hideouthq.resources.file_priority_hide_paths
import gg.hideouthq.resources.file_priority_kept_both
import gg.hideouthq.resources.file_priority_loading
import gg.hideouthq.resources.file_priority_more_paths
import gg.hideouthq.resources.file_priority_move_above
import gg.hideouthq.resources.file_priority_none
import gg.hideouthq.resources.file_priority_not_enabled
import gg.hideouthq.resources.file_priority_own_parts
import gg.hideouthq.resources.file_priority_path_count
import gg.hideouthq.resources.file_priority_relation_first
import gg.hideouthq.resources.file_priority_relation_not_together
import gg.hideouthq.resources.file_priority_relation_second
import gg.hideouthq.resources.file_priority_show_paths
import gg.hideouthq.resources.file_priority_title
import gg.hideouthq.resources.file_priority_unchecked_reading
import gg.hideouthq.resources.file_priority_unchecked_unavailable
import gg.hideouthq.resources.parts_order_action
import gg.hideouthq.resources.profile_undo
import gg.hideouthq.resources.skin_swap_keep_both
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun FilePriorityDialog(
    filePriorities: FilePriorities,
    library: ModLibrary,
    installId: String?,
    title: String,
    hidden: List<HiddenBy>,
    modal: WindowModal,
    onReorderParts: () -> Unit,
    onOpenFolder: () -> Unit,
    onDismiss: () -> Unit,
) {
    var lastId by remember { mutableStateOf(installId) }
    var lastTitle by remember { mutableStateOf(title) }
    var lastHidden by remember { mutableStateOf(hidden) }
    if (installId != null) {
        lastId = installId
        lastTitle = title
        lastHidden = hidden
    }
    val shownId = lastId
    val libraryState by library.state.collectAsStateWithLifecycle()
    val report by key(shownId) {
        produceState<FilePriorityReport?>(null, installId, filePriorities) {
            if (installId != null) filePriorities.inspect(installId).collect { value = it }
        }
    }
    val close = remember { FocusRequester() }
    val dialogTitle = stringResource(Res.string.file_priority_title, lastTitle)
    AlertModal(visible = installId != null, modal = modal, title = dialogTitle, onDismiss = onDismiss, initialFocus = close) {
        val scheme = MaterialTheme.colorScheme
        val type = HideoutTheme.typography
        Column(verticalArrangement = Arrangement.spacedBy(HideoutDimens.AlertDialog.Gap)) {
            Text(dialogTitle, style = type.titleDialog, color = scheme.onSurface)
            key(shownId) {
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(HideoutDimens.AlertDialog.Gap)) {
                    val current = report
                    when {
                        current == null -> Text(stringResource(Res.string.file_priority_loading), style = type.notice, color = scheme.onSurfaceVariant)
                        !current.chosenEnabled && !current.appliedEnabled ->
                            Text(stringResource(Res.string.file_priority_not_enabled), style = type.notice, color = scheme.onSurfaceVariant)
                        current.peers.isEmpty() && current.unchecked.isEmpty() && lastHidden.isEmpty() ->
                            Text(stringResource(Res.string.file_priority_none), style = type.notice, color = scheme.onSurfaceVariant)
                    }
                    current?.peers.orEmpty().forEach { peer ->
                        key(peer.installId) {
                            PeerRow(
                                peer, lastTitle, canEditChoices = libraryState.canEditChoices, returnFocus = close,
                                onMove = {
                                    shownId?.let { id -> filePriorities.moveAbove(id, peer.installId) }
                                    close.requestFocus()
                                },
                                onKeep = { keep ->
                                    shownId?.let { id -> library.keepBeside(id, peer.installId, keep) }
                                    close.requestFocus()
                                },
                            )
                        }
                    }
                    val parts = lastHidden.filterIsInstance<HiddenBy.Part>()
                    if (parts.isNotEmpty()) {
                        WinnerRow(parts.joinToString(", ") { it.name }, stringResource(Res.string.file_priority_own_parts), stringResource(Res.string.parts_order_action), onReorderParts)
                    }
                    lastHidden.filterIsInstance<HiddenBy.Foreign>().forEach { file ->
                        key(file.name) { WinnerRow(file.name, stringResource(Res.string.file_priority_foreign), stringResource(Res.string.details_open_folder), onOpenFolder) }
                    }
                    current?.unchecked.orEmpty().forEach { issue ->
                        Text(
                            stringResource(if (issue.state == FileIndexState.Reading) Res.string.file_priority_unchecked_reading else Res.string.file_priority_unchecked_unavailable, issue.title),
                            style = type.captionSmall,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = HideoutDimens.AlertDialog.FooterTopPadding),
                horizontalArrangement = Arrangement.spacedBy(HideoutDimens.AlertDialog.FooterGap, Alignment.End),
            ) {
                HideoutButton(stringResource(Res.string.file_priority_close), onDismiss, modifier = Modifier.focusRequester(close), variant = ButtonVariant.Text)
            }
        }
    }
}

@Composable
private fun PeerRow(peer: FilePriorityPeer, selectedTitle: String, canEditChoices: Boolean, returnFocus: FocusRequester, onMove: () -> Unit, onKeep: (Boolean) -> Unit) {
    var shownPaths by remember { mutableIntStateOf(0) }
    val scheme = MaterialTheme.colorScheme
    val type = HideoutTheme.typography
    Column(
        modifier = Modifier.fillMaxWidth().background(scheme.surfaceContainer, HideoutShapes.Row).padding(HideoutDimens.Library.FileRowHorizontalPadding),
        verticalArrangement = Arrangement.spacedBy(HideoutDimens.Library.FilesGap / 2),
    ) {
        Text(peer.title, style = type.caption.copy(fontWeight = HideoutWeights.Label), color = scheme.onSurface)
        Text(pluralStringResource(Res.plurals.file_priority_path_count, peer.paths.size, peer.paths.size), style = type.captionSmall, color = scheme.outline)
        Text(relationLabel(peer.chosen, selectedTitle), style = type.notice, color = scheme.onSurfaceVariant)
        if (peer.applied != peer.chosen) {
            Text(stringResource(Res.string.file_priority_currently_applied, relationLabel(peer.applied, selectedTitle)), style = type.captionSmall, color = scheme.onSurfaceVariant)
        }
        HideoutButton(
            stringResource(if (shownPaths > 0) Res.string.file_priority_hide_paths else Res.string.file_priority_show_paths, peer.paths.size),
            { shownPaths = if (shownPaths > 0) 0 else PathsPerPage },
            variant = ButtonVariant.Text,
            size = ButtonSize.Small,
        )
        peer.paths.take(shownPaths).forEach { path -> Text(path, style = type.mono, color = scheme.onSurfaceVariant) }
        if (shownPaths > 0 && shownPaths < peer.paths.size) {
            HideoutButton(stringResource(Res.string.file_priority_more_paths), {
                shownPaths += PathsPerPage
                if (shownPaths >= peer.paths.size) returnFocus.requestFocus()
            }, variant = ButtonVariant.Text, size = ButtonSize.Small)
        }
        if (peer.chosen != FilePriorityRelation.NotTogether) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(HideoutDimens.AlertDialog.FooterGap),
                verticalArrangement = Arrangement.spacedBy(HideoutDimens.AlertDialog.FooterGap),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                if (peer.chosen == FilePriorityRelation.OtherFirst && canEditChoices) {
                    HideoutButton(stringResource(Res.string.file_priority_move_above, peer.title), onMove, variant = ButtonVariant.Tonal, size = ButtonSize.Small)
                }
                if (peer.kept) {
                    Row(horizontalArrangement = Arrangement.spacedBy(HideoutDimens.Library.NoteGap), verticalAlignment = Alignment.CenterVertically) {
                        HideoutIcon(HideoutIcons.Check, contentDescription = null, size = HideoutDimens.Icon.Small, tint = scheme.outline)
                        Text(stringResource(Res.string.file_priority_kept_both), style = type.captionSmall, color = scheme.onSurfaceVariant)
                        HideoutButton(stringResource(Res.string.profile_undo), { onKeep(false) }, variant = ButtonVariant.Text, size = ButtonSize.Small)
                    }
                } else {
                    HideoutButton(stringResource(Res.string.skin_swap_keep_both), { onKeep(true) }, variant = ButtonVariant.Tonal, size = ButtonSize.Small)
                }
            }
        }
    }
}

@Composable
private fun WinnerRow(title: String, note: String, action: String, onAction: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val type = HideoutTheme.typography
    Column(
        modifier = Modifier.fillMaxWidth().background(scheme.surfaceContainer, HideoutShapes.Row).padding(HideoutDimens.Library.FileRowHorizontalPadding),
        verticalArrangement = Arrangement.spacedBy(HideoutDimens.Library.FilesGap / 2),
    ) {
        Text(title, style = type.caption.copy(fontWeight = HideoutWeights.Label), color = scheme.onSurface)
        Text(note, style = type.notice, color = scheme.onSurfaceVariant)
        HideoutButton(action, onAction, variant = ButtonVariant.Tonal, size = ButtonSize.Small)
    }
}

@Composable
private fun relationLabel(relation: FilePriorityRelation, selectedTitle: String): String = when (relation) {
    FilePriorityRelation.SelectedFirst -> stringResource(Res.string.file_priority_relation_first, selectedTitle)
    FilePriorityRelation.OtherFirst -> stringResource(Res.string.file_priority_relation_second, selectedTitle)
    FilePriorityRelation.NotTogether -> stringResource(Res.string.file_priority_relation_not_together)
}

private const val PathsPerPage = 50
