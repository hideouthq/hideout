package gg.hideouthq.feature.ingame

import gg.hideouthq.core.customgames.Listing
import gg.hideouthq.core.customgames.ModeId
import gg.hideouthq.core.customgames.Outreach
import gg.hideouthq.core.customgames.RestartReason
import gg.hideouthq.core.customgames.SettingKey
import gg.hideouthq.core.customgames.SettingValue
import gg.hideouthq.core.customgames.SteamId
import gg.hideouthq.core.customgames.social.InvitationId
import gg.hideouthq.core.customgames.social.ListingId
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Bumped when a frame or command changes shape. The pack's own number rides in every command, and `hello` says the app's. */
const val InGameProtocol = 1

/** A stream's frames, each replacing the last of its topic. */
enum class Topic { Hello, Catalog, Game, Inbox, Servers }

@Serializable
sealed interface Frame {
    @Serializable
    @SerialName("hello")
    data class Hello(val protocol: Int, val social: SocialWire) : Frame

    @Serializable
    @SerialName("catalog")
    data class Catalog(val modes: List<ModeCard>) : Frame

    /** [restartAhead] is what Play would do to the running Deadlock first, echoed back in `host`. */
    @Serializable
    @SerialName("game")
    data class Game(val host: HostCard?, val join: JoinCard?, val restartAhead: Restart?) : Frame

    /** Invites to show, newest first. Each leaves at its [InviteCard.expires], or once any context joins or forgets it. */
    @Serializable
    @SerialName("inbox")
    data class Inbox(val invites: List<InviteCard>) : Frame

    @Serializable
    @SerialName("servers")
    data class Servers(val state: ServersState, val rows: List<ServerRow>) : Frame

    /** Sent after a quiet while, so the game can tell a quiet stream from a dead one. */
    @Serializable
    @SerialName("alive")
    data object Alive : Frame
}

/**
 * Whether invites and Servers work. [Off] is a build without the invite key, or no Steam login the app can read.
 * [OtherAccount] is a game signed in to another Steam account than the app reads, whose invites would go elsewhere.
 */
@Serializable
enum class SocialWire { @SerialName("on") On, @SerialName("off") Off, @SerialName("other-account") OtherAccount }

@Serializable
data class ModeCard(
    val id: String,
    val name: String,
    val tagline: String,
    val kind: String?,
    val players: List<Int>,
    val minutes: List<Int>?,
    val by: List<String>,
    /** What hosting it downloads first, or null when it is on this computer. */
    val bytes: Long?,
    val art: ModeArtCard,
    val settings: List<SettingCard>,
)

@Serializable
data class ModeArtCard(val card: String?, val backdrop: String?, val render: String?, val overlay: String?, val tile: String?)

@Serializable
data class SettingCard(val key: String, val label: String, val hint: String?, val default: String, val options: List<OptionCard>)

@Serializable
data class OptionCard(val value: String, val label: String, val players: Int?)

@Serializable
data class Words(val title: String, val body: String)

@Serializable
data class Restart(val reason: RestartWire, val text: String)

@Serializable
enum class RestartWire(val reason: RestartReason) {
    @SerialName("not-set-up") NotSetUp(RestartReason.NotSetUp),
    @SerialName("game-updated") GameUpdated(RestartReason.GameUpdated),
    @SerialName("menus-did-not-start") MenusDidNotStart(RestartReason.MenusDidNotStart),
    ;

    companion object {
        fun of(reason: RestartReason): RestartWire = entries.first { it.reason == reason }
    }
}

@Serializable
data class StepCard(val label: String, val status: StepState, val note: String?, val progress: Float?)

@Serializable
enum class StepState { @SerialName("done") Done, @SerialName("running") Running, @SerialName("waiting") Waiting }

/** A card's buttons, each the `act` command of the same name. */
@Serializable
enum class Action { @SerialName("stop") Stop, @SerialName("restart") Restart, @SerialName("play") Play, @SerialName("dismiss") Dismiss, @SerialName("again") Again }

@Serializable
enum class ListingWire(val listing: Listing) {
    @SerialName("invite-only") InviteOnly(Listing.InviteOnly),
    @SerialName("public") Public(Listing.Public),
    ;

    companion object {
        fun of(listing: Listing): ListingWire = entries.first { it.listing == listing }
    }
}

/** The game this computer hosts, from the app's own game screen. */
@Serializable
data class HostCard(
    val mode: String,
    val phase: HostPhase,
    val steps: List<StepCard>,
    val words: Words?,
    /** The join link, while friends can use it. */
    val link: String?,
    /** Signed on, then the match size, while the game runs. */
    val players: List<Int>?,
    val listing: ListingWire,
    val invitees: List<String>,
    /** What each invite sent so far came to, by invitee. An invitee missing here waits for the game to be ready. */
    val deliveries: Map<String, DeliveryWire>,
    val actions: List<Action>,
)

/** [NotReached] means no Hideout of theirs took it, so the card offers Copy link. */
@Serializable
enum class DeliveryWire { @SerialName("sending") Sending, @SerialName("delivered") Delivered, @SerialName("not-reached") NotReached }

@Serializable
enum class HostPhase {
    @SerialName("starting") Starting,
    @SerialName("restart") Restart,
    @SerialName("restarting") Restarting,
    @SerialName("entering") Entering,
    @SerialName("ready") Ready,
    @SerialName("notin") NotIn,
    @SerialName("ending") Ending,
    @SerialName("failed") Failed,
    @SerialName("ended") Ended,
}

/**
 * A join on this computer that has not reached the match, from the app's join dialog. In [JoinPhase.Review] the card
 * offers Join, which is the `confirm` command carrying [restart].
 */
@Serializable
data class JoinCard(
    val title: String,
    val mode: String,
    val picks: List<String>,
    val capacity: Int?,
    val phase: JoinPhase,
    val steps: List<StepCard>,
    val words: Words?,
    /** The line beside a spinner, while the dialog shows one. */
    val busy: String?,
    val restart: Restart?,
    val actions: List<Action>,
)

@Serializable
enum class JoinPhase {
    @SerialName("finding") Finding,
    @SerialName("review") Review,
    @SerialName("cleanup") Cleanup,
    @SerialName("progress") Progress,
    @SerialName("stopping") Stopping,
    @SerialName("restart") Restart,
    @SerialName("restarting") Restarting,
    @SerialName("notin") NotIn,
    @SerialName("failed") Failed,
}

/**
 * An invite that came in. [from] is the sender's claim, which the game shows only for a Steam friend and under the
 * friend list's own name. [mode] and [picks] read as the join dialog's do. [expires] is in epoch milliseconds.
 */
@Serializable
data class InviteCard(
    val id: String,
    val from: String,
    val mode: String,
    val picks: List<String>,
    val tile: String?,
    /** Signed on, then the match size. */
    val players: List<Int>,
    val expires: Long,
)

@Serializable
enum class ServersState {
    /** Not read since the app started. */
    @SerialName("idle") Idle,
    @SerialName("loading") Loading,
    @SerialName("ready") Ready,
    @SerialName("unreachable") Unreachable,

    /** This build can't read the directory. */
    @SerialName("unavailable") Unavailable,
}

/** A public game. [host] is the name its offer carries. [region] is the host's relay region code and [ping] this computer's round trip to it. */
@Serializable
data class ServerRow(
    val id: String,
    val host: String?,
    val mode: String,
    val picks: List<String>,
    val tile: String?,
    val region: String,
    val players: List<Int>,
    val ping: Int?,
    val status: ServerStatus,
)

@Serializable
enum class ServerStatus { @SerialName("lobby") Lobby, @SerialName("match") Match, @SerialName("full") Full }

/** What the endpoint answers a command with, as the width of a one-pixel-high PNG. */
enum class CommandResult(val width: Int) {
    Accepted(1),

    /** A session runs that this can't start beside. */
    Busy(2),

    /** The mode, invite or server is no longer there. */
    Gone(3),
    Invalid(4),

    /** Invites and Servers aren't in this build yet. */
    Unavailable(5),

    /** The pack speaks another protocol, and only a restart of Deadlock loads the one the app built. */
    Outdated(6),
}

/** A press in the game, after [parseCommand]. Nothing past the parse sees the wire's strings. */
sealed interface PanelCommand {
    data class Host(val mode: ModeId, val picks: Map<SettingKey, SettingValue>, val outreach: Outreach, val restart: RestartReason?) : PanelCommand

    data class Act(val action: Action) : PanelCommand

    /** Join after a review, with the restart the card said. */
    data class Confirm(val restart: RestartReason?) : PanelCommand

    data class Invite(val to: Set<SteamId>) : PanelCommand

    data class Relist(val listing: Listing) : PanelCommand

    data class JoinInvite(val id: InvitationId) : PanelCommand

    data class JoinServer(val id: ListingId) : PanelCommand

    /** Closes an invite's toast in every context. */
    data class Forget(val id: InvitationId) : PanelCommand

    /** The Servers tab wants a fresh read. */
    data object Servers : PanelCommand

    /** The game's own Steam account, when the UI can read it, so the app opens no mailbox for another account. */
    data class Hello(val self: SteamId?) : PanelCommand
}

object InGameWire {
    private val json = Json { classDiscriminator = "t" }

    /** ASCII only, with `\u` escapes, so the game can read a frame's bytes without decoding UTF-8. */
    fun encode(frame: Frame): String = json.encodeToString(Frame.serializer(), frame).asciiOnly()

    /** One line of the bridge page's stream, `~<sequence>:<packed>`, packed as Modlock's `panorama.AppendPackedLine` packs. */
    fun line(sequence: Long, frame: String): ByteArray = buildString {
        append('~').append(sequence).append(':')
        for (byte in frame.encodeToByteArray()) {
            val b = byte.toInt() and 0xff
            append(if (b > ' '.code && b < 0x7f && b != '\\'.code) b.toChar() else (0x100 + b).toChar())
        }
        append('\n')
    }.encodeToByteArray()

    private fun String.asciiOnly(): String = buildString(length) {
        for (c in this@asciiOnly) if (c.code < 0x80) append(c) else append("\\u").append(c.code.toString(16).padStart(4, '0'))
    }

    /** The `c=` query, JSON. Null for anything malformed, too long, or of an unknown kind. */
    fun parseCommand(text: String): PanelCommand? {
        if (text.length > MaxCommand) return null
        val command = try {
            json.parseToJsonElement(text) as? JsonObject
        } catch (e: SerializationException) {
            null
        } ?: return null
        return when (command.text("k")) {
            "host" -> host(command)
            "act" -> decode(command["action"], Action.serializer())?.let(PanelCommand::Act)
            "confirm" -> restartIn(command)?.let { PanelCommand.Confirm(it.reason) }
            "invite" -> steamIds(command["to"])?.let(PanelCommand::Invite)
            "listing" -> decode(command["listing"], ListingWire.serializer())?.let { PanelCommand.Relist(it.listing) }
            "join" -> when {
                command.size != 2 -> null
                "invite" in command -> command.text("invite")?.let(InvitationId::parse)?.let(PanelCommand::JoinInvite)
                else -> command.text("server")?.let(ListingId::parse)?.let(PanelCommand::JoinServer)
            }
            "forget" -> command.text("invite")?.let(InvitationId::parse)?.let(PanelCommand::Forget)
            "servers" -> PanelCommand.Servers.takeIf { command.size == 1 }
            "hello" -> when (val self = command["self"]) {
                JsonNull -> PanelCommand.Hello(null)
                else -> (self as? JsonPrimitive)?.takeIf { it.isString }?.content?.let(SteamId::parse)?.let { PanelCommand.Hello(it) }
            }
            else -> null
        }
    }

    private fun host(command: JsonObject): PanelCommand.Host? {
        val mode = command.text("mode")?.let(ModeId::parse) ?: return null
        val picks = (command["picks"] as? JsonObject ?: return null).entries.associate { (key, value) ->
            (SettingKey.parse(key) ?: return null) to ((value as? JsonPrimitive)?.takeIf { it.isString }?.content?.let(SettingValue::parse) ?: return null)
        }
        val listing = decode(command["listing"], ListingWire.serializer())?.listing ?: return null
        val invite = steamIds(command["invite"]) ?: return null
        val restart = restartIn(command) ?: return null
        return PanelCommand.Host(mode, picks, Outreach(listing, invite), restart.reason)
    }

    /** Present, and null or a known reason, as a press echoes what it was shown. */
    private class Echo(val reason: RestartReason?)

    private fun restartIn(command: JsonObject): Echo? = when (val restart = command["restart"]) {
        null -> null
        JsonNull -> Echo(null)
        else -> decode(restart, RestartWire.serializer())?.let { Echo(it.reason) }
    }

    private fun steamIds(element: JsonElement?): Set<SteamId>? {
        val array = element as? JsonArray ?: return null
        if (array.size > MaxInvitees) return null
        return array.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.let(SteamId::parse) ?: return null }.toSet()
    }

    private fun <T> decode(element: JsonElement?, serializer: KSerializer<T>): T? = try {
        element?.let { json.decodeFromJsonElement(serializer, it) }
    } catch (e: SerializationException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private const val MaxCommand = 4096
    private const val MaxInvitees = 63
}
