package gg.hideouthq.feature.customgames

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import gg.hideouthq.core.customgames.CustomGames
import gg.hideouthq.core.customgames.Role
import gg.hideouthq.core.customgames.SiteOrigin
import gg.hideouthq.core.designsystem.component.BackLink
import gg.hideouthq.core.designsystem.component.ButtonSize
import gg.hideouthq.core.designsystem.component.ButtonVariant
import gg.hideouthq.core.designsystem.component.EmptyState
import gg.hideouthq.core.designsystem.component.HideoutButton
import gg.hideouthq.core.designsystem.component.HideoutIcon
import gg.hideouthq.core.designsystem.component.ScrollColumn
import gg.hideouthq.core.designsystem.component.isScrolled
import gg.hideouthq.core.designsystem.icon.HideoutIcons
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import gg.hideouthq.core.designsystem.theme.HideoutWeights
import gg.hideouthq.core.ui.MainAndSide
import gg.hideouthq.core.ui.PaneScaffold
import gg.hideouthq.core.ui.SearchTopBar
import gg.hideouthq.core.ui.WindowModal
import gg.hideouthq.core.ui.transition.staggeredEnter
import gg.hideouthq.resources.*
import org.jetbrains.compose.resources.stringResource

@Composable
fun CustomGameRoute(games: CustomGames, site: SiteOrigin?, hideoutVersion: String, modal: WindowModal, onLibrary: () -> Unit, onOpenMode: (String) -> Unit, onSearch: (String) -> Unit) {
    val present = rememberPresent(games, site)
    val live = present.game
    val record by games.live.collectAsStateWithLifecycle()
    val last = rememberLast(live)
    var leaving by remember { mutableStateOf(false) }
    val view = live ?: last.takeIf { leaving || present.join != null || record.game?.let { it.role is Role.Host || it.started } == true }
    val toasts = rememberCgToasts()
    val copy = rememberCopy(toasts)
    var confirming by remember { mutableStateOf(false) }
    val back = {
        leaving = true
        games.dismissEnded()
        onLibrary()
    }
    val end = { if (view?.endCutsOff.isNullOrEmpty()) games.stop() else confirming = true }
    val scroll = rememberScrollState()
    PaneScaffold(
        topBar = { SearchTopBar(query = "", onQueryChange = onSearch, placeholder = stringResource(Res.string.search_catalogue)) },
        scrolled = scroll.isScrolled,
        staggered = true,
    ) {
        Box(Modifier.fillMaxSize()) {
            if (view == null) {
                EmptyState(HideoutIcons.Gamepad, stringResource(Res.string.cg_nothing_running_title), stringResource(Res.string.cg_nothing_running_body), Modifier.fillMaxSize()) {
                    HideoutButton(stringResource(Res.string.cg_back_to_library), onLibrary, variant = ButtonVariant.Tonal)
                }
            } else {
                ScrollColumn(Modifier.fillMaxSize(), scroll, PaddingValues(start = 24.dp, end = 24.dp, top = 2.dp, bottom = 28.dp), Arrangement.spacedBy(20.dp)) {
                    Breadcrumb(view.face.name(), onLibrary, Modifier.staggeredEnter(0))
                    GameHeader(view, Modifier.staggeredEnter(1))
                    GameBody(view, games, copy, end, back, hideoutVersion, onOpenMode, onLibrary, Modifier.staggeredEnter(2))
                }
            }
            CgToastHost(toasts)
        }
    }
    val cutOff = view?.endCutsOff.orEmpty()
    if (cutOff.isEmpty()) confirming = false
    val words = if (confirming) ConfirmWords(
        title = stringResource(Res.string.cg_end_confirm_title),
        body = cutOff.singleOrNull()?.let { stringResource(Res.string.cg_end_confirm_body, it) } ?: stringResource(Res.string.cg_end_confirm_body_many),
        keep = stringResource(Res.string.cg_keep_playing),
        confirm = stringResource(Res.string.cg_end_game),
    ) else null
    ConfirmDialog(words, modal, onKeep = { confirming = false }, onConfirm = {
        confirming = false
        games.stop()
    })
}

@Composable
private fun Breadcrumb(mode: String, onLibrary: () -> Unit, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    Row(modifier.fillMaxWidth().height(44.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        BackLink(stringResource(Res.string.nav_custom_games), onLibrary)
        Text("/", style = HideoutTheme.typography.captionSmall, color = scheme.outline)
        Text(mode, style = HideoutTheme.typography.captionSmall, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun GameHeader(view: GameView, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    val type = HideoutTheme.typography
    val hosted = when (val host = view.host) {
        HostedBy.ThisPc -> stringResource(if (view.solo) Res.string.cg_on_this_pc else Res.string.cg_hosted_here)
        is HostedBy.Friend -> named(host.name, Res.string.cg_hosted_by, Res.string.cg_hosted_by_friend)
        null -> null
    }
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        ModeTile(view.face, 56.dp, 16.dp)
        Column(Modifier.weight(1f)) {
            Row(Modifier.padding(bottom = 3.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                HideoutIcon(HideoutIcons.Monitor, null, size = 14.dp, tint = scheme.outline)
                Text((listOfNotNull(hosted) + view.face.picks).joinToString(" · "), style = type.subtitle.copy(fontWeight = HideoutWeights.Label), color = scheme.outline, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(view.face.name(), style = type.headlinePage.copy(fontSize = 28.sp, lineHeight = 31.sp, letterSpacing = (-0.02).em), color = scheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        StatusPill(view.status)
    }
}

private enum class GameLayout { Live, Result, Ending }

@Composable
private fun GameBody(
    view: GameView, games: CustomGames, copy: CopyAction, onEnd: () -> Unit, onBack: () -> Unit,
    hideoutVersion: String, onOpenMode: (String) -> Unit, onLibrary: () -> Unit, modifier: Modifier,
) {
    val motion = HideoutTheme.motion
    AnimatedContent(
        view,
        modifier,
        contentKey = {
            when {
                it.players != null -> GameLayout.Live
                it.body is Body.Ended -> GameLayout.Result
                else -> GameLayout.Ending
            }
        },
        transitionSpec = { (fadeIn(motion.sceneFade()) togetherWith fadeOut(motion.sceneFade())).using(null) },
        label = "gameLayout",
    ) { shown ->
        val players = shown.players
        val body = shown.body
        if (body is Body.Ended) {
            MainAndSide(
                360.dp,
                main = { inner ->
                    Column(inner, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        StatusCard(shown, games, copy, onEnd, onBack, Modifier.fillMaxWidth())
                        body.lastMatch?.let { LastMatchCard(it, Modifier.fillMaxWidth()) }
                        (body.outcome as? Outcome.CouldNotStart)?.trace?.let { WhatHappenedCard(it, shown.face, Modifier.fillMaxWidth()) }
                    }
                },
                side = { inner ->
                    Column(inner, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        when (val side = body.side) {
                            is EndedSide.PlaySomethingElse -> if (side.modes.isNotEmpty()) PlaySomethingElseCard(side.modes, onOpenMode, onLibrary, Modifier.fillMaxWidth())
                            is EndedSide.StillNotWorking -> StillNotWorkingCard("${side.details}\nHideout $hideoutVersion", copy, Modifier.fillMaxWidth())
                        }
                        if (body.outcome is Outcome.CouldNotStart) NobodyWaitingNote(Modifier.fillMaxWidth())
                    }
                },
            )
        } else if (players == null) {
            StatusCard(shown, games, copy, onEnd, onBack, Modifier.widthIn(max = 760.dp).fillMaxWidth())
        } else {
            MainAndSide(
                360.dp,
                main = { inner ->
                    Column(inner, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        StatusCard(shown, games, copy, onEnd, onBack, Modifier.fillMaxWidth())
                        if (!shown.solo) PlayersCard(players, Modifier.fillMaxWidth())
                    }
                },
                side = { inner ->
                    Column(inner, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        if (shown.host == HostedBy.ThisPc) shown.invite?.let { InviteCard(it, shown.face.name(), copy, Modifier.fillMaxWidth()) }
                        shown.host?.let { HostNote(it, shown.solo, onEnd, games::stop) }
                    }
                },
            )
        }
    }
}

@Composable
private fun HostNote(host: HostedBy, solo: Boolean, onEnd: () -> Unit, onLeave: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val type = HideoutTheme.typography
    val title = when (host) {
        HostedBy.ThisPc -> stringResource(Res.string.cg_note_host_title)
        is HostedBy.Friend -> named(host.name, Res.string.cg_note_guest_title, Res.string.cg_note_guest_title_friend)
    }
    val body = stringResource(
        when {
            host != HostedBy.ThisPc -> Res.string.cg_note_guest_body
            solo -> Res.string.cg_note_solo_body
            else -> Res.string.cg_note_host_body
        },
    )
    val (action, onAction) = if (host == HostedBy.ThisPc) stringResource(Res.string.cg_end_game) to onEnd else stringResource(Res.string.cg_leave_game) to onLeave
    CgCard(Modifier.fillMaxWidth(), padding = 16.dp, radius = 20.dp, gap = 0.dp) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconBox(HideoutIcons.Monitor, Tone.Starting, 34.dp, 11.dp, 18.dp)
            Column(Modifier.weight(1f)) {
                Text(title, Modifier.padding(bottom = 3.dp), style = type.body.copy(fontWeight = HideoutWeights.Title), color = scheme.onSurface)
                Text(body, style = type.subtitle.copy(lineHeight = 18.75.sp), color = scheme.onSurfaceVariant)
                HideoutButton(action, onAction, Modifier.padding(top = 8.dp).offset(x = (-12).dp), variant = ButtonVariant.Text, size = ButtonSize.Small, contentColor = scheme.error)
            }
        }
    }
}
