package gg.hideouthq.feature.discover

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import gg.hideouthq.core.data.CatalogRepository
import gg.hideouthq.core.data.InstallStatus
import gg.hideouthq.core.data.ModInstallations
import gg.hideouthq.core.data.NewsRepository
import gg.hideouthq.core.data.PlayingSample
import gg.hideouthq.core.data.SamplePreview
import gg.hideouthq.core.data.SoundSamplePlayer
import gg.hideouthq.core.data.collections.CollectionsRepository
import gg.hideouthq.core.library.ModLibrary
import gg.hideouthq.core.model.DiscoverFeed
import gg.hideouthq.core.model.Hero
import gg.hideouthq.core.model.Mod
import gg.hideouthq.core.model.NewsPost
import gg.hideouthq.core.ui.catalog.SoundWaveforms
import gg.hideouthq.core.ui.catalog.featuredRenderUrl
import gg.hideouthq.core.ui.catalog.toCatalogItem
import gg.hideouthq.core.ui.catalog.whileCatalogLoads
import gg.hideouthq.feature.collections.collectionCards
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Clock

private const val StopTimeoutMillis = 5_000L

@OptIn(ExperimentalCoroutinesApi::class)
class DiscoverViewModel(
    private val catalog: CatalogRepository,
    private val installations: ModInstallations,
    private val player: SoundSamplePlayer,
    private val news: NewsRepository = NewsRepository.None,
    /** The heroes the user plays, catalogue ids, the most played first, which the feed leads with. */
    preferredHeroes: Flow<List<String>> = flowOf(emptyList()),
    collections: CollectionsRepository = CollectionsRepository.None,
) : ViewModel(), DiscoverPageActions {
    private val featuredIndex = MutableStateFlow(0)

    private val catalogue = combine(catalog.discoverFeed, catalog.mods, catalog.heroes, news.posts, preferredHeroes) { feed, mods, heroes, posts, preferred ->
        Catalogue(personalize(feed, mods, preferred), mods.associateBy { it.id }, heroes.associateBy { it.id }, posts)
    }

    private val catalogStatuses = catalogue.map { it.feed }.distinctUntilChanged().flatMapLatest { feed ->
        val installable = (feed.featured + feed.trendingSounds + feed.trendingSkins + feed.trendingHud + feed.trendingGameplay).distinct()
        combineAll(installable.map { id -> installations.status(id).map { id to it } }).map { it.toMap() }
    }

    private val availability = (installations as? ModLibrary)?.state?.map { it.canInstall } ?: flowOf(true)
    private val installStatuses = combine(catalogStatuses, availability) { statuses, canInstall ->
        statuses.mapValues { (_, status) ->
            if (!canInstall && (status == InstallStatus.NotInstalled || status is InstallStatus.Failed)) InstallStatus.Unknown else status
        }
    }

    val uiState: StateFlow<DiscoverUiState> =
        combine(catalogue, installStatuses, featuredIndex, player.nowPlaying) { cat, statuses, index, playing -> build(cat, statuses, index, playing) }
            .combine(catalog.freshness) { state, freshness -> state.copy(freshness = freshness) }
            .combine(collectionCards(catalog, collections)) { state, summaries -> state.copy(collections = if (state.filling) emptyList() else summaries) }
            .whileCatalogLoads(catalog.freshness) { freshness ->
                val error = freshness.error.takeUnless { freshness.checking }
                DiscoverUiState(loading = error == null, loadError = error, freshness = freshness)
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(StopTimeoutMillis), DiscoverUiState())

    override fun selectFeatured(index: Int) = featuredIndex.update { index.coerceIn(0, (uiState.value.featured.size - 1).coerceAtLeast(0)) }

    override fun advanceFeatured() = featuredIndex.update { current ->
        val count = uiState.value.featured.size
        if (count == 0) 0 else (current + 1) % count
    }

    /** Starts [modId]'s sample (stopping any other) or stops it if it is the one playing. */
    override fun toggleSample(modId: String) {
        val sound = uiState.value.sounds.firstOrNull { it.item.id == modId }?.takeIf { it.playable } ?: return
        if (player.nowPlaying.value?.modId == modId) player.stop() else player.play(SamplePreview(modId, sound.previewUrl))
    }

    /** Checks the catalogue for new and changed mods now, or tries loading it again after it failed. */
    override fun refresh() = catalog.refresh()

    override fun cancelInstall(modId: String) {
        installations.cancel(modId)
    }

    fun install(modId: String, onInstalled: (String) -> Unit) {
        viewModelScope.launch {
            if (installations.status(modId).first() is InstallStatus.Installed) onInstalled(modId)
            else installations.install(modId)
        }
    }

    fun stopSample() = player.stop()

    private fun build(cat: Catalogue, statuses: Map<String, InstallStatus>, index: Int, playing: PlayingSample?): DiscoverUiState {
        val feed = cat.feed
        fun item(mod: Mod) = mod.toCatalogItem(cat.heroes).copy(installation = statuses[mod.id])
        val featured = feed.featured.mapNotNull { id ->
            val mod = cat.mods[id] ?: return@mapNotNull null
            FeaturedMod(
                item = item(mod),
                heroId = mod.heroId,
                updated = mod.updated,
                install = statuses[id] ?: InstallStatus.NotInstalled,
                backgroundUrl = mod.heroId?.let { cat.heroes[it]?.backgroundUrl },
                heroNameImageUrl = mod.heroId?.let { cat.heroes[it]?.nameImageUrl },
                renderUrl = mod.featuredRenderUrl,
            )
        }
        val currentIndex = index.coerceIn(0, (featured.size - 1).coerceAtLeast(0))
        val featuredHero = featured.getOrNull(currentIndex)?.heroId
        return DiscoverUiState(
            loading = false,
            featured = featured,
            featuredIndex = currentIndex,
            heroes = feed.heroesToBrowse.mapNotNull { id -> cat.heroes[id]?.let { HeroItem(it.id, it.name, it.id == featuredHero, it.iconUrl) } },
            heroCount = cat.heroes.size,
            trendingSkins = feed.trendingSkins.mapNotNull { cat.mods[it]?.let(::item) },
            trendingHud = feed.trendingHud.mapNotNull { cat.mods[it]?.let(::item) },
            trendingGameplay = feed.trendingGameplay.mapNotNull { cat.mods[it]?.let(::item) },
            news = cat.news,
            now = Clock.System.now().epochSeconds,
            sounds = feed.trendingSounds.mapNotNull { id ->
                val mod = cat.mods[id] ?: return@mapNotNull null
                SoundItem(
                    item = item(mod),
                    waveform = SoundWaveforms.of(id, SoundWaveforms.RowBars),
                    previewUrl = mod.audioPreviewUrl,
                    playback = playing?.takeIf { it.modId == id },
                    install = statuses[id] ?: InstallStatus.NotInstalled,
                )
            },
        )
    }

    override fun onCleared() {
        player.stop()
    }

    private class Catalogue(val feed: DiscoverFeed, val mods: Map<String, Mod>, val heroes: Map<String, Hero>, val news: List<NewsPost>)
}

private inline fun <reified T> combineAll(flows: List<Flow<T>>): Flow<List<T>> =
    if (flows.isEmpty()) flowOf(emptyList()) else combine(flows) { it.toList() }
