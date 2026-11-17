package gg.hideouthq.feature.moddetail.viewer

import androidx.compose.runtime.Immutable
import gg.hideouthq.preview.AnimationCategory
import gg.hideouthq.preview.PreviewAbility
import gg.hideouthq.preview.PreviewClip
import gg.hideouthq.preview.PreviewPicture

enum class ViewerCategory { Idle, Movement, Combat, Abilities, Poses, Animations }

@Immutable
data class ClipGroup(val category: ViewerCategory, val clips: List<PreviewClip>, val menu: List<PreviewClip>) {
    val changed: Boolean get() = clips.any { it.changedByMod }
}

/**
 * What the viewer offers of a verified bundle: its clips on the bar's pills, in the order ← and → step through
 * them, and its abilities by slot. Clips without a category share the Animations pill; none is guessed into another.
 * The stage opens on the pose the bundle's [still] was captured in, so the model takes over from the still in the same
 * pose: its clip, or the rest pose when it names none. Only a bundle without a still opens on its first clip.
 *
 * A pill's [ClipGroup.menu] is [ClipGroup.clips] with same-named variants collapsed to their first, a shorter list
 * for Browse-by-name than the bar's full, exact step order: redundant directional or internal duplicates (the same
 * readable name) needn't each get their own row, but a clip the mod changed or an ability plays always keeps its own.
 */
@Immutable
class ViewerCatalogue(exported: List<PreviewClip>, abilities: List<PreviewAbility>, still: PreviewPicture? = null) {
    val abilities: List<PreviewAbility> = abilities.sortedBy { it.slot }

    /**
     * The names of the abilities playing the clip, else its own label: an ability's name is already curated, not a guess. Abilities
     * the game casts with the same generic throw or cast share that clip and are all named.
     */
    fun label(clip: PreviewClip): String =
        abilities.filter { it.clipId == clip.id }.joinToString(" · ") { it.name }.ifEmpty { clip.label }

    private fun slotOf(clip: PreviewClip): Int = abilities.firstOrNull { it.clipId == clip.id }?.slot ?: Int.MAX_VALUE

    /** Each pill's clips, the mod's changes first; the Abilities pill in ability slot order, as the strip and keys 1–4 are. */
    val groups: List<ClipGroup> = exported.groupBy { it.category.pill }.let { byPill ->
        ViewerCategory.entries.mapNotNull { pill ->
            byPill[pill]?.let { clips ->
                if (pill == ViewerCategory.Abilities) clips.sortedBy(::slotOf) else clips.sortedByDescending(PreviewClip::changedByMod)
            }?.let { ClipGroup(pill, it, menuOf(it)) }
        }
    }

    val clips: List<PreviewClip> = groups.flatMap { it.clips }

    private val byId = exported.associateBy { it.id }

    private fun menuOf(clips: List<PreviewClip>): List<PreviewClip> {
        val seenLabels = hashSetOf<String>()
        return clips.filter { it.changedByMod || abilities.any { ability -> ability.clipId == it.id } || seenLabels.add(label(it)) }
    }

    fun clip(id: String?): PreviewClip? = id?.let(byId::get)

    private val opening: PreviewClip? = if (still == null) clips.firstOrNull() else clip(still.clip)

    /** The clip [id] names, else the opening pose's: null is the rest pose. */
    fun resolve(id: String?): PreviewClip? = clip(id) ?: opening

    /** The clip [by] places from [from], wrapping; from the rest pose, 1 is the first clip and -1 the last. */
    fun step(from: PreviewClip?, by: Int): PreviewClip? {
        if (clips.isEmpty()) return null
        val index = clips.indexOf(from)
        return clips[(if (index < 0 && by < 0) by else index + by).mod(clips.size)]
    }

    fun group(category: ViewerCategory): ClipGroup? = groups.firstOrNull { it.category == category }

    fun categoryOf(clip: PreviewClip): ViewerCategory = clip.category.pill

    fun abilityClip(slot: Int): PreviewClip? = clip(abilities.firstOrNull { it.slot == slot }?.clipId)

    companion object {
        val Empty = ViewerCatalogue(emptyList(), emptyList())
    }
}

private val AnimationCategory?.pill: ViewerCategory
    get() = when (this) {
        AnimationCategory.Idle -> ViewerCategory.Idle
        AnimationCategory.Movement -> ViewerCategory.Movement
        AnimationCategory.Combat -> ViewerCategory.Combat
        AnimationCategory.Abilities -> ViewerCategory.Abilities
        AnimationCategory.Poses -> ViewerCategory.Poses
        null -> ViewerCategory.Animations
    }
