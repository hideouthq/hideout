package gg.hideouthq.core.appupdate

import gg.hideouthq.core.model.CalendarDate
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import java.time.LocalDate
import java.time.format.DateTimeParseException

data class AppVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<AppVersion> {
    override fun compareTo(other: AppVersion): Int = compareValuesBy(this, other, AppVersion::major, AppVersion::minor, AppVersion::patch)

    override fun toString(): String = "$major.$minor.$patch"

    companion object {
        private val Shape = Regex("""(\d+)\.(\d+)\.(\d+)""")

        /** The version [text] spells out in full, or null for anything else, a development build's version among them. */
        fun parse(text: String): AppVersion? {
            val (major, minor, patch) = Shape.matchEntire(text)?.destructured ?: return null
            return AppVersion(major.toIntOrNull() ?: return null, minor.toIntOrNull() ?: return null, patch.toIntOrNull() ?: return null)
        }
    }
}

/** The kinds of package a release ships, each named in the feed's `download` block by [feedKey]. */
enum class PackageFormat(val feedKey: String) {
    Msi("msi"),
    AppImage("appimage"),
    Deb("deb"),
    Rpm("rpm"),
}

/** One file of a release, with what it must measure and hash to before it counts as downloaded. [sha256] is lower-case hex. */
data class ReleasePackage(
    val version: AppVersion,
    val format: PackageFormat,
    val url: String,
    val sizeBytes: Long,
    val sha256: String,
)

enum class ReleaseKind { Feature, Fixes }

/** A headline card in What's new. [imageUrl] is null when the feed names none, or names one from anywhere but beside itself. */
data class Highlight(val title: String, val text: String, val imageUrl: String?)

data class ReleaseEntry(val area: String, val text: String)

data class ReleaseNotes(
    val version: AppVersion,
    val date: CalendarDate,
    val kind: ReleaseKind,
    val highlights: List<Highlight>,
    val new: List<ReleaseEntry>,
    val improved: List<ReleaseEntry>,
    val fixed: List<ReleaseEntry>,
)

/**
 * The release-notes file as Hideout trusts it: [latest] is the version on offer and [packages] its files by format.
 * [versions] run newest first and hold nothing newer than [latest].
 */
data class ReleaseFeed(
    val latest: AppVersion,
    val notesUrl: String,
    val versions: List<ReleaseNotes>,
    val packages: Map<PackageFormat, ReleasePackage>,
)

/**
 * Reads the release-notes file fetched from [feedUrl], or null when its `schema`, `latest` or `notesUrl` is missing or
 * broken. A schema this build doesn't know still names the version on offer, with no notes and no packages, so a later
 * feed can always point this copy at GitHub. Below that it ignores unknown fields, reads an unknown kind as fixes, and
 * drops only the version, highlight, line or package that is malformed. The latest version's packages are read apart
 * from its notes, so broken notes never cost the update.
 */
fun parseReleaseFeed(text: String, feedUrl: String): ReleaseFeed? {
    val root = try {
        FeedJson.parseToJsonElement(text)
    } catch (e: IllegalArgumentException) {
        return null
    }
    val head = root.readAs<WireHead>() ?: return null
    val latest = AppVersion.parse(head.latest) ?: return null
    if (head.notesUrl.isBlank()) return null
    if (head.schema != SupportedSchema) return ReleaseFeed(latest, head.notesUrl, emptyList(), emptyMap())
    val entries = root.readAs<WireVersions>()?.versions.orEmpty()
    val download = entries.mapNotNull { it.readAs<WireDownload>() }.firstOrNull { AppVersion.parse(it.version) == latest }
    return ReleaseFeed(
        latest = latest,
        notesUrl = head.notesUrl,
        versions = entries.mapNotNull { parseNotes(it, feedUrl) }
            .filter { it.version <= latest }
            .distinctBy { it.version }
            .sortedByDescending { it.version },
        packages = download?.let { parsePackages(it.download, latest) }.orEmpty(),
    )
}

private fun parseNotes(element: JsonElement, feedUrl: String): ReleaseNotes? {
    val wire = element.readAs<WireVersion>() ?: return null
    return ReleaseNotes(
        version = AppVersion.parse(wire.version) ?: return null,
        date = parseDate(wire.date) ?: return null,
        kind = if (wire.kind == "feature") ReleaseKind.Feature else ReleaseKind.Fixes,
        highlights = wire.highlights.mapNotNull { it.readAs<WireHighlight>() }
            .take(MaxHighlights)
            .map { Highlight(it.title, it.text, imageUrl(it.image, feedUrl)) },
        new = entries(wire.new),
        improved = entries(wire.improved),
        fixed = entries(wire.fixed),
    )
}

private fun entries(group: List<JsonElement>): List<ReleaseEntry> = group.mapNotNull { it.readAs<WireEntry>() }.map { ReleaseEntry(it.area, it.text) }

private fun parseDate(text: String): CalendarDate? = try {
    LocalDate.parse(text).let { CalendarDate(it.year, it.monthValue, it.dayOfMonth) }
} catch (e: DateTimeParseException) {
    null
}

private fun parsePackages(download: JsonObject, version: AppVersion): Map<PackageFormat, ReleasePackage> = buildMap {
    for (format in PackageFormat.entries) {
        val wire = download[format.feedKey]?.readAs<WirePackage>() ?: continue
        val hash = wire.sha256.lowercase()
        val usable = wire.size > 0 && Sha256Shape.matches(hash) && (wire.url.startsWith("https://") || wire.url.startsWith("http://"))
        if (usable) put(format, ReleasePackage(version, format, wire.url, wire.size, hash))
    }
}

private fun imageUrl(name: String?, feedUrl: String): String? {
    if (name.isNullOrBlank() || name == "." || name == ".." || '/' in name || '\\' in name) return null
    return feedUrl.substringBeforeLast('/') + "/" + name
}

private const val SupportedSchema = 1
private const val MaxHighlights = 2
private val Sha256Shape = Regex("[0-9a-f]{64}")
private val FeedJson = Json { ignoreUnknownKeys = true; explicitNulls = false }

private inline fun <reified T> JsonElement.readAs(): T? = try {
    FeedJson.decodeFromJsonElement<T>(this)
} catch (e: IllegalArgumentException) {
    null
}

@Serializable
private data class WireHead(val schema: Int, val latest: String, val notesUrl: String)

@Serializable
private data class WireVersions(val versions: List<JsonElement>)

@Serializable
private data class WireDownload(val version: String, val download: JsonObject = JsonObject(emptyMap()))

@Serializable
private data class WireVersion(
    val version: String,
    val date: String,
    val kind: String? = null,
    val highlights: List<JsonElement> = emptyList(),
    val new: List<JsonElement> = emptyList(),
    val improved: List<JsonElement> = emptyList(),
    val fixed: List<JsonElement> = emptyList(),
)

@Serializable
private data class WireHighlight(val title: String, val text: String, val image: String? = null)

@Serializable
private data class WireEntry(val area: String, val text: String)

@Serializable
private data class WirePackage(val url: String, val size: Long, val sha256: String)
