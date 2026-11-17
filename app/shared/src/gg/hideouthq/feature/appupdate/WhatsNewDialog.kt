package gg.hideouthq.feature.appupdate

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import gg.hideouthq.core.appupdate.AppUpdates
import gg.hideouthq.core.appupdate.AppVersion
import gg.hideouthq.core.appupdate.AppUpdateState
import gg.hideouthq.feature.customgames.GameInProgress
import gg.hideouthq.core.appupdate.Highlight
import gg.hideouthq.core.appupdate.ReleaseEntry
import gg.hideouthq.core.appupdate.ReleaseNotes
import gg.hideouthq.core.appupdate.RestartStage
import gg.hideouthq.core.appupdate.WhatsNewFooter
import gg.hideouthq.core.appupdate.WhatsNewTag
import gg.hideouthq.core.appupdate.WhatsNewVersion
import gg.hideouthq.core.appupdate.whatsNew
import gg.hideouthq.core.designsystem.component.ButtonSize
import gg.hideouthq.core.designsystem.component.ButtonVariant
import gg.hideouthq.core.designsystem.component.CookieTile
import gg.hideouthq.core.designsystem.component.HideoutButton
import gg.hideouthq.core.designsystem.component.HideoutDivider
import gg.hideouthq.core.designsystem.component.HideoutIcon
import gg.hideouthq.core.designsystem.component.HideoutIconButton
import gg.hideouthq.core.designsystem.component.HideoutMenu
import gg.hideouthq.core.designsystem.component.MenuEntry
import gg.hideouthq.core.designsystem.component.ScrollColumn
import gg.hideouthq.core.designsystem.icon.HideoutIcons
import gg.hideouthq.core.designsystem.image.LocalRemoteImages
import gg.hideouthq.core.designsystem.interaction.collectInteractionStates
import gg.hideouthq.core.designsystem.interaction.focusRing
import gg.hideouthq.core.designsystem.interaction.hideoutClickable
import gg.hideouthq.core.designsystem.interaction.hideoutSelectable
import gg.hideouthq.core.designsystem.theme.HideoutDimens
import gg.hideouthq.core.designsystem.theme.HideoutShapes
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import gg.hideouthq.core.ui.AlertModal
import gg.hideouthq.core.ui.ModLabels
import gg.hideouthq.core.ui.WindowModal
import gg.hideouthq.core.ui.windowWidth
import gg.hideouthq.resources.Res
import gg.hideouthq.resources.app_update_action
import gg.hideouthq.resources.app_update_cancel
import gg.hideouthq.resources.app_update_close
import gg.hideouthq.resources.app_update_confirm_password
import gg.hideouthq.resources.app_update_done
import gg.hideouthq.resources.app_update_download_size
import gg.hideouthq.resources.app_update_downloading_percent
import gg.hideouthq.resources.app_update_full_notes
import gg.hideouthq.resources.app_update_github
import gg.hideouthq.resources.app_update_group_fixed
import gg.hideouthq.resources.app_update_group_improved
import gg.hideouthq.resources.app_update_group_new
import gg.hideouthq.resources.app_update_restart_after_game
import gg.hideouthq.resources.app_update_notes_heading
import gg.hideouthq.resources.app_update_restart_to_update
import gg.hideouthq.resources.app_update_restarting
import gg.hideouthq.resources.app_update_tag_installed
import gg.hideouthq.resources.app_update_tag_new
import gg.hideouthq.resources.app_update_up_to_date
import gg.hideouthq.resources.app_update_version_menu
import gg.hideouthq.resources.app_update_versions
import gg.hideouthq.resources.app_update_waiting_mods
import gg.hideouthq.resources.app_update_whats_new_title
import gg.hideouthq.resources.hideout_logo
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun WhatsNewDialog(open: Boolean, state: AppUpdateState, game: GameInProgress?, updates: AppUpdates, modal: WindowModal, onClose: () -> Unit) {
    val close = remember { FocusRequester() }
    val dimens = HideoutDimens.AppUpdate
    val title = stringResource(Res.string.app_update_whats_new_title)
    AlertModal(open, modal, title, onClose, close, width = dimens.DialogWidth, padding = 0.dp) {
        val view = whatsNew(state)
        var selected by remember { mutableStateOf<AppVersion?>(null) }
        LaunchedEffect(open) { if (open) selected = null }
        val shown = view.versions.firstOrNull { it.notes.version == selected } ?: view.versions.firstOrNull()
        Column(Modifier.fillMaxWidth().height(dimens.DialogHeight)) {
            Row(Modifier.padding(start = 22.dp, top = 20.dp, end = 18.dp, bottom = 18.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                CookieTile(MaterialTheme.colorScheme.surfaceContainerHigh, size = dimens.LogoTile) {
                    Image(painterResource(Res.drawable.hideout_logo), null, Modifier.size(dimens.LogoSize))
                }
                Column(Modifier.weight(1f)) {
                    Text(title, style = HideoutTheme.typography.titleDialog, color = MaterialTheme.colorScheme.onSurface)
                    Text(headlineLine(view.headline), Modifier.padding(top = 4.dp), style = HideoutTheme.typography.captionSmall, color = MaterialTheme.colorScheme.outline)
                }
                HideoutIconButton(HideoutIcons.Close, stringResource(Res.string.app_update_close), onClose, Modifier.align(Alignment.Top).focusRequester(close))
            }
            HideoutDivider()
            val narrow = windowWidth() < dimens.NarrowWindow
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (narrow) Column(Modifier.fillMaxHeight()) {
                    if (shown != null) VersionDropdown(view.versions, shown) { selected = it.notes.version }
                    HideoutDivider()
                    Notes(shown?.notes, view.notesUrl, Modifier.weight(1f))
                } else Row(Modifier.fillMaxHeight()) {
                    VersionList(view.versions, shown) { selected = it.notes.version }
                    Box(Modifier.width(HideoutDimens.Hairline).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant))
                    Notes(shown?.notes, view.notesUrl, Modifier.weight(1f))
                }
            }
            HideoutDivider()
            Footer(view.footer, view.notesUrl, state.phase.version, game, updates, onClose)
        }
    }
}

@Composable
private fun VersionList(versions: List<WhatsNewVersion>, selected: WhatsNewVersion?, onSelect: (WhatsNewVersion) -> Unit) {
    Column(
        Modifier.width(HideoutDimens.AppUpdate.VersionColumn - HideoutDimens.Hairline).fillMaxHeight().verticalScroll(rememberScrollState()).padding(start = 10.dp, end = 10.dp, top = 10.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(stringResource(Res.string.app_update_versions), Modifier.padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 8.dp), style = HideoutTheme.typography.labelSection, color = MaterialTheme.colorScheme.outline)
        versions.forEach { version ->
            VersionRow(version, version == selected) { onSelect(version) }
        }
    }
}

@Composable
private fun VersionRow(version: WhatsNewVersion, selected: Boolean, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val states = source.collectInteractionStates()
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(HideoutDimens.AppUpdate.VersionRadius)
    val fill = when {
        selected -> scheme.surfaceContainerHigh
        states.hovered -> Color.White.copy(alpha = 0.04f)
        else -> Color.Transparent
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = HideoutDimens.AppUpdate.VersionRow).background(fill, shape)
            .focusRing(states.focusVisible, shape)
            .hideoutSelectable(selected, source, onClick = onClick)
            .padding(start = 12.dp, end = 10.dp, top = 6.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VersionText(version.notes, Modifier.weight(1f))
        version.tag?.let { VersionTag(it) }
    }
}

@Composable
private fun VersionText(notes: ReleaseNotes, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Text(notes.version.toString(), style = HideoutTheme.typography.labelButton.copy(fontFeatureSettings = TabularFigures), color = MaterialTheme.colorScheme.onSurface)
        Text(ModLabels.date(notes.date), style = HideoutTheme.typography.caption, color = MaterialTheme.colorScheme.outline)
    }
}

@Composable
private fun VersionTag(tag: WhatsNewTag) {
    val scheme = MaterialTheme.colorScheme
    val (container, content, label) = when (tag) {
        WhatsNewTag.New -> Triple(scheme.secondaryContainer, scheme.secondary, Res.string.app_update_tag_new)
        WhatsNewTag.Installed -> Triple(scheme.surfaceContainerHighest, scheme.onSurfaceVariant, Res.string.app_update_tag_installed)
    }
    Box(Modifier.height(HideoutDimens.AppUpdate.TagHeight).background(container, HideoutShapes.Full).padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
        Text(stringResource(label), style = HideoutTheme.typography.labelVersionTag, color = content, maxLines = 1)
    }
}

@Composable
private fun VersionDropdown(versions: List<WhatsNewVersion>, selected: WhatsNewVersion, onSelect: (WhatsNewVersion) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val source = remember { MutableInteractionSource() }
    val states = source.collectInteractionStates()
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(HideoutDimens.AppUpdate.VersionRadius)
    val label = stringResource(Res.string.app_update_version_menu)
    Box(Modifier.padding(10.dp)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = HideoutDimens.AppUpdate.VersionRow).background(scheme.surfaceContainerHigh, shape)
                .focusRing(states.focusVisible, shape)
                .hideoutClickable(source, role = Role.DropdownList, onClick = { open = true })
                .padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            VersionText(selected.notes, Modifier.weight(1f))
            selected.tag?.let { VersionTag(it) }
            HideoutIcon(HideoutIcons.ChevronDown, label, size = HideoutDimens.Icon.Small, tint = scheme.onSurfaceVariant)
        }
        if (open) HideoutMenu(
            entries = versions.map { version ->
                MenuEntry.Item(
                    "${version.notes.version} · ${ModLabels.date(version.notes.date)}",
                    if (version == selected) HideoutIcons.Check else HideoutIcons.Blank,
                    { open = false; onSelect(version) },
                )
            },
            onClose = { open = false },
            label = label,
        )
    }
}

@Composable
private fun Notes(notes: ReleaseNotes?, notesUrl: String?, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val type = HideoutTheme.typography
    val uris = LocalUriHandler.current
    key(notes?.version) {
        ScrollColumn(
            modifier.fillMaxHeight(),
            contentPadding = PaddingValues(start = 24.dp, end = 26.dp, top = 20.dp, bottom = 26.dp),
            verticalArrangement = Arrangement.spacedBy(HideoutDimens.AppUpdate.NotesGap),
        ) {
            if (notes == null) return@ScrollColumn
            Column {
                Text(stringResource(Res.string.app_update_notes_heading, notes.version.toString()), style = type.headlineVersion, color = scheme.onSurface)
                Text(longDate(notes.date), Modifier.padding(top = 3.dp), style = type.captionSmall, color = scheme.outline)
            }
            if (notes.highlights.isNotEmpty()) Highlights(notes.highlights)
            NoteGroup(Res.string.app_update_group_new, HideoutIcons.PlusBold, scheme.secondaryContainer, scheme.secondary, notes.new)
            NoteGroup(Res.string.app_update_group_improved, HideoutIcons.TrendUp, scheme.primary.copy(alpha = ImprovedTileAlpha), scheme.primary, notes.improved)
            NoteGroup(Res.string.app_update_group_fixed, HideoutIcons.Wrench, HideoutTheme.colors.cautionContainer, HideoutTheme.colors.caution, notes.fixed)
            if (notesUrl != null) HideoutButton(
                stringResource(Res.string.app_update_full_notes),
                { uris.openUri(notesUrl) },
                Modifier.offset(x = -HideoutDimens.Button.TextHorizontalPadding, y = (-1.5).dp),
                variant = ButtonVariant.Text,
                size = ButtonSize.Small,
                trailingIcon = HideoutIcons.ArrowUpRight,
                trailingIconSize = HideoutDimens.Icon.Small,
            )
        }
    }
}

@Composable
private fun Highlights(highlights: List<Highlight>) {
    val single = highlights.size == 1
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        highlights.forEach { HighlightCard(it, single, Modifier.weight(1f).fillMaxHeight()) }
    }
}

@Composable
private fun HighlightCard(highlight: Highlight, single: Boolean, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    val dimens = HideoutDimens.AppUpdate
    Column(modifier.clip(RoundedCornerShape(dimens.HighlightRadius)).background(scheme.surfaceContainerHigh)) {
        val image = highlight.imageUrl?.let { LocalRemoteImages.current.rememberImage(it, ContentScale.Crop) }
        if (image != null && !image.failed) {
            Image(
                image.painter, null,
                Modifier.fillMaxWidth().height(if (single) dimens.HighlightArtSingle else dimens.HighlightArt).then(image.layoutModifier),
                contentScale = ContentScale.Crop,
            )
        }
        Column(Modifier.padding(start = 14.dp, end = 14.dp, top = 11.dp, bottom = 13.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(highlight.title, style = HideoutTheme.typography.titleHighlight, color = scheme.onSurface)
            Text(highlight.text, style = HideoutTheme.typography.subtitle.copy(lineHeight = HighlightTextLine), color = scheme.outline)
        }
    }
}

@Composable
private fun NoteGroup(name: StringResource, icon: ImageVector, container: Color, content: Color, entries: List<ReleaseEntry>) {
    if (entries.isEmpty()) return
    val scheme = MaterialTheme.colorScheme
    val dimens = HideoutDimens.AppUpdate
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(dimens.GroupTile).background(container, RoundedCornerShape(dimens.GroupTileRadius)), contentAlignment = Alignment.Center) {
                HideoutIcon(icon, null, size = dimens.GroupIcon, tint = content)
            }
            Text(stringResource(name), style = HideoutTheme.typography.labelButton, color = scheme.onSurface)
        }
        Column(Modifier.padding(start = dimens.GroupIndent), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            entries.forEach { entry ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(entry.area, Modifier.width(dimens.AreaColumn).padding(top = 2.dp), style = HideoutTheme.typography.labelSection, color = scheme.outline)
                    Text(entry.text, Modifier.weight(1f), style = HideoutTheme.typography.bodyDialog, color = scheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun Footer(footer: WhatsNewFooter, notesUrl: String?, offered: AppVersion?, game: GameInProgress?, updates: AppUpdates, onClose: () -> Unit) {
    val uris = LocalUriHandler.current
    val restartLabel = if (game != null) Res.string.app_update_restart_after_game else Res.string.app_update_restart_to_update
    val done: @Composable () -> Unit = { HideoutButton(stringResource(Res.string.app_update_done), onClose) }
    val restart: @Composable (enabled: Boolean, label: StringResource) -> Unit = { enabled, label ->
        HideoutButton(stringResource(label), { updates.restart() }, leadingIcon = HideoutIcons.Restart, leadingIconSize = HideoutDimens.Icon.Small, enabled = enabled)
    }
    val (info, buttons) = when (footer) {
        is WhatsNewFooter.Download -> stringResource(Res.string.app_update_download_size, updateSize(footer.sizeBytes)) to @Composable {
            HideoutButton(stringResource(Res.string.app_update_action), { updates.update(); onClose() }, leadingIcon = HideoutIcons.Download, leadingIconSize = HideoutDimens.Icon.Small)
        }
        is WhatsNewFooter.Downloading -> stringResource(Res.string.app_update_downloading_percent, footer.percent) to @Composable {}
        is WhatsNewFooter.Restart -> when (footer.stage) {
            RestartStage.Idle -> "" to @Composable { restart(true, restartLabel) }
            RestartStage.Waiting -> (if (game != null) afterGameLine(offered.toString(), game) else stringResource(Res.string.app_update_waiting_mods)) to @Composable {
                HideoutButton(
                    stringResource(Res.string.app_update_cancel), { updates.cancelRestart(); onClose() }, variant = ButtonVariant.Text, size = ButtonSize.Small,
                    contentPadding = PaddingValues(horizontal = HideoutDimens.AppUpdate.FooterTextButtonPadding),
                )
                restart(false, restartLabel)
            }
            RestartStage.Installing -> stringResource(Res.string.app_update_confirm_password) to @Composable {}
            RestartStage.Exiting -> "" to @Composable { restart(false, Res.string.app_update_restarting) }
        }
        WhatsNewFooter.GitHub -> "" to @Composable {
            if (notesUrl != null) HideoutButton(
                stringResource(Res.string.app_update_github), { uris.openUri(notesUrl) },
                variant = ButtonVariant.Tonal, trailingIcon = HideoutIcons.ArrowUpRight, trailingIconSize = HideoutDimens.Icon.Small,
            )
            done()
        }
        WhatsNewFooter.UpToDate -> stringResource(Res.string.app_update_up_to_date) to done
    }
    Row(
        Modifier.fillMaxWidth().padding(start = 24.dp, end = 18.dp, top = 14.dp, bottom = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(info, Modifier.weight(1f), style = HideoutTheme.typography.subtitle, color = MaterialTheme.colorScheme.outline)
        buttons()
    }
}

private const val ImprovedTileAlpha = 0.14f
private const val TabularFigures = "tnum"
private val HighlightTextLine = 17.5.sp
