package gg.hideouthq.feature.discover

import gg.hideouthq.core.model.ModCategory
import gg.hideouthq.core.model.NewsPost

interface DiscoverNavigation {
    fun search(query: String)
    fun openMod(modId: String)
    /** Opens [modId]'s page with its 3D preview loading. */
    fun previewMod(modId: String)
    fun browseHero(heroId: String)
    fun browseAllHeroes()
    fun browseCategory(category: ModCategory)
    fun openCollection(id: String)
    fun openCollections()
}

interface DiscoverPageActions {
    fun selectFeatured(index: Int)
    fun advanceFeatured()
    fun toggleSample(modId: String)
    fun refresh()

    /** Stops [modId]'s install while it gets or downloads its files. */
    fun cancelInstall(modId: String)
}

interface DiscoverActions : DiscoverNavigation, DiscoverPageActions {
    fun install(modId: String)
    fun readNews(post: NewsPost)
    /** Opens Deadlock's news on Steam in the browser. */
    fun openAllNews()
}
