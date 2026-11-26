package gg.hideouthq.feature.customgames

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import gg.hideouthq.core.customgames.Choices
import gg.hideouthq.core.customgames.CustomGames
import gg.hideouthq.core.customgames.Mode
import gg.hideouthq.core.customgames.ModeList
import gg.hideouthq.core.customgames.RestartReason
import gg.hideouthq.core.customgames.SettingValue
import gg.hideouthq.core.customgames.solo
import gg.hideouthq.core.designsystem.component.ButtonSize
import gg.hideouthq.core.designsystem.component.ButtonVariant
import gg.hideouthq.core.designsystem.component.HideoutButton
import gg.hideouthq.core.designsystem.component.HideoutIcon
import gg.hideouthq.core.designsystem.component.HideoutIconButton
import gg.hideouthq.core.designsystem.component.RollingText
import gg.hideouthq.core.designsystem.component.SegmentedControl
import gg.hideouthq.core.designsystem.component.SegmentedStyle
import gg.hideouthq.core.designsystem.icon.HideoutIcons
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import gg.hideouthq.core.designsystem.theme.HideoutWeights
import gg.hideouthq.core.ui.AlertModal
import gg.hideouthq.core.ui.WindowModal
import gg.hideouthq.resources.*
import org.jetbrains.compose.resources.stringResource

/** Open while [mode] is not null. */
@Composable
fun HostDialog(mode: Mode?, modes: ModeList, block: HostBlock?, restarts: RestartReason?, games: CustomGames, modal: WindowModal, onClose: () -> Unit) {
    val (current, shownDownload, shownBlock, shownRestart) = rememberLast(mode?.let { HostLatch(it, modes.downloadToHost(it), block, restarts) }) ?: return
    val focus = remember { FocusRequester() }
    var choices by remember(current) { mutableStateOf(Choices.defaultsOf(current.listing)) }
    val view = hostDialog(current, shownDownload, choices)
    val name = current.listing.name
    val solo = current.listing.solo
    val title = stringResource(if (solo) Res.string.cg_play_title else Res.string.cg_host_title, name)
    AlertModal(visible = mode != null, modal = modal, title = title, onDismiss = onClose, initialFocus = focus, width = 600.dp, padding = 0.dp) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Header(current, title, stringResource(if (solo) Res.string.cg_play_meta else Res.string.cg_host_meta), onClose)
            Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 14.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                view.settings.forEach { pick ->
                    val sizes = pick.setting.options.any { it.players != null }
                    SettingRow(pick, players = view.capacity.takeIf { sizes }) { choices = choices.with(pick.setting, it) }
                }
                when {
                    shownBlock != null -> HintBox(HideoutIcons.Warning, blockText(shownBlock))
                    view.download != null -> DownloadRow(megabytes(view.download), stringResource(Res.string.cg_host_download_sub, name))
                }
                if (shownBlock == null && shownRestart != null) HintBox(HideoutIcons.Refresh, restartAhead(shownRestart, host = true))
            }
            Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 20.dp, top = 14.dp, bottom = 18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                HideoutButton(stringResource(Res.string.cg_cancel), onClose, variant = ButtonVariant.Text, size = ButtonSize.Medium)
                HideoutButton(
                    stringResource(Res.string.cg_start_game), { games.host(current, choices, shownRestart); onClose() }, Modifier.focusRequester(focus),
                    leadingIcon = HideoutIcons.Play, leadingIconSize = 16.dp, enabled = mode != null && shownBlock == null,
                )
            }
        }
    }
}

@Composable
private fun Header(mode: Mode, title: String, meta: String, onClose: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 18.dp, top = 22.dp, bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        ModeTile(mode.id.value, mode.listing.art.tile, 56.dp, 16.dp)
        Column(Modifier.weight(1f).align(Alignment.CenterVertically)) {
            Text(title, style = HideoutTheme.typography.titleDialog.copy(fontSize = 22.sp, lineHeight = 25.sp), color = scheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(meta, Modifier.padding(top = 3.dp), style = HideoutTheme.typography.caption.copy(fontSize = 13.sp), color = scheme.outline)
        }
        HideoutIconButton(HideoutIcons.Close, stringResource(Res.string.cg_close), onClose)
    }
}

@Composable
private fun SettingRow(pick: SettingPick, players: Int?, onPick: (SettingValue) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val type = HideoutTheme.typography
    val sub = type.caption.copy(fontSize = 12.5.sp)
    val setting = pick.setting
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(setting.label, style = type.bodyStrong.copy(fontSize = 14.sp, fontWeight = HideoutWeights.Label), color = scheme.onSurface)
            setting.hint?.let { Text(it, style = sub, color = scheme.outline) }
            if (players != null) RollingText(stringResource(Res.string.cg_host_players_sub, players), sub, color = scheme.outline)
        }
        SegmentedControl(
            setting.options.map { it.value }, pick.picked ?: setting.default, onPick,
            label = { value -> setting.options.first { it.value == value }.label },
            segmentHeight = 30.dp, segmentPadding = 13.dp, style = SegmentedStyle.Card,
        )
    }
}

@Composable
private fun DownloadRow(size: String, sub: String) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().background(scheme.surfaceContainerHigh, RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(44.dp).background(scheme.surfaceContainerHighest, RoundedCornerShape(13.dp)), contentAlignment = Alignment.Center) {
            HideoutIcon(HideoutIcons.Download, null, size = 20.dp, tint = scheme.onSurfaceVariant)
        }
        Column(Modifier.weight(1f)) {
            Text(stringResource(Res.string.cg_host_download, size), style = HideoutTheme.typography.bodyStrong.copy(fontSize = 14.sp, fontWeight = HideoutWeights.Label), color = scheme.onSurface)
            Text(sub, style = HideoutTheme.typography.caption.copy(fontSize = 12.5.sp, lineHeight = 17.5.sp), color = scheme.outline)
        }
    }
}

private data class HostLatch(val mode: Mode, val download: Long?, val block: HostBlock?, val restarts: RestartReason?)

@Composable
fun blockText(block: HostBlock): String = stringResource(
    when (block) {
        HostBlock.CleaningUp -> Res.string.cg_block_cleaning_up
        HostBlock.GameRunning -> Res.string.cg_block_game_running
    },
)
