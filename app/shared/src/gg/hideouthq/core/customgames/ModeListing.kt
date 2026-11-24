package gg.hideouthq.core.customgames

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * What a player sees of a mode and may choose for it, from whoever made it. Nothing in Hideout knows a mode's rules, so
 * this is all a mode is to the screens. A feed entry nests it as `listing`, and a mode author's build folder holds it as
 * `hideout.json`, so every kind of mode reads through the same [parseListing].
 */
data class ModeListing(
    val name: String,
    val tagline: String,
    val description: String,
    val players: IntRange,
    /** Never empty, except in a [plainListing]. */
    val creators: List<String>,
    /** Null only for a [plainListing]. */
    val released: LocalDate?,
    /** Null for a kind this Hideout doesn't know, which then lists under All only. */
    val kind: ModeKind? = null,
    val minutes: IntRange? = null,
    /** How a match is won, such as "First to {kills} kills". Each `{key}` names one of [settings], for the screen to fill with the picked option's label. */
    val goal: String? = null,
    /** Listed by Hideout's own team. A folder is never curated, whatever its file says. */
    val curated: Boolean = false,
    /** The game's `SourceRevision` the author last checked the mode on. */
    val checkedOn: GameRevision? = null,
    /** At most three. */
    val howToPlay: List<HowToPlay> = emptyList(),
    val whatsNew: List<String> = emptyList(),
    val art: ModeArt = ModeArt(),
    val settings: List<ModeSetting> = emptyList(),
    val sections: List<SectionKind> = emptyList(),
    /** The page's banner line. Without it the banner shows [description]. */
    val summary: String? = null,
    /** What the swatches under [ModeArt.explainer] mean. At most six. */
    val legend: List<LegendEntry> = emptyList(),
    /** Rows of the page's side panel that only the mode can state, such as how a match is won. At most four. */
    val facts: List<ModeFact> = emptyList(),
)

enum class ModeKind(val wire: String) { Duel("duel"), Teams("teams"), FreeForAll("free-for-all"), Coop("co-op") }

/** [icon] null shows the step's number, as for a name this Hideout doesn't know. */
data class HowToPlay(val title: String, val body: String, val icon: StepIcon? = null)

/** The icons a how-to-play step may name. Like [SectionKind], a new one is a PR that adds an entry. */
enum class StepIcon(val wire: String) { Hook("hook"), Souls("souls"), Trophy("trophy") }

/** [color] is `0xRRGGBB`. */
data class LegendEntry(val label: String, val color: Int)

data class ModeFact(val label: String, val value: String, val note: String? = null)

/**
 * Absolute image addresses. One that is missing shows placeholder art. [card] and [backdrop] are the card's and the
 * banner's background. A mode with both [render] and [overlay] gets the layered composition: the render standing in
 * front of the background and the overlay drawn over everything, past the banner's top edge.
 */
data class ModeArt(
    val card: String? = null,
    val backdrop: String? = null,
    val render: String? = null,
    val tile: String? = null,
    val explainer: String? = null,
    val screenshots: List<String> = emptyList(),
    val overlay: String? = null,
) {
    val layered: Boolean get() = render != null && overlay != null
}

/** `[a-z0-9_-]{1,16}`, so it goes into `--arg key=value` and an invite with no escaping. */
@JvmInline
value class SettingKey private constructor(val value: String) {
    companion object {
        fun parse(text: String): SettingKey? = if (Token.matches(text)) SettingKey(text) else null
    }
}

/** The same shape as [SettingKey]. */
@JvmInline
value class SettingValue private constructor(val value: String) {
    companion object {
        fun parse(text: String): SettingValue? = if (Token.matches(text)) SettingValue(text) else null
    }
}

private val Token = Regex("[a-z0-9_-]{1,16}")

/** The most players a mode may take, and so the largest match an invite can name. */
const val MaxPlayers = 64

/**
 * One choice the host makes before starting. What it means is the mode's alone: Hideout passes the pick as
 * `--arg key=value` and reads only [SettingOption.players]. Parsing guarantees 2 to 5 options with distinct values, a
 * [default] among them, and that at most one setting of a listing carries player counts, on every option, each inside
 * [ModeListing.players].
 */
data class ModeSetting(val key: SettingKey, val label: String, val hint: String?, val options: List<SettingOption>, val default: SettingValue)

/** [players] is the match size this option sets, as "Teams: 2v2" sets 4. */
data class SettingOption(val value: SettingValue, val label: String, val players: Int? = null)

@ConsistentCopyVisibility
data class Choices private constructor(val picks: Map<SettingKey, SettingValue>) {
    operator fun get(key: SettingKey): SettingValue? = picks[key]

    fun with(setting: ModeSetting, value: SettingValue): Choices =
        if (setting.options.none { it.value == value }) this else Choices(picks + (setting.key to value))

    fun labels(listing: ModeListing?): List<String> = listing?.settings.orEmpty().mapNotNull { setting ->
        setting.options.firstOrNull { it.value == picks[setting.key] }?.label
    }

    /** One `key=value` per pick, for Modlock's `--arg`. */
    val args: List<String> get() = picks.map { (key, value) -> "${key.value}=${value.value}" }

    override fun toString() = "Choices($args)"

    companion object {
        /** No more than a listing may declare, which also bounds what an invite may carry. */
        const val MaxPicks = 4

        val None = Choices(emptyMap())

        fun defaultsOf(listing: ModeListing): Choices = Choices(listing.settings.associate { it.key to it.default })

        fun of(picks: Map<SettingKey, SettingValue>): Choices? = if (picks.size > MaxPicks) null else Choices(picks)
    }
}

fun ModeListing.capacity(choices: Choices): Int =
    settings.firstNotNullOfOrNull { setting -> setting.options.firstOrNull { it.value == choices[setting.key] }?.players } ?: players.last

val ModeListing.capacities: IntRange
    get() = settings.firstNotNullOfOrNull { setting -> setting.options.mapNotNull { it.players }.takeIf { it.isNotEmpty() } }
        ?.let { it.min()..it.max() } ?: players

/**
 * The sections a mode can turn on, by the name its listing uses. This enum is the one list: a new kind is a PR that adds
 * an entry, and the exhaustive `when`s over it then fail the build until the kind is handled everywhere. A listing naming
 * a kind not here skips it, so a new mode never breaks an older Hideout.
 */
enum class SectionKind(val wire: String) {
    Scoreboard("scoreboard"),
}

/** What a mode last reported for one of its sections. Only a host hears any. */
sealed interface SectionState {
    val kind: SectionKind

    /** [sides] in the mode's order, each with the server slots on it. [notes] are at most two short lines, like "First to 5". */
    data class Scoreboard(val sides: List<ScoreSide>, val notes: List<String>) : SectionState {
        override val kind get() = SectionKind.Scoreboard
    }
}

data class ScoreSide(val score: Int, val slots: List<Int>)

/**
 * The sections in one state a mode reported, `{"scoreboard":{…}}`. A key that names no [SectionKind] is skipped, and so is
 * a section that breaks a limit, since a running mode's mistake shouldn't blank what it reported before.
 */
fun parseSectionState(json: JsonObject): List<SectionState> = SectionKind.entries.mapNotNull { kind ->
    val body = json[kind.wire] as? JsonObject ?: return@mapNotNull null
    when (kind) {
        SectionKind.Scoreboard -> scoreboard(body)
    }
}

private fun scoreboard(body: JsonObject): SectionState.Scoreboard? {
    val sides = (body["sides"] as? JsonArray)?.map { side ->
        val fields = side as? JsonObject ?: return null
        val score = fields.whole("score") ?: return null
        val slots = (fields["slots"] as? JsonArray ?: JsonArray(emptyList())).map { slot ->
            (slot as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull?.takeIf { it in 0 until MaxPlayers } ?: return null
        }
        ScoreSide(score, slots)
    } ?: return null
    val notes = (body["notes"] as? JsonArray ?: JsonArray(emptyList())).map { note ->
        (note as? JsonPrimitive)?.takeIf { it.isString }?.let { text(it.content, 48) } ?: return null
    }
    return if (sides.size in 1..MaxSides && notes.size <= MaxNotes) SectionState.Scoreboard(sides, notes) else null
}

private fun JsonObject.whole(key: String): Int? = (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull

private const val MaxSides = 16
private const val MaxNotes = 2

/** A build folder's listing when it has no `hideout.json`: its name, and nothing else a player would read. */
fun plainListing(name: String): ModeListing = ModeListing(name, "", "", 1..MaxPlayers, emptyList(), released = null)

/**
 * Reads a listing, or null when it breaks a rule, which a signed feed treats as a publishing mistake. [art] turns a listed
 * image into an absolute address, or null to leave that image out. Unknown fields, unknown kinds, unknown section and
 * icon names, and settings with no `options`, which a later Hideout may draw, are skipped.
 */
fun parseListing(json: JsonObject, art: (String) -> String?): ModeListing? {
    val wire = try {
        ListingJson.decodeFromJsonElement<WireListing>(json)
    } catch (e: IllegalArgumentException) {
        return null
    }
    val players = wire.players.range(MaxPlayers) ?: return null
    val settings = wire.settings.filter { it.options != null }.map { it.parse(players) ?: return null }
    val sized = settings.count { setting -> setting.options.any { it.players != null } }
    if (settings.size > Choices.MaxPicks || settings.distinctBy { it.key }.size != settings.size || sized > 1) return null
    if (wire.creators.size !in 1..MaxCreators || wire.howToPlay.size > MaxHowToPlay || wire.whatsNew.size > MaxWhatsNew) return null
    if (wire.art.screenshots.size > MaxScreenshots || wire.checkedOn?.let { it <= 0 } == true) return null
    if (wire.legend.size > MaxLegend || wire.facts.size > MaxFacts) return null
    return ModeListing(
        name = text(wire.name, 48) ?: return null,
        tagline = text(wire.tagline, 120) ?: return null,
        description = text(wire.description, 2000, lines = true) ?: return null,
        players = players,
        creators = wire.creators.map { text(it, 48) ?: return null },
        released = date(wire.released ?: return null) ?: return null,
        kind = ModeKind.entries.firstOrNull { it.wire == wire.kind },
        minutes = wire.minutes?.let { it.range(MaxMinutes) ?: return null },
        goal = wire.goal?.let { goal(it, settings) ?: return null },
        curated = wire.curated,
        checkedOn = wire.checkedOn?.let(::GameRevision),
        howToPlay = wire.howToPlay.map { step ->
            HowToPlay(text(step.title, 48) ?: return null, text(step.body, 300) ?: return null, StepIcon.entries.firstOrNull { it.wire == step.icon })
        },
        whatsNew = wire.whatsNew.map { text(it, 200) ?: return null },
        art = with(wire.art) {
            ModeArt(card?.let(art), backdrop?.let(art), render?.let(art), tile?.let(art), explainer?.let(art), screenshots.mapNotNull(art), overlay?.let(art))
        },
        settings = settings,
        sections = wire.sections.mapNotNull { name -> SectionKind.entries.firstOrNull { it.wire == name } }.distinct(),
        summary = wire.summary?.let { text(it, 200) ?: return null },
        legend = wire.legend.map { LegendEntry(text(it.label, 48) ?: return null, color(it.color) ?: return null) },
        facts = wire.facts.map { ModeFact(text(it.label, 24) ?: return null, text(it.value, 48) ?: return null, it.note?.let { note -> text(note, 48) ?: return null }) },
    )
}

internal fun ModeListing.toJson(): JsonObject = ListingJson.encodeToJsonElement(
    WireListing(
        name = name,
        tagline = tagline,
        description = description,
        players = WireRange(players.first, players.last),
        creators = creators,
        released = released?.toString(),
        kind = kind?.wire,
        minutes = minutes?.let { WireRange(it.first, it.last) },
        goal = goal,
        curated = curated,
        checkedOn = checkedOn?.source,
        howToPlay = howToPlay.map { WireHowToPlay(it.title, it.body, it.icon?.wire) },
        whatsNew = whatsNew,
        art = WireArt(art.card, art.backdrop, art.render, art.tile, art.explainer, art.screenshots, art.overlay),
        settings = settings.map { setting ->
            WireSetting(setting.key.value, setting.label, setting.default.value, setting.hint, setting.options.map { WireOption(it.value.value, it.label, it.players) })
        },
        sections = sections.map { it.wire },
        summary = summary,
        legend = legend.map { WireLegend(it.label, "#%06X".format(it.color)) },
        facts = facts.map { WireFact(it.label, it.value, it.note) },
    ),
).jsonObject

private fun WireSetting.parse(players: IntRange): ModeSetting? {
    val options = options.orEmpty().map { option ->
        SettingOption(SettingValue.parse(option.value) ?: return null, text(option.label, 24) ?: return null, option.players)
    }
    val counts = options.mapNotNull { it.players }
    val sizesFit = counts.isEmpty() || (counts.size == options.size && counts.all { it in players })
    if (options.size !in 2..5 || options.distinctBy { it.value }.size != options.size || !sizesFit) return null
    val default = SettingValue.parse(default ?: return null)?.takeIf { value -> options.any { it.value == value } } ?: return null
    return ModeSetting(SettingKey.parse(key) ?: return null, text(label, 32) ?: return null, hint?.let { text(it, 80) ?: return null }, options, default)
}

private val Placeholder = Regex("\\{([a-z0-9_-]{1,16})\\}")

private fun goal(raw: String, settings: List<ModeSetting>): String? {
    val goal = text(raw, 48) ?: return null
    val rest = Placeholder.replace(goal, "")
    val named = Placeholder.findAll(goal).all { found -> settings.any { it.key.value == found.groupValues[1] } }
    return goal.takeIf { '{' !in rest && '}' !in rest && named }
}

private fun WireRange.range(most: Int): IntRange? = if (min in 1..max && max <= most) min..max else null

private fun text(raw: String, most: Int, lines: Boolean = false): String? {
    val trimmed = raw.trim()
    val clean = trimmed.none { Character.isISOControl(it) && !(lines && it == '\n') }
    return trimmed.takeIf { clean && it.isNotEmpty() && it.codePointCount(0, it.length) <= most }
}

private val HexColor = Regex("#[0-9A-Fa-f]{6}")

private fun color(text: String): Int? = if (HexColor.matches(text)) text.substring(1).toInt(16) else null

private fun date(text: String): LocalDate? = try {
    LocalDate.parse(text)
} catch (e: DateTimeParseException) {
    null
}

private const val MaxMinutes = 600
private const val MaxCreators = 8
private const val MaxHowToPlay = 3
private const val MaxWhatsNew = 8
private const val MaxScreenshots = 8
private const val MaxLegend = 6
private const val MaxFacts = 4

private val ListingJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

@Serializable
private data class WireListing(
    val name: String,
    val tagline: String,
    val description: String,
    val players: WireRange,
    val creators: List<String>,
    val released: String? = null,
    val kind: String? = null,
    val minutes: WireRange? = null,
    val goal: String? = null,
    val curated: Boolean = false,
    val checkedOn: Long? = null,
    val howToPlay: List<WireHowToPlay> = emptyList(),
    val whatsNew: List<String> = emptyList(),
    val art: WireArt = WireArt(),
    val settings: List<WireSetting> = emptyList(),
    val sections: List<String> = emptyList(),
    val summary: String? = null,
    val legend: List<WireLegend> = emptyList(),
    val facts: List<WireFact> = emptyList(),
)

@Serializable
private data class WireLegend(val label: String, val color: String)

@Serializable
private data class WireFact(val label: String, val value: String, val note: String? = null)

@Serializable
private data class WireRange(val min: Int, val max: Int)

@Serializable
private data class WireHowToPlay(val title: String, val body: String, val icon: String? = null)

@Serializable
private data class WireArt(
    val card: String? = null,
    val backdrop: String? = null,
    val render: String? = null,
    val tile: String? = null,
    val explainer: String? = null,
    val screenshots: List<String> = emptyList(),
    val overlay: String? = null,
)

@Serializable
private data class WireSetting(val key: String, val label: String, val default: String? = null, val hint: String? = null, val options: List<WireOption>? = null)

@Serializable
private data class WireOption(val value: String, val label: String, val players: Int? = null)
