package gg.hideouthq.core.crosshair

import gg.hideouthq.core.library.Manager
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlin.jvm.JvmInline

@JvmInline
value class CrosshairId(val value: String)

sealed interface CrosshairOrigin {
    val base: Crosshair

    data class Preset(val preset: CrosshairPreset) : CrosshairOrigin {
        override val base: Crosshair get() = preset.crosshair
    }

    data class Community(val id: String, val creator: String?, override val base: Crosshair) : CrosshairOrigin

    data class CommunityCopy(val id: String, val creator: String?, override val base: Crosshair) : CrosshairOrigin

    data class Imported(override val base: Crosshair) : CrosshairOrigin

    data class FromManager(val manager: Manager, override val base: Crosshair) : CrosshairOrigin

    data object Made : CrosshairOrigin {
        override val base: Crosshair get() = Crosshair.GameDefault
    }

    data class Mine(override val base: Crosshair) : CrosshairOrigin
}

data class SavedCrosshair(val id: CrosshairId, val name: String, val origin: CrosshairOrigin, val crosshair: Crosshair) {
    /** The community crosshair this is saved from, while it still holds that crosshair's settings: an edited one is the user's own. */
    val savedFrom: String? get() = (origin as? CrosshairOrigin.Community)?.takeIf { it.base == crosshair }?.id
}

data class Yours(val entries: List<SavedCrosshair>, val inUse: CrosshairId?) {
    companion object {
        val None = Yours(emptyList(), null)
    }
}

/**
 * Which crosshair of [kept] the game uses, [running] (null while it is not known): the entry with exactly its settings,
 * [lastUsed] first among several. When none has them, a crosshair named "Mine" with its settings comes first, in use,
 * without joining [kept]. Pure: reading never keeps anything.
 */
fun inUse(running: Crosshair?, kept: List<SavedCrosshair>, lastUsed: CrosshairId?): Yours {
    running ?: return Yours(kept, null)
    val equal = kept.filter { it.crosshair == running }
    (equal.firstOrNull { it.id == lastUsed } ?: equal.firstOrNull())?.let { return Yours(kept, it.id) }
    val ids = kept.map { it.id }.toSet()
    val id = generateSequence(1) { it + 1 }.map { CrosshairId(if (it == 1) "mine" else "mine-$it") }.first { it !in ids }
    return Yours(listOf(SavedCrosshair(id, "Mine", CrosshairOrigin.Mine(running), running)) + kept, id)
}

internal data class Kept(val crosshairs: List<SavedCrosshair>, val lastUsed: CrosshairId?, val waiting: Crosshair? = null)

internal object KeptCodec {
    const val FileName = "crosshairs.json"

    val Seed: Kept get() = Kept(CrosshairPreset.entries.map { SavedCrosshair(CrosshairId("preset-${it.name.lowercase()}"), it.title, CrosshairOrigin.Preset(it), it.crosshair) }, lastUsed = null)

    fun encode(kept: Kept): String = json.encodeToString(
        KeptDto.serializer(),
        KeptDto(kept.lastUsed?.value, kept.crosshairs.map { json.encodeToJsonElement(EntryDto.serializer(), it.dto()) }, kept.waiting?.settings()),
    )

    fun decode(text: String?): Kept? {
        text ?: return Seed
        val dto = try {
            json.decodeFromString(KeptDto.serializer(), text)
        } catch (e: IllegalArgumentException) {
            return null
        }
        return Kept(dto.crosshairs.mapNotNull(::entry), dto.lastUsed?.let(::CrosshairId), dto.waiting?.let(::full))
    }

    private fun entry(element: JsonElement): SavedCrosshair? = try {
        json.decodeFromJsonElement(EntryDto.serializer(), element).saved()
    } catch (e: IllegalArgumentException) {
        null
    }

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    private fun SavedCrosshair.dto(): EntryDto {
        val kind = when (origin) {
            is CrosshairOrigin.Preset -> OriginDto("preset", preset = origin.preset.name)
            is CrosshairOrigin.Community -> OriginDto("community", id = origin.id, creator = origin.creator)
            is CrosshairOrigin.CommunityCopy -> OriginDto("copy", id = origin.id, creator = origin.creator)
            is CrosshairOrigin.Imported -> OriginDto("imported")
            is CrosshairOrigin.FromManager -> OriginDto("manager", manager = origin.manager.name)
            CrosshairOrigin.Made -> OriginDto("made")
            is CrosshairOrigin.Mine -> OriginDto("mine")
        }
        return EntryDto(id.value, name, kind.copy(base = origin.base.settings()), crosshair.settings())
    }

    private fun EntryDto.saved(): SavedCrosshair? {
        val crosshair = full(settings) ?: return null
        val base = origin.base?.let(::full)
        val made = when (origin.kind) {
            "preset" -> CrosshairPreset.entries.firstOrNull { it.name == origin.preset }?.let { CrosshairOrigin.Preset(it) }
            "community" -> origin.id?.let { id -> base?.let { CrosshairOrigin.Community(id, origin.creator, it) } }
            "copy" -> origin.id?.let { id -> base?.let { CrosshairOrigin.CommunityCopy(id, origin.creator, it) } }
            "imported" -> base?.let { CrosshairOrigin.Imported(it) }
            "manager" -> Manager.entries.firstOrNull { it.name == origin.manager }?.let { manager -> base?.let { CrosshairOrigin.FromManager(manager, it) } }
            "made" -> CrosshairOrigin.Made
            "mine" -> base?.let { CrosshairOrigin.Mine(it) }
            else -> null
        } ?: return null
        return SavedCrosshair(CrosshairId(id), name, made, crosshair)
    }

    private fun full(settings: Map<String, String>): Crosshair? =
        crosshairOf(settings).takeIf { read -> read.set.size == settings.keys.count { key -> CrosshairSetting.entries.any { it.suffix == key } } }?.crosshair
}

@Serializable
private data class KeptDto(val lastUsed: String? = null, val crosshairs: List<JsonElement>, val waiting: Map<String, String>? = null)

@Serializable
private data class EntryDto(val id: String, val name: String, val origin: OriginDto, val settings: Map<String, String>)

@Serializable
private data class OriginDto(
    val kind: String,
    val preset: String? = null,
    val id: String? = null,
    val creator: String? = null,
    val manager: String? = null,
    val base: Map<String, String>? = null,
)
