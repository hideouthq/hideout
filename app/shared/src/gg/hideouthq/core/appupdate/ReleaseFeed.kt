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
 * Reads the release-notes file fetched from [feedUrl], or null when this build cannot use it: a newer schema, or a
 * top-level field missing or broken. Below that it ignores unknown fields, treats missing groups as empty, and drops a
 * version or a package with a malformed field.
 */
fun parseReleaseFeed(text: String, feedUrl: String): ReleaseFeed? {
    val wire = try {
        FeedJson.decodeFromString<WireFeed>(text)
    } catch (e: IllegalArgumentException) {
        return null
    }
    if (wire.schema != SupportedSchema) return null
    val latest = AppVersion.parse(wire.latest) ?: return null
    if (wire.notesUrl.isBlank()) return null
    val parsed = wire.versions.mapNotNull { parseVersion(it, feedUrl) }
        .filter { it.notes.version <= latest }
        .distinctBy { it.notes.version }
        .sortedByDescending { it.notes.version }
    return ReleaseFeed(
        latest = latest,
        notesUrl = wire.notesUrl,
        versions = parsed.map { it.notes },
        packages = parsed.firstOrNull { it.notes.version == latest }?.packages.orEmpty(),
    )
}

private class ParsedVersion(val notes: ReleaseNotes, val packages: Map<PackageFormat, ReleasePackage>)

private fun parseVersion(element: JsonElement, feedUrl: String): ParsedVersion? {
    val wire = try {
        FeedJson.decodeFromJsonElement<WireVersion>(element)
    } catch (e: IllegalArgumentException) {
        return null
    }
    val version = AppVersion.parse(wire.version) ?: return null
    val notes = ReleaseNotes(
        version = version,
        date = parseDate(wire.date) ?: return null,
        kind = when (wire.kind) {
            "feature" -> ReleaseKind.Feature
            "fixes" -> ReleaseKind.Fixes
            else -> return null
        },
        highlights = wire.highlights.take(MaxHighlights).map { Highlight(it.title, it.text, imageUrl(it.image, feedUrl)) },
        new = wire.new.map { ReleaseEntry(it.area, it.text) },
        improved = wire.improved.map { ReleaseEntry(it.area, it.text) },
        fixed = wire.fixed.map { ReleaseEntry(it.area, it.text) },
    )
    return ParsedVersion(notes, parsePackages(wire.download, version))
}

private fun parseDate(text: String): CalendarDate? = try {
    LocalDate.parse(text).let { CalendarDate(it.year, it.monthValue, it.dayOfMonth) }
} catch (e: DateTimeParseException) {
    null
}

private fun parsePackages(download: JsonObject, version: AppVersion): Map<PackageFormat, ReleasePackage> = buildMap {
    for (format in PackageFormat.entries) {
        val wire = try {
            download[format.feedKey]?.let { FeedJson.decodeFromJsonElement<WirePackage>(it) }
        } catch (e: IllegalArgumentException) {
            null
        } ?: continue
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

@Serializable
private data class WireFeed(val schema: Int, val latest: String, val notesUrl: String, val versions: List<JsonElement>)

@Serializable
private data class WireVersion(
    val version: String,
    val date: String,
    val kind: String,
    val download: JsonObject = JsonObject(emptyMap()),
    val highlights: List<WireHighlight> = emptyList(),
    val new: List<WireEntry> = emptyList(),
    val improved: List<WireEntry> = emptyList(),
    val fixed: List<WireEntry> = emptyList(),
)

@Serializable
private data class WireHighlight(val title: String, val text: String, val image: String? = null)

@Serializable
private data class WireEntry(val area: String, val text: String)

@Serializable
private data class WirePackage(val url: String, val size: Long, val sha256: String)
