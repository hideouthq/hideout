package gg.hideouthq.feature.install.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import gg.hideouthq.core.designsystem.component.ButtonSize
import gg.hideouthq.core.designsystem.component.ButtonVariant
import gg.hideouthq.core.designsystem.component.CoverArt
import gg.hideouthq.core.designsystem.component.HideoutButton
import gg.hideouthq.core.designsystem.component.HideoutCheckbox
import gg.hideouthq.core.designsystem.component.HideoutIcon
import gg.hideouthq.core.designsystem.component.HideoutIconButton
import gg.hideouthq.core.designsystem.component.HideoutTooltip
import gg.hideouthq.core.designsystem.icon.HideoutIcons
import gg.hideouthq.core.designsystem.interaction.collectInteractionStates
import gg.hideouthq.core.designsystem.interaction.focusRing
import gg.hideouthq.core.designsystem.interaction.hideoutToggleable
import gg.hideouthq.core.designsystem.theme.HideoutDimens
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import gg.hideouthq.core.library.ArchiveNote
import gg.hideouthq.core.ui.LibraryLabels
import gg.hideouthq.core.ui.ModLabels
import gg.hideouthq.core.ui.ProfileChips
import gg.hideouthq.core.ui.SizeAmount
import gg.hideouthq.core.ui.SizeUnit
import gg.hideouthq.feature.install.DialogHeader
import gg.hideouthq.feature.install.InstallChoiceViewModel
import gg.hideouthq.feature.install.InstallDialogState
import gg.hideouthq.feature.install.Question
import gg.hideouthq.feature.install.VersionPick
import gg.hideouthq.feature.install.VersionRow
import gg.hideouthq.resources.Res
import gg.hideouthq.resources.install
import gg.hideouthq.resources.install_dialog_add_on_above
import gg.hideouthq.resources.install_dialog_add_on_below
import gg.hideouthq.resources.install_dialog_apply
import gg.hideouthq.resources.install_dialog_cancel
import gg.hideouthq.resources.install_dialog_change_files
import gg.hideouthq.resources.install_dialog_close
import gg.hideouthq.resources.install_dialog_delisted
import gg.hideouthq.resources.install_dialog_deselect_all
import gg.hideouthq.resources.install_dialog_install_files
import gg.hideouthq.resources.install_dialog_install_parts
import gg.hideouthq.resources.install_dialog_installed
import gg.hideouthq.resources.install_dialog_left_out
import gg.hideouthq.resources.install_dialog_most_downloaded
import gg.hideouthq.resources.install_dialog_needs_fonts
import gg.hideouthq.resources.install_dialog_notes
import gg.hideouthq.resources.install_dialog_part_down
import gg.hideouthq.resources.install_dialog_part_up
import gg.hideouthq.resources.install_dialog_pick_one
import gg.hideouthq.resources.install_dialog_pick_one_shared
import gg.hideouthq.resources.install_dialog_preview
import gg.hideouthq.resources.install_dialog_preview_tooltip
import gg.hideouthq.resources.install_dialog_select_all
import gg.hideouthq.resources.install_dialog_set_versions
import gg.hideouthq.resources.install_dialog_sets
import gg.hideouthq.resources.install_dialog_sets_label
import gg.hideouthq.resources.install_dialog_versions
import gg.hideouthq.resources.install_dialog_versions_label
import gg.hideouthq.resources.profile_install_turn_on
import gg.hideouthq.resources.size_gb
import gg.hideouthq.resources.size_kb
import gg.hideouthq.resources.size_mb
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun InstallDialogContent(
    state: InstallDialogState,
    viewModel: InstallChoiceViewModel,
    ready: Boolean,
    rowsScroll: ScrollState,
    modifier: Modifier = Modifier,
) {
    val dimens = HideoutDimens.InstallDialog
    val id = state.id
    Column(modifier.padding(dimens.Padding), verticalArrangement = Arrangement.spacedBy(dimens.SectionGap)) {
        Header(state.header) { viewModel.cancel(id) }
        Text(
            text = when {
                state.versions -> stringResource(Res.string.install_dialog_set_versions, state.download.orEmpty(), state.rows.size)
                state.question == Question.Sets -> pluralStringResource(Res.plurals.install_dialog_sets, state.rows.size, state.download.orEmpty(), state.rows.size)
                state.replacing -> stringResource(Res.string.install_dialog_change_files)
                else -> stringResource(Res.string.install_dialog_versions)
            },
            style = HideoutTheme.typography.bodyDialog,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val label = stringResource(if (state.question == Question.Files) Res.string.install_dialog_versions_label else Res.string.install_dialog_sets_label)
        if (state.question == Question.Sets && state.rows.size > 1 && state.rows.none { it.group != null }) {
            HideoutButton(
                text = stringResource(if (state.selected.size == state.rows.size) Res.string.install_dialog_deselect_all else Res.string.install_dialog_select_all),
                onClick = { viewModel.selectAll(id) },
                variant = ButtonVariant.Text,
                size = ButtonSize.Small,
            )
        }
        Column(
            modifier = Modifier
                .weight(1f, fill = false)
                .verticalScroll(rowsScroll)
                .semantics { contentDescription = label },
            verticalArrangement = Arrangement.spacedBy(dimens.RowGap),
        ) {
            val previews = state.rows.any { it.preview != null }
            var revealed by remember(id) { mutableStateOf(false) }
            val reveal = state.rows.firstOrNull { it.pick in state.selected }?.pick?.takeIf { !revealed }
            @Composable
            fun item(index: Int, row: VersionRow) = key(row.pick) {
                VersionRowItem(
                    row = row,
                    selected = row.pick in state.selected,
                    reveal = row.pick == reveal,
                    onRevealed = { revealed = true },
                    showInstalled = state.replacing,
                    onToggle = { viewModel.toggle(id, row.pick) },
                    onConfirm = { if (ready) viewModel.confirm(id) },
                    onPreview = (row.pick as? VersionPick.File)?.takeIf { row.preview != null }?.let { file -> { viewModel.preview(id, file.fileId) } },
                    alignPreview = previews,
                    onMoveUp = if (row.pick in state.movable && index > 0) ({ viewModel.movePart(id, row.pick, -1) }) else null,
                    onMoveDown = if (row.pick in state.movable && index < state.rows.lastIndex) ({ viewModel.movePart(id, row.pick, 1) }) else null,
                )
            }
            groupRuns(state.rows).forEach { run ->
                val group = run.first().value.group
                if (group == null || state.versions) {
                    run.forEach { (index, row) -> item(index, row) }
                } else {
                    key(group, run.first().index) { PickOneGroup(state.shared[group]) { run.forEach { (index, row) -> item(index, row) } } }
                }
            }
        }
        if (state.versions) state.shared.values.firstOrNull()?.let { shared ->
            Text(pluralStringResource(Res.plurals.install_dialog_pick_one_shared, shared, shared), style = HideoutTheme.typography.notice, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (state.notes.isNotEmpty()) Notes(state.notes)
        if (state.leftOut.isNotEmpty()) {
            Note {
                Text(stringResource(Res.string.install_dialog_left_out), style = HideoutTheme.typography.notice, color = MaterialTheme.colorScheme.onSurfaceVariant)
                state.leftOut.forEach {
                    Text(it.key, style = HideoutTheme.typography.mono, color = MaterialTheme.colorScheme.outline)
                    Text(LibraryLabels.rejected(it), style = HideoutTheme.typography.notice, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (!state.replacing && state.profiles.size > 1) {
            Column(verticalArrangement = Arrangement.spacedBy(dimens.ProfilesGap)) {
                Text(stringResource(Res.string.profile_install_turn_on), style = HideoutTheme.typography.labelSection, color = MaterialTheme.colorScheme.outline)
                ProfileChips(state.profiles, state.selectedProfiles, { profile, selected -> viewModel.setProfile(id, profile, selected) }, enabled = state.canInstall && !state.closing)
            }
        }
        Footer(state, ready, onCancel = { viewModel.cancel(id) }, onConfirm = { viewModel.confirm(id) })
    }
}

@Composable
private fun Header(header: DialogHeader, onClose: () -> Unit) {
    val dimens = HideoutDimens.InstallDialog
    Row(horizontalArrangement = Arrangement.spacedBy(dimens.HeaderGap), verticalAlignment = Alignment.CenterVertically) {
        if (header.coverUrl != null) {
            CoverArt(
                seed = header.title,
                modifier = Modifier.size(dimens.Thumb),
                shape = RoundedCornerShape(dimens.ThumbRadius),
                imageUrl = header.coverUrl,
                tintIndex = ModLabels.tint(header.coverTone),
            )
        }
        Text(
            text = header.title,
            style = HideoutTheme.typography.titleDialog,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        HideoutIconButton(
            icon = HideoutIcons.Close,
            contentDescription = stringResource(Res.string.install_dialog_close),
            onClick = onClose,
            modifier = Modifier.align(Alignment.Top).offset(x = dimens.CloseOutset, y = -dimens.CloseOutset),
        )
    }
}

@Composable
private fun VersionRowItem(
    row: VersionRow,
    selected: Boolean,
    reveal: Boolean,
    onRevealed: () -> Unit,
    showInstalled: Boolean,
    onToggle: () -> Unit,
    onConfirm: () -> Unit,
    onPreview: (() -> Unit)?,
    alignPreview: Boolean,
    onMoveUp: (() -> Unit)?,
    onMoveDown: (() -> Unit)?,
) {
    val dimens = HideoutDimens.InstallDialog
    val source = remember { MutableInteractionSource() }
    val states = source.collectInteractionStates()
    val shape = RoundedCornerShape(dimens.RowRadius)
    val scheme = MaterialTheme.colorScheme
    val fill by animateColorAsState(
        targetValue = when {
            selected -> HideoutTheme.colors.selectedVersion
            states.hovered -> scheme.surfaceContainerHigh
            else -> scheme.surfaceContainerHigh.copy(alpha = 0f)
        },
        animationSpec = HideoutTheme.motion.effectsFast(),
        label = "versionRowFill",
    )
    val revealer = remember { BringIntoViewRequester() }
    LaunchedEffect(reveal) {
        if (reveal) {
            revealer.bringIntoView()
            onRevealed()
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = dimens.RowMinHeight)
            .bringIntoViewRequester(revealer)
            .clip(shape)
            .background(fill)
            .focusRing(states.focusVisible, shape)
            .hideoutToggleable(selected, source, role = Role.Checkbox, onValueChange = { onToggle() })
            // After the row's own focus target, so it hears Enter only when the row itself is focused: a focused
            // Preview button inside the row takes Enter first.
            .onKeyEvent { event ->
                val enter = event.key == Key.Enter || event.key == Key.NumPadEnter
                if (enter && event.type == KeyEventType.KeyDown) onConfirm()
                enter
            }
            .padding(PaddingValues(start = dimens.RowStartPadding, top = dimens.RowVerticalPadding, end = dimens.RowEndPadding, bottom = dimens.RowVerticalPadding)),
        horizontalArrangement = Arrangement.spacedBy(dimens.RowContentGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HideoutCheckbox(selected)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(dimens.RowLineGap)) {
            Text(
                text = row.name,
                style = HideoutTheme.typography.titleSetting,
                color = scheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val state = ModLabels.join(
                if (row.mostDownloaded) stringResource(Res.string.install_dialog_most_downloaded) else null,
                if (showInstalled && row.installed) stringResource(Res.string.install_dialog_installed) else null,
                if (!row.listed) stringResource(Res.string.install_dialog_delisted) else null,
            )
            if (state.isNotEmpty()) Text(state, style = HideoutTheme.typography.caption, color = scheme.outline, maxLines = 1, overflow = TextOverflow.Ellipsis)
            row.path?.let { Text(it, style = HideoutTheme.typography.mono, color = scheme.outline, maxLines = 1, overflow = TextOverflow.MiddleEllipsis) }
            if (row.needsFonts) Text(stringResource(Res.string.install_dialog_needs_fonts), style = HideoutTheme.typography.caption, color = scheme.onSurfaceVariant)
            row.covers?.let { covers ->
                val plural = if (covers.above) Res.plurals.install_dialog_add_on_above else Res.plurals.install_dialog_add_on_below
                Text(pluralStringResource(plural, covers.files, covers.files, covers.names), style = HideoutTheme.typography.caption, color = scheme.onSurfaceVariant)
            }
        }
        Text(dialogSize(row.sizeBytes), style = HideoutTheme.typography.labelPill.tabular(), color = scheme.onSurfaceVariant, maxLines = 1)
        when {
            onPreview != null -> HideoutTooltip(stringResource(Res.string.install_dialog_preview_tooltip)) {
                HideoutIconButton(
                    icon = HideoutIcons.Cube,
                    contentDescription = stringResource(Res.string.install_dialog_preview, row.name),
                    onClick = onPreview,
                    iconSize = dimens.PreviewIcon,
                    hoverColor = scheme.surfaceContainerHighest,
                )
            }
            alignPreview -> Spacer(Modifier.width(HideoutDimens.IconButton.Size))
        }
        if (onMoveUp != null) HideoutIconButton(HideoutIcons.ArrowUp, stringResource(Res.string.install_dialog_part_up, row.name), onMoveUp)
        if (onMoveDown != null) HideoutIconButton(HideoutIcons.ArrowDown, stringResource(Res.string.install_dialog_part_down, row.name), onMoveDown)
    }
}

private fun groupRuns(rows: List<VersionRow>): List<List<IndexedValue<VersionRow>>> = rows.withIndex().fold(mutableListOf<MutableList<IndexedValue<VersionRow>>>()) { runs, item ->
    val last = runs.lastOrNull()?.last()?.value
    if (item.value.group != null && last?.group == item.value.group) runs.last() += item else runs += mutableListOf(item)
    runs
}

@Composable
private fun PickOneGroup(shared: Int?, rows: @Composable () -> Unit) {
    val dimens = HideoutDimens.InstallDialog
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(dimens.GroupBorder, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(dimens.RowRadius + dimens.GroupPadding))
            .padding(dimens.GroupPadding),
        verticalArrangement = Arrangement.spacedBy(dimens.RowGap),
    ) {
        Text(
            text = if (shared == null) stringResource(Res.string.install_dialog_pick_one) else pluralStringResource(Res.plurals.install_dialog_pick_one_shared, shared, shared),
            style = HideoutTheme.typography.notice,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = dimens.RowStartPadding, top = dimens.GroupLabelTop),
        )
        rows()
    }
}

@Composable
private fun Notes(notes: List<ArchiveNote>) {
    val dimens = HideoutDimens.InstallDialog
    val scheme = MaterialTheme.colorScheme
    val label = stringResource(Res.string.install_dialog_notes)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = dimens.NotesMaxHeight)
            .clip(RoundedCornerShape(dimens.NoteRadius))
            .background(scheme.surfaceContainerHigh)
            .verticalScroll(rememberScrollState())
            .semantics { contentDescription = label }
            .padding(horizontal = dimens.NoteHorizontalPadding, vertical = dimens.NoteVerticalPadding),
        verticalArrangement = Arrangement.spacedBy(dimens.RowLineGap),
    ) {
        notes.forEach { note ->
            Text(note.path, style = HideoutTheme.typography.mono, color = scheme.outline)
            Text(note.text, style = HideoutTheme.typography.notice, color = scheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Note(content: @Composable () -> Unit) {
    val dimens = HideoutDimens.InstallDialog
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(dimens.NoteRadius))
            .background(scheme.surfaceContainerHigh)
            .padding(horizontal = dimens.NoteHorizontalPadding, vertical = dimens.NoteVerticalPadding),
        horizontalArrangement = Arrangement.spacedBy(dimens.NoteGap),
    ) {
        HideoutIcon(HideoutIcons.Info, contentDescription = null, size = dimens.NoteIcon, tint = scheme.onSurfaceVariant, modifier = Modifier.padding(top = dimens.NoteIconTop))
        Column(verticalArrangement = Arrangement.spacedBy(dimens.RowLineGap)) { content() }
    }
}

@Composable
private fun Footer(state: InstallDialogState, ready: Boolean, onCancel: () -> Unit, onConfirm: () -> Unit) {
    val dimens = HideoutDimens.InstallDialog
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = dimens.FooterTopPadding),
        horizontalArrangement = Arrangement.spacedBy(dimens.FooterGap, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HideoutButton(
            text = stringResource(Res.string.install_dialog_cancel),
            onClick = onCancel,
            variant = ButtonVariant.Text,
            size = ButtonSize.Large,
            contentPadding = PaddingValues(horizontal = dimens.CancelPadding),
        )
        val picked = state.selected.size
        val action = when {
            state.question == Question.Files && state.replacing -> stringResource(Res.string.install_dialog_apply)
            picked < 2 -> stringResource(Res.string.install)
            state.question == Question.Sets -> stringResource(Res.string.install_dialog_install_parts, picked)
            else -> stringResource(Res.string.install_dialog_install_files, picked)
        }
        val download = state.toDownload.takeIf { state.question == Question.Files && it.isNotEmpty() }?.let { files -> dialogSize(files.sumOf { it.sizeBytes }) }
        HideoutButton(
            text = ModLabels.join(action, download),
            onClick = onConfirm,
            size = ButtonSize.Large,
            leadingIcon = HideoutIcons.Download,
            enabled = ready && state.canConfirm,
            contentPadding = PaddingValues(start = dimens.InstallStartPadding, end = dimens.InstallEndPadding),
        )
    }
}

@Composable
internal fun dialogSize(bytes: Long): String {
    val amount = dialogSizeAmount(bytes)
    val unit = when (amount.unit) {
        SizeUnit.KB -> Res.string.size_kb
        SizeUnit.MB -> Res.string.size_mb
        SizeUnit.GB -> Res.string.size_gb
    }
    return stringResource(unit, amount.value)
}

internal fun dialogSizeAmount(bytes: Long): SizeAmount {
    val megabyteTenths = (bytes * Ten + Megabyte / 2) / Megabyte
    return when {
        bytes < Megabyte -> SizeAmount(((bytes + Kilobyte / 2) / Kilobyte).coerceIn(1L, Kilobyte - 1).toString(), SizeUnit.KB)
        megabyteTenths < Ten * Kilobyte -> SizeAmount(oneDecimal(megabyteTenths), SizeUnit.MB)
        else -> SizeAmount(oneDecimal((bytes * Ten + Gigabyte / 2) / Gigabyte), SizeUnit.GB)
    }
}

private fun oneDecimal(tenths: Long) = "${tenths / Ten}.${tenths % Ten}"

private fun TextStyle.tabular() = copy(fontFeatureSettings = "tnum")

private const val Ten = 10L
private const val Kilobyte = 1_000L
private const val Megabyte = 1_000_000L
private const val Gigabyte = 1_000_000_000L
