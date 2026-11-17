package gg.hideouthq.feature.collections

import androidx.compose.runtime.Immutable
import gg.hideouthq.core.data.CatalogRepository
import gg.hideouthq.core.data.collections.CollectionsRepository
import gg.hideouthq.core.model.CalendarDate
import gg.hideouthq.core.model.CollectionLook
import gg.hideouthq.core.model.Hero
import gg.hideouthq.core.model.Mod
import gg.hideouthq.core.model.SkinCollection
import gg.hideouthq.core.ui.catalog.featuredRenderUrl
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/** A collection as the catalogue has it: the members it lists, best first, and the transparent renders its cover shows. */
class JoinedCollection(val collection: SkinCollection, val members: List<Mod>, val figures: List<String>) {
    /** The heroes the members are for, most members first, then by name. Members for no hero, or an unknown one, count for none. */
    fun heroes(catalogue: Map<String, Hero>): List<CollectionHero> =
        members.mapNotNull { it.heroId }.groupingBy { it }.eachCount().mapNotNull { (id, count) ->
            catalogue[id]?.let { CollectionHero(it.id, it.name, it.iconUrl, count) }
        }.sortedWith(compareByDescending<CollectionHero> { it.count }.thenBy { it.name })

    fun summary(catalogue: Map<String, Hero>, today: CalendarDate): CollectionSummary {
        val heroes = heroes(catalogue)
        return CollectionSummary(
            id = collection.id,
            title = collection.title,
            look = collection.look,
            skins = members.size,
            heroes = heroes.size,
            topHeroes = heroes.take(TopHeroes),
            figures = figures,
            seasonEnd = collection.season?.takeIf { today in it }?.end(today),
        )
    }

    private companion object {
        const val TopHeroes = 3
    }
}

/** [collection]'s members and cover that the catalogue lists and that are not mature; a cover mod without a ready render shows nothing. */
fun join(collection: SkinCollection, mods: Map<String, Mod>): JoinedCollection {
    fun listed(ids: List<String>) = ids.mapNotNull { mods[it] }.filterNot { it.mature }
    return JoinedCollection(collection, listed(collection.members), listed(collection.cover).mapNotNull { it.featuredRenderUrl })
}

/** The cards of [published] over the catalogue's [mods] and [heroes], in display order; one with no members listed has none. */
fun summaries(published: List<SkinCollection>, mods: List<Mod>, heroes: List<Hero>, today: CalendarDate): List<CollectionSummary> {
    val byId = mods.associateBy { it.id }
    val heroMap = heroes.associateBy { it.id }
    val ordered = published.sortedBy { collection ->
        when {
            collection.season == null -> 1
            today in collection.season -> 0
            else -> 2
        }
    }
    return ordered.map { join(it, byId) }.filter { it.members.isNotEmpty() }.map { it.summary(heroMap, today) }
}

/** The [summaries] of [collections] over [catalog], again whenever either changes. */
fun collectionCards(catalog: CatalogRepository, collections: CollectionsRepository): Flow<List<CollectionSummary>> =
    combine(collections.collections, catalog.mods, catalog.heroes) { published, mods, heroes -> summaries(published, mods, heroes, catalog.today) }

/** A collection's card: its name, how its art is lit, what it holds and the renders its art shows. [seasonEnd] is set while it is in season. */
@Immutable
data class CollectionSummary(
    val id: String,
    val title: String,
    val look: CollectionLook,
    val skins: Int,
    val heroes: Int,
    val topHeroes: List<CollectionHero>,
    val figures: List<String>,
    val seasonEnd: CalendarDate?,
)

/** A hero that [count] of a collection's members are for. */
@Immutable
data class CollectionHero(val id: String, val name: String, val iconUrl: String?, val count: Int) {
    val initials: String get() = name.take(InitialsLength)

    private companion object {
        const val InitialsLength = 2
    }
}
