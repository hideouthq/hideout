package gg.hideouthq.feature.customgames

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import gg.hideouthq.core.designsystem.component.ButtonSize
import gg.hideouthq.core.designsystem.component.ButtonVariant
import gg.hideouthq.core.designsystem.component.ChipChevron
import gg.hideouthq.core.designsystem.component.HideoutButton
import gg.hideouthq.core.designsystem.component.HideoutIconButton
import gg.hideouthq.core.designsystem.component.TopBarChip
import gg.hideouthq.core.designsystem.icon.HideoutIcons
import gg.hideouthq.core.designsystem.theme.HideoutDimens
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import gg.hideouthq.core.designsystem.theme.HideoutWeights
import gg.hideouthq.core.ui.copyText
import gg.hideouthq.resources.*
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

sealed interface StatusDetail {
    data object Pending : StatusDetail
    data class Score(val mine: Int, val theirs: Int) : StatusDetail
    data class Count(val players: Int, val capacity: Int) : StatusDetail
    data object None : StatusDetail
}

fun statusDetail(chip: HostingChipView): StatusDetail = when {
    chip.status.busy -> StatusDetail.Pending
    chip.status == Status.InMatch && chip.score != null -> StatusDetail.Score(chip.score.first, chip.score.second)
    chip.count != null -> StatusDetail.Count(chip.count.first, chip.count.second)
    else -> StatusDetail.None
}

fun inFriendsGame(chip: HostingChipView): Boolean = chip.title is HostingTitle.InGame && chip.status == Status.InMatch

val HostingChipView.over: Boolean get() = status == Status.Ended || status == Status.CouldNotStart

@Composable
fun HostingChip(chip: HostingChipView, expanded: Boolean, onToggle: () -> Unit, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val dimens = HideoutDimens.TopBarChip
    val status = statusLine(chip)
    val text = when {
        chip.status == Status.Ended -> stringResource(Res.string.cg_ended_title)
        inFriendsGame(chip) -> chipTitle(chip.title)
        else -> status
    }
    val tone = animatedTone(chip.status.tone).content
    TopBarChip(
        label = if (chip.status == Status.Ended) text else stringResource(Res.string.cg_status_with, chipTitle(chip.title), status),
        expanded = expanded && !chip.over,
        onToggle = if (chip.over) onOpen else onToggle,
        panelWidth = dimens.HostingPanelWidth,
        modifier = modifier,
        contentPadding = PaddingValues(start = dimens.HostingStart, end = dimens.HostingEnd),
        panel = { first -> HostingPanel(chip, first, onOpen = { onToggle(); onOpen() }) },
    ) {
        ModeTile(chip.face, dimens.ModeTile, dimens.ModeTileRadius)
        StatusRingDot(tone)
        Text(text, style = ChipText, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
        if (!chip.over) ChipChevron(expanded)
    }
}

@Composable
private fun StatusRingDot(color: Color) {
    val dimens = HideoutDimens.TopBarChip
    Box(Modifier.size(dimens.StatusDot + dimens.StatusDotRing * 2).background(color.copy(alpha = dimens.StatusDotRingAlpha), CircleShape), contentAlignment = Alignment.Center) {
        Box(Modifier.size(dimens.StatusDot).background(color, CircleShape))
    }
}

@Composable
private fun HostingPanel(chip: HostingChipView, first: FocusRequester, onOpen: () -> Unit) {
    val dimens = HideoutDimens.TopBarChip
    val scheme = MaterialTheme.colorScheme
    val tone = animatedTone(chip.status.tone).content
    Row(horizontalArrangement = Arrangement.spacedBy(dimens.HeaderGap), verticalAlignment = Alignment.CenterVertically) {
        ModeTile(chip.face, dimens.HostingHeaderTile, dimens.HostingHeaderTileRadius)
        Column(Modifier.weight(1f)) {
            Text(chipTitle(chip.title), style = PanelTitle, color = scheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                StatusDot(tone, 7.dp)
                Text(statusLine(chip), style = PanelStatus, color = tone, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Image(painterResource(Res.drawable.game_helpers), null, Modifier.size(64.dp))
    }
    chip.invite?.code?.let { code -> CodeBox(code) }
    Row(horizontalArrangement = Arrangement.spacedBy(dimens.ActionGap)) {
        chip.invite?.let { invite ->
            CopyInPlace(
                invite.link ?: invite.code.orEmpty(), if (invite.link != null) Res.string.cg_copy_link else Res.string.cg_copy_code, HideoutIcons.Link, ButtonVariant.Tonal,
                PaddingValues(horizontal = 14.dp), Modifier.weight(1f), ButtonSize.Regular,
            )
        }
        if (chip.opens) HideoutButton(stringResource(Res.string.cg_chip_open_game), onOpen, Modifier.weight(1f).focusRequester(first))
    }
}

@Composable
private fun CodeBox(code: String) {
    val scheme = MaterialTheme.colorScheme
    val clipboard = LocalClipboard.current
    var presses by remember { mutableIntStateOf(0) }
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(presses) {
        if (presses == 0) return@LaunchedEffect
        copied = clipboard.copyText(code)
        delay(CopiedMillis)
        copied = false
    }
    Row(
        Modifier.fillMaxWidth().background(scheme.surface, RoundedCornerShape(HideoutDimens.TopBarChip.BoxRadius)).padding(start = 14.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(Res.string.cg_code).uppercase(), style = HideoutTheme.typography.mono.copy(fontSize = 10.5.sp, letterSpacing = 0.12.em), color = scheme.outline)
            Text(code, style = HideoutTheme.typography.mono.copy(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.08.em), color = scheme.onSurface, maxLines = 1)
        }
        HideoutIconButton(
            if (copied) HideoutIcons.Check else HideoutIcons.Copy, stringResource(if (copied) Res.string.cg_chip_copied else Res.string.cg_copy_code), { presses++ },
            iconSize = 18.dp, tint = scheme.onSurface, hoverColor = scheme.surfaceContainerHigh,
        )
    }
}

@Composable
private fun chipTitle(title: HostingTitle): String = when (title) {
    is HostingTitle.Hosting -> stringResource(Res.string.cg_chip_hosting, title.mode ?: stringResource(Res.string.cg_unknown_mode))
    is HostingTitle.Playing -> stringResource(Res.string.cg_chip_playing, title.mode ?: stringResource(Res.string.cg_unknown_mode))
    is HostingTitle.InGame -> named(title.host, Res.string.cg_chip_in_game, Res.string.cg_chip_in_game_friend)
    HostingTitle.CleaningUp -> stringResource(Res.string.cg_chip_cleaning_up)
}

@Composable
private fun statusLine(chip: HostingChipView): String {
    val label = statusLabel(chip.status)
    return when (val detail = statusDetail(chip)) {
        StatusDetail.Pending -> stringResource(Res.string.cg_status_pending, label)
        is StatusDetail.Score -> stringResource(Res.string.cg_status_with, label, stringResource(Res.string.cg_score, detail.mine, detail.theirs))
        is StatusDetail.Count -> stringResource(Res.string.cg_status_with, label, stringResource(Res.string.cg_count_of, detail.players, detail.capacity))
        StatusDetail.None -> label
    }
}

private val ChipText @Composable get() = HideoutTheme.typography.labelStatus.copy(fontSize = 13.sp, lineHeight = 16.sp, fontWeight = HideoutWeights.Label)
private val PanelTitle @Composable get() = HideoutTheme.typography.titleUpdateCard.copy(fontSize = 15.sp, lineHeight = 19.sp)
private val PanelStatus @Composable get() = HideoutTheme.typography.labelStatus.copy(fontSize = 12.5.sp, fontWeight = HideoutWeights.Label)
private const val CopiedMillis = 1_500L
