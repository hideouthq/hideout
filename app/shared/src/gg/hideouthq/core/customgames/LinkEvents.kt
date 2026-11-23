package gg.hideouthq.core.customgames

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/** The helper's stdout, parsed once at the boundary. These lines feed straight into [reduce]. */
sealed interface LinkEvent : SessionEvent {
    data class InviteReady(val code: InviteCode, val region: RelayRegion? = null) : LinkEvent

    data class Offer(val offer: InviteOffer) : LinkEvent

    data object BadOffer : LinkEvent

    data class Server(val event: ServerEvent) : LinkEvent

    data object Linked : LinkEvent

    /** A null [kind] says the tunnel stopped answering, which it can start doing again. */
    data class Path(val kind: LinkPath?) : LinkEvent

    data class Guests(val paths: GuestPaths) : LinkEvent

    /** A guest's helper says this of its game's packets, a host's of its own account on the server. */
    data class Game(val traffic: GameTraffic) : LinkEvent

    /** The game's page took the connect for an `enter`, or the game was already on the server. */
    data object Entered : LinkEvent

    /** The page took nothing within the `enter`'s window. [siteUnreachable] says the helper's own fetch of the bridge page failed. */
    data class NotEntered(val siteUnreachable: Boolean) : LinkEvent

    /** What the mode reported on its tagged log line, which the host's helper alone reads off Modlock's stream. */
    data class Reported(val states: List<SectionState>) : LinkEvent

    /** How much of the host's pack has arrived. */
    data class Fetch(val transfer: Transfer) : LinkEvent

    /** The host's pack arrived whole and checked, in the file the helper was told to fetch it to. */
    data object Fetched : LinkEvent

    /**
     * The helper's last line. When its output ends without one, [LinkProcess] emits `Stopped(Unknown)` itself, and
     * when it cannot be started the owner posts `Stopped(Unknown)`, so a session waiting on the helper always moves on.
     */
    data class Stopped(val reason: LinkStop, val message: String? = null) : LinkEvent
}

enum class LinkPath { Direct, Relay }

/**
 * What the helper sees of the game's own packets right now, said again every second. [Flowing] means both the game and
 * the server sent lately. [Lost] says that stopped, or that a game keeps asking with no answer, while the tunnel itself
 * may be fine. [Quiet] says no game is sending, or a new one has yet to be answered or lost.
 */
enum class GameTraffic { Flowing, Lost, Quiet }

/** How many of a host's guests its tunnel is talking to, by path. It says nothing about which guest, which the host has no way to name. */
data class GuestPaths(val direct: Int = 0, val relay: Int = 0)

enum class LinkStop { StdinClosed, Signal, ServerExited, ServerStartFailed, ServerDead, LinkTimeout, NotFound, LinkLost, HostEnded, HostFailed, BadInvite, RelayUnavailable, ListenFailed, FetchFailed, FetchMismatch, Unknown }

/** A human in the game, for a screen to show. It says who a friend is, so it prints as nothing and goes in no log. */
data class Player(val name: String, val steamId: String) {
    override fun toString() = "Player(redacted)"
}

/** Modlock's `--json` lines, which nest as `{"host":{"ready":{"map":…}}}`. [Failed] does not mean the server stopped. [Ready] fires per world load, even one the server does not survive. */
sealed interface ServerEvent {
    data class Ready(val map: String) : ServerEvent

    /** A null [error] says the server gave no words for it. */
    data class Failed(val error: String?) : ServerEvent

    /** [player] is null for a bot. */
    data class Joined(val slot: Int, val player: Player?) : ServerEvent

    /** Bots leave too, and a slot may never have been seen joining. */
    data class Left(val slot: Int) : ServerEvent
}

data class RelayRegion(val id: Int, val code: String)

private val RegionCode = Regex("[a-z0-9-]{1,16}")

private fun relayRegion(json: JsonObject): RelayRegion? {
    val id = json.int("id")?.takeIf { it in 1..999_999 } ?: return null
    return json.text("code")?.takeIf(RegionCode::matches)?.let { RelayRegion(id, it) }
}

/** The [LinkEvent] on one line of the helper's stdout, or null for a line it cannot read or a line nothing here acts on, such as `flow-open`. Never throws. */
fun parseLinkEvent(line: String): LinkEvent? {
    val json = try {
        Json.parseToJsonElement(line) as? JsonObject
    } catch (e: IllegalArgumentException) {
        null
    } catch (e: StackOverflowError) {
        // The JSON reader recurses on nested arrays, and this runs on whatever the helper's child printed.
        null
    } ?: return null
    return when (json.text("type")) {
        "invite" -> json.text("code")?.let(InviteCode::parse)?.let { LinkEvent.InviteReady(it, (json["region"] as? JsonObject)?.let(::relayRegion)) }
        "offer" -> json.text("offer")?.let(InviteOffer::decode)?.let(LinkEvent::Offer) ?: LinkEvent.BadOffer
        "server" -> (json["event"] as? JsonObject)?.let(::serverEvent)?.let(LinkEvent::Server)
        "linked" -> LinkEvent.Linked
        "path" -> when (json.text("kind")) {
            "direct" -> LinkEvent.Path(LinkPath.Direct)
            "relay" -> LinkEvent.Path(LinkPath.Relay)
            "none" -> LinkEvent.Path(null)
            else -> null
        }
        "game" -> gameTraffic(json.text("state"))?.let(LinkEvent::Game)
        "guests" -> LinkEvent.Guests(GuestPaths(json.int("direct") ?: 0, json.int("relay") ?: 0))
        "entered" -> LinkEvent.Entered
        "enter-expired" -> LinkEvent.NotEntered(json.text("page") == "unreachable")
        "state" -> (json["state"] as? JsonObject)?.let(::parseSectionState)?.takeIf { it.isNotEmpty() }?.let(LinkEvent::Reported)
        "fetch" -> json.long("done")?.let { done -> json.long("total")?.let { LinkEvent.Fetch(Transfer(done, it)) } }
        "fetched" -> LinkEvent.Fetched
        "stopped" -> LinkEvent.Stopped(linkStop(json.text("reason")), json.words("message"))
        else -> null
    }
}

private fun gameTraffic(state: String?): GameTraffic? = when (state) {
    "flowing" -> GameTraffic.Flowing
    "lost" -> GameTraffic.Lost
    "quiet" -> GameTraffic.Quiet
    else -> null
}

private fun serverEvent(event: JsonObject): ServerEvent? {
    val (kind, body) = event.entries.firstOrNull() ?: return null
    val fields = body as? JsonObject
    return when (kind) {
        "host" -> fields?.let(::hostEvent)
        // With no game to run, `play` silently falls back to running the mods with no server at all.
        "sandbox" -> ServerEvent.Failed(fields?.words("reason"))
        else -> null
    }
}

private fun hostEvent(host: JsonObject): ServerEvent? {
    val (kind, body) = host.entries.firstOrNull() ?: return null
    val fields = body as? JsonObject ?: return null
    // Protobuf JSON leaves out zero values, so a missing slot is slot 0 and a missing map is an empty one.
    return when (kind) {
        "ready" -> ServerEvent.Ready(fields.text("map").orEmpty())
        "failed" -> ServerEvent.Failed(fields.words("error"))
        "joined" -> ServerEvent.Joined(fields.int("slot") ?: 0, fields.text("steamId")?.takeIf { it != "0" }?.let { Player(fields.text("name").orEmpty(), it) })
        "left" -> ServerEvent.Left(fields.int("slot") ?: 0)
        else -> null
    }
}

private fun linkStop(reason: String?): LinkStop = when (reason) {
    "stdin-closed" -> LinkStop.StdinClosed
    "signal" -> LinkStop.Signal
    "server-exited" -> LinkStop.ServerExited
    "server-start-failed" -> LinkStop.ServerStartFailed
    "server-dead" -> LinkStop.ServerDead
    "link-timeout" -> LinkStop.LinkTimeout
    "not-found" -> LinkStop.NotFound
    "link-lost" -> LinkStop.LinkLost
    "host-ended" -> LinkStop.HostEnded
    "host-failed" -> LinkStop.HostFailed
    "bad-invite" -> LinkStop.BadInvite
    "relay-unavailable" -> LinkStop.RelayUnavailable
    "listen-failed" -> LinkStop.ListenFailed
    "fetch-failed" -> LinkStop.FetchFailed
    "fetch-mismatch" -> LinkStop.FetchMismatch
    else -> LinkStop.Unknown
}

private fun JsonObject.primitive(key: String): JsonPrimitive? = (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }

private fun JsonObject.text(key: String): String? = primitive(key)?.content

private fun JsonObject.words(key: String): String? = text(key)?.takeUnless { it.isBlank() }?.let(::redactInvites)

private fun JsonObject.int(key: String): Int? = primitive(key)?.intOrNull

private fun JsonObject.long(key: String): Long? = primitive(key)?.longOrNull
