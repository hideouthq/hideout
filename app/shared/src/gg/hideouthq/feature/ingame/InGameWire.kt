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

/** Bump when a frame or command changes shape. */
const val InGameProtocol = 1

enum class Topic { Hello, Catalog, Game, Inbox, Servers }

@Serializable
sealed interface Frame {
    /** [supportId] is set only while [social] is blocked: the player's own SteamID64, for support to reset this PC. */
    @Serializable
    @SerialName("hello")
    data class Hello(val protocol: Int, val social: SocialWire, val supportId: String?) : Frame

    @Serializable
    @SerialName("catalog")
    data class Catalog(val modes: List<ModeCard>) : Frame

    @Serializable
    @SerialName("game")
    data class Game(val host: HostCard?, val join: JoinCard?, val restartAhead: Restart?) : Frame

    @Serializable
    @SerialName("inbox")
    data class Inbox(val invites: List<InviteCard>) : Frame

    @Serializable
    @SerialName("servers")
    data class Servers(val state: ServersState, val rows: List<ServerRow>) : Frame

    @Serializable
    @SerialName("alive")
    data object Alive : Frame
}

@Serializable
enum class SocialWire { @SerialName("on") On, @SerialName("off") Off, @SerialName("other-account") OtherAccount, @SerialName("blocked") Blocked }

@Serializable
data class ModeCard(
    val id: String,
    val name: String,
    val tagline: String,
    val kind: String?,
    val players: List<Int>,
    val minutes: List<Int>?,
    val by: List<String>,
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

@Serializable
data class HostCard(
    val mode: String,
    val phase: HostPhase,
    val steps: List<StepCard>,
    val words: Words?,
    val link: String?,
    val players: List<Int>?,
    val listing: ListingWire,
    val invitees: List<String>,
    val deliveries: Map<String, DeliveryWire>,
    val actions: List<Action>,
)

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

@Serializable
data class JoinCard(
    val title: String,
    val mode: String,
    val picks: List<String>,
    val capacity: Int?,
    val phase: JoinPhase,
    val steps: List<StepCard>,
    val words: Words?,
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

@Serializable
data class InviteCard(
    val id: String,
    val from: String,
    val mode: String,
    val picks: List<String>,
    val tile: String?,
    val players: List<Int>,
    val expires: Long,
)

@Serializable
enum class ServersState {
    @SerialName("idle") Idle,
    @SerialName("loading") Loading,
    @SerialName("ready") Ready,
    @SerialName("unreachable") Unreachable,

    @SerialName("unavailable") Unavailable,
}

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

/** Answered as the width of a one-pixel-high PNG. */
enum class CommandResult(val width: Int) {
    Accepted(1),

    Busy(2),

    Gone(3),
    Invalid(4),

    Unavailable(5),

    Outdated(6),
}

sealed interface PanelCommand {
    data class Host(val mode: ModeId, val picks: Map<SettingKey, SettingValue>, val outreach: Outreach, val restart: RestartReason?) : PanelCommand

    data class Act(val action: Action) : PanelCommand

    data class Confirm(val restart: RestartReason?) : PanelCommand

    data class Invite(val to: Set<SteamId>) : PanelCommand

    data class Relist(val listing: Listing) : PanelCommand

    data class JoinInvite(val id: InvitationId) : PanelCommand

    data class JoinServer(val id: ListingId) : PanelCommand

    data class Forget(val id: InvitationId) : PanelCommand

    data object Servers : PanelCommand

    data class Hello(val self: SteamId?) : PanelCommand
}

object InGameWire {
    private val json = Json { classDiscriminator = "t" }

    fun encode(frame: Frame): String = json.encodeToString(Frame.serializer(), frame).asciiOnly()

    /** Packs as Modlock's `panorama.AppendPackedLine` does. */
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
            "hello" -> if (command["self"] == JsonNull) PanelCommand.Hello(null) else command.text("self")?.let(SteamId::parse)?.let(PanelCommand::Hello)
            else -> null
        }
    }

    private fun host(command: JsonObject): PanelCommand.Host? {
        val mode = command.text("mode")?.let(ModeId::parse) ?: return null
        val picks = (command["picks"] as? JsonObject ?: return null).entries.associate { (key, value) ->
            (SettingKey.parse(key) ?: return null) to (value.string()?.let(SettingValue::parse) ?: return null)
        }
        val listing = decode(command["listing"], ListingWire.serializer())?.listing ?: return null
        val invite = steamIds(command["invite"]) ?: return null
        val restart = restartIn(command) ?: return null
        return PanelCommand.Host(mode, picks, Outreach(listing, invite), restart.reason)
    }

    /** A press echoes the restart it was shown, so `restart` must be present, even as null. */
    private class Echo(val reason: RestartReason?)

    private fun restartIn(command: JsonObject): Echo? = when (val restart = command["restart"]) {
        null -> null
        JsonNull -> Echo(null)
        else -> decode(restart, RestartWire.serializer())?.let { Echo(it.reason) }
    }

    private fun steamIds(element: JsonElement?): Set<SteamId>? {
        val array = element as? JsonArray ?: return null
        if (array.size > MaxInvitees) return null
        return array.map { it.string()?.let(SteamId::parse) ?: return null }.toSet()
    }

    private fun <T> decode(element: JsonElement?, serializer: KSerializer<T>): T? = try {
        element?.let { json.decodeFromJsonElement(serializer, it) }
    } catch (e: SerializationException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }

    private fun JsonObject.text(key: String): String? = this[key].string()

    private fun JsonElement?.string(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

    private const val MaxCommand = 4096
    private const val MaxInvitees = 63
}
