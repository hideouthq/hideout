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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.security.SecureRandom
import java.util.HexFormat
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** An invite that came in. [from] is the sender's claim, which the game shows only for a Steam friend. */
data class Invitation(val id: InvitationId, val from: SteamId, val ticket: JoinTicket, val players: Int, val expires: Instant)

enum class GameStatus { Lobby, Match, Full }

/** A listed game. Its code is not here: [Directory.resolve] answers it at the press, and only while the game has room. */
data class PublicGame(val id: ListingId, val offer: InviteOffer, val region: RelayRegion, val players: Int, val status: GameStatus)

/** What an invite to one friend came to. [NotReached] covers no Hideout of theirs listening and a mailbox that failed, both of which leave Copy link. */
enum class Delivery { Sending, Delivered, NotReached }

/** 32 hex digits drawn per hosted code. Only its holder can refresh or remove the listing. */
@JvmInline
value class ListingToken(val value: String) {
    override fun toString() = "ListingToken(redacted)"
}

/** What this computer's hosted game wants in the directory. [Directory.keep] holds it there until this changes. */
data class ListingWanted(val token: ListingToken, val ticket: JoinTicket, val region: RelayRegion, val players: Int, val status: GameStatus)

sealed interface Resolved {
    data class Open(val ticket: JoinTicket) : Resolved

    /** Closed, full, or the directory couldn't say. */
    data object Gone : Resolved
}

interface Mailbox {
    /** Keeps [owner]'s mailbox open, reconnecting with backoff, until called with another; null closes it. */
    fun hold(owner: SteamId?)

    /** Invites as they arrive on the open mailbox, already parsed. */
    val invitations: Flow<Invitation>

    suspend fun deliver(to: SteamId, from: SteamId, ticket: JoinTicket, players: Int): Delivery
}

interface Directory {
    /** Null when the directory can't be read. */
    suspend fun list(): List<PublicGame>?

    suspend fun resolve(id: ListingId): Resolved

    /** Puts [wanted] and refreshes it until the next call; a new token or null removes the old listing. */
    fun keep(wanted: ListingWanted?)
}

/** This computer's round trip to each relay region, by region id, or null when it couldn't measure. */
fun interface RelayLatency {
    suspend fun measure(): Map<Int, Int>?
}

/** The network a build with the invite key reaches. */
class SocialNetwork(val mailbox: Mailbox, val directory: Directory, val latency: RelayLatency)

/** Where invites and Servers stand for the game: [Off] without a key or a Steam login, [OtherAccount] when the game's account isn't the app's. */
enum class SocialStatus { On, Off, OtherAccount }

/** An invite's identity, kept until its expiry even once closed, so a second delivery of it never shows again. */
data class InvitationKey(val from: SteamId, val id: InvitationId)

data class Hosted(val code: InviteCode, val token: ListingToken, val deliveries: Map<SteamId, Delivery> = emptyMap())

enum class ServersRead { Idle, Loading, Ready, Unreachable }

data class Servers(val read: ServersRead = ServersRead.Idle, val games: List<PublicGame> = emptyList(), val ping: Map<Int, Int> = emptyMap(), val pingAt: Instant? = null)

/**
 * What the in-game surfaces need beyond the session. [inbox] is what shows, newest first, and [seen] every invite that
 * came, until its expiry. [hosted] follows the session while it hosts with a code, so a new game starts with no
 * deliveries. [streams] counts open in-game streams and [closedAt] is when the last one closed. [gameSelf] is the
 * game's own Steam account, when its UI said it.
 */
data class SocialState(
    val inbox: List<Invitation> = emptyList(),
    val seen: Map<InvitationKey, Instant> = emptyMap(),
    val hosted: Hosted? = null,
    val servers: Servers = Servers(),
    val streams: Int = 0,
    val closedAt: Instant? = null,
    val gameSelf: SteamId? = null,
)

sealed interface SocialInput {
    data class Live(val live: LiveSession) : SocialInput

    data class Came(val invitation: Invitation) : SocialInput

    /** Joined or dismissed in some context. */
    data class Closed(val id: InvitationId) : SocialInput

    data class Answered(val code: InviteCode, val to: SteamId, val delivery: Delivery) : SocialInput

    data object ServersWanted : SocialInput

    data class ServersAnswered(val games: List<PublicGame>?, val ping: Map<Int, Int>?) : SocialInput

    data class Streams(val open: Int) : SocialInput

    data class GameSelf(val id: SteamId?) : SocialInput

    data object Tick : SocialInput
}

sealed interface SocialEffect {
    data class Deliver(val code: InviteCode, val to: SteamId, val ticket: JoinTicket, val players: Int) : SocialEffect

    data class ReadServers(val measure: Boolean) : SocialEffect
}

val MailboxGrace = 30.seconds
val PingLifetime = 5.minutes

/**
 * Pure and total. Deliveries converge: on every [SocialInput.Live] each invitee of the hosted game with no delivery yet
 * gets one, so a friend invited during the start goes out with the rest once the code arrives, and a repeated Live sends
 * nothing twice. [fresh] draws a listing token, used only when a new hosted code appears.
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

/** Pure. The listing the directory should hold now: set only while the session hosts a public feed game with a code. */
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

/** Pure. Whose mailbox should be open: [me]'s while a game of theirs listens, or did within [MailboxGrace]. */
fun mailboxOwner(state: SocialState, now: Instant, me: SteamId?): SteamId? {
    val listening = state.streams > 0 || state.closedAt?.let { now - it < MailboxGrace } == true
    return me?.takeIf { listening && (state.gameSelf == null || state.gameSelf == it) }
}

/**
 * One coroutine reads [SocialInput]s, runs [reduceSocial], publishes [state] and runs the effects, as [CustomGames]
 * does. It writes nothing [CustomGames] owns: invitees and the listing choice live in the hosted game's record, and this
 * only acts on them. [network] is null in a build with no invite key, where nothing is sent and every invite is
 * [Delivery.NotReached], so the host gets Copy link.
 */
class Social(
    private val games: CustomGames,
    private val network: SocialNetwork?,
    private val scope: CoroutineScope,
    private val me: () -> SteamId? = { games.localPlayer?.steamId?.let(SteamId::parse) },
    private val clock: Clock = Clock.System,
    private val newToken: () -> ListingToken = ::drawToken,
) {
    private val published = MutableStateFlow(SocialState())
    val state: StateFlow<SocialState> = published.asStateFlow()

    private val inputs = Channel<SocialInput>(Channel.UNLIMITED)

    val available: Boolean get() = network != null

    fun start() {
        scope.launch { for (input in inputs) step(input) }
        scope.launch { games.live.collect { post(SocialInput.Live(it)) } }
        network?.let { scope.launch { it.mailbox.invitations.collect { invitation -> post(SocialInput.Came(invitation)) } } }
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
            else -> SocialStatus.On
        }
    }

    /** The in-game endpoint's open stream count, as it changes. */
    fun streams(open: Int) = post(SocialInput.Streams(open))

    fun gameSelf(id: SteamId?) = post(SocialInput.GameSelf(id))

    fun wantServers() = post(SocialInput.ServersWanted)

    /** The ticket of an invite still showing, which then closes everywhere. Null once it expired or another context took it. */
    fun take(id: InvitationId): JoinTicket? {
        val ticket = published.value.inbox.firstOrNull { it.id == id }?.ticket ?: return null
        post(SocialInput.Closed(id))
        return ticket
    }

    fun forget(id: InvitationId) = post(SocialInput.Closed(id))

    /** Asks the directory for a listed game's code at the press, so a closed or full game is [Resolved.Gone] then. */
    suspend fun resolve(id: ListingId): Resolved = network?.directory?.resolve(id) ?: Resolved.Gone

    private fun post(input: SocialInput) {
        inputs.trySend(input)
    }

    private fun step(input: SocialInput) {
        val now = clock.now()
        val (next, effects) = reduceSocial(published.value, input, now, newToken)
        published.value = next
        network?.mailbox?.hold(mailboxOwner(next, now, me()))
        network?.directory?.keep(listingWanted(next, games.live.value))
        effects.forEach(::execute)
    }

    private fun execute(effect: SocialEffect) {
        when (effect) {
            is SocialEffect.Deliver -> scope.launch {
                val from = me()
                val delivery = if (network == null || from == null) Delivery.NotReached else network.mailbox.deliver(effect.to, from, effect.ticket, effect.players)
                post(SocialInput.Answered(effect.code, effect.to, delivery))
            }
            is SocialEffect.ReadServers -> scope.launch {
                val games = network?.directory?.list()
                val ping = if (effect.measure && games != null) network.latency.measure() else null
                post(SocialInput.ServersAnswered(games, ping))
            }
        }
    }
}

private val random = SecureRandom()

private fun drawToken(): ListingToken = ListingToken(HexFormat.of().formatHex(ByteArray(16).also(random::nextBytes)))
