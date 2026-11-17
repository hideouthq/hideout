package gg.hideouthq.feature.discover

import androidx.compose.runtime.Immutable
import gg.hideouthq.core.data.CatalogFreshness
import gg.hideouthq.core.data.DataError
import gg.hideouthq.core.data.InstallStatus
import gg.hideouthq.core.data.PlayingSample
import gg.hideouthq.core.model.CalendarDate
import gg.hideouthq.core.model.NewsPost
import gg.hideouthq.core.ui.catalog.CatalogItem
import gg.hideouthq.feature.collections.CollectionSummary

@Immutable
data class DiscoverUiState(
    val loading: Boolean = true,
    /** Set while there is nothing to show because the catalogue could not be loaded. */
    val loadError: DataError? = null,
    val featured: List<FeaturedMod> = emptyList(),
    val featuredIndex: Int = 0,
    val heroes: List<HeroItem> = emptyList(),
    val heroCount: Int = 0,
    val trendingSkins: List<CatalogItem> = emptyList(),
    val trendingHud: List<CatalogItem> = emptyList(),
    val sounds: List<SoundItem> = emptyList(),
    val trendingGameplay: List<CatalogItem> = emptyList(),
    /** The skin collections, those in season first; none while there are none to show. */
    val collections: List<CollectionSummary> = emptyList(),
    /** Deadlock's latest posts on Steam, newest first, and the time, in Unix seconds, their ages count from. */
    val news: List<NewsPost> = emptyList(),
    val now: Long = 0,
    val freshness: CatalogFreshness = CatalogFreshness(),
) {
    val current: FeaturedMod? get() = featured.getOrNull(featuredIndex)

    /** Whether a first listing is arriving, so a section with nothing yet is still to be filled rather than empty. */
    val filling: Boolean get() = freshness.checking && freshness.checkedAt == null
}

@Immutable
data class FeaturedMod(
    val item: CatalogItem,
    val heroId: String?,
    val updated: CalendarDate,
    val install: InstallStatus,
    /** The hero's key art for the hero background, if known. */
    val backgroundUrl: String?,
    /** The transparent cut-out of this mod the hero's stage shows, which stays empty without one. */
    val renderUrl: String?,
    val heroNameImageUrl: String? = null,
)

/** A hero in "Browse by hero"; [featured] marks the current featured mod's hero. */
@Immutable
data class HeroItem(val id: String, val name: String, val featured: Boolean, val iconUrl: String? = null) {
    val initials: String get() = name.take(InitialsLength)

    private companion object {
        const val InitialsLength = 2
    }
}

@Immutable
data class SoundItem(
    val item: CatalogItem,
    val waveform: List<Float>,
    val previewUrl: String?,
    val playback: PlayingSample?,
    val install: InstallStatus,
) {
    val playable: Boolean get() = previewUrl != null
    val playing: Boolean get() = playback?.let { !it.paused && !it.loading } == true
    val playingSeconds: Int? get() = playback?.takeIf { playing }?.elapsedSeconds
    val progress: Float get() = playback?.progress ?: 0f
}
