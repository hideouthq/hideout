package gg.hideouthq.core.customgames

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

enum class DesktopOs(val feedKey: String) {
    Linux("linux"),
    Windows("windows"),
}

@JvmInline
value class Sha256 private constructor(val hex: String) {
    companion object {
        private val Shape = Regex("[0-9a-f]{64}")

        fun parse(text: String): Sha256? = if (Shape.matches(text)) Sha256(text) else null
    }
}

@JvmInline
value class ModeId(val value: String) {
    companion object {
        private val Shape = Regex("[a-z0-9][a-z0-9._-]{0,63}")

        /** It names a folder, so nothing that could reach another passes. */
        fun parse(text: String): ModeId? = if (Shape.matches(text)) ModeId(text) else null
    }
}

/** The `SourceRevision` in the game's `steam.inf`. */
@JvmInline
value class GameRevision(val source: Long)

data class Artifact(val url: String, val bytes: Long, val sha256: Sha256)

/** [upcoming] are announced with no package yet, in the feed's order, none sharing an id with [modes]. */
data class CustomGamesFeed(val sequence: Long, val runtime: ModeRuntime, val modes: List<Mode>, val upcoming: List<UpcomingMode> = emptyList()) {
    fun mode(id: ModeId): Mode? = modes.firstOrNull { it.id == id }
}

/** A mode the feed announces before it ships. Its [listing] may have no release date. */
data class UpcomingMode(val id: ModeId, val listing: ModeListing)

/** [cli] may lack an OS, which then cannot host, but always names at least one. */
data class ModeRuntime(val cli: Map<DesktopOs, Artifact>, val host: Artifact, val platform: Artifact, val renderer: Artifact)

/**
 * Screens read [id], [version] and [listing], so a feed mode and a developer's folder take the same path through every
 * screen. Only the platform reads [origin].
 */
data class Mode(val id: ModeId, val version: String, val listing: ModeListing, val origin: ModeOrigin)

sealed interface ModeOrigin {
    /** Signed and hash-checked. [client] is the content and map a player's game needs, null with stock assets only. */
    data class Feed(val rules: Artifact, val client: Artifact?) : ModeOrigin

    /** A mode author's build, hosted through the real invite flow with no release. */
    data class Folder(val builtDir: String) : ModeOrigin
}

sealed interface FolderRead {
    data class Read(val mode: Mode) : FolderRead

    data object NotThere : FolderRead

    /** No `mod.json` with a slug Hideout can use as the mode's id, and a version. */
    data object NotAMode : FolderRead

    data object BadListing : FolderRead
}

val Mode.packages: Set<Sha256> get() = (origin as? ModeOrigin.Feed)?.let { setOfNotNull(it.rules.sha256, it.client?.sha256) }.orEmpty()

/** The client package's digest, which an invite names. */
val Mode.clientDigest: Sha256? get() = (origin as? ModeOrigin.Feed)?.client?.sha256

/** The runtime a mode shares with other modes isn't counted. */
val Mode.download: Long? get() = (origin as? ModeOrigin.Feed)?.let { it.rules.bytes + (it.client?.bytes ?: 0) }

/**
 * Reads the custom games feed, or null when any part of it breaks a limit. A signed feed with a broken entry is a
 * publishing mistake, so nothing from it is offered. Unknown fields and unknown operating systems are ignored. An
 * upcoming mode is only decoration, so a broken one, or one sharing an id with a mode or an earlier upcoming one, is
 * skipped instead.
 */
fun parseCustomGamesFeed(text: String): CustomGamesFeed? {
    val wire = try {
        FeedJson.decodeFromString<WireFeed>(text)
    } catch (e: IllegalArgumentException) {
        return null
    }
    if (wire.schema != SupportedSchema || wire.sequence <= 0) return null
    val runtime = parseRuntime(wire.runtime) ?: return null
    val modes = wire.modes.map { parseMode(it) ?: return null }
    val ids = modes.map { it.id }
    if (ids.distinct().size != ids.size) return null
    val upcoming = (wire.upcoming as? JsonArray).orEmpty().mapNotNull(::parseUpcoming).filter { it.id !in ids }.distinctBy { it.id }
    return CustomGamesFeed(wire.sequence, runtime, modes, upcoming)
}

private fun parseUpcoming(entry: JsonElement): UpcomingMode? {
    val fields = entry as? JsonObject ?: return null
    val id = (fields["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.let(ModeId::parse) ?: return null
    val listing = parseUpcomingListing(fields["listing"] as? JsonObject ?: return null, ::feedArt) ?: return null
    return UpcomingMode(id, listing)
}

private fun parseRuntime(wire: WireRuntime): ModeRuntime? {
    val cli = buildMap {
        for (os in DesktopOs.entries) {
            val entry = wire.cli[os.feedKey] ?: continue
            put(os, parseCliEntry(entry) ?: return null)
        }
    }
    if (cli.isEmpty()) return null
    return ModeRuntime(cli, parseArtifact(wire.host) ?: return null, parseArtifact(wire.platform) ?: return null, parseArtifact(wire.renderer) ?: return null)
}

private fun parseCliEntry(entry: JsonElement): Artifact? = try {
    parseArtifact(FeedJson.decodeFromJsonElement<WireArtifact>(entry))
} catch (e: IllegalArgumentException) {
    null
}

internal fun parseModeEntry(json: JsonObject): Mode? = try {
    parseMode(FeedJson.decodeFromJsonElement<WireMode>(json))
} catch (e: IllegalArgumentException) {
    null
}

internal fun Mode.toEntry(): JsonObject? {
    val feed = origin as? ModeOrigin.Feed ?: return null
    return FeedJson.encodeToJsonElement(WireMode(id.value, version, listing.toJson(), feed.rules.wire(), feed.client?.wire())).jsonObject
}

private fun Artifact.wire() = WireArtifact(url, bytes, sha256.hex)

private fun parseMode(wire: WireMode): Mode? {
    val id = ModeId.parse(wire.id) ?: return null
    if (wire.version.isBlank()) return null
    val listing = parseListing(wire.listing, ::feedArt) ?: return null
    val origin = ModeOrigin.Feed(parseArtifact(wire.rules) ?: return null, wire.client?.let { parseArtifact(it) ?: return null })
    return Mode(id, wire.version, listing, origin)
}

private fun feedArt(url: String): String? = url.takeIf { it.startsWith("https://") }

private fun parseArtifact(wire: WireArtifact): Artifact? {
    val usable = wire.bytes in 1..MaxArtifactBytes && (wire.url.startsWith("https://") || wire.url.startsWith("http://"))
    return if (usable) Artifact(wire.url, wire.bytes, Sha256.parse(wire.sha256) ?: return null) else null
}

private const val SupportedSchema = 2
internal const val MaxArtifactBytes = 8L * 1024 * 1024 * 1024
private val FeedJson = Json { ignoreUnknownKeys = true }

@Serializable
private data class WireFeed(val schema: Int, val sequence: Long, val runtime: WireRuntime, val modes: List<WireMode>, val upcoming: JsonElement? = null)

@Serializable
private data class WireRuntime(val cli: JsonObject, val host: WireArtifact, val platform: WireArtifact, val renderer: WireArtifact)

@Serializable
private data class WireMode(val id: String, val version: String, val listing: JsonObject, val rules: WireArtifact, val client: WireArtifact? = null)

@Serializable
private data class WireArtifact(val url: String, val bytes: Long, val sha256: String)
