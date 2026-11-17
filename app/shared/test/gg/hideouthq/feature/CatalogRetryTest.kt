package gg.hideouthq.feature

import gg.hideouthq.core.data.CatalogFreshness
import gg.hideouthq.core.data.DataError
import gg.hideouthq.core.data.InstallStatus
import gg.hideouthq.core.data.fake.FixedInstallations
import gg.hideouthq.core.data.fake.FakeSoundSamplePlayer
import gg.hideouthq.core.data.fake.MemoryStorage
import gg.hideouthq.core.data.gamebanana.GameBananaFixtures
import gg.hideouthq.core.data.gamebanana.SubmissionFile
import gg.hideouthq.core.data.gamebanana.parseProfile
import gg.hideouthq.core.data.live.CatalogSnapshot
import gg.hideouthq.core.data.live.CatalogSource
import gg.hideouthq.core.data.live.LiveCatalogRepository
import gg.hideouthq.core.data.live.LiveFixtures
import gg.hideouthq.core.data.live.ModDetailMapper
import gg.hideouthq.core.data.live.SnapshotStore
import gg.hideouthq.core.data.live.StubSource
import gg.hideouthq.core.data.preview.PreviewFixtures
import gg.hideouthq.core.data.preview.noPreviews
import gg.hideouthq.core.library.SubmissionKind
import gg.hideouthq.core.library.SubmissionRef as InstalledRef
import gg.hideouthq.core.network.RemoteException
import gg.hideouthq.feature.browse.BrowseStart
import gg.hideouthq.feature.browse.BrowseViewModel
import gg.hideouthq.feature.discover.DiscoverViewModel
import gg.hideouthq.feature.moddetail.ModDetailViewModel
import gg.hideouthq.preview.PreviewIndex
import gg.hideouthq.preview.SubmissionRef
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class CatalogRetryTest {
    private var online = false
    private var pageOnline = false
    private val detail = ModDetailMapper.toDetail(parseProfile(GameBananaFixtures.Profile, InstalledRef(SubmissionKind.Mod, 1)))

    private val source = StubSource(
        fetch = {
            if (!online) throw RemoteException(DataError.Offline, "No route to host")
            LiveFixtures.snapshot()
        },
        detail = {
            if (!pageOnline) throw RemoteException(DataError.Unavailable, "HTTP 503")
            detail
        },
    )

    @Test
    fun aWarmCatalogueWhoseCheckFailsKeepsItsCardsAndShowsTheFailureUntilAnotherCheckSucceeds() = withViewModels { viewModels ->
        val stale = LiveFixtures.snapshot(syncedAt = LiveFixtures.Now - 56 * 3_600)
        val storage = MemoryStorage().also { SnapshotStore(it).save(stale) }
        val catalog = LiveCatalogRepository(source, SnapshotStore(storage), backgroundScope, LiveFixtures.clockAt(LiveFixtures.Now))
        val installations = FixedInstallations(flowOf(InstallStatus.NotInstalled))
        val browse = viewModels.keep(BrowseViewModel(catalog, BrowseStart(), emptyMap()))
        val discover = viewModels.keep(DiscoverViewModel(catalog, installations, FakeSoundSamplePlayer()))
        backgroundScope.launch { browse.uiState.collect {} }
        backgroundScope.launch { discover.uiState.collect {} }
        runCurrent()

        assertNull(browse.uiState.value.loadError, "the cards stay")
        assertEquals(5, browse.uiState.value.results.size)
        assertEquals(CatalogFreshness(checkedAt = stale.checkedAt, error = DataError.Offline), browse.uiState.value.freshness)
        assertNull(discover.uiState.value.loadError)
        assertTrue(discover.uiState.value.featured.isNotEmpty())
        assertEquals(DataError.Offline, discover.uiState.value.freshness.error)

        online = true
        browse.refresh()
        runCurrent()
        assertEquals(CatalogFreshness(checkedAt = LiveFixtures.Now), browse.uiState.value.freshness)
        assertEquals(CatalogFreshness(checkedAt = LiveFixtures.Now), discover.uiState.value.freshness)
    }

    @Test
    fun whileGameBananaHoldsACheckTheScreensSayUntilWhenAndAWarmCatalogueShowsItsCardsAtOnce() = withViewModels { viewModels ->
        val until = Instant.parse("2026-11-11T18:05:00Z")
        val answer = CompletableDeferred<CatalogSnapshot>()
        val held = StubSource(fetch = { answer.await() })
        for (warm in listOf(false, true)) {
            val storage = MemoryStorage()
            if (warm) SnapshotStore(storage).save(LiveFixtures.snapshot(syncedAt = LiveFixtures.Now - 56 * 3_600))
            val catalog = LiveCatalogRepository(held, SnapshotStore(storage), backgroundScope, LiveFixtures.clockAt(LiveFixtures.Now), busy = MutableStateFlow(until))
            val browse = viewModels.keep(BrowseViewModel(catalog, BrowseStart(), emptyMap()))
            val discover = viewModels.keep(DiscoverViewModel(catalog, FixedInstallations(flowOf(InstallStatus.NotInstalled)), FakeSoundSamplePlayer()))
            backgroundScope.launch { browse.uiState.collect {} }
            backgroundScope.launch { discover.uiState.collect {} }
            runCurrent()

            assertEquals(until, browse.uiState.value.freshness.busyUntil, "warm $warm")
            assertEquals(until, discover.uiState.value.freshness.busyUntil, "warm $warm")
            assertEquals(!warm, browse.uiState.value.loading, "a saved catalogue shows at once: warm $warm")
            assertEquals(if (warm) 5 else 0, browse.uiState.value.results.size)
            assertEquals(!warm, discover.uiState.value.loading)
        }
    }

    @Test
    fun theFeaturedCardCancelsAnInstallItStarted() = withViewModels { viewModels ->
        online = true
        val catalog = LiveCatalogRepository(source, SnapshotStore(MemoryStorage()), backgroundScope, LiveFixtures.clockAt(LiveFixtures.Now))
        val installations = FixedInstallations(flowOf(InstallStatus.Preparing))
        val discover = viewModels.keep(DiscoverViewModel(catalog, installations, FakeSoundSamplePlayer()))
        discover.cancelInstall("mod-1")
        assertEquals(listOf("mod-1"), installations.cancels)
    }

    @Test
    fun configuredPreviewFiltersRemainAvailableWhileTheirIndexLoads() = withViewModels { viewModels ->
        online = true
        val fixtures = LiveFixtures.snapshot()
        val withFile = fixtures.copy(submissions = fixtures.submissions.map {
            if (it.key.modId == "mod-1") it.copy(facts = it.facts?.copy(currentFile = SubmissionFile(101, PreviewFixtures.Md5))) else it
        })
        val previews = MutableStateFlow<PreviewIndex?>(null)
        val catalog = LiveCatalogRepository(
            object : CatalogSource by source {
                override fun fetch(since: CatalogSnapshot?, knownPicks: List<InstalledRef>, onProgress: (Float?) -> Unit) = flowOf(withFile)
            },
            SnapshotStore(MemoryStorage()), backgroundScope, LiveFixtures.clockAt(LiveFixtures.Now), previews = previews, previewsAvailable = true,
        )
        val browse = viewModels.keep(BrowseViewModel(catalog, BrowseStart(), emptyMap()))
        backgroundScope.launch { browse.uiState.collect {} }
        runCurrent()
        assertTrue(browse.uiState.value.viewerFilters)
        assertEquals(5, browse.uiState.value.results.size)

        previews.value = PreviewFixtures.index(PreviewFixtures.entry(PreviewFixtures.manifest(SubmissionRef("Mod", 1), 101, PreviewFixtures.Md5)))
        runCurrent()
        assertTrue(browse.uiState.value.viewerFilters)
    }

    @Test
    fun aCatalogueWithoutAPreviewHostIgnoresPreviewFiltersOnColdAndWarmStarts() = withViewModels { viewModels ->
        for (warm in listOf(false, true)) {
            online = true
            val storage = MemoryStorage()
            if (warm) SnapshotStore(storage).save(LiveFixtures.snapshot())
            val catalog = LiveCatalogRepository(source, SnapshotStore(storage), backgroundScope, LiveFixtures.clockAt(LiveFixtures.Now))
            val browse = viewModels.keep(BrowseViewModel(catalog, BrowseStart(), emptyMap()))
            browse.setOnly3d(true)
            backgroundScope.launch { browse.uiState.collect {} }
            runCurrent()

            assertEquals(5, browse.uiState.value.results.size)
            assertFalse(browse.uiState.value.viewerFilters)
            assertFalse(browse.uiState.value.filters.only3d)
            online = false
            browse.refresh()
            runCurrent()
            assertEquals(5, browse.uiState.value.results.size)
            assertEquals(DataError.Offline, browse.uiState.value.freshness.error)
        }
    }

    @Test
    fun aFailedConfiguredPreviewHostDoesNotFailTheCatalogue() = withViewModels { viewModels ->
        online = true
        var requests = 0
        val previews = gg.hideouthq.core.data.preview.PreviewRepository(
            object : gg.hideouthq.core.data.preview.PreviewSource {
                override suspend fun indexText(held: String?): String? {
                    requests++
                    throw IllegalStateException("Preview host offline")
                }
                override suspend fun manifestText(entry: gg.hideouthq.preview.IndexEntry): String = error("No bundle requested")
            }, MemoryStorage(), backgroundScope,
        )
        val catalog = LiveCatalogRepository(source, SnapshotStore(MemoryStorage()), backgroundScope, LiveFixtures.clockAt(LiveFixtures.Now),
            previews = previews.index, previewsAvailable = previews.available)
        val browse = viewModels.keep(BrowseViewModel(catalog, BrowseStart(), emptyMap()))
        backgroundScope.launch { browse.uiState.collect {} }
        runCurrent()

        assertEquals(1, requests)
        assertEquals(5, browse.uiState.value.results.size)
        assertNull(browse.uiState.value.freshness.error)
        assertNull(previews.index.value)
        assertTrue(browse.uiState.value.viewerFilters)
    }

    @Test
    fun aPendingModFetchDoesNotBlockHeroesAndCanRecover() = withViewModels { viewModels ->
        var listed = kotlinx.coroutines.CompletableDeferred<Unit>()
        var hydrationOnline = false
        val modSource = StubSource(fetch = {
            listed.await()
            if (!hydrationOnline) throw RemoteException(DataError.Offline, "Hydration offline")
            LiveFixtures.snapshot()
        })
        val catalog = LiveCatalogRepository(modSource, SnapshotStore(MemoryStorage()), backgroundScope, LiveFixtures.clockAt(LiveFixtures.Now))
        val installations = FixedInstallations(flowOf(InstallStatus.NotInstalled))
        val browse = viewModels.keep(BrowseViewModel(catalog, BrowseStart(), emptyMap()))
        val discover = viewModels.keep(DiscoverViewModel(catalog, installations, FakeSoundSamplePlayer(backgroundScope)))
        backgroundScope.launch { browse.uiState.collect {} }
        backgroundScope.launch { discover.uiState.collect {} }
        runCurrent()
        assertTrue(browse.uiState.value.results.isEmpty())
        assertTrue(browse.uiState.value.loading)
        assertTrue(discover.uiState.value.loading)
        assertEquals("Lady Geist", catalog.cachedHero("hero_ghost")?.name)
        listed.complete(Unit)
        runCurrent()
        assertEquals(DataError.Offline, browse.uiState.value.loadError)
        assertEquals(DataError.Offline, discover.uiState.value.loadError)
        assertFalse(browse.uiState.value.viewerFilters, "no preview switch without a host, even before the catalogue loads")
        hydrationOnline = true
        listed = kotlinx.coroutines.CompletableDeferred()
        browse.refresh()
        runCurrent()
        assertTrue(browse.uiState.value.loading)
        assertTrue(discover.uiState.value.loading)
        assertEquals("Lady Geist", catalog.cachedHero("hero_ghost")?.name)
        listed.complete(Unit)
        runCurrent()
        assertNull(browse.uiState.value.loadError)
        assertNull(discover.uiState.value.loadError)
        assertEquals(5, browse.uiState.value.results.size)
        assertEquals(listOf("mod-2", "mod-1"), discover.uiState.value.featured.map { it.item.id })
        assertEquals("https://cdn.test/spectre_sm.webp", discover.uiState.value.heroes.first { it.id == "hero_ghost" }.iconUrl)
        hydrationOnline = false
        browse.refresh()
        runCurrent()
        assertNull(browse.uiState.value.loadError)
        assertNull(discover.uiState.value.loadError)
        assertEquals(5, browse.uiState.value.results.size)
    }

    @Test
    fun theModPageRetriesItsDetails() = withViewModels { viewModels ->
        online = true
        val catalog = LiveCatalogRepository(source, SnapshotStore(MemoryStorage()), backgroundScope, LiveFixtures.clockAt(LiveFixtures.Now))
        val installations = FixedInstallations(flowOf(InstallStatus.NotInstalled))
        runCurrent()
        val page = viewModels.keep(ModDetailViewModel("mod-1", catalog, gg.hideouthq.AppContainer.fake().modLibrary, FakeSoundSamplePlayer(backgroundScope), noPreviews()))
        backgroundScope.launch { page.uiState.collect {} }
        runCurrent()

        assertEquals("Bride of Victor | Lady Geist", page.uiState.value.header?.title)
        assertEquals("https://cdn.test/spectre_sm.webp", page.uiState.value.header?.heroIconUrl)
        assertEquals(DataError.Unavailable, page.uiState.value.detailError)

        pageOnline = true
        page.retryDetail()
        runCurrent()

        assertNull(page.uiState.value.detailError)
        assertEquals(detail, page.uiState.value.detail)
    }
}
