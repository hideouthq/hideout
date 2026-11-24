package gg.hideouthq.feature.ingame

import gg.hideouthq.core.customgames.Choices
import gg.hideouthq.core.customgames.CustomGames
import gg.hideouthq.core.customgames.DisplayName
import gg.hideouthq.core.customgames.InviteCode
import gg.hideouthq.core.customgames.LibraryEntry
import gg.hideouthq.core.customgames.Listing
import gg.hideouthq.core.customgames.ModeId
import gg.hideouthq.core.customgames.ModeList
import gg.hideouthq.core.customgames.ModeListing
import gg.hideouthq.core.customgames.ModeOrigin
import gg.hideouthq.core.customgames.Role
import gg.hideouthq.core.customgames.Session
import gg.hideouthq.core.customgames.SettingKey
import gg.hideouthq.core.customgames.SettingValue
import gg.hideouthq.core.customgames.SiteOrigin
import gg.hideouthq.core.customgames.SteamId
import gg.hideouthq.core.customgames.download
import gg.hideouthq.core.customgames.library
import gg.hideouthq.core.customgames.social.Delivery
import gg.hideouthq.core.customgames.social.GameStatus
import gg.hideouthq.core.customgames.social.Invitation
import gg.hideouthq.core.customgames.social.PublicGame
import gg.hideouthq.core.customgames.social.Resolved
import gg.hideouthq.core.customgames.social.ServersRead
import gg.hideouthq.core.customgames.social.Social
import gg.hideouthq.core.customgames.social.SocialState
import gg.hideouthq.core.customgames.social.SocialStatus
import gg.hideouthq.core.customgames.startsHost
import gg.hideouthq.core.customgames.takesInvite
import gg.hideouthq.core.designsystem.component.StepStatus
import gg.hideouthq.feature.customgames.Body
import gg.hideouthq.feature.customgames.EndingStage
import gg.hideouthq.feature.customgames.GameView
import gg.hideouthq.feature.customgames.HostedBy
import gg.hideouthq.feature.customgames.JoinStep
import gg.hideouthq.feature.customgames.JoinView
import gg.hideouthq.feature.customgames.ModeFace
import gg.hideouthq.feature.customgames.NotInAction
import gg.hideouthq.feature.customgames.Outcome
import gg.hideouthq.feature.customgames.Phrase
import gg.hideouthq.feature.customgames.Problem
import gg.hideouthq.feature.customgames.Present
import gg.hideouthq.feature.customgames.PresentInputs
import gg.hideouthq.feature.customgames.StepView
import gg.hideouthq.feature.customgames.faceOf
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.ResourceEnvironment
import org.jetbrains.compose.resources.getSystemResourceEnvironment
import java.security.SecureRandom
import java.util.HexFormat
import java.util.concurrent.atomic.AtomicInteger

/** [epoch] is this Hideout start's, which the game's commands carry so one sent to an earlier start never runs. */
class InGame(private val games: CustomGames, val social: Social, site: SiteOrigin?, private val scope: CoroutineScope, val epoch: String = drawEpoch()) {
    private val serverJoin = MutableStateFlow<ServerJoin?>(null)
    private val invitedBy = MutableStateFlow<InvitedBy?>(null)
    private var looking: Job? = null

    @OptIn(ExperimentalCoroutinesApi::class)
    val topics: StateFlow<Map<Topic, String>> =
        combine(games.live, games.modes, games.transfer, games.restartsDeadlock, social.state) { live, modes, transfer, restarts, state ->
            PresentInputs(live, restarts, transfer, modes, site) to SocialView(social.status(state), social.available, state)
        }.combine(serverJoin.combine(invitedBy, ::Pair)) { (inputs, view), joins -> Triple(inputs, view, joins) }
            .mapLatest { (inputs, view, joins) -> inGameFrames(inputs, epoch, view, joins.first, joins.second) }.stateIn(scope, SharingStarted.Eagerly, emptyMap())

    private val open = AtomicInteger()

    init {
        scope.launch(Dispatchers.IO) { words }
    }

    fun opened() {
        games.refreshModes()
        social.streams(open.incrementAndGet())
    }

    fun closed() = social.streams(open.decrementAndGet())

    suspend fun run(command: PanelCommand): CommandResult = when (command) {
        is PanelCommand.Host -> host(command)
        is PanelCommand.Act -> accepted {
            if (!actOnServerJoin(command.action)) when (command.action) {
                Action.Stop -> games.stop()
                Action.Restart -> games.restartGame()
                Action.Play -> games.play()
                Action.Dismiss -> games.dismissEnded()
                Action.Again -> games.again()
            }
        }
        is PanelCommand.Confirm -> accepted { games.confirmJoin(command.restart) }
        is PanelCommand.Hello -> accepted { social.gameSelf(command.self) }
        is PanelCommand.Forget -> accepted { social.forget(command.id) }
        is PanelCommand.Invite -> online { if (hosted() == null) CommandResult.Busy else accepted { games.invite(command.to) } }
        is PanelCommand.Relist -> online {
            val hosted = hosted()
            when {
                hosted == null -> CommandResult.Busy
                command.listing == Listing.Public && hosted.mode.origin !is ModeOrigin.Feed -> CommandResult.Invalid
                else -> accepted { games.list(command.listing) }
            }
        }
        is PanelCommand.JoinInvite -> online {
            if (!games.live.value.session.takesInvite) return@online CommandResult.Busy
            val found = social.take(command.id) ?: return@online CommandResult.Gone
            invitedBy.value = InvitedBy(found.ticket.code, found.from)
            accepted { games.join(found.ticket) }
        }
        is PanelCommand.JoinServer -> online {
            if (!games.live.value.session.takesInvite) return@online CommandResult.Busy
            val game = social.state.value.servers.games.firstOrNull { it.id == command.id } ?: return@online CommandResult.Gone
            accepted { look(game) }
        }
        PanelCommand.Servers -> online { accepted { social.wantServers() } }
    }

    /** The site answers which game a listing is, which can take seconds, so the joining layer shows the wait and the answer. */
    @Synchronized
    private fun look(game: PublicGame) {
        looking?.cancel()
        serverJoin.value = ServerJoin(game)
        looking = scope.launch {
            val answer = social.resolve(game.id)
            synchronized(this@InGame) {
                ensureActive()
                when (answer) {
                    is Resolved.Open -> games.join(answer.ticket)
                    is Resolved.Refused -> serverJoin.value = ServerJoin(game, answer)
                }
            }
            if (answer !is Resolved.Open) return@launch
            games.live.first { !it.session.startsHost }
            synchronized(this@InGame) { if (isActive) serverJoin.value = null }
        }
    }

    /** While a Servers join shows, the joining layer's buttons are its own. */
    @Synchronized
    private fun actOnServerJoin(action: Action): Boolean {
        val join = serverJoin.value ?: return false
        when (action) {
            Action.Stop, Action.Dismiss -> {
                looking?.cancel()
                serverJoin.value = null
            }
            Action.Again -> look(join.game)
            Action.Restart, Action.Play -> return false
        }
        return true
    }

    private fun hosted(): Role.Host? = games.live.value.let { live -> (live.game?.role as? Role.Host)?.takeIf { !live.session.startsHost } }

    private inline fun online(press: () -> CommandResult): CommandResult = if (social.available) press() else CommandResult.Unavailable

    private inline fun accepted(post: () -> Unit): CommandResult {
        post()
        return CommandResult.Accepted
    }

    private fun host(command: PanelCommand.Host): CommandResult {
        val mode = games.modes.value.hostable(command.mode) ?: return CommandResult.Gone
        val choices = choicesOf(mode.listing, command.picks) ?: return CommandResult.Invalid
        if (!games.live.value.session.startsHost) return CommandResult.Busy
        games.host(mode, choices, command.restart, command.outreach)
        return CommandResult.Accepted
    }
}

data class SocialView(val status: SocialStatus = SocialStatus.Off, val available: Boolean = false, val state: SocialState = SocialState())

/** The invite this panel last joined through. Its sender is the only host id a guest learns, since a listing carries none. */
data class InvitedBy(val code: InviteCode, val from: SteamId)

/** A join pressed in Servers, before the session has its code. [refused] is null while the site is asked. */
data class ServerJoin(val game: PublicGame, val refused: Resolved.Refused? = null)

private fun choicesOf(listing: ModeListing, picks: Map<SettingKey, SettingValue>): Choices? =
    picks.entries.fold(Choices.defaultsOf(listing)) { choices, (key, value) ->
        val setting = listing.settings.firstOrNull { it.key == key }?.takeIf { setting -> setting.options.any { it.value == value } } ?: return null
        choices.with(setting, value)
    }

/** Read once: it asks the desktop for its theme over D-Bus, which no string needs and which takes 25 s where nothing answers. */
private val words: ResourceEnvironment by lazy { getSystemResourceEnvironment() }

private fun drawEpoch(): String = HexFormat.of().formatHex(ByteArray(8).also(SecureRandom()::nextBytes))

/** A [serverJoin] is the newest press, so its card shows over the session's. */
suspend fun inGameFrames(inputs: PresentInputs, epoch: String, social: SocialView = SocialView(), serverJoin: ServerJoin? = null, invitedBy: InvitedBy? = null): Map<Topic, String> {
    val shown = present(inputs)
    val role = inputs.live.game?.role
    val deliveries = social.state.hosted?.takeIf { it.code == (inputs.live.session as? Session.Hosting)?.code }?.deliveries.orEmpty()
    val host = if (role !is Role.Host) null else shown.game?.let { hostCard(it, role, shown.chip?.count, deliveries) }
    val join = serverJoin?.let { joinView(it, inputs.modes) } ?: shown.join
    val hostId = invitedBy?.takeIf { (role as? Role.Guest)?.code == it.code }?.from
    val restartAhead = inputs.restartsDeadlock?.let { Restart(RestartWire.of(it), restartAheadPhrase(it, host = true).load(words)) }
    val session = inputs.live.session
    val game = Frame.Game(host, join?.let { joinCard(it, hostId.takeIf { serverJoin == null }) }, sessionCard(shown, role, hostId), restartAhead, session.startsHost, session.takesInvite)
    return mapOf(
        Topic.Hello to Frame.Hello(InGameProtocol, epoch, social = socialWire(social.status), supportId = (social.status as? SocialStatus.Blocked)?.self?.value),
        Topic.Catalog to catalog(inputs.modes),
        Topic.Game to game,
        Topic.Inbox to Frame.Inbox(social.state.inbox.map { inviteCard(it, inputs.modes) }),
        Topic.Servers to servers(social, inputs.modes),
    ).mapValues { (_, frame) -> InGameWire.encode(frame) }
}

private fun socialWire(status: SocialStatus): SocialWire = when (status) {
    SocialStatus.On -> SocialWire.On
    SocialStatus.Off -> SocialWire.Off
    SocialStatus.OtherAccount -> SocialWire.OtherAccount
    is SocialStatus.Blocked -> SocialWire.Blocked
}

private suspend fun inviteCard(invitation: Invitation, modes: ModeList): InviteCard {
    val offer = invitation.ticket.offer
    val face = faceOf(offer, modes)
    return InviteCard(
        invitation.id.value, invitation.from.value, face.namePhrase.load(words), face.picks, face.art?.tile,
        listOf(invitation.players, offer.capacity), invitation.expires.toEpochMilliseconds(),
    )
}

/** A game whose mode this computer's list doesn't show is left out: joining it would offer nothing to review. */
private suspend fun servers(social: SocialView, modes: ModeList): Frame.Servers {
    if (!social.available) return Frame.Servers(ServersState.Unavailable, emptyList())
    val servers = social.state.servers
    val state = when (servers.read) {
        ServersRead.Idle -> ServersState.Idle
        ServersRead.Loading -> ServersState.Loading
        ServersRead.Ready -> ServersState.Ready
        ServersRead.Unreachable -> ServersState.Unreachable
    }
    return Frame.Servers(state, servers.games.filter { modes.shownFor(it.offer) != null }.map { serverRow(it, modes, servers.ping) })
}

private suspend fun serverRow(game: PublicGame, modes: ModeList, ping: Map<Int, Int>): ServerRow {
    val face = faceOf(game.offer, modes)
    val status = when (game.status) {
        GameStatus.Lobby -> ServerStatus.Lobby
        GameStatus.Match -> ServerStatus.Match
        GameStatus.Full -> ServerStatus.Full
    }
    return ServerRow(
        game.id.value, game.offer.host?.value, face.namePhrase.load(words), face.picks, face.art?.tile, game.region.code,
        listOf(game.players, game.offer.capacity), ping[game.region.id], status,
    )
}

private fun catalog(modes: ModeList): Frame.Catalog {
    val listed = library(modes).filterIsInstance<LibraryEntry.Listed>().map { it.mode }
    return Frame.Catalog(listed.map { modeCard(it.id, it.listing, it.download) })
}

private fun modeCard(id: ModeId, listing: ModeListing, bytes: Long?): ModeCard {
    val art = listing.art
    return ModeCard(
        id = id.value,
        name = listing.name,
        tagline = listing.tagline,
        kind = listing.kind?.wire,
        players = listOf(listing.players.first, listing.players.last),
        minutes = listing.minutes?.let { listOf(it.first, it.last) },
        goal = listing.goal,
        by = listing.creators,
        bytes = bytes,
        art = ModeArtCard(art.card, art.backdrop, art.render, art.overlay, art.tile),
        settings = listing.settings.map { setting ->
            SettingCard(setting.key.value, setting.label, setting.hint, setting.default.value, setting.options.map { OptionCard(it.value.value, it.label, it.players) })
        },
    )
}

private suspend fun hostCard(view: GameView, role: Role.Host, players: Pair<Int, Int>?, deliveries: Map<SteamId, Delivery>): HostCard {
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
        deliveries = deliveries.entries.sortedBy { it.key.value }.associate { (to, delivery) ->
            to.value to when (delivery) {
                Delivery.Sending -> DeliveryWire.Sending
                Delivery.Delivered -> DeliveryWire.Delivered
                Delivery.NotReached -> DeliveryWire.NotReached
            }
        },
        actions = gameActions(body),
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

internal fun gameActions(body: Body): List<Action> = when (body) {
    is Body.Starting, is Body.Restarting, is Body.Entering, is Body.Open, is Body.Match -> listOf(Action.Stop)
    is Body.RestartNeeded -> listOf(Action.Restart, Action.Stop)
    is Body.NotIn -> listOfNotNull(Action.Play.takeIf { notInAction(body.problem, body.canPlay) == NotInAction.Play }, Action.Stop)
    is Body.Ending -> if (body.stage is EndingStage.Failed) listOf(Action.Stop) else emptyList()
    is Body.Ended -> when (val outcome = body.outcome) {
        is Outcome.CouldNotStart -> listOfNotNull(Action.Again.takeIf { tryAgainHelps(outcome.problem) }, Action.Dismiss)
        else -> listOf(Action.Again, Action.Dismiss)
    }
}

/** The session as the game's own screens summarise it. [host] is a guest's host. */
internal data class SessionSummary(val face: ModeFace, val phase: SessionPhase, val host: DisplayName?, val actions: List<Action>)

/** A guest's session follows the join dialog until the game starts, then the game screen, as a host's always does. */
internal fun sessionOf(shown: Present): SessionSummary? =
    shown.join?.let { SessionSummary(it.face, sessionPhase(it.step), it.host, joinActions(it.step)) }
        ?: shown.game?.let { SessionSummary(it.face, sessionPhase(it.body), (it.host as? HostedBy.Friend)?.name, gameActions(it.body)) }

private fun sessionPhase(body: Body): SessionPhase = when (body) {
    is Body.Starting, is Body.RestartNeeded, is Body.Restarting, is Body.Entering -> SessionPhase.Starting
    is Body.Open, is Body.Match -> SessionPhase.In
    is Body.NotIn -> SessionPhase.NotIn
    is Body.Ending -> SessionPhase.Ending
    is Body.Ended -> SessionPhase.Over
}

private fun sessionPhase(step: JoinStep): SessionPhase = when (step) {
    JoinStep.Finding, is JoinStep.Review, JoinStep.AfterCleanup, is JoinStep.Progress, is JoinStep.RestartNeeded, is JoinStep.Restarting -> SessionPhase.Starting
    is JoinStep.NotIn -> SessionPhase.NotIn
    is JoinStep.Stopping -> SessionPhase.Ending
    is JoinStep.Failed -> SessionPhase.Over
}

private suspend fun sessionCard(shown: Present, role: Role?, hostId: SteamId?): SessionCard? {
    val wire = when (role) {
        is Role.Host -> SessionRole.Host
        is Role.Guest -> SessionRole.Guest
        null -> return null
    }
    val summary = sessionOf(shown) ?: return null
    return SessionCard(wire, summary.face.namePhrase.load(words), summary.phase, hostId?.value, summary.host?.value, summary.actions)
}

private fun joinView(join: ServerJoin, modes: ModeList): JoinView {
    val offer = join.game.offer
    val step = when (join.refused) {
        null -> JoinStep.Finding
        Resolved.Refused.Closed -> JoinStep.Failed(Problem.ListingClosed, details = "")
        Resolved.Refused.Full -> JoinStep.Failed(Problem.ListingFull, details = "")
        Resolved.Refused.Unreachable -> JoinStep.Failed(Problem.ListingUnreachable, details = "")
    }
    return JoinView(offer.host, faceOf(offer, modes), offer.capacity, step)
}

private suspend fun joinCard(view: JoinView, hostId: SteamId?): JoinCard {
    val step = view.step
    return JoinCard(
        title = namedPhrase(view.host, Res.string.cg_join_title, Res.string.cg_join_title_friend).load(words),
        mode = view.face.namePhrase.load(words),
        picks = view.face.picks,
        capacity = view.capacity,
        hostId = hostId?.value,
        hostName = view.host?.value,
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

internal fun joinActions(step: JoinStep): List<Action> = when (step) {
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
