package gg.hideouthq.feature.appupdate

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import gg.hideouthq.core.appupdate.AppUpdatePhase
import gg.hideouthq.core.appupdate.AppUpdateState
import gg.hideouthq.core.appupdate.AppUpdates
import gg.hideouthq.core.appupdate.RestartStage
import gg.hideouthq.core.appupdate.UpdateFailure
import gg.hideouthq.core.designsystem.component.ButtonSize
import gg.hideouthq.core.designsystem.component.ButtonVariant
import gg.hideouthq.core.designsystem.component.HideoutButton
import gg.hideouthq.core.designsystem.component.HideoutIcon
import gg.hideouthq.core.designsystem.component.TopBarChip
import gg.hideouthq.core.designsystem.icon.HideoutIcons
import gg.hideouthq.core.designsystem.theme.HideoutDimens
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import gg.hideouthq.core.designsystem.theme.HideoutWeights
import gg.hideouthq.feature.customgames.GameInProgress
import gg.hideouthq.resources.*
import org.jetbrains.compose.resources.stringResource

enum class UpdateChipState { Available, Seen, AvailableInGame, Downloading, Ready, ReadyInGame, AfterGame, Failed }

fun updateChipState(state: AppUpdateState, game: GameInProgress?): UpdateChipState? = when (val phase = state.phase) {
    AppUpdatePhase.Idle -> null
    is AppUpdatePhase.GitHubOnly, is AppUpdatePhase.Available -> when {
        game != null -> UpdateChipState.AvailableInGame
        state.openedFor == phase.version -> UpdateChipState.Seen
        else -> UpdateChipState.Available
    }
    is AppUpdatePhase.Downloading -> UpdateChipState.Downloading
    is AppUpdatePhase.Ready -> when {
        game == null -> UpdateChipState.Ready
        phase.restart == RestartStage.Idle -> UpdateChipState.ReadyInGame
        phase.restart == RestartStage.Waiting -> UpdateChipState.AfterGame
        else -> UpdateChipState.Ready
    }
    is AppUpdatePhase.Failed -> UpdateChipState.Failed
}

@Composable
fun UpdateChip(
    state: AppUpdateState,
    game: GameInProgress?,
    updates: AppUpdates,
    expanded: Boolean,
    onToggle: () -> Unit,
    onWhatsNew: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val chip = updateChipState(state, game) ?: return
    var wasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(expanded) {
        if (expanded) wasOpen = true
        else if (wasOpen) {
            wasOpen = false
            updates.opened()
        }
    }
    val scheme = MaterialTheme.colorScheme
    val dimens = HideoutDimens.TopBarChip
    val ready = chip == UpdateChipState.Ready
    val text = when (chip) {
        UpdateChipState.Available -> stringResource(Res.string.app_update_available)
        UpdateChipState.Ready -> stringResource(Res.string.app_update_restart_to_update)
        else -> null
    }
    val container = if (ready) scheme.secondaryContainer else scheme.surfaceContainer
    val hover = if (ready) scheme.secondary.copy(alpha = ReadyHoverMix).compositeOver(scheme.secondaryContainer) else scheme.surfaceContainerHigh
    TopBarChip(
        label = chipLabel(chip, state),
        expanded = expanded,
        onToggle = onToggle,
        panelWidth = dimens.UpdatePanelWidth,
        modifier = modifier,
        container = container,
        hoverContainer = hover,
        outlined = !ready,
        contentPadding = if (text == null) PaddingValues(0.dp) else PaddingValues(start = dimens.UpdateStart, end = dimens.UpdateEnd),
        gap = dimens.UpdateGap,
        panel = { first -> UpdatePanel(chip, state, game, updates, first, onWhatsNew = { onToggle(); onWhatsNew() }) },
    ) {
        ChipIcon(chip, state.phase, ring = container)
        if (text != null) Text(text, style = ChipText, color = if (ready) scheme.secondary else scheme.onSurface, maxLines = 1)
    }
}

@Composable
private fun ChipIcon(chip: UpdateChipState, phase: AppUpdatePhase, ring: Color) {
    val dimens = HideoutDimens.TopBarChip
    val scheme = MaterialTheme.colorScheme
    val dot = when (chip) {
        UpdateChipState.AvailableInGame, UpdateChipState.ReadyInGame -> scheme.secondary
        else -> null
    }
    val tint = when (chip) {
        UpdateChipState.Ready -> scheme.secondary
        UpdateChipState.Failed -> scheme.error
        else -> scheme.onSurface
    }
    Box(Modifier.size(dimens.IconSlot), contentAlignment = Alignment.Center) {
        when (chip) {
            UpdateChipState.Downloading -> {
                UpdateProgressRing((phase as? AppUpdatePhase.Downloading)?.percent?.div(100f) ?: 0f)
                HideoutIcon(HideoutIcons.Download, null, size = dimens.RingIcon, tint = scheme.onSurface)
            }
            else -> HideoutIcon(chipIcon(chip), null, size = dimens.Icon, tint = tint)
        }
        if (dot != null) Box(
            Modifier.align(Alignment.TopEnd).size(dimens.Dot).drawBehind {
                val centre = Offset(size.width / 2, size.height / 2)
                drawCircle(ring, size.width / 2 + dimens.DotRing.toPx(), centre)
                drawCircle(dot, size.width / 2, centre)
            },
        )
    }
}

private fun chipIcon(chip: UpdateChipState): ImageVector = when (chip) {
    UpdateChipState.Ready, UpdateChipState.ReadyInGame -> HideoutIcons.Restart
    UpdateChipState.AfterGame -> HideoutIcons.Clock
    UpdateChipState.Failed -> HideoutIcons.Warning
    else -> HideoutIcons.Download
}

@Composable
private fun chipLabel(chip: UpdateChipState, state: AppUpdateState): String {
    val version = state.phase.version.toString()
    return when (chip) {
        UpdateChipState.Available, UpdateChipState.Seen, UpdateChipState.AvailableInGame -> stringResource(Res.string.app_update_chip_available, version)
        UpdateChipState.Downloading -> stringResource(Res.string.app_update_chip_downloading, (state.phase as? AppUpdatePhase.Downloading)?.percent ?: 0)
        UpdateChipState.Ready, UpdateChipState.ReadyInGame -> stringResource(Res.string.app_update_chip_ready, version)
        UpdateChipState.AfterGame -> stringResource(Res.string.app_update_chip_after_game, version)
        UpdateChipState.Failed -> stringResource(Res.string.app_update_chip_failed, version)
    }
}

private enum class PanelTone { Plain, Good, Bad }

@Composable
private fun UpdatePanel(chip: UpdateChipState, state: AppUpdateState, game: GameInProgress?, updates: AppUpdates, first: FocusRequester, onWhatsNew: () -> Unit) {
    LaunchedEffect(chip) { runCatching { first.requestFocus() } }
    val phase = state.phase
    val version = phase.version.toString()
    val running = state.running.toString()
    val uris = LocalUriHandler.current
    val whatsNew: @Composable () -> Unit = { PanelButton(stringResource(Res.string.app_update_whats_new), onWhatsNew, ButtonVariant.Text) }
    when (chip) {
        UpdateChipState.Available, UpdateChipState.Seen, UpdateChipState.AvailableInGame -> {
            val line = phase.pkg?.let { stringResource(Res.string.app_update_available_have, version, updateSize(it.sizeBytes), running) }
                ?: stringResource(Res.string.app_update_github_have, version, running)
            PanelHeader(HideoutIcons.Download, PanelTone.Plain, stringResource(Res.string.app_update_available), line)
            PanelActions {
                if (phase is AppUpdatePhase.GitHubOnly) PanelButton(stringResource(Res.string.app_update_github), { uris.openUri(phase.releasesUrl) }, ButtonVariant.Tonal, first, HideoutIcons.ArrowUpRight)
                else PanelButton(stringResource(Res.string.app_update_action), updates::update, ButtonVariant.Primary, first)
                whatsNew()
            }
        }
        UpdateChipState.Downloading -> {
            PanelHeader(HideoutIcons.Download, PanelTone.Plain, stringResource(Res.string.app_update_downloading_title, version), phaseLine(phase))
            (phase as? AppUpdatePhase.Downloading)?.let { downloading ->
                WavyProgressBar(downloading.percent / 100f, Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 4.dp).height(HideoutDimens.AppUpdate.ProgressHeight))
            }
            PanelActions {
                PanelButton(stringResource(Res.string.app_update_cancel), updates::cancelDownload, ButtonVariant.Text, first)
                whatsNew()
            }
        }
        UpdateChipState.Ready -> {
            val restart = (phase as? AppUpdatePhase.Ready)?.restart ?: RestartStage.Idle
            PanelHeader(HideoutIcons.Check, PanelTone.Good, stringResource(Res.string.app_update_ready), phaseLine(phase))
            PanelNote(stringResource(Res.string.app_update_restart_soon))
            if (restart != RestartStage.Installing) PanelActions {
                PanelButton(
                    stringResource(if (restart == RestartStage.Exiting) Res.string.app_update_restarting else Res.string.app_update_restart),
                    updates::restart, ButtonVariant.Primary, first, leading = HideoutIcons.Restart, enabled = restart == RestartStage.Idle,
                )
                if (restart != RestartStage.Exiting) whatsNew()
            }
        }
        UpdateChipState.ReadyInGame -> {
            PanelHeader(HideoutIcons.Check, PanelTone.Good, stringResource(Res.string.app_update_ready), phaseLine(phase))
            game?.let { GameWarning(it) }
            PanelActions {
                PanelButton(stringResource(Res.string.app_update_restart_after_game), updates::restart, ButtonVariant.Primary, first)
                whatsNew()
            }
        }
        UpdateChipState.AfterGame -> {
            PanelHeader(HideoutIcons.Clock, PanelTone.Plain, stringResource(Res.string.app_update_after_game), afterGameLine(version, game))
            PanelNote(stringResource(Res.string.app_update_after_game_note))
            PanelActions {
                PanelButton(stringResource(Res.string.app_update_restart_now), updates::restartNow, ButtonVariant.Text, first, contentColor = MaterialTheme.colorScheme.error)
                PanelButton(stringResource(Res.string.app_update_cancel), updates::cancelRestart, ButtonVariant.Text)
            }
        }
        UpdateChipState.Failed -> {
            val failed = phase as? AppUpdatePhase.Failed
            PanelHeader(HideoutIcons.Warning, PanelTone.Bad, stringResource(Res.string.app_update_failed), phaseLine(phase))
            val retryHelps = failed?.why != UpdateFailure.Unwritable && failed?.why != UpdateFailure.NotReplaced
            PanelActions {
                if (retryHelps) PanelButton(stringResource(Res.string.app_update_try_again), updates::update, ButtonVariant.Tonal, first)
                failed?.let {
                    PanelButton(
                        stringResource(Res.string.app_update_github), { uris.openUri(it.releasesUrl) }, if (retryHelps) ButtonVariant.Text else ButtonVariant.Tonal,
                        if (retryHelps) null else first, trailing = HideoutIcons.ArrowUpRight,
                    )
                }
            }
        }
    }
}

@Composable
internal fun afterGameLine(version: String, game: GameInProgress?): String =
    (game as? GameInProgress.Hosting)?.mode?.let { stringResource(Res.string.app_update_after_game_line, version, it) }
        ?: stringResource(Res.string.app_update_after_game_line_unnamed, version)

@Composable
private fun PanelHeader(icon: ImageVector, tone: PanelTone, title: String, line: String) {
    val scheme = MaterialTheme.colorScheme
    val dimens = HideoutDimens.TopBarChip
    val (container, content) = when (tone) {
        PanelTone.Plain -> scheme.surfaceContainerHigh to scheme.onSurface
        PanelTone.Good -> scheme.secondaryContainer to scheme.secondary
        PanelTone.Bad -> scheme.error.copy(alpha = FailedTileAlpha) to scheme.error
    }
    Row(horizontalArrangement = Arrangement.spacedBy(dimens.HeaderGap), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(dimens.UpdateHeaderTile).background(container, RoundedCornerShape(dimens.UpdateHeaderTileRadius)), contentAlignment = Alignment.Center) {
            HideoutIcon(icon, null, size = dimens.UpdateHeaderIcon, tint = content)
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = HideoutTheme.typography.titleUpdateCard.copy(fontSize = 15.sp, lineHeight = 19.sp), color = scheme.onSurface)
            Text(line, Modifier.padding(top = 2.dp), style = HideoutTheme.typography.caption.copy(fontSize = 12.5.sp, lineHeight = 17.sp), color = scheme.outline)
        }
    }
}

@Composable
private fun GameWarning(game: GameInProgress) {
    val scheme = MaterialTheme.colorScheme
    val caution = HideoutTheme.colors.caution
    val (lead, rest) = when (game) {
        is GameInProgress.Hosting -> {
            val lead = game.mode?.let { stringResource(Res.string.app_update_hosting_warning_title, it) } ?: stringResource(Res.string.app_update_hosting_warning_title_unnamed)
            lead to if (game.others.isEmpty()) stringResource(Res.string.app_update_hosting_warning_alone) else stringResource(Res.string.app_update_hosting_warning_others, spokenList(game.others))
        }
        is GameInProgress.Guest -> {
            val lead = game.host?.let { stringResource(Res.string.app_update_guest_warning_title, it.value) } ?: stringResource(Res.string.app_update_guest_warning_title_friend)
            lead to stringResource(Res.string.app_update_guest_warning)
        }
    }
    Row(
        Modifier.fillMaxWidth().background(caution.copy(alpha = WarningAlpha), RoundedCornerShape(HideoutDimens.TopBarChip.BoxRadius)).padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        HideoutIcon(HideoutIcons.Monitor, null, Modifier.padding(top = 1.dp), size = 16.dp, tint = caution)
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = scheme.onSurface, fontWeight = HideoutWeights.Title)) { append(lead) }
                append(" ")
                append(rest)
            },
            style = HideoutTheme.typography.caption.copy(fontSize = 12.5.sp, lineHeight = 18.sp), color = scheme.onSurfaceVariant,
        )
    }
}

internal fun spokenList(names: List<String>): String =
    if (names.size < 2) names.joinToString() else names.dropLast(1).joinToString(", ") + " and " + names.last()

@Composable
private fun PanelNote(text: String) {
    Text(text, style = HideoutTheme.typography.caption.copy(lineHeight = 17.sp), color = MaterialTheme.colorScheme.outline)
}

@Composable
private fun PanelActions(content: @Composable () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(HideoutDimens.TopBarChip.UpdateActionGap), verticalAlignment = Alignment.CenterVertically) { content() }
}

@Composable
private fun PanelButton(
    text: String,
    onClick: () -> Unit,
    variant: ButtonVariant,
    focus: FocusRequester? = null,
    trailing: ImageVector? = null,
    leading: ImageVector? = null,
    enabled: Boolean = true,
    contentColor: Color? = null,
) {
    HideoutButton(
        text, onClick, if (focus != null) Modifier.focusRequester(focus) else Modifier,
        variant = variant, size = ButtonSize.Medium, enabled = enabled, contentColor = contentColor,
        leadingIcon = leading, leadingIconSize = HideoutDimens.Icon.Small,
        trailingIcon = trailing, trailingIconSize = HideoutDimens.Icon.Small,
        contentPadding = PaddingValues(horizontal = if (variant == ButtonVariant.Text) 12.dp else 16.dp),
    )
}

private val ChipText @Composable get() = HideoutTheme.typography.labelStatus.copy(fontSize = 13.sp, lineHeight = 16.sp, fontWeight = HideoutWeights.Title)
private const val ReadyHoverMix = 0.2f
private const val FailedTileAlpha = 0.16f
private const val WarningAlpha = 0.12f
