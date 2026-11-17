package gg.hideouthq.core.customgames

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.time.Instant

/**
 * A feed mode whose packages are on this computer, kept with the verified listing and packages it was installed from, so
 * it stays listed and hostable with no network and after the feed drops it. [bytes] is what its packages take on disk.
 */
data class InstalledMode(val mode: Mode, val bytes: Long, val lastPlayed: Instant?)

sealed interface Install {
    data object NotInstalled : Install

    /** [update] is the newer mode the feed offers, null when this is the one it offers or the feed no longer lists it. */
    data class Installed(val record: InstalledMode, val update: Mode?) : Install

    data class Updating(val transfer: Transfer?) : Install
}

data class Transfer(val done: Long, val total: Long)

fun encodeInstalled(modes: Collection<InstalledMode>): String = InstalledJson.encodeToString(
    WireInstalled(modes.mapNotNull { record -> record.mode.toEntry()?.let { WireRecord(it, record.bytes, record.lastPlayed?.toEpochMilliseconds()) } }),
)

/** The records in [text], read as strictly as the feed they came from. A record that no longer reads is dropped, and costs only a download. */
fun parseInstalled(text: String): Map<ModeId, InstalledMode> {
    val wire = try {
        InstalledJson.decodeFromString<WireInstalled>(text)
    } catch (e: IllegalArgumentException) {
        return emptyMap()
    }
    return wire.modes.mapNotNull { record ->
        val mode = parseModeEntry(record.mode) ?: return@mapNotNull null
        InstalledMode(mode, record.bytes.coerceAtLeast(0), record.lastPlayedMillis?.let(Instant::fromEpochMilliseconds))
    }.associateBy { it.mode.id }
}

private val InstalledJson = Json { ignoreUnknownKeys = true }

@Serializable
private data class WireInstalled(val modes: List<WireRecord>)

@Serializable
private data class WireRecord(val mode: JsonObject, val bytes: Long, val lastPlayedMillis: Long? = null)
