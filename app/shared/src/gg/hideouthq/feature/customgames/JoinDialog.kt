package gg.hideouthq.feature.customgames

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import gg.hideouthq.core.customgames.CustomGames
import gg.hideouthq.core.customgames.SiteOrigin
import gg.hideouthq.core.designsystem.component.ButtonVariant
import gg.hideouthq.core.designsystem.component.HideoutButton
import gg.hideouthq.core.designsystem.component.HideoutIconButton
import gg.hideouthq.core.designsystem.icon.HideoutIcons
import gg.hideouthq.core.designsystem.theme.HideoutDimens
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import gg.hideouthq.core.designsystem.theme.HideoutWeights
import gg.hideouthq.core.ui.AlertModal
import gg.hideouthq.core.ui.WindowModal
import gg.hideouthq.resources.*
import org.jetbrains.compose.resources.stringResource

@Composable
fun JoinDialog(games: CustomGames, site: SiteOrigin?, modal: WindowModal) {
    val view = rememberPresent(games, site).join
    var setAside by remember { mutableStateOf(false) }
    if (view?.step !is JoinStep.AfterCleanup && view?.step !is JoinStep.Stopping) setAside = false
    val shown = rememberLast(view) ?: return
    val focus = remember { FocusRequester() }
    val close: () -> Unit = when (shown.step) {
        JoinStep.Finding, is JoinStep.Review, is JoinStep.Progress, is JoinStep.RestartNeeded, is JoinStep.Restarting, is JoinStep.NotIn -> games::stop
        JoinStep.AfterCleanup, is JoinStep.Stopping -> { { setAside = true } }
        is JoinStep.Failed -> games::dismissEnded
    }
    val title = named(shown.host, Res.string.cg_join_title, Res.string.cg_join_title_friend)
    val motion = HideoutTheme.motion
    AlertModal(view != null && !setAside, modal, title, onDismiss = close, initialFocus = focus, width = 600.dp, padding = 0.dp) {
        Column {
            Header(shown, title, close, focus)
            AnimatedContent(
                shown.step,
                Modifier.animateContentSize(motion.spatialDefault()),
                contentKey = { it::class },
                transitionSpec = { fadeIn(motion.enterFade()) togetherWith fadeOut(motion.exitFade()) },
                label = "joinStep",
            ) { step -> Step(step, shown, games, close) }
        }
    }
}

@Composable
private fun Header(view: JoinView, title: String, close: () -> Unit, focus: FocusRequester) {
    val scheme = MaterialTheme.colorScheme
    val type = HideoutTheme.typography
    val players = view.capacity?.let { stringResource(Res.string.cg_join_meta_players, it) }
    val meta = (listOf(view.face.name()) + view.face.picks + listOfNotNull(players)).joinToString(" · ")
    Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 18.dp, top = 22.dp, bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        if (view.host != null) Avatar(view.host.value, host = false, size = 52.dp) else ModeTile(view.face, 52.dp, 16.dp)
        Column(Modifier.weight(1f).padding(top = 4.dp)) {
            Text(title, style = type.titleDialog, color = scheme.onSurface)
            Text(meta, Modifier.padding(top = 3.dp), style = type.captionSmall, color = scheme.outline)
        }
        HideoutIconButton(HideoutIcons.Close, stringResource(Res.string.cg_close), close, Modifier.focusRequester(focus))
    }
}

@Composable
private fun Step(step: JoinStep, view: JoinView, games: CustomGames, close: () -> Unit) {
    val cancel = stringResource(Res.string.cg_cancel)
    Column {
        Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 14.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            joinHeadline(step, view)?.let { Head(it.icon, it.tone, it.title.text(), it.body.text()) }
            joinBusy(step)?.let { BusyLine(it.text()) }
            when (step) {
                is JoinStep.Review -> {
                    InfoRow(view, step.download)
                    step.restarts?.let { HintBox(HideoutIcons.Refresh, restartAhead(it, host = false)) }
                    HintBox(HideoutIcons.Play, named(view.host, Res.string.cg_join_hint, Res.string.cg_join_hint_friend))
                }
                is JoinStep.Progress -> StepList(step.steps, view.face.namePhrase, view.host)
                is JoinStep.Stopping -> StoppingLine(step.stage, failed = step.problem != null)
                JoinStep.Finding, JoinStep.AfterCleanup, is JoinStep.Failed, is JoinStep.RestartNeeded, is JoinStep.Restarting, is JoinStep.NotIn -> Unit
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 20.dp, top = 14.dp, bottom = 18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
            when (step) {
                is JoinStep.Review -> {
                    HideoutButton(cancel, games::stop, variant = ButtonVariant.Text)
                    HideoutButton(stringResource(Res.string.cg_join_game), { games.confirmJoin(step.restarts) }, leadingIcon = HideoutIcons.Play, leadingIconSize = 16.dp)
                }
                JoinStep.Finding, JoinStep.AfterCleanup, is JoinStep.Progress, is JoinStep.Restarting -> HideoutButton(cancel, close, variant = ButtonVariant.Text)
                is JoinStep.RestartNeeded -> {
                    HideoutButton(cancel, games::stop, variant = ButtonVariant.Text)
                    HideoutButton(stringResource(Res.string.cg_restart_button), games::restartGame, leadingIcon = HideoutIcons.Refresh, leadingIconSize = 16.dp)
                }
                is JoinStep.NotIn -> {
                    HideoutButton(cancel, games::stop, variant = ButtonVariant.Text)
                    when (notInAction(step.problem, step.canPlay)) {
                        NotInAction.Play -> HideoutButton(stringResource(Res.string.cg_play), games::play, leadingIcon = HideoutIcons.Play, leadingIconSize = 16.dp)
                        NotInAction.OpenSteam -> {
                            val steam = LocalUriHandler.current
                            HideoutButton(stringResource(Res.string.cg_open_steam), { steam.openUri(SteamUri) }, variant = ButtonVariant.Tonal, leadingIcon = HideoutIcons.OpenExternal, leadingIconSize = 16.dp)
                        }
                        null -> Unit
                    }
                }
                is JoinStep.Stopping -> (step.stage as? EndingStage.Failed)?.let { failed ->
                    CopyDetails(failed.details)
                    HideoutButton(stringResource(Res.string.cg_try_again), games::stop)
                }
                is JoinStep.Failed -> {
                    HideoutButton(stringResource(Res.string.cg_close), games::dismissEnded, variant = ButtonVariant.Text)
                    CopyDetails(step.details)
                    if (tryAgainHelps(step.problem)) HideoutButton(stringResource(Res.string.cg_try_again), games::again)
                }
            }
        }
    }
}

@Composable
private fun InfoRow(view: JoinView, download: JoinDownload) {
    val scheme = MaterialTheme.colorScheme
    val type = HideoutTheme.typography
    val name = listOfNotNull(view.face.name(), view.face.version).joinToString(" ")
    val sub = when (download) {
        JoinDownload.None -> stringResource(Res.string.cg_join_no_download)
        JoinDownload.OnThisPc -> stringResource(Res.string.cg_join_installed)
        is JoinDownload.Bytes -> stringResource(Res.string.cg_join_download, megabytes(download.bytes))
        JoinDownload.Unknown -> stringResource(Res.string.cg_join_size_unknown)
    }
    Row(
        Modifier.fillMaxWidth().background(scheme.surfaceContainerHigh, RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ModeTile(view.face, 44.dp, 13.dp)
        Column {
            Text(name, style = type.body.copy(fontWeight = HideoutWeights.Label), color = scheme.onSurface)
            Text(sub, style = type.subtitle, color = scheme.outline)
        }
    }
}

@Composable
private fun BusyLine(text: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Spinner(MaterialTheme.colorScheme.primary, 20.dp)
        Text(text, style = HideoutTheme.typography.body, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun StoppingLine(stage: EndingStage, failed: Boolean) {
    when (stage) {
        is EndingStage.Failed -> HintBox(HideoutIcons.Warning, stringResource(Res.string.cg_ending_failed))
        EndingStage.Stopping, EndingStage.PuttingFilesBack -> if (failed) BusyLine(stringResource(Res.string.cg_ending_files))
    }
}

/** The join dialog's heading at [step], which the in-game join card says too. */
fun joinHeadline(step: JoinStep, view: JoinView): Headline? = when (step) {
    is JoinStep.Stopping -> step.problem?.let { problemHeadline(it, view) }
    is JoinStep.Failed -> problemHeadline(step.problem, view)
    is JoinStep.RestartNeeded -> Headline(HideoutIcons.Refresh, Tone.Warn, phrase(Res.string.cg_restart_title), restartWhyPhrase(step.why))
    is JoinStep.Restarting -> if (step.closeFailed) Headline(HideoutIcons.Monitor, Tone.Warn, phrase(Res.string.cg_restart_close_title), phrase(Res.string.cg_restart_close_body)) else null
    is JoinStep.NotIn -> notInHeadline(step.problem, step.canPlay, host = false)
    JoinStep.Finding, is JoinStep.Review, JoinStep.AfterCleanup, is JoinStep.Progress -> null
}

/** The line beside the join dialog's spinner at [step], which the in-game join card says too. */
fun joinBusy(step: JoinStep): Phrase? = when (step) {
    JoinStep.Finding -> phrase(Res.string.cg_join_finding)
    JoinStep.AfterCleanup -> phrase(Res.string.cg_join_cleanup)
    is JoinStep.Stopping -> if (step.problem == null) phrase(Res.string.cg_join_cancelling) else null
    is JoinStep.Restarting -> if (step.closeFailed) null else phrase(Res.string.cg_restarting_title)
    is JoinStep.Review, is JoinStep.Progress, is JoinStep.Failed, is JoinStep.RestartNeeded, is JoinStep.NotIn -> null
}

private fun problemHeadline(problem: Problem, view: JoinView): Headline =
    problemWords(problem, host = false, hostName = view.host, mode = view.face.namePhrase).let { (title, body) -> Headline(problemIcon(problem), Tone.Fail, title, body) }

@Composable
private fun Head(icon: ImageVector, tone: Tone, title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        IconBox(icon, tone, 48.dp, 16.dp, 22.dp)
        Column {
            Text(title, style = HideoutTheme.typography.titleDialog.copy(fontSize = 18.sp, lineHeight = 22.5.sp), color = MaterialTheme.colorScheme.onSurface)
            Text(body, Modifier.padding(top = 6.dp), style = HideoutTheme.typography.body.copy(lineHeight = 21.7.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun CopyDetails(details: String) =
    CopyInPlace(details, Res.string.cg_copy_details, HideoutIcons.Copy, ButtonVariant.Text, PaddingValues(horizontal = HideoutDimens.Button.TextHorizontalPadding))
