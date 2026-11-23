package gg.hideouthq.core.customgames.social

import gg.hideouthq.core.customgames.CustomGames
import gg.hideouthq.core.customgames.InviteCode
import gg.hideouthq.core.customgames.InviteOffer
import gg.hideouthq.core.customgames.JoinTicket
import gg.hideouthq.core.customgames.Listing
import gg.hideouthq.core.customgames.LiveSession
import gg.hideouthq.core.customgames.ModeOrigin
import gg.hideouthq.core.customgames.RelayRegion
import gg.hideouthq.core.customgames.Role
import gg.hideouthq.core.customgames.Session
import gg.hideouthq.core.customgames.SteamId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.security.SecureRandom
import java.util.HexFormat
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** [from] is the sender's claim, which the game shows only for a Steam friend. */
data class Invitation(val id: InvitationId, val from: SteamId, val ticket: JoinTicket, val players: Int, val expires: Instant)

enum class GameStatus { Lobby, Match, Full }

/** Carries no code: [Directory.resolve] answers it at the press, and only while the game has room. */
data class PublicGame(val id: ListingId, val offer: InviteOffer, val region: RelayRegion, val players: Int, val status: GameStatus)

enum class Delivery { Sending, Delivered, NotReached }

/** Only its holder can refresh or remove the listing. */
@JvmInline
value class ListingToken(val value: String) {
    override fun toString() = "ListingToken(redacted)"
}

data class ListingWanted(val token: ListingToken, val ticket: JoinTicket, val region: RelayRegion, val players: Int, val status: GameStatus)

sealed interface Resolved {
    data class Open(val ticket: JoinTicket) : Resolved

    /** [Closed] is a listing its host took down or let lapse. */
    enum class Refused : Resolved { Closed, Full, Unreachable }
}

interface Mailbox {
    fun hold(owner: SteamId?)

    val invitations: Flow<Invitation>

    /** The owner whose mailbox the site refused this PC, until one opens for them. */
    val refused: StateFlow<SteamId?>

    suspend fun deliver(to: SteamId, from: SteamId, ticket: JoinTicket, players: Int): Delivery
}

interface Directory {
    suspend fun list(): List<PublicGame>?

    suspend fun resolve(id: ListingId): Resolved

    /** Refreshes [wanted] until the next call. A new token or null removes the old listing. */
    fun keep(wanted: ListingWanted?)
}

fun interface RelayLatency {
    suspend fun measure(): Map<Int, Int>?
}

class SocialNetwork(val mailbox: Mailbox, val directory: Directory, val latency: RelayLatency)

sealed interface SocialStatus {
    data object On : SocialStatus

    data object Off : SocialStatus

    data object OtherAccount : SocialStatus

    /** The site refused this PC for [self]'s mailbox. Support resets it by that id, the player's own. */
    data class Blocked(val self: SteamId) : SocialStatus
}

/** Kept until its expiry even once closed, so a second delivery never shows again. */
data class InvitationKey(val from: SteamId, val id: InvitationId)

data class Hosted(val code: InviteCode, val token: ListingToken, val deliveries: Map<SteamId, Delivery> = emptyMap())

enum class ServersRead { Idle, Loading, Ready, Unreachable }

data class Servers(val read: ServersRead = ServersRead.Idle, val games: List<PublicGame> = emptyList(), val ping: Map<Int, Int> = emptyMap(), val pingAt: Instant? = null)

/** [hosted] follows the hosted code, so a new game starts with no deliveries. */
data class SocialState(
    val inbox: List<Invitation> = emptyList(),
    val seen: Map<InvitationKey, Instant> = emptyMap(),
    val hosted: Hosted? = null,
    val servers: Servers = Servers(),
    val streams: Int = 0,
    val closedAt: Instant? = null,
    val gameSelf: SteamId? = null,
    val refused: SteamId? = null,
)

sealed interface SocialInput {
    data class Live(val live: LiveSession) : SocialInput

    data class Came(val invitation: Invitation) : SocialInput

    data class Closed(val id: InvitationId) : SocialInput

    data class Answered(val code: InviteCode, val to: SteamId, val delivery: Delivery) : SocialInput

    data object ServersWanted : SocialInput

    data class ServersAnswered(val games: List<PublicGame>?, val ping: Map<Int, Int>?) : SocialInput

    data class Streams(val open: Int) : SocialInput

    data class GameSelf(val id: SteamId?) : SocialInput

    data class Refused(val owner: SteamId?) : SocialInput

    data object Tick : SocialInput
}

sealed interface SocialEffect {
    data class Deliver(val code: InviteCode, val to: SteamId, val ticket: JoinTicket, val players: Int) : SocialEffect

    data class ReadServers(val measure: Boolean) : SocialEffect
}

val MailboxGrace = 30.seconds
val SendLimit = 15.seconds
val PingLifetime = 5.minutes

/**
 * Deliveries converge on every [SocialInput.Live]: a friend invited during the start goes out with the rest once the
 * code arrives, and a repeated Live sends nothing twice.
 */
fun reduceSocial(state: SocialState, input: SocialInput, now: Instant, fresh: () -> ListingToken): Pair<SocialState, List<SocialEffect>> = when (input) {
    is SocialInput.Live -> deliveries(state, input.live, fresh)
    is SocialInput.Came -> {
        val invitation = input.invitation
        val key = InvitationKey(invitation.from, invitation.id)
        if (key in state.seen || invitation.expires <= now) {
            state to emptyList()
        } else {
            state.copy(inbox = listOf(invitation) + state.inbox.filter { it.from != invitation.from }, seen = state.seen + (key to invitation.expires)) to emptyList()
        }
    }
    is SocialInput.Closed -> state.copy(inbox = state.inbox.filter { it.id != input.id }) to emptyList()
    is SocialInput.Answered -> {
        val hosted = state.hosted?.takeIf { it.code == input.code }
        (if (hosted == null) state else state.copy(hosted = hosted.copy(deliveries = hosted.deliveries + (input.to to input.delivery)))) to emptyList()
    }
    SocialInput.ServersWanted ->
        if (state.servers.read == ServersRead.Loading) {
            state to emptyList()
        } else {
            val measure = state.servers.pingAt.let { it == null || now - it >= PingLifetime }
            state.copy(servers = state.servers.copy(read = ServersRead.Loading)) to listOf(SocialEffect.ReadServers(measure))
        }
    is SocialInput.ServersAnswered -> {
        val servers = state.servers
        val measured = input.ping?.let { servers.copy(ping = it, pingAt = now) } ?: servers
        state.copy(servers = if (input.games == null) measured.copy(read = ServersRead.Unreachable) else measured.copy(read = ServersRead.Ready, games = input.games)) to emptyList()
    }
    is SocialInput.Streams -> state.copy(streams = input.open, closedAt = if (input.open == 0 && state.streams > 0) now else state.closedAt) to emptyList()
    is SocialInput.GameSelf -> state.copy(gameSelf = input.id) to emptyList()
    is SocialInput.Refused -> state.copy(refused = input.owner) to emptyList()
    SocialInput.Tick -> {
        val seen = state.seen.filterValues { it > now }
        (if (seen.size == state.seen.size) state else state.copy(inbox = state.inbox.filter { it.expires > now }, seen = seen)) to emptyList()
    }
}

private fun deliveries(state: SocialState, live: LiveSession, fresh: () -> ListingToken): Pair<SocialState, List<SocialEffect>> {
    val hosting = live.session as? Session.Hosting
    val role = live.game?.role as? Role.Host
    val offer = role?.offer
    if (hosting == null || offer == null) return (if (state.hosted == null) state else state.copy(hosted = null)) to emptyList()
    val hosted = state.hosted?.takeIf { it.code == hosting.code } ?: Hosted(hosting.code, fresh())
    val owed = role.outreach.invitees - hosted.deliveries.keys
    val ticket = JoinTicket(hosting.code, offer)
    val players = hosting.players.size.coerceAtLeast(1)
    val next = hosted.copy(deliveries = hosted.deliveries + owed.associateWith { Delivery.Sending })
    return state.copy(hosted = next) to owed.map { SocialEffect.Deliver(hosting.code, it, ticket, players) }
}

fun listingWanted(state: SocialState, live: LiveSession): ListingWanted? {
    val hosting = live.session as? Session.Hosting ?: return null
    val record = live.game ?: return null
    val role = record.role as? Role.Host ?: return null
    val hosted = state.hosted?.takeIf { it.code == hosting.code } ?: return null
    if (role.outreach.listing != Listing.Public || role.mode.origin !is ModeOrigin.Feed) return null
    val offer = role.offer ?: return null
    val region = hosting.region ?: return null
    val players = hosting.players.size.coerceAtLeast(1)
    val status = when {
        players >= offer.capacity -> GameStatus.Full
        record.reported.isNotEmpty() -> GameStatus.Match
        else -> GameStatus.Lobby
    }
    return ListingWanted(hosted.token, JoinTicket(hosting.code, offer), region, players, status)
}

fun mailboxOwner(state: SocialState, now: Instant, me: SteamId?): SteamId? {
    val listening = state.streams > 0 || state.closedAt?.let { now - it < MailboxGrace } == true
    return me?.takeIf { listening && (state.gameSelf == null || state.gameSelf == it) }
}

class Social(
    private val games: CustomGames,
    private val network: SocialNetwork?,
    private val scope: CoroutineScope,
    private val me: () -> SteamId? = { games.localPlayer?.steamId?.let(SteamId::parse) },
    private val sendLimit: Duration = SendLimit,
) {
    private val published = MutableStateFlow(SocialState())
    val state: StateFlow<SocialState> = published.asStateFlow()

    private val inputs = Channel<SocialInput>(Channel.UNLIMITED)

    val available: Boolean get() = network != null

    fun start() {
        scope.launch { for (input in inputs) step(input) }
        scope.launch { games.live.collect { post(SocialInput.Live(it)) } }
        network?.let { scope.launch { it.mailbox.invitations.collect { invitation -> post(SocialInput.Came(invitation)) } } }
        network?.let { scope.launch { it.mailbox.refused.collect { owner -> post(SocialInput.Refused(owner)) } } }
        scope.launch {
            while (isActive) {
                delay(1.seconds)
                post(SocialInput.Tick)
            }
        }
    }

    fun status(state: SocialState): SocialStatus {
        val me = me()
        return when {
            network == null || me == null -> SocialStatus.Off
            state.gameSelf != null && state.gameSelf != me -> SocialStatus.OtherAccount
            state.refused == me -> SocialStatus.Blocked(me)
            else -> SocialStatus.On
        }
    }

    fun streams(open: Int) = post(SocialInput.Streams(open))

    fun gameSelf(id: SteamId?) = post(SocialInput.GameSelf(id))

    fun wantServers() = post(SocialInput.ServersWanted)

    fun take(id: InvitationId): JoinTicket? {
        val ticket = published.value.inbox.firstOrNull { it.id == id }?.ticket ?: return null
        post(SocialInput.Closed(id))
        return ticket
    }

    fun forget(id: InvitationId) = post(SocialInput.Closed(id))

    suspend fun resolve(id: ListingId): Resolved = network?.directory?.resolve(id) ?: Resolved.Refused.Unreachable

    private fun post(input: SocialInput) {
        inputs.trySend(input)
    }

    private fun step(input: SocialInput) {
        val now = Clock.System.now()
        val (next, effects) = reduceSocial(published.value, input, now, ::drawToken)
        published.value = next
        network?.mailbox?.hold(mailboxOwner(next, now, me()))
        network?.directory?.keep(listingWanted(next, games.live.value))
        effects.forEach(::execute)
    }

    private fun execute(effect: SocialEffect) {
        when (effect) {
            is SocialEffect.Deliver -> scope.launch { post(SocialInput.Answered(effect.code, effect.to, send(effect))) }
            is SocialEffect.ReadServers -> scope.launch {
                val games = network?.directory?.list()
                val ping = if (effect.measure && games != null) network.latency.measure() else null
                post(SocialInput.ServersAnswered(games, ping))
            }
        }
    }

    /** Only the mailbox's word that it delivered counts. An error, or no answer in [sendLimit], is an invite not reached. */
    private suspend fun send(effect: SocialEffect.Deliver): Delivery {
        val from = me()
        if (network == null || from == null) return Delivery.NotReached
        val answer = try {
            withTimeoutOrNull(sendLimit) { network.mailbox.deliver(effect.to, from, effect.ticket, effect.players) }
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            null
        }
        return answer ?: Delivery.NotReached
    }
}

private val random = SecureRandom()

private fun drawToken(): ListingToken = ListingToken(HexFormat.of().formatHex(ByteArray(16).also(random::nextBytes)))
