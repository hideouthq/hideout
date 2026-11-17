package gg.hideouthq.feature.customgames

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import gg.hideouthq.core.customgames.CustomGames
import gg.hideouthq.core.customgames.EntryProblem
import gg.hideouthq.core.customgames.RestartReason
import gg.hideouthq.core.designsystem.component.ButtonSize
import gg.hideouthq.core.designsystem.component.ButtonVariant
import gg.hideouthq.core.designsystem.component.HideoutButton
import gg.hideouthq.core.designsystem.component.HideoutDivider
import gg.hideouthq.core.designsystem.component.HideoutIcon
import gg.hideouthq.core.designsystem.component.RollingText
import gg.hideouthq.core.designsystem.icon.HideoutIcons
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import gg.hideouthq.core.designsystem.theme.HideoutWeights
import gg.hideouthq.resources.*
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.time.Instant

@Composable
internal fun StatusCard(view: GameView, games: CustomGames, copy: CopyAction, onEnd: () -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val motion = HideoutTheme.motion
    val rise = with(LocalDensity.current) { motion.enterOffset.roundToPx() }
    AnimatedContent(
        view.body,
        modifier.background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(28.dp)),
        contentKey = { it::class },
        transitionSpec = {
            (fadeIn(motion.enterFade()) + slideInVertically(motion.spatialDefault()) { rise }) togetherWith fadeOut(motion.exitFade()) using
                SizeTransform(clip = false) { _, _ -> motion.spatialDefault() }
        },
        label = "statusCard",
    ) { body ->
        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            StatusContent(body, view, games, copy, onEnd, onBack)
        }
    }
}

@Composable
private fun ColumnScope.StatusContent(body: Body, view: GameView, games: CustomGames, copy: CopyAction, onEnd: () -> Unit, onBack: () -> Unit) {
    val host = view.host == HostedBy.ThisPc
    val hostName = (view.host as? HostedBy.Friend)?.name
    val steam = LocalUriHandler.current
    val openSteam = { steam.openUri(SteamUri) }
    when (body) {
        is Body.Starting -> {
            Heading(HideoutIcons.Gamepad, Tone.Starting, stringResource(Res.string.cg_starting_title), stringResource(Res.string.cg_starting_body))
            StepList(body.steps, view.face.name(), hostName)
            if (body.firewallHint) HintBox(HideoutIcons.Info, stringResource(Res.string.cg_firewall_hint))
        }
        is Body.RestartNeeded -> {
            Heading(HideoutIcons.Refresh, Tone.Warn, stringResource(Res.string.cg_restart_title), restartWhy(body.why))
            Buttons {
                Primary(stringResource(Res.string.cg_restart_button), games::restartGame, HideoutIcons.Refresh)
                Tonal(stringResource(Res.string.cg_cancel), games::stop)
            }
        }
        is Body.Restarting -> if (body.closeFailed) {
            Heading(HideoutIcons.Monitor, Tone.Warn, stringResource(Res.string.cg_restart_close_title), stringResource(Res.string.cg_restart_close_body))
        } else {
            Heading(HideoutIcons.Refresh, Tone.Warn, stringResource(Res.string.cg_restarting_title), stringResource(Res.string.cg_restarting_body), busy = true)
        }
        is Body.Open -> {
            val waiting = body.friends == 0
            Heading(
                HideoutIcons.Users, Tone.Ready,
                stringResource(if (waiting) Res.string.cg_ready_title else Res.string.cg_room_title),
                stringResource(if (waiting) Res.string.cg_ready_body else Res.string.cg_room_body),
            )
            HideoutDivider()
            OkLine(stringResource(Res.string.cg_ready_ok))
        }
        is Body.Match -> {
            if (body.sections.isEmpty()) {
                Heading(
                    HideoutIcons.Users, Tone.Match,
                    stringResource(if (host) Res.string.cg_match_title else Res.string.cg_match_guest_title),
                    stringResource(if (host) Res.string.cg_match_body else Res.string.cg_match_guest_body),
                )
            }
            body.sections.forEach { Section(it) }
        }
        is Body.Entering -> if (body.joining) {
            Heading(HideoutIcons.Gamepad, Tone.Starting, stringResource(Res.string.cg_joining_title), stringResource(Res.string.cg_joining_body), busy = true)
        } else {
            Heading(HideoutIcons.Gamepad, Tone.Starting, stringResource(Res.string.cg_sending_title), stringResource(Res.string.cg_sending_body), busy = true)
        }
        is Body.NotIn -> {
            val words = notInCopy(body.problem, body.canPlay, host)
            Heading(words.icon, Tone.Warn, words.title, words.body)
            Buttons {
                when (notInAction(body.problem, body.canPlay)) {
                    NotInAction.Play -> Primary(stringResource(Res.string.cg_play), games::play, HideoutIcons.Play)
                    NotInAction.OpenSteam -> Primary(stringResource(Res.string.cg_open_steam), openSteam, HideoutIcons.OpenExternal)
                    null -> Unit
                }
                if (host) EndGame(onEnd) else Tonal(stringResource(Res.string.cg_leave_game), games::stop)
            }
        }
        is Body.Ending -> Ending(body, copy, games)
        is Body.Ended -> Ended(body, view, games, onBack)
    }
}

@Composable
private fun Ending(body: Body.Ending, copy: CopyAction, games: CustomGames) {
    val title = when (body.of) {
        EndingOf.YourGame -> Res.string.cg_ending_host_title
        EndingOf.YourGameStopped -> Res.string.cg_ending_stopped_title
        EndingOf.YouLeft -> Res.string.cg_ending_guest_title
        EndingOf.FriendsGame -> Res.string.cg_ending_over_title
        EndingOf.LastGame -> Res.string.cg_ending_last_title
    }
    val host = body.of == EndingOf.YourGame || body.of == EndingOf.YourGameStopped
    when (val stage = body.stage) {
        EndingStage.Stopping -> Heading(
            HideoutIcons.Stop, Tone.Starting, stringResource(title),
            stringResource(if (host) Res.string.cg_ending_stopping_host else Res.string.cg_ending_stopping_guest), busy = true,
        )
        EndingStage.PuttingFilesBack -> Heading(HideoutIcons.Stop, Tone.Starting, stringResource(title), stringResource(Res.string.cg_ending_files), busy = true)
        is EndingStage.Failed -> {
            Heading(HideoutIcons.Warning, Tone.Fail, stringResource(title), stringResource(Res.string.cg_ending_failed))
            Buttons {
                Primary(stringResource(Res.string.cg_try_again), games::stop, HideoutIcons.Refresh)
                CopyDetails(stage.details, copy)
            }
        }
    }
}

@Composable
private fun Ended(body: Body.Ended, view: GameView, games: CustomGames, onBack: () -> Unit) {
    val host = view.host == HostedBy.ThisPc
    val hostName = (view.host as? HostedBy.Friend)?.name
    val back: @Composable () -> Unit = { Tonal(stringResource(Res.string.cg_back_to_library), onBack) }
    val hostAgain: @Composable () -> Unit = { Primary(stringResource(Res.string.cg_host_again), games::again, HideoutIcons.Refresh) }
    val joinAgain: @Composable () -> Unit = { Primary(stringResource(Res.string.cg_join_again), games::again, HideoutIcons.Refresh) }
    when (val outcome = body.outcome) {
        is Outcome.YouEnded -> {
            val at = outcome.at
            val lasted = outcome.lasted
            val ended = if (at != null && lasted != null) {
                val minutes = lasted.inWholeMinutes.toInt().coerceAtLeast(1)
                pluralStringResource(Res.plurals.cg_ended_you_after, minutes, clockTime(at), minutes)
            } else {
                stringResource(Res.string.cg_ended_you)
            }
            val sentBack = when (outcome.sentBack.size) {
                0 -> null
                1 -> stringResource(Res.string.cg_ended_sent_back, outcome.sentBack.single())
                else -> stringResource(Res.string.cg_ended_sent_back_many)
            }
            Heading(HideoutIcons.Stop, Tone.Ended, stringResource(Res.string.cg_ended_title), listOfNotNull(ended, sentBack).joinToString(" "))
            Buttons {
                hostAgain()
                back()
            }
        }
        Outcome.GameStopped -> {
            Heading(HideoutIcons.Warning, Tone.Fail, stringResource(Res.string.cg_ended_stopped_title), stringResource(Res.string.cg_ended_stopped_body))
            Buttons { hostAgain() }
        }
        Outcome.DeadlockUpdated -> {
            Heading(
                HideoutIcons.SwapVersions, Tone.Warn, stringResource(Res.string.cg_ended_updated_title),
                stringResource(if (host) Res.string.cg_ended_updated_body else Res.string.cg_ended_updated_guest_body),
            )
            Buttons {
                if (host) hostAgain()
                back()
            }
        }
        Outcome.FriendEnded -> {
            Heading(HideoutIcons.Stop, Tone.Ended, named(hostName, Res.string.cg_friend_ended_title, Res.string.cg_friend_ended_title_friend), stringResource(Res.string.cg_ended_friend_body))
            Buttons { back() }
        }
        Outcome.FriendsGameStopped -> {
            Heading(HideoutIcons.Warning, Tone.Fail, named(hostName, Res.string.cg_ended_friend_stopped_title, Res.string.cg_ended_friend_stopped_title_friend), stringResource(Res.string.cg_ended_friend_stopped_body))
            Buttons { back() }
        }
        Outcome.ConnectionLost -> {
            Heading(HideoutIcons.WifiOff, Tone.Ended, named(hostName, Res.string.cg_ended_lost_title, Res.string.cg_ended_lost_title_friend), stringResource(Res.string.cg_ended_over_body))
            Buttons {
                joinAgain()
                back()
            }
        }
        Outcome.UnknownEnd -> {
            Heading(HideoutIcons.Stop, Tone.Ended, stringResource(Res.string.cg_ended_over_title), stringResource(Res.string.cg_ended_over_body))
            Buttons {
                joinAgain()
                back()
            }
        }
        Outcome.YouLeft -> {
            Heading(HideoutIcons.Stop, Tone.Ended, stringResource(Res.string.cg_ended_left_title), stringResource(Res.string.cg_ended_left_body))
            Buttons { back() }
        }
        Outcome.DeadlockClosed -> {
            Heading(HideoutIcons.Monitor, Tone.Ended, stringResource(Res.string.cg_ended_closed_title), stringResource(Res.string.cg_ended_closed_body))
            Buttons {
                joinAgain()
                back()
            }
        }
        is Outcome.CouldNotStart -> {
            val problem = outcome.problem
            val words = problemCopy(problem, host = true, hostName = null, mode = view.face.name())
            Heading(problemIcon(problem), Tone.Fail, words.title, words.body)
            if (tryAgainHelps(problem)) Buttons { Primary(stringResource(Res.string.cg_try_again), games::again, HideoutIcons.Refresh) }
        }
    }
}

@Composable
private fun Section(section: SectionView) {
    when (section) {
        is SectionView.Scoreboard -> if (section.sides.size == 2) FaceOff(section) else Ranking(section)
    }
}

@Composable
private fun FaceOff(board: SectionView.Scoreboard) {
    val (left, right) = yoursFirst(board.sides)
    val hostSide = board.sides.firstOrNull { side -> side.people.any { it.host } } ?: board.sides.first()
    val amber = HideoutTheme.colors.caution
    val blue = HideoutTheme.colors.match
    fun color(side: BoardSide) = if (side === hostSide) amber else blue
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Avatar(left.people.firstOrNull()?.name, left === hostSide, size = 52.dp)
            SideName(left, Modifier.weight(1f), TextAlign.Start)
            BigScore(left.score, color(left))
        }
        Notes(board.notes, Alignment.CenterHorizontally)
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            BigScore(right.score, color(right))
            SideName(right, Modifier.weight(1f), TextAlign.End)
            Avatar(right.people.firstOrNull()?.name, right === hostSide, size = 52.dp)
        }
    }
}

@Composable
private fun Ranking(board: SectionView.Scoreboard) {
    val scheme = MaterialTheme.colorScheme
    Notes(board.notes, Alignment.Start)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        board.sides.sortedByDescending { it.score }.forEachIndexed { index, side ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).background(scheme.surfaceContainerHigh, RoundedCornerShape(18.dp)).padding(horizontal = 14.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("${index + 1}", style = HideoutTheme.typography.bodyStrong, color = scheme.outline)
                Avatar(side.people.firstOrNull()?.name, side.people.any { it.host })
                SideName(side, Modifier.weight(1f), TextAlign.Start)
                RollingText(side.score.toString(), HideoutTheme.typography.titleSection.copy(fontFeatureSettings = "tnum"), color = scheme.onSurface)
            }
        }
    }
}

@Composable
private fun SideName(side: BoardSide, modifier: Modifier, align: TextAlign) {
    val type = HideoutTheme.typography
    val you = stringResource(Res.string.cg_you)
    Column(modifier) {
        val names = side.people.joinToString(", ") { if (it.you) you else it.name ?: "" }.ifBlank { null }
        names?.let { Text(it, Modifier.fillMaxWidth(), style = type.body.copy(fontSize = 15.sp, fontWeight = HideoutWeights.Title), color = MaterialTheme.colorScheme.onSurface, textAlign = align, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        if (side.people.any { it.host }) Text(stringResource(Res.string.cg_tag_host), Modifier.fillMaxWidth(), style = type.caption.copy(fontWeight = HideoutWeights.Medium), color = MaterialTheme.colorScheme.outline, textAlign = align)
    }
}

@Composable
private fun BigScore(score: Int, color: Color) {
    RollingText(score.toString(), HideoutTheme.typography.displayHero.copy(fontSize = 56.sp, lineHeight = 60.sp, letterSpacing = (-0.03).em, fontFeatureSettings = "tnum"), color = color)
}

@Composable
private fun Notes(notes: List<String>, align: Alignment.Horizontal) {
    if (notes.isEmpty()) return
    Column(horizontalAlignment = align) {
        Text(notes.first(), style = HideoutTheme.typography.captionSmall.copy(fontWeight = HideoutWeights.Title), color = MaterialTheme.colorScheme.onSurfaceVariant)
        notes.drop(1).forEach { Text(it, style = HideoutTheme.typography.caption, color = MaterialTheme.colorScheme.outline) }
    }
}

@Composable
private fun Heading(icon: ImageVector, tone: Tone, title: String, body: String, busy: Boolean = false) {
    val scheme = MaterialTheme.colorScheme
    val type = HideoutTheme.typography
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        if (busy) {
            val colors = animatedTone(tone)
            Box(Modifier.size(52.dp).background(colors.container, RoundedCornerShape(18.dp)), contentAlignment = Alignment.Center) { Spinner(colors.content, 22.dp, 2.5.dp) }
        } else {
            IconBox(icon, tone, 52.dp, 18.dp, 24.dp)
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = type.titleDialog.copy(lineHeight = 26.4.sp), color = scheme.onSurface)
            Text(body, Modifier.padding(top = 4.dp).widthIn(max = 560.dp), style = type.body.copy(lineHeight = 21.sp), color = scheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun OkLine(text: String) {
    val color = MaterialTheme.colorScheme.secondary
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        HideoutIcon(HideoutIcons.CheckBold, null, size = 15.dp, tint = color)
        Text(text, style = HideoutTheme.typography.subtitle.copy(fontWeight = HideoutWeights.Label), color = color)
    }
}

@Composable
private fun Buttons(content: @Composable () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { content() }
}

@Composable
private fun Primary(text: String, onClick: () -> Unit, icon: ImageVector? = null) =
    HideoutButton(text, onClick, leadingIcon = icon, leadingIconSize = 16.dp)

@Composable
private fun Tonal(text: String, onClick: () -> Unit, icon: ImageVector? = null) =
    HideoutButton(text, onClick, variant = ButtonVariant.Tonal, leadingIcon = icon, leadingIconSize = 16.dp)

@Composable
private fun EndGame(onEnd: () -> Unit) =
    HideoutButton(stringResource(Res.string.cg_end_game), onEnd, variant = ButtonVariant.Text, size = ButtonSize.Small, contentColor = MaterialTheme.colorScheme.error)

@Composable
internal fun CopyDetails(details: String, copy: CopyAction) =
    HideoutButton(stringResource(Res.string.cg_copy_details), { copy(details, Res.string.cg_toast_details_copied) }, variant = ButtonVariant.Text, size = ButtonSize.Small, leadingIcon = HideoutIcons.Copy, leadingIconSize = 16.dp)

private fun clockTime(at: Instant): String =
    DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).format(java.time.Instant.ofEpochMilli(at.toEpochMilliseconds()).atZone(ZoneId.systemDefault()))

internal const val SteamUri = "steam://open/main"

@Composable
internal fun restartWhy(why: RestartReason): String {
    val reason = when (why) {
        RestartReason.NotSetUp -> Res.string.cg_restart_not_set_up
        RestartReason.GameUpdated -> Res.string.cg_restart_game_updated
        RestartReason.MenusDidNotStart -> Res.string.cg_restart_menus
    }
    return stringResource(reason) + " " + stringResource(Res.string.cg_restart_queue)
}

@Composable
internal fun restartAhead(why: RestartReason, host: Boolean): String {
    val reason = when (why) {
        RestartReason.NotSetUp -> Res.string.cg_restarts_not_set_up
        RestartReason.GameUpdated -> Res.string.cg_restarts_game_updated
        RestartReason.MenusDidNotStart -> Res.string.cg_restarts_menus
    }
    return stringResource(if (host) Res.string.cg_host_restarts else Res.string.cg_join_restarts) + " " + stringResource(reason) + " " + stringResource(Res.string.cg_restart_queue)
}

internal data class NotInCopy(val icon: ImageVector, val title: String, val body: String)

@Composable
internal fun notInCopy(problem: EntryProblem?, canPlay: Boolean, host: Boolean): NotInCopy {
    if (!canPlay) return NotInCopy(HideoutIcons.WifiOff, stringResource(Res.string.cg_dropped_silent_title), stringResource(Res.string.cg_dropped_silent_body))
    val (icon, title, body) = when (problem) {
        null -> Triple(HideoutIcons.Monitor, Res.string.cg_not_in_title, if (host) Res.string.cg_not_in_body else Res.string.cg_not_in_guest_body)
        EntryProblem.Dropped -> Triple(HideoutIcons.WifiOff, Res.string.cg_dropped_title, Res.string.cg_dropped_body)
        EntryProblem.MenusDidNotStart -> Triple(HideoutIcons.Monitor, Res.string.cg_menus_failed_title, if (host) Res.string.cg_menus_failed_host_body else Res.string.cg_menus_failed_guest_body)
        EntryProblem.SiteUnreachable -> Triple(HideoutIcons.WifiOff, Res.string.cg_site_unreachable_title, Res.string.cg_site_unreachable_body)
        EntryProblem.ClientDidNotStart, is EntryProblem.Launch -> Triple(HideoutIcons.Monitor, Res.string.cg_did_not_open_title, Res.string.cg_did_not_open_body)
        EntryProblem.GameUpdatePending -> Triple(HideoutIcons.SwapVersions, Res.string.cg_steam_update_title, Res.string.cg_steam_update_body)
        EntryProblem.DidNotJoin -> Triple(HideoutIcons.Monitor, Res.string.cg_did_not_join_title, Res.string.cg_did_not_join_body)
    }
    return NotInCopy(icon, stringResource(title), stringResource(body))
}

internal enum class NotInAction { Play, OpenSteam }

internal fun notInAction(problem: EntryProblem?, canPlay: Boolean): NotInAction? = when {
    !canPlay || problem == EntryProblem.MenusDidNotStart -> null
    problem == EntryProblem.GameUpdatePending -> NotInAction.OpenSteam
    else -> NotInAction.Play
}
