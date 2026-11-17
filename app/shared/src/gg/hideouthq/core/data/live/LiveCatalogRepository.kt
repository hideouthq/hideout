package gg.hideouthq.core.data.live

import gg.hideouthq.core.data.CatalogFreshness
import gg.hideouthq.core.data.CatalogRepository
import gg.hideouthq.core.data.CheckProgress
import gg.hideouthq.core.data.DataError
import gg.hideouthq.core.data.Loadable
import gg.hideouthq.core.data.quietly
import gg.hideouthq.core.library.FilesObservation
import gg.hideouthq.core.library.SubmissionIdentity
import gg.hideouthq.core.library.SubmissionRef
import gg.hideouthq.core.model.CalendarDate
import gg.hideouthq.core.model.DiscoverFeed
import gg.hideouthq.core.model.Hero
import gg.hideouthq.core.model.Mod
import gg.hideouthq.core.model.ModDetail
import gg.hideouthq.core.network.RemoteException
import gg.hideouthq.preview.PreviewIndex
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

internal class LiveCatalogRepository(
    private val source: CatalogSource,
    private val snapshots: SnapshotStore,
    private val scope: CoroutineScope,
    private val clock: Clock = Clock.System,
    previews: StateFlow<PreviewIndex?> = MutableStateFlow(null),
    installed: Flow<Set<SubmissionIdentity>> = emptyFlow(),
    override val previewsAvailable: Boolean = false,
    busy: StateFlow<Instant?> = MutableStateFlow(null),
) : CatalogRepository {
    private val snapshot = MutableStateFlow<CatalogSnapshot?>(null)

    private var settled: CatalogSnapshot? = null
    private val state = MutableStateFlow(CatalogFreshness())
    private val requests = Channel<Unit>(Channel.CONFLATED)
    private val installedSources = MutableStateFlow(emptySet<SubmissionIdentity>())
    private val newlyInstalled = Channel<Unit>(Channel.CONFLATED)
    private val observations = MutableStateFlow(emptyMap<SubmissionRef, FilesObservation>())
    private var heroRefresh: Job? = null
    private val detailMutex = Mutex()
    private val details = LinkedHashMap<String, ModDetail>()

    private val heroRoster = MutableStateFlow(emptyList<Hero>())

    private val ready = combine(snapshot.filterNotNull(), heroRoster) { saved, heroes ->
        CatalogAssembler.assemble(saved, heroes, today)
    }.stateIn(scope, SharingStarted.Eagerly, null).filterNotNull()
    private val enriched = combine(ready, previews.map { it?.entries.orEmpty().groupBy { entry -> entry.submission } }) { catalog, entries ->
        catalog.previewed(entries)
    }.stateIn(scope, SharingStarted.Eagerly, null)

    override val mods: Flow<List<Mod>> = enriched.filterNotNull().map { it.mods }
    override val heroes: Flow<List<Hero>> = heroRoster.map { roster -> roster.sortedBy { it.name } }
    override val popularHeroIds: Flow<List<String>> = ready.map { it.popularHeroIds }
    override val discoverFeed: Flow<DiscoverFeed> = enriched.filterNotNull().map { it.discoverFeed }
    override val freshness: StateFlow<CatalogFreshness> = combine(state, busy) { freshness, until ->
        if (freshness.checking) freshness.copy(busyUntil = until) else freshness
    }.stateIn(scope, SharingStarted.Eagerly, state.value)
    override val installedFiles: StateFlow<Map<SubmissionRef, FilesObservation>> = observations.asStateFlow()
    override val today: CalendarDate get() = CalendarDate.today(clock)

    init {
        scope.launch {
            installed.collect { sources ->
                installedSources.value = sources
                newlyInstalled.trySend(Unit)
            }
        }
        scope.launch {
            heroRoster.value = snapshots.loadHeroes()
            refreshHeroes()
            val saved = snapshots.load()
            settled = saved
            snapshot.value = saved
            state.update { it.copy(checkedAt = saved?.checkedAt) }
            var due = saved?.takeUnless { it.dueInFull() }?.let { it.checkedAt + CheckInterval.inWholeSeconds } ?: now()
            var asked = emptySet<SubmissionIdentity>()
            while (true) {
                val cataloguePass = withTimeoutOrNull((due - now()).seconds) {
                    select {
                        requests.onReceive { true }
                        newlyInstalled.onReceive { false }
                    }
                } ?: true
                val sources = installedSources.value
                if (cataloguePass) {
                    requests.tryReceive()
                    state.update { it.copy(running = CheckProgress()) }
                    observe(sources)
                    check()
                    due = now() + CheckInterval.inWholeSeconds
                } else {
                    val renewed = (sources - asked).mapTo(HashSet()) { it.ref }
                    observe(sources.filterTo(HashSet()) { it.ref in renewed })
                }
                asked = sources
            }
        }
    }

    override fun refresh() {
        requests.trySend(Unit)
    }

    override fun detail(modId: String): Flow<Loadable<ModDetail>> = flow {
        val cached = detailMutex.withLock { details.remove(modId)?.also { details[modId] = it } }
        if (cached != null) return@flow emit(Loadable.Ready(cached))
        emit(Loadable.Loading)
        val loaded = try {
            Loadable.Ready(source.detail(modId))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Loadable.Failed(e.dataError())
        }
        if (loaded is Loadable.Ready) detailMutex.withLock {
            details[modId] = loaded.value
            if (details.size > DetailCapacity) details.remove(details.keys.first())
        }
        emit(loaded)
    }

    override fun cachedMod(modId: String): Mod? = enriched.value?.modsById?.get(modId)

    override fun cachedHero(heroId: String): Hero? = heroRoster.value.firstOrNull { it.id == heroId }

    private suspend fun observe(sources: Set<SubmissionIdentity>) {
        if (sources.isEmpty()) return
        val seen = try {
            source.observe(sources)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            sources.groupBy { it.ref }.mapValues { (_, files) -> FilesObservation(answer = null, files.toSet(), failure = e.dataError()) }
        }
        observations.update { it + seen }
    }

    private suspend fun check() {
        val previous = settled
        val full = previous.dueInFull()
        if (full) refreshHeroes()
        val fetched = try {
            source.fetch(since = previous.takeUnless { full }, knownPicks = previous?.featured.orEmpty()) { fraction -> state.update { it.copy(running = CheckProgress(fraction)) } }
                .onEach { if (previous == null) snapshot.value = it }
                .last()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (previous == null) snapshot.update { it?.copy(unheard = emptyMap()) }
            state.update { it.copy(running = null, error = e.dataError()) }
            return
        }
        settled = fetched
        val changed = previous == null || fetched.copy(checkedAt = previous.checkedAt) != previous
        detailMutex.withLock { details.clear() }
        if (changed) snapshot.value = fetched
        state.value = CatalogFreshness(checkedAt = fetched.checkedAt)
        if (changed) quietly { snapshots.save(fetched) }
    }

    private fun refreshHeroes() {
        if (heroRefresh?.isActive == true) return
        heroRefresh = scope.launch {
            val heroes = quietly { source.heroes() }?.takeIf { it.isNotEmpty() } ?: return@launch
            heroRoster.value = heroes
            quietly { snapshots.saveHeroes(heroes) }
        }
    }

    private fun CatalogSnapshot?.dueInFull(): Boolean = this == null || now() - syncedAt >= FullInterval.inWholeSeconds

    private fun now(): Long = clock.now().epochSeconds

    private fun Exception.dataError(): DataError = (this as? RemoteException)?.error ?: DataError.Unavailable

    private companion object {
        const val DetailCapacity = 32
        val CheckInterval = 1.hours
        val FullInterval = 24.hours
    }
}
