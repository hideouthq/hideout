package gg.hideouthq.feature.discover

import gg.hideouthq.core.data.CatalogRepository
import gg.hideouthq.core.designsystem.image.RemoteImages
import gg.hideouthq.core.ui.catalog.featuredRenderUrl
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Loads Discover's Featured renders as soon as the catalogue and the preview index name them, without waiting for the page
 * to lay out where they go: the one Discover opens on first, then the rest together. Each render is asked for once.
 */
suspend fun prefetchFeaturedRenders(catalog: CatalogRepository, preferredHeroes: Flow<List<String>>, images: RemoteImages) = coroutineScope {
    val asked = mutableSetOf<String>()
    combine(catalog.discoverFeed, catalog.mods, preferredHeroes) { feed, mods, preferred ->
        val byId = mods.associateBy { it.id }
        personalize(feed, mods, preferred).featured.mapNotNull { byId[it]?.featuredRenderUrl }
    }.distinctUntilChanged().collect { renders ->
        val fresh = renders.filter(asked::add)
        fresh.firstOrNull()?.let { images.prefetch(it) }
        fresh.drop(1).forEach { launch { images.prefetch(it) } }
    }
}
