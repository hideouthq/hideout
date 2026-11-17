package gg.hideouthq.core.data.collections

import gg.hideouthq.core.library.SubmissionKind
import gg.hideouthq.core.library.SubmissionRef
import gg.hideouthq.core.model.CollectionLook
import gg.hideouthq.core.model.Glow
import gg.hideouthq.core.model.MonthDay
import gg.hideouthq.core.model.Rgb
import gg.hideouthq.core.model.Season
import gg.hideouthq.core.model.SkinCollection
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The collections in a published `collections.json`, in its order. Members and covers become
 * catalogue mod ids; entries for anything but a GameBanana mod are left out. A document that breaks the contract in
 * any other way is rejected whole: this throws [IllegalArgumentException].
 */
fun decodeCollections(text: String): List<SkinCollection> {
    val document = DocumentJson.decodeFromString(CollectionsDto.serializer(), text)
    require(document.schema == Schema) { "Unsupported collections schema ${document.schema}" }
    return document.collections.map { entry ->
        require(entry.id.isNotBlank() && entry.title.isNotBlank()) { "A collection needs an id and a title" }
        require(entry.title.none { it.isISOControl() }) { "A collection title has a control character" }
        SkinCollection(entry.id, entry.title, entry.season?.let { Season(monthDay(it.from), monthDay(it.until)) }, look(entry.look), modIds(entry.cover), modIds(entry.members))
    }
}

private fun look(look: LookDto): CollectionLook = CollectionLook(
    rgb(look.top), rgb(look.bottom), rgb(look.main), look.glows.map { Glow(rgb(it.color), it.alpha, it.x, it.y, it.r) }, look.arch, look.rim.map(::rgb),
)

private fun rgb(text: String): Rgb {
    require(HexColour.matches(text)) { "Not a #RRGGBB colour: $text" }
    return Rgb(text.substring(1).toInt(16))
}

private fun monthDay(text: String): MonthDay {
    val match = requireNotNull(MonthDayPattern.matchEntire(text)) { "Not a month and day: $text" }
    return MonthDay(match.groupValues[1].toInt(), match.groupValues[2].toInt())
}

private fun modIds(refs: List<RefDto>): List<String> =
    refs.filter { it.model == SubmissionKind.Mod.apiName }.map { SubmissionRef(SubmissionKind.Mod, it.id).modId }

private const val Schema = 1
private val MonthDayPattern = Regex("""(\d\d)-(\d\d)""")
private val HexColour = Regex("#[0-9A-Fa-f]{6}")
private val DocumentJson = Json { ignoreUnknownKeys = true }

@Serializable
private class CollectionsDto(val schema: Int, val collections: List<CollectionDto>)

@Serializable
private class CollectionDto(val id: String, val title: String, val season: SeasonDto? = null, val look: LookDto, val cover: List<RefDto>, val members: List<RefDto>)

@Serializable
private class SeasonDto(val from: String, val until: String)

@Serializable
private class LookDto(val top: String, val bottom: String, val main: String, val glows: List<GlowDto>, val arch: Boolean, val rim: List<String>)

@Serializable
private class GlowDto(val color: String, val alpha: Float, val x: Float, val y: Float, val r: Float)

@Serializable
private class RefDto(val model: String, val id: Long)
