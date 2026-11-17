package gg.hideouthq.core.data.live

import gg.hideouthq.core.data.CacheStorage
import gg.hideouthq.core.data.CatalogFreshness
import gg.hideouthq.core.data.CheckProgress
import gg.hideouthq.core.data.DataError
import gg.hideouthq.core.data.Loadable
import gg.hideouthq.core.data.fake.MemoryStorage
import gg.hideouthq.core.data.gamebanana.GameBananaFixtures
import gg.hideouthq.core.data.gamebanana.parseProfile
import gg.hideouthq.core.data.live.LiveFixtures.virtualClock
import gg.hideouthq.core.library.SubmissionKind
import gg.hideouthq.core.library.SubmissionRef
import gg.hideouthq.core.network.RemoteException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

@OptIn(ExperimentalCoroutinesApi::class)
class LiveCatalogRepositoryTest {
    private val clock = LiveFixtures.clockAt(LiveFixtures.Now)
    private val detail = ModDetailMapper.toDetail(parseProfile(GameBananaFixtures.Profile, SubmissionRef(SubmissionKind.Mod, 1)))

    @Test
    fun aFirstRunShowsTheCatalogueAsItArrivesAndSavesOnlyTheWholeOneOnce() = runTest {
        val storage = MemoryStorage()
        val saves = mutableListOf<String>()
        val counted = object : CacheStorage by storage {
            override suspend fun write(name: String, text: String) {
                if (name == "catalog.json") saves += text
                storage.write(name, text)
            }
        }
        val whole = LiveFixtures.snapshot()
        val rated = whole.submissions[1].copy(facts = null, mature = true)
        val arrived = CompletableDeferred<Unit>()
        val source = StubSource()
        val repository = LiveCatalogRepository(object : CatalogSource by source {
            override fun fetch(since: CatalogSnapshot?, knownPicks: List<SubmissionRef>, onProgress: (Float?) -> Unit) = flow {
                source.sinces += since
                emit(whole.copy(submissions = listOf(whole.submissions[0].copy(facts = null), rated)))
                arrived.await()
                emit(whole)
            }
        }, SnapshotStore(counted), backgroundScope, clock)
        runCurrent()

        assertTrue(repository.freshness.value.checking)
        val shown = repository.mods.first().associateBy { it.id }
        assertEquals(setOf("mod-1", "mod-2"), shown.keys)
        assertNull(shown.getValue("mod-1").downloads, "no count until its facts arrive")
        assertTrue(shown.getValue("mod-2").mature)
        assertEquals(emptyList(), saves)

        arrived.complete(Unit)
        runCurrent()
        assertEquals(9_000, repository.mods.first().first { it.id == "mod-1" }.downloads)
        assertEquals(CatalogFreshness(checkedAt = LiveFixtures.Now), repository.freshness.value)
        assertEquals(1, saves.size, "only the whole catalogue is saved, once")
        assertEquals(whole, SnapshotStore(storage).load())
        assertEquals(listOf<CatalogSnapshot?>(null), source.sinces, "a first run reconciles everything")
    }

    @Test
    fun aFailureMidCrawlKeepsWhatHadArrivedWithoutACatalogueAndThePreviousOneWithIt() = runTest {
        val whole = LiveFixtures.snapshot()
        val source = StubSource()
        val failing = object : CatalogSource by source {
            override fun fetch(since: CatalogSnapshot?, knownPicks: List<SubmissionRef>, onProgress: (Float?) -> Unit) = flow {
                source.sinces += since
                emit(whole.copy(submissions = whole.submissions.take(2)))
                throw RemoteException(DataError.Offline, "No route to host")
            }
        }
        val storage = MemoryStorage()
        val cold = repository(failing, storage)
        runCurrent()
        assertEquals(2, cold.mods.first().size, "what had arrived stays")
        assertEquals(CatalogFreshness(error = DataError.Offline), cold.freshness.value)
        assertNull(SnapshotStore(storage).load())
        cold.refresh()
        runCurrent()
        assertEquals(listOf<CatalogSnapshot?>(null, null), source.sinces, "part of a catalogue is never built on")

        val stale = whole.copy(syncedAt = LiveFixtures.Now - 30.hours.inWholeSeconds)
        SnapshotStore(storage).save(stale)
        val warm = repository(failing, storage)
        runCurrent()
        assertEquals(5, warm.mods.first().size, "the previous catalogue, not the part that arrived")
        assertEquals(DataError.Offline, warm.freshness.value.error)
        assertEquals(stale, SnapshotStore(storage).load())
    }

    @Test
    fun aSnapshotCheckedWithinTheHourIsServedWithoutChecking() = runTest {
        val saved = LiveFixtures.snapshot(checkedAt = LiveFixtures.Now - 30.minutes.inWholeSeconds)
        val storage = MemoryStorage().also { SnapshotStore(it).save(saved.copy(submissions = saved.submissions.map { it.copy(mature = it.key.modId == "mod-2") })) }
        val source = StubSource(fetch = { error("Should not check") })
        val release = CompletableDeferred<Unit>()
        val repository = repository(object : CatalogSource by source {
            override suspend fun heroes() = source.heroes().also { release.await() }
        }, storage)
        runCurrent()
        assertNull(assertNotNull(repository.cachedMod("mod-1")).heroId)
        release.complete(Unit)
        runCurrent()

        assertEquals("hero_ghost", repository.cachedMod("mod-1")?.heroId)
        assertEquals(5, repository.mods.first().size)
        assertEquals(true, repository.cachedMod("mod-2")?.mature, "rated in the listing, so hidden from a saved catalogue too")
        assertEquals(listOf("Lady Geist", "The Doorman", "Victor"), repository.heroes.first().map { it.name })
        assertEquals(0, source.fetches)
        assertEquals(CatalogFreshness(checkedAt = LiveFixtures.Now - 30.minutes.inWholeSeconds), repository.freshness.value)
    }

    @Test
    fun aStaleSnapshotIsServedThenReplaced() = runTest {
        val stale = LiveFixtures.snapshot(syncedAt = LiveFixtures.Now - 30.hours.inWholeSeconds)
        val storage = MemoryStorage().also { SnapshotStore(it).save(stale.copy(submissions = stale.submissions.take(2))) }
        val source = StubSource()
        val repository = repository(source, storage)
        runCurrent()

        assertEquals(5, repository.mods.first().size)
        assertEquals(LiveFixtures.Now, SnapshotStore(storage).load()?.syncedAt)
        assertEquals(listOf<CatalogSnapshot?>(null), source.sinces, "a day after the last reconciliation the next check is a full one")
    }

    @Test
    fun anOlderSnapshotIsNotShownAndTheFirstCheckStreams() = runTest {
        val storage = MemoryStorage().also { SnapshotStore(it).save(LiveFixtures.snapshot(checkedAt = LiveFixtures.Now - 1.minutes.inWholeSeconds).copy(schemaVersion = CatalogSnapshot.SchemaVersion - 1)) }
        val whole = LiveFixtures.snapshot()
        val release = CompletableDeferred<Unit>()
        val source = StubSource()
        val repository = repository(object : CatalogSource by source {
            override fun fetch(since: CatalogSnapshot?, knownPicks: List<SubmissionRef>, onProgress: (Float?) -> Unit) = flow {
                source.sinces += since
                emit(whole.copy(submissions = whole.submissions.take(2)))
                release.await()
                emit(whole)
            }
        }, storage)
        runCurrent()

        assertEquals(setOf("mod-1", "mod-2"), repository.mods.first().map { it.id }.toSet(), "what the first check has brought, never the older catalogue")
        assertEquals(listOf<CatalogSnapshot?>(null), source.sinces)
        release.complete(Unit)
        runCurrent()
        assertEquals(whole, SnapshotStore(storage).load())
    }

    @Test
    fun aSnapshotFromANewerSchemaIsIgnored() = runTest {
        val storage = MemoryStorage()
        SnapshotStore(storage).save(LiveFixtures.snapshot().copy(schemaVersion = CatalogSnapshot.SchemaVersion + 1))
        assertNull(SnapshotStore(storage).load())
    }

    @Test
    fun aFailedFirstCheckReportsWhyAndRefreshTriesAgain() = runTest {
        var online = false
        val source = StubSource(fetch = {
            if (!online) throw RemoteException(DataError.Offline, "No route to host")
            LiveFixtures.snapshot()
        })
        val repository = repository(source, MemoryStorage())
        runCurrent()

        assertEquals(CatalogFreshness(error = DataError.Offline), repository.freshness.value)
        assertNull(repository.cachedMod("mod-1"))

        online = true
        repository.refresh()
        runCurrent()

        assertEquals(CatalogFreshness(checkedAt = LiveFixtures.Now), repository.freshness.value)
        assertEquals("Bride of Victor | Lady Geist", repository.cachedMod("mod-1")?.title)
    }

    @Test
    fun aFailedCheckOfAWarmCatalogueIsShownAndKeepsItsCardsAndTimes() = runTest {
        val stale = LiveFixtures.snapshot(syncedAt = LiveFixtures.Now - 56.hours.inWholeSeconds)
        val storage = MemoryStorage().also { SnapshotStore(it).save(stale) }
        val before = storage.read("catalog.json")
        var online = false
        val repository = repository(StubSource(fetch = {
            if (!online) throw RemoteException(DataError.Unavailable, "HTTP 503")
            LiveFixtures.snapshot()
        }), storage)
        runCurrent()

        assertEquals(CatalogFreshness(checkedAt = stale.checkedAt, error = DataError.Unavailable), repository.freshness.value)
        assertEquals(5, repository.mods.first().size)
        assertEquals(before, storage.read("catalog.json"))

        online = true
        repository.refresh()
        runCurrent()
        assertEquals(CatalogFreshness(checkedAt = LiveFixtures.Now), repository.freshness.value)
    }

    @Test
    fun heroesRemainAvailableWhenTheModListingFailsAndSurviveARestart() = runTest {
        val storage = MemoryStorage()
        val source = StubSource(fetch = { throw RemoteException(DataError.Offline, "No route to host") })
        val repository = repository(source, storage)
        runCurrent()

        assertEquals(DataError.Offline, repository.freshness.value.error)
        assertNull(repository.cachedMod("mod-1"))
        assertEquals("Lady Geist", repository.cachedHero("hero_ghost")?.name)

        val offline = object : CatalogSource by source {
            override suspend fun heroes() = throw RemoteException(DataError.Offline, "No route to host")
            override fun fetch(since: CatalogSnapshot?, knownPicks: List<SubmissionRef>, onProgress: (Float?) -> Unit) = flowOf(LiveFixtures.snapshot())
        }
        val restarted = repository(offline, storage)
        runCurrent()
        assertEquals("Lady Geist", restarted.cachedHero("hero_ghost")?.name)
        assertEquals("hero_ghost", restarted.cachedMod("mod-1")?.heroId)
        assertNull(restarted.freshness.value.error)
    }

    @Test
    fun aStalledHeroSourceDoesNotHoldBackTheCatalogue() = runTest {
        var heroCalls = 0
        val source = object : CatalogSource by StubSource() {
            override suspend fun heroes(): Nothing {
                heroCalls++
                awaitCancellation()
            }
        }
        val repository = repository(source, MemoryStorage())
        runCurrent()

        assertEquals(5, repository.mods.first().size)
        assertEquals(CatalogFreshness(checkedAt = LiveFixtures.Now), repository.freshness.value)
        assertEquals(1, heroCalls, "the first full check does not ask again while the roster is still on its way")
    }

    @Test
    fun startupChecksOnceTheLastCheckIsAnHourOldThenHourlyAndADayAfterTheLastFullCheckInFull() = runTest {
        val clock = virtualClock()
        val start = LiveFixtures.Now
        val storage = MemoryStorage().also { SnapshotStore(it).save(LiveFixtures.snapshot(syncedAt = start - 20.hours.inWholeSeconds, checkedAt = start - 59.minutes.inWholeSeconds)) }
        val source = StubSource(fetch = { since -> stamped(since, clock) })
        val repository = LiveCatalogRepository(source, SnapshotStore(storage), backgroundScope, clock)
        runCurrent()
        assertEquals(0, source.fetches, "checked 59 minutes ago")

        advanceTimeBy(1.minutes)
        runCurrent()
        assertEquals(1, source.fetches)
        assertNotNull(source.sinces.last(), "within a day of the last full check, the check is incremental")
        assertEquals(start - 20.hours.inWholeSeconds, saved(storage).syncedAt)
        assertEquals(start + 60, repository.freshness.value.checkedAt)

        advanceTimeBy(59.minutes)
        runCurrent()
        assertEquals(1, source.fetches)
        advanceTimeBy(1.minutes)
        runCurrent()
        assertEquals(2, source.fetches)

        advanceTimeBy(3.hours)
        runCurrent()
        assertEquals(5, source.fetches)
        assertEquals(listOf(false, false, false, false, true), source.sinces.map { it == null })
        assertEquals(start + 4.hours.inWholeSeconds + 60, saved(storage).syncedAt)
    }

    @Test
    fun refreshChecksAtOnceAndRequestsDuringACheckRunOneMoreAfterIt() = runTest {
        val clock = virtualClock()
        val storage = MemoryStorage().also { SnapshotStore(it).save(LiveFixtures.snapshot()) }
        var gate = CompletableDeferred<Unit>()
        val source = StubSource(fetch = { since ->
            gate.await()
            stamped(since, clock)
        })
        val repository = LiveCatalogRepository(source, SnapshotStore(storage), backgroundScope, clock)
        runCurrent()
        assertEquals(0, source.fetches, "just checked")

        repository.refresh()
        runCurrent()
        assertEquals(1, source.fetches, "refresh does not wait for the hour")
        assertTrue(repository.freshness.value.checking)
        repeat(3) { repository.refresh() }
        gate.complete(Unit)
        runCurrent()
        assertEquals(2, source.fetches, "the three requests made during the check run one more check")
        assertFalse(repository.freshness.value.checking)

        gate = CompletableDeferred()
        advanceTimeBy(59.minutes)
        runCurrent()
        assertEquals(2, source.fetches, "the next scheduled check is an hour after the last")
        advanceTimeBy(1.minutes)
        runCurrent()
        assertEquals(3, source.fetches)
        repository.refresh()
        gate.complete(Unit)
        runCurrent()
        assertEquals(4, source.fetches)
    }

    @Test
    fun aFailedCheckIsTriedAgainAtTheNextHourNotInALoop() = runTest {
        val clock = virtualClock()
        val storage = MemoryStorage().also { SnapshotStore(it).save(LiveFixtures.snapshot(checkedAt = LiveFixtures.Now - 2.hours.inWholeSeconds)) }
        var online = false
        val source = StubSource(fetch = { since ->
            if (!online) throw RemoteException(DataError.Offline, "No route to host")
            stamped(since, clock)
        })
        val repository = LiveCatalogRepository(source, SnapshotStore(storage), backgroundScope, clock)
        runCurrent()
        assertEquals(1, source.fetches)
        assertEquals(DataError.Offline, repository.freshness.value.error)

        advanceTimeBy(59.minutes)
        runCurrent()
        assertEquals(1, source.fetches)
        online = true
        advanceTimeBy(1.minutes)
        runCurrent()
        assertEquals(2, source.fetches)
        assertEquals(CatalogFreshness(checkedAt = LiveFixtures.Now + 1.hours.inWholeSeconds), repository.freshness.value)
    }

    @Test
    fun progressIsReportedWhileACheckRuns() = runTest {
        val halfway = CompletableDeferred<Unit>()
        val source = object : CatalogSource by StubSource() {
            override fun fetch(since: CatalogSnapshot?, knownPicks: List<SubmissionRef>, onProgress: (Float?) -> Unit) = flow {
                onProgress(0.5f)
                halfway.await()
                emit(LiveFixtures.snapshot())
            }
        }
        val repository = repository(source, MemoryStorage())
        runCurrent()
        assertEquals(CheckProgress(0.5f), repository.freshness.value.running)
        halfway.complete(Unit)
        runCurrent()
        assertNull(repository.freshness.value.running)
    }

    @Test
    fun aModPageIsFetchedOnceAndFailuresAreReported() = runTest {
        val detail = ModDetailMapper.toDetail(parseProfile(GameBananaFixtures.Profile, SubmissionRef(SubmissionKind.Mod, 723542)))
        val source = StubSource(detail = { id ->
            if (id == "mod-723542") detail else throw RemoteException(DataError.Offline, "No route to host")
        })
        val repository = repository(source, MemoryStorage())
        runCurrent()

        assertEquals(listOf(Loadable.Loading, Loadable.Ready(detail)), repository.detail("mod-723542").toList())
        assertEquals(listOf(Loadable.Ready(detail)), repository.detail("mod-723542").toList())
        assertEquals(1, source.details)
        assertEquals(Loadable.Failed(DataError.Offline), repository.detail("mod-1").toList().last())
    }

    @Test
    fun aSuccessfulRefreshInvalidatesDetailButAFailedRefreshDoesNot() = runTest {
        var version = "old"
        var online = true
        val source = StubSource(fetch = {
            if (!online) throw RemoteException(DataError.Offline, "Offline")
            LiveFixtures.snapshot()
        }, detail = { detail.copy(version = version) })
        val repository = repository(source, MemoryStorage())
        runCurrent()
        assertEquals("old", (repository.detail("mod-1").toList().last() as Loadable.Ready).value.version)
        version = "new"
        repository.refresh()
        runCurrent()
        assertEquals("new", (repository.detail("mod-1").toList().last() as Loadable.Ready).value.version)
        version = "unreachable"
        online = false
        repository.refresh()
        runCurrent()
        assertEquals(listOf(Loadable.Ready(detail.copy(version = "new"))), repository.detail("mod-1").toList())
        assertEquals(2, source.details)
    }

    @Test
    fun anUnreadableSnapshotIsACacheMiss() = runTest {
        val unreadable = object : gg.hideouthq.core.data.CacheStorage {
            override suspend fun read(name: String): String? = error("Disk unreadable")
            override suspend fun write(name: String, text: String) = Unit
        }
        assertNull(SnapshotStore(unreadable).load())
        val source = StubSource()
        val repository = LiveCatalogRepository(source, SnapshotStore(unreadable), backgroundScope, clock)
        runCurrent()
        assertEquals(5, repository.mods.first().size)
        repository.refresh()
        runCurrent()
        assertEquals(2, source.fetches)
    }

    @Test
    fun anUnchangedCheckRefreshesFreshnessAndStillInvalidatesDetailButSkipsTheRewrite() = runTest {
        val storage = MemoryStorage()
        val initial = LiveFixtures.snapshot(checkedAt = LiveFixtures.Now - 30.minutes.inWholeSeconds)
        SnapshotStore(storage).save(initial)
        val before = assertNotNull(storage.read("catalog.json"))
        var detailCalls = 0
        val source = StubSource(fetch = { since -> assertNotNull(since).copy(checkedAt = clock.now().epochSeconds) }, detail = { detailCalls++; detail })
        val repository = repository(source, storage)
        runCurrent()
        assertEquals(listOf(Loadable.Loading, Loadable.Ready(detail)), repository.detail("mod-1").toList())
        assertEquals(1, detailCalls)

        repository.refresh()
        runCurrent()

        assertEquals(before, storage.read("catalog.json"), "content unchanged but for checkedAt is not rewritten")
        assertEquals(CatalogFreshness(checkedAt = LiveFixtures.Now), repository.freshness.value, "freshness still reflects the successful check")
        assertEquals(listOf(Loadable.Loading, Loadable.Ready(detail)), repository.detail("mod-1").toList(), "a successful check still invalidates the detail cache")
        assertEquals(2, detailCalls)
        assertEquals(5, repository.mods.first().size, "the unchanged catalogue is still served from the retained snapshot")

        val restarted = repository(StubSource(fetch = { error("should not check yet") }), storage)
        runCurrent()
        assertEquals(initial.checkedAt, restarted.freshness.value.checkedAt, "a restart starts from the last persisted check, not the skipped one")
    }

    @Test
    fun anIncrementalCheckWithRealChangesRewritesTheCatalogue() = runTest {
        val storage = MemoryStorage()
        val initial = LiveFixtures.snapshot(checkedAt = LiveFixtures.Now - 30.minutes.inWholeSeconds)
        SnapshotStore(storage).save(initial)
        val changed = initial.copy(submissions = initial.submissions.map {
            if (it.key.id == 1L && it.key.kind == SubmissionKind.Mod) it.copy(facts = it.facts?.copy(downloads = 12_345)) else it
        })
        val repository = repository(StubSource(fetch = { changed.copy(checkedAt = clock.now().epochSeconds) }), storage)
        runCurrent()

        repository.refresh()
        runCurrent()

        assertEquals(12_345, repository.mods.first().first { it.id == "mod-1" }.downloads)
        assertEquals(LiveFixtures.Now, assertNotNull(SnapshotStore(storage).load()).checkedAt, "a real change is persisted with its fresh checkedAt")
    }

    private fun TestScope.repository(source: CatalogSource, storage: MemoryStorage) =
        LiveCatalogRepository(source, SnapshotStore(storage), backgroundScope, clock)

    private suspend fun saved(storage: MemoryStorage): CatalogSnapshot = assertNotNull(SnapshotStore(storage).load())

    private fun stamped(since: CatalogSnapshot?, clock: Clock): CatalogSnapshot {
        val now = clock.now().epochSeconds
        return since?.copy(checkedAt = now) ?: LiveFixtures.snapshot(syncedAt = now)
    }
}
