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
import gg.hideouthq.core.customgames.DisplayName
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
    headlineOf(body, view)?.let { Heading(it.icon, it.tone, it.title.text(), it.body.text(), it.busy) }
    when (body) {
        is Body.Starting -> {
            StepList(body.steps, view.face.namePhrase, hostName)
            if (body.firewallHint) HintBox(HideoutIcons.Info, stringResource(Res.string.cg_firewall_hint))
        }
        is Body.RestartNeeded -> Buttons {
            Primary(stringResource(Res.string.cg_restart_button), games::restartGame, HideoutIcons.Refresh)
            Tonal(stringResource(Res.string.cg_cancel), games::stop)
        }
        is Body.Restarting -> Unit
        is Body.Open -> {
            HideoutDivider()
            OkLine(stringResource(Res.string.cg_ready_ok))
        }
        is Body.Match -> body.sections.forEach { Section(it) }
        is Body.Entering -> Unit
        is Body.NotIn -> Buttons {
            when (notInAction(body.problem, body.canPlay)) {
                NotInAction.Play -> Primary(stringResource(Res.string.cg_play), games::play, HideoutIcons.Play)
                NotInAction.OpenSteam -> Primary(stringResource(Res.string.cg_open_steam), openSteam, HideoutIcons.OpenExternal)
                null -> Unit
            }
            if (host) EndGame(onEnd) else Tonal(stringResource(Res.string.cg_leave_game), games::stop)
        }
        is Body.Ending -> (body.stage as? EndingStage.Failed)?.let { failed ->
            Buttons {
                Primary(stringResource(Res.string.cg_try_again), games::stop, HideoutIcons.Refresh)
                CopyDetails(failed.details, copy)
            }
        }
        is Body.Ended -> EndedButtons(body, host, games, onBack)
    }
}

data class Headline(val icon: ImageVector, val tone: Tone, val title: Phrase, val body: Phrase, val busy: Boolean = false)

fun headlineOf(body: Body, view: GameView): Headline? {
    val host = view.host == HostedBy.ThisPc
    val hostName = (view.host as? HostedBy.Friend)?.name
    return when (body) {
        is Body.Starting -> Headline(HideoutIcons.Gamepad, Tone.Starting, phrase(Res.string.cg_starting_title), phrase(Res.string.cg_starting_body))
        is Body.RestartNeeded -> Headline(HideoutIcons.Refresh, Tone.Warn, phrase(Res.string.cg_restart_title), restartWhyPhrase(body.why))
        is Body.Restarting -> if (body.closeFailed) {
            Headline(HideoutIcons.Monitor, Tone.Warn, phrase(Res.string.cg_restart_close_title), phrase(Res.string.cg_restart_close_body))
        } else {
            Headline(HideoutIcons.Refresh, Tone.Warn, phrase(Res.string.cg_restarting_title), phrase(Res.string.cg_restarting_body), busy = true)
        }
        is Body.Open -> {
            val waiting = body.friends == 0
            Headline(
                HideoutIcons.Users, Tone.Ready,
                phrase(if (waiting) Res.string.cg_ready_title else Res.string.cg_room_title),
                phrase(if (waiting) Res.string.cg_ready_body else Res.string.cg_room_body),
            )
        }
        is Body.Match -> if (body.sections.isEmpty()) {
            Headline(
                HideoutIcons.Users, Tone.Match,
                phrase(if (host) Res.string.cg_match_title else Res.string.cg_match_guest_title),
                phrase(if (host) Res.string.cg_match_body else Res.string.cg_match_guest_body),
            )
        } else {
            null
        }
        is Body.Entering -> if (body.joining) {
            Headline(HideoutIcons.Gamepad, Tone.Starting, phrase(Res.string.cg_joining_title), phrase(Res.string.cg_joining_body), busy = true)
        } else {
            Headline(HideoutIcons.Gamepad, Tone.Starting, phrase(Res.string.cg_sending_title), phrase(Res.string.cg_sending_body), busy = true)
        }
        is Body.NotIn -> notInHeadline(body.problem, body.canPlay, host)
        is Body.Ending -> endingHeadline(body)
        is Body.Ended -> endedHeadline(body.outcome, host, hostName, view.face)
    }
}

private fun endingHeadline(body: Body.Ending): Headline {
    val title = phrase(
        when (body.of) {
            EndingOf.YourGame -> Res.string.cg_ending_host_title
            EndingOf.YourGameStopped -> Res.string.cg_ending_stopped_title
            EndingOf.YouLeft -> Res.string.cg_ending_guest_title
            EndingOf.FriendsGame -> Res.string.cg_ending_over_title
            EndingOf.LastGame -> Res.string.cg_ending_last_title
        },
    )
    val host = body.of == EndingOf.YourGame || body.of == EndingOf.YourGameStopped
    return when (body.stage) {
        EndingStage.Stopping -> Headline(HideoutIcons.Stop, Tone.Starting, title, phrase(if (host) Res.string.cg_ending_stopping_host else Res.string.cg_ending_stopping_guest), busy = true)
        EndingStage.PuttingFilesBack -> Headline(HideoutIcons.Stop, Tone.Starting, title, phrase(Res.string.cg_ending_files), busy = true)
        is EndingStage.Failed -> Headline(HideoutIcons.Warning, Tone.Fail, title, phrase(Res.string.cg_ending_failed))
    }
}

private fun endedHeadline(outcome: Outcome, host: Boolean, hostName: DisplayName?, face: ModeFace): Headline = when (outcome) {
    is Outcome.YouEnded -> {
        val at = outcome.at
        val lasted = outcome.lasted
        val ended = if (at != null && lasted != null) {
            val minutes = lasted.inWholeMinutes.toInt().coerceAtLeast(1)
            Phrase.Count(Res.plurals.cg_ended_you_after, minutes, listOf(clockTime(at), minutes))
        } else {
            phrase(Res.string.cg_ended_you)
        }
        val sentBack = when (outcome.sentBack.size) {
            0 -> null
            1 -> phrase(Res.string.cg_ended_sent_back, outcome.sentBack.single())
            else -> phrase(Res.string.cg_ended_sent_back_many)
        }
        Headline(HideoutIcons.Stop, Tone.Ended, phrase(Res.string.cg_ended_title), Phrase.Joined(listOfNotNull(ended, sentBack)))
    }
    Outcome.GameStopped -> Headline(HideoutIcons.Warning, Tone.Fail, phrase(Res.string.cg_ended_stopped_title), phrase(Res.string.cg_ended_stopped_body))
    Outcome.DeadlockUpdated -> Headline(
        HideoutIcons.SwapVersions, Tone.Warn, phrase(Res.string.cg_ended_updated_title),
        phrase(if (host) Res.string.cg_ended_updated_body else Res.string.cg_ended_updated_guest_body),
    )
    Outcome.FriendEnded -> Headline(HideoutIcons.Stop, Tone.Ended, namedPhrase(hostName, Res.string.cg_friend_ended_title, Res.string.cg_friend_ended_title_friend), phrase(Res.string.cg_ended_friend_body))
    Outcome.FriendsGameStopped -> Headline(
        HideoutIcons.Warning, Tone.Fail, namedPhrase(hostName, Res.string.cg_ended_friend_stopped_title, Res.string.cg_ended_friend_stopped_title_friend),
        phrase(Res.string.cg_ended_friend_stopped_body),
    )
    Outcome.ConnectionLost -> Headline(HideoutIcons.WifiOff, Tone.Ended, namedPhrase(hostName, Res.string.cg_ended_lost_title, Res.string.cg_ended_lost_title_friend), phrase(Res.string.cg_ended_over_body))
    Outcome.UnknownEnd -> Headline(HideoutIcons.Stop, Tone.Ended, phrase(Res.string.cg_ended_over_title), phrase(Res.string.cg_ended_over_body))
    Outcome.YouLeft -> Headline(HideoutIcons.Stop, Tone.Ended, phrase(Res.string.cg_ended_left_title), phrase(Res.string.cg_ended_left_body))
    Outcome.DeadlockClosed -> Headline(HideoutIcons.Monitor, Tone.Ended, phrase(Res.string.cg_ended_closed_title), phrase(Res.string.cg_ended_closed_body))
    is Outcome.CouldNotStart -> problemWords(outcome.problem, host = true, hostName = null, mode = face.namePhrase).let { (title, body) ->
        Headline(problemIcon(outcome.problem), Tone.Fail, title, body)
    }
}

@Composable
private fun EndedButtons(body: Body.Ended, host: Boolean, games: CustomGames, onBack: () -> Unit) {
    val back: @Composable () -> Unit = { Tonal(stringResource(Res.string.cg_back_to_library), onBack) }
    val hostAgain: @Composable () -> Unit = { Primary(stringResource(Res.string.cg_host_again), games::again, HideoutIcons.Refresh) }
    val joinAgain: @Composable () -> Unit = { Primary(stringResource(Res.string.cg_join_again), games::again, HideoutIcons.Refresh) }
    when (val outcome = body.outcome) {
        is Outcome.YouEnded -> Buttons {
            hostAgain()
            back()
        }
        Outcome.GameStopped -> Buttons { hostAgain() }
        Outcome.DeadlockUpdated -> Buttons {
            if (host) hostAgain()
            back()
        }
        Outcome.FriendEnded, Outcome.FriendsGameStopped, Outcome.YouLeft -> Buttons { back() }
        Outcome.ConnectionLost, Outcome.UnknownEnd, Outcome.DeadlockClosed -> Buttons {
            joinAgain()
            back()
        }
        is Outcome.CouldNotStart -> if (tryAgainHelps(outcome.problem)) Buttons { Primary(stringResource(Res.string.cg_try_again), games::again, HideoutIcons.Refresh) }
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

fun restartWhyPhrase(why: RestartReason): Phrase {
    val reason = when (why) {
        RestartReason.NotSetUp -> Res.string.cg_restart_not_set_up
        RestartReason.GameUpdated -> Res.string.cg_restart_game_updated
        RestartReason.MenusDidNotStart -> Res.string.cg_restart_menus
    }
    return Phrase.Joined(listOf(phrase(reason), phrase(Res.string.cg_restart_queue)))
}

fun restartAheadPhrase(why: RestartReason, host: Boolean): Phrase {
    val reason = when (why) {
        RestartReason.NotSetUp -> Res.string.cg_restarts_not_set_up
        RestartReason.GameUpdated -> Res.string.cg_restarts_game_updated
        RestartReason.MenusDidNotStart -> Res.string.cg_restarts_menus
    }
    return Phrase.Joined(listOf(phrase(if (host) Res.string.cg_host_restarts else Res.string.cg_join_restarts), phrase(reason), phrase(Res.string.cg_restart_queue)))
}

@Composable
internal fun restartAhead(why: RestartReason, host: Boolean): String = restartAheadPhrase(why, host).text()

fun notInHeadline(problem: EntryProblem?, canPlay: Boolean, host: Boolean): Headline {
    if (!canPlay) return Headline(HideoutIcons.WifiOff, Tone.Warn, phrase(Res.string.cg_dropped_silent_title), phrase(Res.string.cg_dropped_silent_body))
    val (icon, title, body) = when (problem) {
        null -> Triple(HideoutIcons.Monitor, Res.string.cg_not_in_title, if (host) Res.string.cg_not_in_body else Res.string.cg_not_in_guest_body)
        EntryProblem.Dropped -> Triple(HideoutIcons.WifiOff, Res.string.cg_dropped_title, Res.string.cg_dropped_body)
        EntryProblem.MenusDidNotStart -> Triple(HideoutIcons.Monitor, Res.string.cg_menus_failed_title, if (host) Res.string.cg_menus_failed_host_body else Res.string.cg_menus_failed_guest_body)
        EntryProblem.SiteUnreachable -> Triple(HideoutIcons.WifiOff, Res.string.cg_site_unreachable_title, Res.string.cg_site_unreachable_body)
        EntryProblem.ClientDidNotStart, is EntryProblem.Launch -> Triple(HideoutIcons.Monitor, Res.string.cg_did_not_open_title, Res.string.cg_did_not_open_body)
        EntryProblem.GameUpdatePending -> Triple(HideoutIcons.SwapVersions, Res.string.cg_steam_update_title, Res.string.cg_steam_update_body)
        EntryProblem.DidNotJoin -> Triple(HideoutIcons.Monitor, Res.string.cg_did_not_join_title, Res.string.cg_did_not_join_body)
    }
    return Headline(icon, Tone.Warn, phrase(title), phrase(body))
}

enum class NotInAction { Play, OpenSteam }

fun notInAction(problem: EntryProblem?, canPlay: Boolean): NotInAction? = when {
    !canPlay || problem == EntryProblem.MenusDidNotStart -> null
    problem == EntryProblem.GameUpdatePending -> NotInAction.OpenSteam
    else -> NotInAction.Play
}
