package gg.hideouthq.feature.discover

import gg.hideouthq.core.model.DiscoverFeed
import gg.hideouthq.core.model.Mod
import gg.hideouthq.core.model.ModCategory

/**
 * [feed] led by the [preferred] heroes, catalogue ids, the most played first. Featured opens with each one's best-trending skin
 * that Discover would feature (a skin with art, not mature, with a ready picture while any has one), as many as the
 * feed featured. Trending skins open with
 * all of their skins with art by trending rank, then the rest as the feed had them, as many as before. The hero strip
 * opens with them. No preferred heroes leaves the feed as it is.
 */
fun personalize(feed: DiscoverFeed, mods: List<Mod>, preferred: List<String>): DiscoverFeed {
    if (preferred.isEmpty()) return feed
    val heroesToBrowse = (preferred.filter { it in feed.heroesToBrowse } + feed.heroesToBrowse).distinct()
    if (mods.any { it.category == ModCategory.Skins && !it.rankFinal }) return feed.copy(heroesToBrowse = heroesToBrowse)
    val skins = mods.filter { it.category == ModCategory.Skins && !it.mature && it.cover.url != null }.sortedBy { it.trendingRank }
    val featurable = skins.filter { it.hasReadyPicture }.ifEmpty { skins }
    val leading = preferred.mapNotNull { hero -> featurable.firstOrNull { it.heroId == hero }?.id }
    val featured = (leading + feed.featured).distinct().take(feed.featured.size)
    val theirs = skins.filter { it.heroId in preferred }.map { it.id }
    return feed.copy(
        featured = featured,
        trendingSkins = (theirs + feed.featured + feed.trendingSkins).distinct().filter { it !in featured }.take(feed.trendingSkins.size),
        heroesToBrowse = heroesToBrowse,
    )
}
