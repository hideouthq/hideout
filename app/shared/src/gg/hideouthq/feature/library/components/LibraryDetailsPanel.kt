package gg.hideouthq.feature.library.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import gg.hideouthq.core.designsystem.component.ButtonSize
import gg.hideouthq.core.designsystem.component.ButtonVariant
import gg.hideouthq.core.designsystem.component.CoverArt
import gg.hideouthq.core.designsystem.component.FactItem
import gg.hideouthq.core.designsystem.component.HideoutButton
import gg.hideouthq.core.designsystem.component.HideoutDivider
import gg.hideouthq.core.designsystem.component.HideoutIcon
import gg.hideouthq.core.designsystem.component.HideoutTooltip
import gg.hideouthq.core.designsystem.component.PaneHeader
import gg.hideouthq.core.designsystem.component.ScrollColumn
import gg.hideouthq.core.designsystem.icon.HideoutIcons
import gg.hideouthq.core.designsystem.theme.HideoutDimens
import gg.hideouthq.core.designsystem.theme.HideoutMotion
import gg.hideouthq.core.designsystem.theme.HideoutShapes
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import gg.hideouthq.core.designsystem.theme.HideoutWeights
import gg.hideouthq.core.library.ProfileBook
import gg.hideouthq.core.library.ProfileId
import gg.hideouthq.core.library.incomplete
import gg.hideouthq.core.ui.CompatChip
import gg.hideouthq.core.ui.ModLabels
import gg.hideouthq.core.ui.ProfileChips
import gg.hideouthq.feature.library.DownloadDetail
import gg.hideouthq.feature.library.FilesPlace
import gg.hideouthq.feature.library.LibraryDetail
import gg.hideouthq.feature.library.UpdateState
import gg.hideouthq.resources.Res
import gg.hideouthq.resources.action_uninstall
import gg.hideouthq.resources.action_view_page
import gg.hideouthq.resources.compat_checking
import gg.hideouthq.resources.compat_details
import gg.hideouthq.resources.compat_not_checked
import gg.hideouthq.resources.compat_section
import gg.hideouthq.resources.compat_unknown_heading
import gg.hideouthq.resources.details_open_folder
import gg.hideouthq.resources.details_title
import gg.hideouthq.resources.fact_status
import gg.hideouthq.resources.file_priority_action
import gg.hideouthq.resources.install_dialog_set_files
import gg.hideouthq.resources.library_download_source
import gg.hideouthq.resources.library_files
import gg.hideouthq.resources.library_files_changed
import gg.hideouthq.resources.library_files_in_game
import gg.hideouthq.resources.library_files_location
import gg.hideouthq.resources.library_files_missing
import gg.hideouthq.resources.library_files_summary
import gg.hideouthq.resources.library_going_in_note
import gg.hideouthq.resources.library_going_out_note
import gg.hideouthq.resources.library_held_in
import gg.hideouthq.resources.library_held_out
import gg.hideouthq.resources.library_installed_files
import gg.hideouthq.resources.library_keep_edits
import gg.hideouthq.resources.library_keep_edits_note
import gg.hideouthq.resources.library_off_note
import gg.hideouthq.resources.library_past_limit_note
import gg.hideouthq.resources.library_reinstall
import gg.hideouthq.resources.library_restore_original
import gg.hideouthq.resources.library_restore_original_note
import gg.hideouthq.resources.library_update_keep
import gg.hideouthq.resources.library_update_review_note
import gg.hideouthq.resources.parts_order_action
import gg.hideouthq.resources.profile_install_change_version
import gg.hideouthq.resources.profile_library_membership
import gg.hideouthq.resources.profile_library_row_past_limit
import gg.hideouthq.resources.profile_library_status_off
import gg.hideouthq.resources.profile_library_status_on
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun LibraryDetailsPanel(
    detail: LibraryDetail,
    onViewPage: (String) -> Unit,
    onOpenFolder: (String) -> Unit,
    onUninstall: (String) -> Unit,
    onReinstall: (String) -> Unit,
    onKeepEdits: (String) -> Unit,
    onChangeFiles: (String) -> Unit,
    onKeepCurrentFiles: (String) -> Unit,
    profiles: ProfileBook?,
    onMembership: (String, Boolean, ProfileId) -> Unit,
    onInspect: ((String) -> Unit)? = null,
    onReorderParts: (String) -> Unit = {},
    onCompat: ((String) -> Unit)? = null,
) {
    val row = detail.row
    val held = detail.held
    val scheme = MaterialTheme.colorScheme
    val motion = HideoutTheme.motion
    val rise = with(LocalDensity.current) { HideoutMotion.PanelContentOffset.roundToPx() }
    Column(Modifier.fillMaxSize()) {
        PaneHeader(stringResource(Res.string.details_title))
        ScrollColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(start = HideoutDimens.SidePanel.Padding, end = HideoutDimens.SidePanel.Padding, bottom = HideoutDimens.SidePanel.Padding),
            verticalArrangement = Arrangement.spacedBy(HideoutDimens.Library.PanelGap),
        ) {
            if (row.coverFullUrl != null) AnimatedContent(
                targetState = row,
                contentKey = { it.installId },
                transitionSpec = {
                    (fadeIn(motion.effectsDefault()) + scaleIn(motion.spatialDefault(), HideoutMotion.PanelEnterScale, TransformOrigin(0f, 0.5f)))
                        .togetherWith(fadeOut(motion.exitFade()))
                },
                label = "libraryCover",
            ) {
                CoverArt(
                    seed = it.installId,
                    modifier = Modifier.fillMaxWidth().height(HideoutDimens.Library.PanelCover),
                    shape = HideoutShapes.Card,
                    imageUrl = it.coverFullUrl,
                )
            }
            AnimatedContent(
                targetState = row,
                contentKey = { it.installId },
                transitionSpec = {
                    (fadeIn(motion.effectsDefault()) + slideInVertically(motion.spatialDefault()) { rise }).togetherWith(fadeOut(motion.exitFade()))
                },
                label = "libraryTitle",
            ) { shown ->
                Text(shown.title, style = HideoutTheme.typography.titleSection, color = scheme.onSurface)
            }
            if (row.damage != null) {
                val edited = held?.edited.orEmpty().ifEmpty { listOf(row.title) }
                Row(horizontalArrangement = Arrangement.spacedBy(HideoutDimens.Notice.IconGap)) {
                    HideoutIcon(HideoutIcons.Warning, contentDescription = null, tint = scheme.tertiary)
                    Text(
                        text = if (held == null) stringResource(Res.string.library_files_missing) else pluralStringResource(Res.plurals.library_files_changed, edited.size, edited.joinToString(", ")),
                        style = HideoutTheme.typography.notice,
                        color = scheme.onSurfaceVariant,
                    )
                }
                if (row.reinstall != null || held != null) {
                    val enabled = row.canInstall && !row.busy
                    Row(horizontalArrangement = Arrangement.spacedBy(HideoutDimens.Button.Gap)) {
                        if (held != null) HideoutTooltip(stringResource(Res.string.library_keep_edits_note)) {
                            HideoutButton(stringResource(Res.string.library_keep_edits), { onKeepEdits(row.installId) }, variant = ButtonVariant.Text, size = ButtonSize.Small, enabled = enabled)
                        }
                        if (row.reinstall != null) {
                            if (held != null) HideoutTooltip(stringResource(Res.string.library_restore_original_note)) {
                                HideoutButton(stringResource(Res.string.library_restore_original), { onReinstall(row.installId) }, variant = ButtonVariant.Text, size = ButtonSize.Small, enabled = enabled)
                            } else {
                                HideoutButton(stringResource(Res.string.library_reinstall), { onReinstall(row.installId) }, variant = ButtonVariant.Text, size = ButtonSize.Small, enabled = enabled)
                            }
                        }
                    }
                }
            }
            if (row.update == UpdateState.Review && row.operation == null) {
                Row(horizontalArrangement = Arrangement.spacedBy(HideoutDimens.Notice.IconGap)) {
                    HideoutIcon(HideoutIcons.Info, contentDescription = null, tint = scheme.onSurfaceVariant)
                    Text(stringResource(Res.string.library_update_review_note), style = HideoutTheme.typography.notice, color = scheme.onSurfaceVariant)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(HideoutDimens.Button.Gap)) {
                    HideoutButton(stringResource(Res.string.profile_install_change_version), { onChangeFiles(row.installId) }, variant = ButtonVariant.Text, size = ButtonSize.Small, enabled = row.page != null && row.canInstall && !row.busy)
                    HideoutButton(stringResource(Res.string.library_update_keep), { onKeepCurrentFiles(row.installId) }, variant = ButtonVariant.Text, size = ButtonSize.Small, enabled = row.canToggle)
                }
            }
            if (onCompat != null && (row.compatStatus != null || row.compat?.failed == null)) CompatFact(detail, onCompat)
            HideoutDivider()
            FactItem(
                icon = when {
                    row.pastLimit || held != null -> HideoutIcons.Warning
                    row.enabled -> HideoutIcons.Check
                    else -> HideoutIcons.Minus
                },
                label = stringResource(Res.string.fact_status),
                value = when {
                    row.pastLimit -> stringResource(Res.string.profile_library_row_past_limit)
                    held == null -> stringResource(if (row.enabled) Res.string.profile_library_status_on else Res.string.profile_library_status_off, profiles?.activeProfile?.name.orEmpty())
                    held.numbers.isEmpty() -> stringResource(Res.string.library_held_out)
                    else -> stringResource(Res.string.library_held_in, listOf(held.numbers.first(), held.numbers.last()).distinct().joinToString("–"))
                },
            )
            if (onInspect != null) HideoutButton(stringResource(Res.string.file_priority_action), { onInspect(row.installId) }, variant = ButtonVariant.Text, size = ButtonSize.Small)
            val fileCount = row.availableFiles?.let { stringResource(Res.string.library_files_summary, row.installedFiles, it) }
                ?: pluralStringResource(Res.plurals.install_dialog_set_files, row.installedVpks, row.installedVpks)
            FactItem(icon = HideoutIcons.SwapVersions, label = stringResource(Res.string.library_installed_files), value = ModLabels.join(row.variant, fileCount))
            if (row.partsOverlap && !row.held) {
                HideoutButton(stringResource(Res.string.parts_order_action), { onReorderParts(row.installId) }, variant = ButtonVariant.Text, size = ButtonSize.Small, enabled = row.canInstall && !row.busy)
            }
            HideoutDivider()
            profiles?.takeIf { it.profiles.size > 1 }?.let { book ->
                Text(stringResource(Res.string.profile_library_membership), style = HideoutTheme.typography.caption, color = scheme.outline)
                ProfileChips(
                    profiles = book.profiles,
                    selected = book.profiles.filter { row.installId in it.enabled }.mapTo(mutableSetOf()) { it.id },
                    onToggle = { profile, enabled -> onMembership(row.installId, enabled, profile) },
                    enabled = row.canToggle,
                    waiting = row.applying,
                )
                HideoutDivider()
            }
            FilesBlock(detail, onOpenFolder = { onOpenFolder(row.installId) })
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = HideoutDimens.SidePanel.Padding,
                    end = HideoutDimens.SidePanel.Padding,
                    top = HideoutDimens.SidePanel.FooterTopPadding,
                    bottom = HideoutDimens.SidePanel.Padding,
                ),
            horizontalArrangement = Arrangement.spacedBy(HideoutDimens.Button.Gap),
        ) {
            row.page?.let { page -> HideoutButton(stringResource(Res.string.action_view_page), { onViewPage(page) }, Modifier.weight(1f), variant = ButtonVariant.Tonal) }
            HideoutButton(stringResource(Res.string.action_uninstall), { onUninstall(row.installId) }, variant = ButtonVariant.Subtle, enabled = row.canToggle && !row.busy)
        }
    }
}

@Composable
private fun FilesBlock(detail: LibraryDetail, onOpenFolder: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val on = detail.files.inGame
    val several = detail.downloads.size > 1
    Column(verticalArrangement = Arrangement.spacedBy(HideoutDimens.Library.FilesGap)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(if (on) Res.string.library_files_in_game else Res.string.library_files),
                style = HideoutTheme.typography.labelSection,
                color = scheme.outline,
                modifier = Modifier.weight(1f),
            )
            HideoutButton(stringResource(Res.string.details_open_folder), onOpenFolder, variant = ButtonVariant.Text, size = ButtonSize.Small)
        }
        when {
            detail.held != null || detail.files == FilesPlace.InGame -> null
            detail.files == FilesPlace.Out -> if (detail.row.pastLimit) Res.string.library_past_limit_note else Res.string.library_off_note
            detail.files == FilesPlace.GoingIn -> Res.string.library_going_in_note
            else -> Res.string.library_going_out_note
        }?.let { note ->
            Text(
                text = stringResource(note),
                style = HideoutTheme.typography.notice,
                color = scheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(scheme.surfaceContainer, HideoutShapes.Row)
                    .padding(horizontal = HideoutDimens.Library.OffNoteHorizontalPadding, vertical = HideoutDimens.Library.OffNoteVerticalPadding),
            )
        }
        if (!on) {
            if (several) detail.downloads.forEach { DownloadHeading(it) }
            return@Column
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(scheme.surfaceContainer, HideoutShapes.Row)
                .padding(vertical = HideoutDimens.Library.FilesBoxVerticalPadding),
        ) {
            detail.downloads.forEach { download ->
                if (several) DownloadHeading(download, Modifier.padding(horizontal = HideoutDimens.Library.FileRowHorizontalPadding))
                download.files.forEach { file ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(HideoutDimens.Library.FileRowHeight)
                            .padding(horizontal = HideoutDimens.Library.FileRowHorizontalPadding),
                        horizontalArrangement = Arrangement.spacedBy(HideoutDimens.Library.FileRowGap),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = file.name,
                            style = HideoutTheme.typography.mono,
                            color = scheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text(ModLabels.size(file.sizeBytes), style = HideoutTheme.typography.mono, color = scheme.outline)
                    }
                }
            }
        }
        Text(
            text = if (several) detail.folderPath else stringResource(Res.string.library_files_location, detail.folderPath, detail.downloads.single().archiveName),
            style = HideoutTheme.typography.mono,
            color = scheme.outline,
        )
    }
}

@Composable
private fun DownloadHeading(download: DownloadDetail, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Column(modifier.fillMaxWidth().padding(top = HideoutDimens.Library.DownloadHeadingTop, bottom = HideoutDimens.Library.DownloadHeadingBottom)) {
        Text(
            text = download.componentPath ?: download.name ?: download.archiveName,
            style = HideoutTheme.typography.caption.copy(fontWeight = HideoutWeights.Label),
            color = scheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = download.number?.let { stringResource(Res.string.library_download_source, download.archiveName, it.toString().padStart(2, '0')) } ?: download.archiveName,
            style = HideoutTheme.typography.mono,
            color = scheme.outline,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun CompatFact(detail: LibraryDetail, onCompat: (String) -> Unit) {
    val row = detail.row
    val compat = row.compat
    val scheme = MaterialTheme.colorScheme
    Row(horizontalArrangement = Arrangement.spacedBy(HideoutDimens.Library.NoteGap), verticalAlignment = Alignment.CenterVertically) {
        if (compat != null && row.compatStatus != null) {
            CompatChip(compat, row.enabled, row.title, onClick = { onCompat(row.installId) })
        } else {
            val text = when {
                compat == null || compat.check == null && compat.failed == null -> stringResource(if (compat?.checking == true) Res.string.compat_checking else Res.string.compat_not_checked)
                compat.failed != null || compat.unknown.isNotEmpty() || compat.incomplete.isNotEmpty() -> stringResource(Res.string.compat_unknown_heading)
                else -> stringResource(Res.string.compat_section)
            }
            Text(text, style = HideoutTheme.typography.captionSmall, color = scheme.outline, modifier = Modifier.weight(1f, fill = false))
        }
        HideoutButton(stringResource(Res.string.compat_details), { onCompat(row.installId) }, variant = ButtonVariant.Text, size = ButtonSize.Small)
    }
}
