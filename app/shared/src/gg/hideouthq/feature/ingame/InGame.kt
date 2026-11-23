package gg.hideouthq.feature.ingame

import gg.hideouthq.core.customgames.Choices
import gg.hideouthq.core.customgames.DisplayName
import gg.hideouthq.core.customgames.CustomGames
import gg.hideouthq.core.customgames.GameRecord
import gg.hideouthq.core.customgames.LibraryEntry
import gg.hideouthq.core.customgames.Mode
import gg.hideouthq.core.customgames.ModeList
import gg.hideouthq.core.customgames.ModeListing
import gg.hideouthq.core.customgames.Role
import gg.hideouthq.core.customgames.SettingKey
import gg.hideouthq.core.customgames.SettingValue
import gg.hideouthq.core.customgames.SiteOrigin
import gg.hideouthq.core.customgames.library
import gg.hideouthq.core.customgames.startsHost
import gg.hideouthq.core.designsystem.component.StepStatus
import gg.hideouthq.feature.customgames.Body
import gg.hideouthq.feature.customgames.EndingStage
import gg.hideouthq.feature.customgames.GameView
import gg.hideouthq.feature.customgames.JoinStep
import gg.hideouthq.feature.customgames.JoinView
import gg.hideouthq.feature.customgames.NotInAction
import gg.hideouthq.feature.customgames.Outcome
import gg.hideouthq.feature.customgames.Phrase
import gg.hideouthq.feature.customgames.PresentInputs
import gg.hideouthq.feature.customgames.StepView
import gg.hideouthq.feature.customgames.headlineOf
import gg.hideouthq.feature.customgames.joinBusy
import gg.hideouthq.feature.customgames.joinHeadline
import gg.hideouthq.feature.customgames.load
import gg.hideouthq.feature.customgames.namePhrase
import gg.hideouthq.feature.customgames.namedPhrase
import gg.hideouthq.feature.customgames.notInAction
import gg.hideouthq.feature.customgames.notePhrase
import gg.hideouthq.feature.customgames.present
import gg.hideouthq.feature.customgames.restartAheadPhrase
import gg.hideouthq.feature.customgames.stepPhrase
import gg.hideouthq.feature.customgames.tryAgainHelps
import gg.hideouthq.resources.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.ResourceEnvironment
import org.jetbrains.compose.resources.getSystemResourceEnvironment

/**
 * The in-game UI's one way into the app. [topics] is the newest frame of every topic, empty until the first is ready,
 * which each stream compares with what it sent. [run] is the only way a press reaches [CustomGames]: it answers what it
 * can see now, as a running session being Busy, and posts, and what the press led to shows in [topics].
 */
class InGame(private val games: CustomGames, site: SiteOrigin?, scope: CoroutineScope) {
    @OptIn(ExperimentalCoroutinesApi::class)
    val topics: StateFlow<Map<Topic, String>> =
        combine(games.live, games.modes, games.transfer, games.restartsDeadlock) { live, modes, transfer, restarts ->
            PresentInputs(live, restarts, transfer, modes, games.firewallMayAsk, site)
        }.mapLatest(::inGameFrames).stateIn(scope, SharingStarted.Eagerly, emptyMap())

    init {
        scope.launch(Dispatchers.IO) { words }
    }

    /** A game began to listen. Reads the mode list and whether Play would restart Deadlock, as the app's page does as it opens. */
    fun opened() = games.refreshModes()

    fun run(command: PanelCommand): CommandResult = when (command) {
        is PanelCommand.Host -> host(command)
        is PanelCommand.Act -> {
            when (command.action) {
                Action.Stop -> games.stop()
                Action.Restart -> games.restartGame()
                Action.Play -> games.play()
                Action.Dismiss -> games.dismissEnded()
                Action.Again -> games.again()
            }
            CommandResult.Accepted
        }
        is PanelCommand.Confirm -> {
            games.confirmJoin(command.restart)
            CommandResult.Accepted
        }
        is PanelCommand.Hello -> CommandResult.Accepted
        is PanelCommand.Invite, is PanelCommand.Relist, is PanelCommand.JoinInvite, is PanelCommand.JoinServer, is PanelCommand.Forget, PanelCommand.Servers ->
            CommandResult.Unavailable
    }

    private fun host(command: PanelCommand.Host): CommandResult {
        val mode = games.modes.value.hostable(command.mode) ?: return CommandResult.Gone
        val choices = choicesOf(mode.listing, command.picks) ?: return CommandResult.Invalid
        if (!games.live.value.session.startsHost) return CommandResult.Busy
        games.host(mode, choices, command.restart, command.outreach)
        return CommandResult.Accepted
    }
}

/** The mode's defaults with each pick laid over, or null when a pick names no setting or option of [listing]. */
private fun choicesOf(listing: ModeListing, picks: Map<SettingKey, SettingValue>): Choices? =
    picks.entries.fold(Choices.defaultsOf(listing)) { choices, (key, value) ->
        val setting = listing.settings.firstOrNull { it.key == key }?.takeIf { setting -> setting.options.any { it.value == value } } ?: return null
        choices.with(setting, value)
    }

/** Read once: reading it asks the desktop for its theme over D-Bus, which no string depends on and which takes 25 s where nothing answers. */
private val words: ResourceEnvironment by lazy { getSystemResourceEnvironment() }

/** Every topic's frame for [inputs], from the same [present] the app's own screens draw, in the words they use. */
suspend fun inGameFrames(inputs: PresentInputs): Map<Topic, String> {
    val shown = present(inputs)
    val record = inputs.live.game
    val host = shown.game?.takeIf { record?.role is Role.Host }?.let { hostCard(it, checkNotNull(record), shown.chip?.count) }
    val game = Frame.Game(host, shown.join?.let { joinCard(it) }, inputs.restartsDeadlock?.let { Restart(RestartWire.of(it), restartAheadPhrase(it, host = true).load(words)) })
    return mapOf(
        Topic.Hello to Frame.Hello(InGameProtocol, social = SocialWire.Off),
        Topic.Catalog to Frame.Catalog(catalog(inputs.modes)),
        Topic.Game to game,
    ).mapValues { (_, frame) -> InGameWire.encode(frame) }
}

private fun catalog(modes: ModeList): List<ModeCard> =
    library(modes).filterIsInstance<LibraryEntry.Listed>().map { modeCard(it.mode, modes.downloadToHost(it.mode)) }

private fun modeCard(mode: Mode, bytes: Long?): ModeCard {
    val listing = mode.listing
    val art = listing.art
    return ModeCard(
        id = mode.id.value,
        name = listing.name,
        tagline = listing.tagline,
        kind = listing.kind?.wire,
        players = listOf(listing.players.first, listing.players.last),
        minutes = listing.minutes?.let { listOf(it.first, it.last) },
        by = listing.creators,
        bytes = bytes,
        art = ModeArtCard(art.card, art.backdrop, art.render, art.overlay, art.tile),
        settings = listing.settings.map { setting ->
            SettingCard(setting.key.value, setting.label, setting.hint, setting.default.value, setting.options.map { OptionCard(it.value.value, it.label, it.players) })
        },
    )
}

private suspend fun hostCard(view: GameView, record: GameRecord, players: Pair<Int, Int>?): HostCard {
    val role = record.role as Role.Host
    val body = view.body
    return HostCard(
        mode = role.mode.id.value,
        phase = hostPhase(body),
        steps = (body as? Body.Starting)?.steps.orEmpty().map { stepCard(it, view.face.namePhrase, hostName = null) },
        words = headlineOf(body, view)?.let { Words(it.title.load(words), it.body.load(words)) },
        link = view.invite?.takeIf { it.live && !it.paused }?.link,
        players = players?.toList(),
        listing = ListingWire.of(role.outreach.listing),
        invitees = role.outreach.invitees.map { it.value }.sorted(),
        deliveries = emptyMap(),
        actions = hostActions(body),
    )
}

private fun hostPhase(body: Body): HostPhase = when (body) {
    is Body.Starting -> HostPhase.Starting
    is Body.RestartNeeded -> HostPhase.Restart
    is Body.Restarting -> HostPhase.Restarting
    is Body.Entering -> HostPhase.Entering
    is Body.Open, is Body.Match -> HostPhase.Ready
    is Body.NotIn -> HostPhase.NotIn
    is Body.Ending -> HostPhase.Ending
    is Body.Ended -> if (body.outcome is Outcome.CouldNotStart) HostPhase.Failed else HostPhase.Ended
}

/** The game screen's buttons for a host, those a press in the game can make. */
private fun hostActions(body: Body): List<Action> = when (body) {
    is Body.Starting, is Body.Restarting, is Body.Entering, is Body.Open, is Body.Match -> listOf(Action.Stop)
    is Body.RestartNeeded -> listOf(Action.Restart, Action.Stop)
    is Body.NotIn -> listOfNotNull(Action.Play.takeIf { notInAction(body.problem, body.canPlay) == NotInAction.Play }, Action.Stop)
    is Body.Ending -> if (body.stage is EndingStage.Failed) listOf(Action.Stop) else emptyList()
    is Body.Ended -> when (val outcome = body.outcome) {
        is Outcome.CouldNotStart -> listOfNotNull(Action.Again.takeIf { tryAgainHelps(outcome.problem) }, Action.Dismiss)
        else -> listOf(Action.Again, Action.Dismiss)
    }
}

private suspend fun joinCard(view: JoinView): JoinCard {
    val step = view.step
    return JoinCard(
        title = namedPhrase(view.host, Res.string.cg_join_title, Res.string.cg_join_title_friend).load(words),
        mode = view.face.namePhrase.load(words),
        picks = view.face.picks,
        capacity = view.capacity,
        phase = joinPhase(step),
        steps = (step as? JoinStep.Progress)?.steps.orEmpty().map { stepCard(it, view.face.namePhrase, view.host) },
        words = joinHeadline(step, view)?.let { Words(it.title.load(words), it.body.load(words)) },
        busy = joinBusy(step)?.load(words),
        restart = (step as? JoinStep.Review)?.restarts?.let { Restart(RestartWire.of(it), restartAheadPhrase(it, host = false).load(words)) },
        actions = joinActions(step),
    )
}

private fun joinPhase(step: JoinStep): JoinPhase = when (step) {
    JoinStep.Finding -> JoinPhase.Finding
    is JoinStep.Review -> JoinPhase.Review
    JoinStep.AfterCleanup -> JoinPhase.Cleanup
    is JoinStep.Progress -> JoinPhase.Progress
    is JoinStep.Stopping -> JoinPhase.Stopping
    is JoinStep.RestartNeeded -> JoinPhase.Restart
    is JoinStep.Restarting -> JoinPhase.Restarting
    is JoinStep.NotIn -> JoinPhase.NotIn
    is JoinStep.Failed -> JoinPhase.Failed
}

/** The join dialog's buttons, but Join itself, which is `confirm`. */
private fun joinActions(step: JoinStep): List<Action> = when (step) {
    JoinStep.Finding, is JoinStep.Review, is JoinStep.Progress, is JoinStep.Restarting -> listOf(Action.Stop)
    JoinStep.AfterCleanup -> emptyList()
    is JoinStep.Stopping -> if (step.stage is EndingStage.Failed) listOf(Action.Stop) else emptyList()
    is JoinStep.RestartNeeded -> listOf(Action.Restart, Action.Stop)
    is JoinStep.NotIn -> listOfNotNull(Action.Play.takeIf { notInAction(step.problem, step.canPlay) == NotInAction.Play }, Action.Stop)
    is JoinStep.Failed -> listOfNotNull(Action.Again.takeIf { tryAgainHelps(step.problem) }, Action.Dismiss)
}

private suspend fun stepCard(step: StepView, mode: Phrase, hostName: DisplayName?): StepCard {
    val (state, progress) = when (val status = step.status) {
        StepStatus.Done -> StepState.Done to null
        is StepStatus.Running -> StepState.Running to status.progress
        StepStatus.Waiting -> StepState.Waiting to null
    }
    return StepCard(stepPhrase(step.name, mode, hostName).load(words), state, step.note?.let { notePhrase(it).load(words) }, progress)
}
