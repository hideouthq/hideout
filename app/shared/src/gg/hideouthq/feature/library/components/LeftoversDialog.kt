package gg.hideouthq.feature.library.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextOverflow
import gg.hideouthq.core.designsystem.component.ButtonSize
import gg.hideouthq.core.designsystem.component.ButtonVariant
import gg.hideouthq.core.designsystem.component.HideoutButton
import gg.hideouthq.core.designsystem.component.HideoutIcon
import gg.hideouthq.core.designsystem.icon.HideoutIcons
import gg.hideouthq.core.designsystem.theme.HideoutDimens
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import gg.hideouthq.core.library.Leftover
import gg.hideouthq.core.library.inCitadel
import gg.hideouthq.core.ui.AlertModal
import gg.hideouthq.core.ui.LibraryLabels
import gg.hideouthq.core.ui.ModLabels
import gg.hideouthq.core.ui.WindowModal
import gg.hideouthq.core.ui.rememberTargetSettled
import gg.hideouthq.feature.library.LeftoverAction
import gg.hideouthq.feature.library.LeftoverResult
import gg.hideouthq.feature.library.LeftoversUi
import gg.hideouthq.feature.library.LibraryActions
import gg.hideouthq.resources.Res
import gg.hideouthq.resources.details_open_folder
import gg.hideouthq.resources.leftovers_add
import gg.hideouthq.resources.leftovers_add_all
import gg.hideouthq.resources.leftovers_add_one
import gg.hideouthq.resources.leftovers_add_some
import gg.hideouthq.resources.leftovers_added
import gg.hideouthq.resources.leftovers_adding
import gg.hideouthq.resources.leftovers_body
import gg.hideouthq.resources.leftovers_body_copies
import gg.hideouthq.resources.leftovers_body_done
import gg.hideouthq.resources.leftovers_close
import gg.hideouthq.resources.leftovers_copy_of
import gg.hideouthq.resources.leftovers_files
import gg.hideouthq.resources.leftovers_move
import gg.hideouthq.resources.leftovers_move_all
import gg.hideouthq.resources.leftovers_moved
import gg.hideouthq.resources.leftovers_moving
import gg.hideouthq.resources.leftovers_needs_closed
import gg.hideouthq.resources.leftovers_title
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun LeftoversDialog(state: LeftoversUi, modal: WindowModal, actions: LibraryActions) {
    val close = remember { FocusRequester() }
    val title = stringResource(Res.string.leftovers_title)
    AlertModal(visible = state.open, modal = modal, title = title, onDismiss = actions::closeLeftovers, initialFocus = close) {
        val scheme = MaterialTheme.colorScheme
        val sets = state.sets
        val several = sets.size > 1
        val free = state.canChange && state.working == null
        val settled by rememberTargetSettled(sets.map { it.slot })
        val act: (() -> Unit) -> Unit = { action -> if (settled) action() }
        Column(verticalArrangement = Arrangement.spacedBy(HideoutDimens.AlertDialog.Gap)) {
            Text(title, style = HideoutTheme.typography.titleDialog, color = scheme.onSurface)
            val body = when {
                sets.isEmpty() -> Res.string.leftovers_body_done
                sets.all { it.slot in state.copies } -> Res.string.leftovers_body_copies
                else -> Res.string.leftovers_body
            }
            Text(stringResource(body), style = HideoutTheme.typography.bodyDialog, color = scheme.onSurfaceVariant)
            if (sets.isNotEmpty()) {
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(HideoutDimens.InstallDialog.RowGap)) {
                    sets.forEach { set -> key(set.slot) { LeftoverRow(set, state.copies[set.slot], actions.takeIf { several }, free, act) } }
                }
            }
            Status(state, actions::openMovedFolder)
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = HideoutDimens.AlertDialog.FooterTopPadding),
                horizontalArrangement = Arrangement.spacedBy(HideoutDimens.AlertDialog.FooterGap, Alignment.End),
            ) {
                HideoutButton(stringResource(Res.string.leftovers_close), actions::closeLeftovers, Modifier.focusRequester(close), variant = ButtonVariant.Text)
                if (sets.isNotEmpty()) {
                    HideoutButton(stringResource(if (several) Res.string.leftovers_move_all else Res.string.leftovers_move), { act { actions.moveLeftoversOut(sets.map { it.slot }) } }, variant = ButtonVariant.Tonal, enabled = free)
                }
                val addable = sets.filter { it.slot !in state.copies }.map { it.slot }
                if (addable.isNotEmpty()) {
                    val label = when {
                        !several -> stringResource(Res.string.leftovers_add)
                        addable.size == sets.size -> stringResource(Res.string.leftovers_add_all)
                        else -> stringResource(Res.string.leftovers_add_some, addable.size)
                    }
                    HideoutButton(label, { act { actions.addLeftovers(addable) } }, enabled = free)
                }
            }
        }
    }
}

@Composable
private fun LeftoverRow(set: Leftover, copyOf: String?, actions: LibraryActions?, enabled: Boolean, act: (() -> Unit) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val dimens = HideoutDimens.InstallDialog
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = dimens.RowMinHeight)
            .background(scheme.surfaceContainerHigh, RoundedCornerShape(dimens.RowRadius))
            .padding(start = dimens.RowStartPadding, end = dimens.RowEndPadding, top = dimens.RowVerticalPadding, bottom = dimens.RowVerticalPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(dimens.RowLineGap)) {
            Text(set.name, style = HideoutTheme.typography.titleSetting, color = scheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val files = set.files.size.takeIf { it > 1 }?.let { pluralStringResource(Res.plurals.leftovers_files, it, it) }
            Text(ModLabels.join(set.folder.inCitadel, files, ModLabels.size(set.sizeBytes)), style = HideoutTheme.typography.mono, color = scheme.outline, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (copyOf != null) Text(stringResource(Res.string.leftovers_copy_of, copyOf), style = HideoutTheme.typography.notice, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (actions != null) {
            HideoutButton(stringResource(Res.string.leftovers_move), { act { actions.moveLeftoversOut(listOf(set.slot)) } }, variant = ButtonVariant.Text, size = ButtonSize.Small, enabled = enabled)
            if (copyOf == null) HideoutButton(stringResource(Res.string.leftovers_add_one), { act { actions.addLeftovers(listOf(set.slot)) } }, variant = ButtonVariant.Text, size = ButtonSize.Small, enabled = enabled)
        }
    }
}

@Composable
private fun Status(state: LeftoversUi, onOpenFolder: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val style = HideoutTheme.typography.notice
    val last = state.last
    when {
        state.working != null -> Text(stringResource(if (state.working == LeftoverAction.Adding) Res.string.leftovers_adding else Res.string.leftovers_moving), style = style, color = scheme.onSurfaceVariant)
        last == LeftoverResult.Added -> Text(stringResource(Res.string.leftovers_added), style = style, color = scheme.onSurfaceVariant)
        last is LeftoverResult.Moved -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(HideoutDimens.Library.NoteGap)) {
            Text(stringResource(Res.string.leftovers_moved, last.folder), Modifier.weight(1f, fill = false), style = style, color = scheme.onSurfaceVariant)
            HideoutButton(stringResource(Res.string.details_open_folder), onOpenFolder, variant = ButtonVariant.Text, size = ButtonSize.Small)
        }
        last is LeftoverResult.Failed -> Warning(LibraryLabels.problem(last.problem))
    }
    if (!state.canChange && state.sets.isNotEmpty()) Warning(stringResource(Res.string.leftovers_needs_closed))
}

@Composable
private fun Warning(text: String) {
    val scheme = MaterialTheme.colorScheme
    Row(horizontalArrangement = Arrangement.spacedBy(HideoutDimens.Library.NoteGap), verticalAlignment = Alignment.CenterVertically) {
        HideoutIcon(HideoutIcons.Warning, contentDescription = null, tint = scheme.tertiary)
        Text(text, style = HideoutTheme.typography.notice, color = scheme.onSurfaceVariant)
    }
}
