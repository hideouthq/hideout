package gg.hideouthq.core.customgames

import gg.hideouthq.core.customgames.SessionEvent.HostRequested
import kotlin.time.Instant

/** One snapshot per event: where the session is, and the game it is for, so a screen never pairs a stage with another moment's record. */
data class LiveSession(val session: Session = Session.Idle, val game: GameRecord? = null)

/**
 * What a session is for and how far it got, which [Session]'s stages alone can't say: [Session.Ended] doesn't know its
 * role or whether the game ever ran, and no host stage before [Session.HostAdmitting] knows its mode. Folded by
 * [recordAfter] beside [reduce], and kept through [Session.Ended], so playing again needs nothing a
 * screen remembered.
 */
data class GameRecord(
    val role: Role,
    val me: LocalPlayer?,
    /** When the host reached [Session.Hosting], or the guest's game was first in the match. Before that, an end is a failure to start. */
    val startedAt: Instant? = null,
    val reported: Map<SectionKind, SectionState> = emptyMap(),
    val endedAt: Instant? = null,
    /** Everyone who signed on to a host's game, by the slot each last held, kept after they leave so the result can name them. */
    val players: Map<Int, Player> = emptyMap(),
    /** The slots signed on when the host's game last ran, which its end sends back to the menu. */
    val signedOn: Set<Int> = emptySet(),
    /** The host's server loaded its map, so a start that failed after that failed on the way to friends. */
    val arenaLoaded: Boolean = false,
) {
    val started: Boolean get() = startedAt != null

    /** Null for a guest whose own list doesn't name the mode, as for a friend's folder. */
    val listing: ModeListing?
        get() = when (role) {
            is Role.Host -> role.mode.listing
            is Role.Guest -> role.listing
        }

    val choices: Choices
        get() = when (role) {
            is Role.Host -> role.choices
            is Role.Guest -> role.offer?.choices ?: Choices.None
        }

    val capacity: Int?
        get() = when (role) {
            is Role.Host -> role.mode.listing.capacity(role.choices)
            is Role.Guest -> role.offer?.capacity
        }
}

sealed interface Role {
    data class Host(val mode: Mode, val choices: Choices) : Role

    data class Guest(val code: InviteCode, val offer: InviteOffer?, val listing: ModeListing?) : Role
}

class RecordContext(val me: LocalPlayer?, val listingOf: (InviteOffer) -> ModeListing?, val now: Instant)

/**
 * Pure and total. A code reaching [Session.Finding] begins a guest record unless it is the one already being found, and
 * a record of an ended game is never carried into a new one. The host's offer joins the record as it arrives.
 */
fun recordAfter(record: GameRecord?, event: SessionEvent, next: Session, context: RecordContext): GameRecord? = when {
    next == Session.Idle || next == Session.Recovering -> null
    event is HostRequested && next is Session.HostPreparing -> GameRecord(Role.Host(event.mode, event.choices), context.me)
    next is Session.Finding && !(record?.endedAt == null && (record?.role as? Role.Guest)?.code == next.code) ->
        GameRecord(Role.Guest(next.code, next.acceptedBefore, next.acceptedBefore?.let(context.listingOf)), context.me)
    else -> record?.let { kept ->
        val role = kept.role
        val signedOn = (next as? Session.Hosting)?.players
        kept.copy(
            role = if (role is Role.Guest) role.found((next as? Session.Confirming)?.invite?.offer, context) else role,
            me = kept.me ?: context.me,
            startedAt = kept.startedAt ?: context.now.takeIf { next is Session.Hosting || (next is Session.Playing && next.own == OwnGame.In) },
            reported = if (event is LinkEvent.Reported) kept.reported + event.states.associateBy { it.kind } else kept.reported,
            endedAt = kept.endedAt ?: context.now.takeIf { next is Session.Ended },
            players = signedOn?.let { now -> kept.players.filter { (slot, player) -> slot !in now && now.values.none { it.steamId == player.steamId } } + now } ?: kept.players,
            signedOn = signedOn?.keys ?: kept.signedOn,
            arenaLoaded = kept.arenaLoaded || next is Session.Hosting || (next as? Session.HostStarting)?.map != null,
        )
    }
}

private fun Role.Guest.found(answered: InviteOffer?, context: RecordContext): Role.Guest = when {
    answered != null && answered != offer -> copy(offer = answered, listing = context.listingOf(answered))
    listing == null && offer != null -> copy(listing = context.listingOf(offer))
    else -> this
}

fun Map<Int, Player>.slotOf(me: LocalPlayer?): Int? = entries.firstOrNull { me != null && it.value.steamId == me.steamId }?.key
