package gg.hideouthq.core.model

data class DiscoverFeed(
    /** Featured mods in rotation order; the first is featured on arrival. */
    val featured: List<String>,
    val heroesToBrowse: List<String>,
    val trendingSkins: List<String>,
    val trendingHud: List<String>,
    val trendingSounds: List<String>,
    val trendingGameplay: List<String> = emptyList(),
)
