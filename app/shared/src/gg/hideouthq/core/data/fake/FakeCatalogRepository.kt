package gg.hideouthq.core.data.fake

import gg.hideouthq.core.data.CatalogFreshness
import gg.hideouthq.core.data.CatalogRepository
import gg.hideouthq.core.data.DataError
import gg.hideouthq.core.data.Loadable
import gg.hideouthq.core.library.FilesObservation
import gg.hideouthq.core.library.SubmissionRef
import gg.hideouthq.core.model.CalendarDate
import gg.hideouthq.core.model.DiscoverFeed
import gg.hideouthq.core.model.Hero
import gg.hideouthq.core.model.Mod
import gg.hideouthq.core.model.ModDetail
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

internal class FakeCatalogRepository(private val store: FakeStore) : CatalogRepository {
    override val mods: Flow<List<Mod>> = with(store) { mods.withLatency() }
    override val heroes: Flow<List<Hero>> = with(store) { flowOf(SampleCatalog.heroes).withLatency() }
    override val popularHeroIds: Flow<List<String>> = flowOf(SampleCatalog.shortListHeroIds)
    override val discoverFeed: Flow<DiscoverFeed> = with(store) { flowOf(SampleCatalog.discoverFeed).withLatency() }
    override val today: CalendarDate = SampleCatalog.Today
    override val previewsAvailable: Boolean = true
    override val freshness: StateFlow<CatalogFreshness> = MutableStateFlow(CatalogFreshness(checkedAt = SampleCatalog.CheckedAt)).asStateFlow()
    override val installedFiles: StateFlow<Map<SubmissionRef, FilesObservation>> = MutableStateFlow(emptyMap<SubmissionRef, FilesObservation>()).asStateFlow()

    override fun refresh() = Unit

    override fun detail(modId: String): Flow<Loadable<ModDetail>> = with(store) {
        mods.map { list ->
            val mod = list.firstOrNull { it.id == modId } ?: return@map Loadable.Failed(DataError.NotFound)
            Loadable.Ready(SampleDetails.detailFor(mod, SampleCatalog.heroes.firstOrNull { it.id == mod.heroId }?.name))
        }.withLatency()
    }

    override fun cachedMod(modId: String): Mod? = store.mods.value.firstOrNull { it.id == modId }

    override fun cachedHero(heroId: String): Hero? = SampleCatalog.heroes.firstOrNull { it.id == heroId }
}
