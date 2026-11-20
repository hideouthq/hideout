package gg.hideouthq.feature.browse

import gg.hideouthq.core.model.CalendarDate
import gg.hideouthq.core.model.Hero
import gg.hideouthq.core.model.Mod
import gg.hideouthq.core.model.ModCategory

internal object BrowseQuery {
    /** [categories] are the labels the screens show for each category, which search matches against. */
    fun results(
        mods: List<Mod>, heroes: Map<String, Hero>, filters: BrowseFilters, mature: MatureMods, today: CalendarDate, categories: Map<ModCategory, String>,
        favourites: Set<String> = emptySet(),
    ): List<Mod> {
        val query = filters.query.trim()
        return mods
            .filter { !filters.favourites || it.id in favourites }
            .filter { filters.category == null || it.category == filters.category }
            .filter { filters.heroIds.isEmpty() || it.heroId in filters.heroIds }
            .filter { !filters.only3d || it.has3dPreview }
            .filter { !filters.currentPatch || it.worksOnCurrentPatch }
            .filter { mature != MatureMods.Hidden || !it.mature }
            .filter { mod -> filters.updated.days?.let { mod.updated.daysUntil(today) <= it } ?: true }
            .filter { query.isEmpty() || it.matches(query, heroes, categories) }
            .sortedWith(
                when (filters.sort) {
                    BrowseSort.Trending -> compareBy { it.trendingRank }
                    BrowseSort.Downloads -> compareByDescending { it.downloads }
                    BrowseSort.Newest -> compareByDescending<Mod> { it.publishedAt }.thenBy { it.id }
                },
            )
    }

    private fun Mod.matches(query: String, heroes: Map<String, Hero>, categories: Map<ModCategory, String>): Boolean =
        listOfNotNull(title, author, heroId?.let { heroes[it]?.name }, kind, categories[category])
            .any { it.contains(query, ignoreCase = true) }

    fun suggestions(query: String, heroes: List<Hero>, filters: BrowseFilters, categories: Map<ModCategory, String>): List<BrowseSuggestion> {
        val text = query.trim()
        if (text.isEmpty()) return emptyList()
        val heroMatches = heroes
            .filter { it.id !in filters.heroIds && it.name.startsWithWord(text) }
            .take(MaxSuggestions)
            .map { BrowseSuggestion.Hero(it.id, it.name) }
        val categoryMatches = categories
            .filter { (category, label) -> category != filters.category && label.startsWithWord(text) }
            .map { BrowseSuggestion.Category(it.key) }
        return heroMatches + categoryMatches
    }

    fun heroChips(heroes: List<Hero>, shortList: List<String>, filters: BrowseFilters): List<HeroChip> {
        val heroQuery = filters.heroQuery.trim()
        val shown = when {
            heroQuery.isNotEmpty() -> heroes.filter { it.name.contains(heroQuery, ignoreCase = true) }
            filters.showAllHeroes -> heroes
            else -> shortList.mapNotNull { id -> heroes.firstOrNull { it.id == id } } +
                heroes.filter { it.id in filters.heroIds && it.id !in shortList }
        }
        return shown.map { HeroChip(it.id, it.name, it.id in filters.heroIds) }
    }

    private fun String.startsWithWord(prefix: String): Boolean =
        startsWith(prefix, ignoreCase = true) || split(' ').any { it.startsWith(prefix, ignoreCase = true) }

    private const val MaxSuggestions = 3
}
