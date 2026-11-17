package gg.hideouthq.core.data.deadlockstats

import gg.hideouthq.core.data.CacheStorage
import io.ktor.client.HttpClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Clock

/** One tracked account. History and loaded details have separate, intentionally partial coverage. */
data class DeadlockStatsState(
    val accountId: Long? = null,
    val profile: PlayerProfile? = null,
    val matchHistory: List<MatchHistoryEntry> = emptyList(),
    val rank: RankStatus? = null,
    val mateStats: List<PlayedWithStats> = emptyList(),
    val enemyStats: List<PlayedWithStats> = emptyList(),
    /** At most 200 details loaded or ingested since the account opened; the archive keeps every saved one. */
    val matchDetails: Map<Long, MatchDetail> = emptyMap(),
    /** Final per-match numbers for history rows, retained independently of loaded details. */
    val matchSummaries: Map<Long, MatchPlayerSummary> = emptyMap(),
    val itemAssets: List<ItemAsset> = emptyList(),
    val rankAssets: List<RankAsset> = emptyList(),
    val watcherEnabled: Boolean = false,
    val lastPersistenceError: String? = null,
    /** When the match history last arrived, under "history"; not a promise of upstream completeness or freshness. */
    val fetchedAt: Map<String, Long> = emptyMap(),
    val steamTracking: SteamTrackingStatus = SteamTrackingStatus(),
)

class DeadlockStatsRepository(
    httpClient: HttpClient,
    private val storage: CacheStorage,
    scope: CoroutineScope,
    private val watcher: ReplayCacheWatcherPort? = null,
    private val clock: Clock = Clock.System,
    baseUrl: String = DeadlockStatsApi.DefaultBaseUrl,
    private val steamTrackingFactory: (() -> SteamTrackingPort)? = null,
    /** The API budget; repositories that talk to the same API at once share one. */
    requestGate: StatsRequestGate = StatsRequestGate(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AutoCloseable {
    private val api = DeadlockStatsApi(httpClient, baseUrl, requestGate)
    private val downloader = ReplayMetadataDownloader(httpClient)
    private val store = DeadlockStatsSnapshotStore(storage)
    private val archive = MatchDetailArchive(storage)
    private val trackingLifecycle = Mutex()
    private var trackingJob: Job? = null
    private var trackingCollector: SteamTrackingCollector? = null
    @Volatile private var trackingEnabled = false
    private val trackingGeneration = AtomicLong()
    private val mutex = Mutex()
    private val refreshMutex = Mutex()
    private val ingestMutex = Mutex()
    private val job = SupervisorJob(scope.coroutineContext[Job])
    private val repoScope = CoroutineScope(scope.coroutineContext + job + ioDispatcher)
    private val sharing = MatchIdSharing(api, storage, repoScope)
    private val generation = AtomicLong()
    @Volatile private var closed = false
    private var watcherJob: Job? = null
    private var assetsFetchedAt = 0L
    // StateFlow conflates maps whose entries match even when their access order changes.
    private var retainedDetails: Map<Long, MatchDetail> = emptyMap()
    private val attempts = LinkedHashMap<String, Attempt>()
    private val summaryMisses = ConcurrentHashMap<Long, Long>()
    private val summariesInFlight: MutableSet<Long> = ConcurrentHashMap.newKeySet()
    private val _state = MutableStateFlow(DeadlockStatsState())
    val state: StateFlow<DeadlockStatsState> = _state.asStateFlow()

    suspend fun selectAccount(accountId: SteamAccountId) = withContext(ioDispatcher) {
        check(!closed)
        trackingJob?.cancelAndJoin()
        trackingGeneration.incrementAndGet()
        trackingJob = null
        trackingCollector = null
        mutex.withLock {
            check(!closed)
            generation.incrementAndGet()
            summaryMisses.clear()
            summariesInFlight.clear()
            _state.update { current ->
                DeadlockStatsState(accountId = accountId.value).copy(
                    watcherEnabled = current.watcherEnabled,
                    itemAssets = current.itemAssets, rankAssets = current.rankAssets,
                )
            }
            retainedDetails = emptyMap()
            val saved = store.load(accountId)
            if (!closed && saved != null) _state.update { current ->
                saved.toState().copy(
                    watcherEnabled = current.watcherEnabled,
                    itemAssets = current.itemAssets, rankAssets = current.rankAssets,
                )
            }
        }
        if (!closed) loadAssetsIfNeeded()
        if (!closed && trackingEnabled) configureSteamTracking(true)
    }

    internal suspend fun clearSaved(accountId: SteamAccountId) = withContext(ioDispatcher) {
        check(closed) { "Close the repository before clearing its saved data" }
        job.join()
        mutex.withLock {
            archive.clear(accountId.value)
            SteamTrackingCollector.clear(storage, accountId.value)
            store.save(DeadlockStatsSnapshot(accountId = accountId.value))
        }
    }

    @Synchronized fun configureSteamTracking(enabled: Boolean) {
        check(!closed)
        trackingEnabled = enabled
        if (!enabled) {
            trackingGeneration.incrementAndGet()
            trackingJob?.cancel(); trackingJob = null; trackingCollector = null
            _state.update { it.copy(steamTracking = SteamTrackingStatus()) }
            return
        }
        if (trackingJob?.isActive == true) return
        val factory = steamTrackingFactory ?: return
        val account = _state.value.accountId ?: return
        val ticket = generation.get()
        val trackingTicket = trackingGeneration.incrementAndGet()
        fun trackingCurrent() = current(ticket, account) && trackingEnabled && trackingGeneration.get() == trackingTicket
        val port = factory()
        val collector = SteamTrackingCollector(account, port, storage, clock,
            ingestHistory = { entries -> mutex.withLock {
                if (trackingCurrent()) {
                    _state.update { it.copy(matchHistory = mergeHistory(it.matchHistory, entries), fetchedAt = it.fetchedAt + ("history" to now())) }
                    check(persist()) { _state.value.lastPersistenceError.orEmpty() }
                }
            } },
            hasMetadata = { id -> trackingCurrent() && hasCompleteDetail(id, ticket, account) },
            ingestMetadata = { ref ->
                sharing.offer(ref, now())
                val detail = MatchMetadataDecoder.decode(downloader.download(ref))
                currentCoroutineContext().ensureActive()
                check(trackingCurrent()) { "Steam tracking session changed" }
                require(detail.matchId == ref.matchId && detail.players.any { it.accountId == account })
                check(mergeAndPersist(detail.copy(fetchedAt = now()), ticket, account))
            },
            publish = { status -> if (trackingCurrent()) _state.update { it.copy(steamTracking = status) } },
        )
        trackingCollector = collector
        trackingJob = repoScope.launch {
            try { trackingLifecycle.withLock { collector.run() } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (trackingCurrent()) _state.update { it.copy(steamTracking = SteamTrackingStatus(SteamTrackingPhase.Failed, e.message)) } }
            finally { port.close() }
        }
    }

    fun backfillSteamHistory() { trackingCollector?.backfill() }
    fun refreshSteamHistory() { trackingCollector?.refresh() }

    @Synchronized fun configure(watcherEnabled: Boolean) {
        check(!closed)
        val wasWatching = _state.value.watcherEnabled
        _state.update { it.copy(watcherEnabled = watcherEnabled) }
        if (!watcherEnabled && wasWatching) stopWatcher()
        if (watcherEnabled && !wasWatching && watcher != null) {
            watcherJob = repoScope.launch {
                try { watcher.start { header, writtenAt -> if (_state.value.watcherEnabled) onCacheCandidate(header, writtenAt) } }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { }
            }
        }
    }

    /**
     * Shares the replay keys of matches found from [since], in epoch seconds, with deadlock-api; null stops at once.
     * Matches found earlier are never sent.
     */
    fun configureSharing(since: Long?) {
        check(!closed)
        sharing.configure(since)
    }

    /**
     * Core dashboard only, asked at once; true when the match history arrived for the account still selected. Details,
     * reference assets, social stats and benchmarks load separately.
     */
    suspend fun refresh(): Boolean = withContext(ioDispatcher) {
        refreshMutex.withLock {
            check(!closed)
            val (ticket, accountId) = session() ?: return@withLock false
            val id = SteamAccountId.ofAccountId(accountId)!!
            val (profile, history, rank) = coroutineScope {
                val profile = async { fetch { api.steamProfiles(listOf(id)).firstOrNull { it.accountId == id.value } } }
                val history = async { fetch { api.matchHistory(id) } }
                val rank = async { fetch { api.rank(id) } }
                Triple(profile.await(), history.await(), rank.await())
            }
            mutex.withLock {
                if (!current(ticket, id.value)) return@withLock false
                val old = _state.value
                _state.update { current -> current.copy(
                    profile = profile.getOrElse { old.profile }, rank = rank.getOrElse { old.rank },
                    matchHistory = history.getOrNull()?.let { mergeHistory(old.matchHistory, it) } ?: old.matchHistory,
                    fetchedAt = if (history.isSuccess) old.fetchedAt + ("history" to now()) else old.fetchedAt,
                ) }
                persist()
                history.isSuccess
            }
        }
    }

    /**
     * Restores archived metadata or fetches it from the API; Steam collection proceeds independently. [prioritizeSteam]
     * moves this match to the front of Steam's own queue and clears its backoff, as an explicit open or an accepted
     * manual check should; an automatic background check leaves Steam's own retry schedule alone.
     */
    suspend fun fetchMatchDetail(matchId: Long, prioritizeSteam: Boolean = true): Result<Unit> = withContext(ioDispatcher) {
        check(!closed)
        val (ticket, accountId) = session() ?: return@withContext Result.failure(IllegalStateException("No account selected"))
        val archived = mutex.withLock { archive.load(accountId, matchId) }
        val result = if (archived?.isComplete == true) Result.success(archived) else {
            if (archived != null) mergeAndPersist(archived, ticket, accountId)
            if (prioritizeSteam) trackingCollector?.detail(matchId)
            fetch { api.matchMetadata(matchId).copy(fetchedAt = now()) }
        }
        val merged = result.getOrNull()?.let { mergeAndPersist(it, ticket, accountId) }
        when {
            result.isFailure -> Result.failure(result.exceptionOrNull()!!)
            merged == true -> Result.success(Unit)
            else -> Result.failure(IllegalStateException(_state.value.lastPersistenceError ?: "The selected account changed"))
        }
    }

    suspend fun searchPlayers(query: String): List<PlayerProfile> = withContext(ioDispatcher) {
        check(!closed)
        val text = query.trim()
        text.toLongOrNull()?.let { raw ->
            val id = SteamAccountId.ofAccountId(raw) ?: SteamAccountId.ofSteamId64(raw) ?: return@withContext emptyList()
            api.steamProfiles(listOf(id))
        } ?: api.searchPlayersByName(text)
    }

    /** Batch enrichment for a loaded scoreboard; never one request per player. */
    suspend fun profiles(accountIds: List<SteamAccountId>): List<PlayerProfile> = call { api.steamProfiles(accountIds) }

    suspend fun refreshSocial(): Unit = withContext(ioDispatcher) {
        check(!closed)
        val (ticket, accountId) = session() ?: return@withContext
        val id = SteamAccountId.ofAccountId(accountId)!!
        val (mates, enemies) = coroutineScope {
            val mates = async { fetch { api.mateStats(id) } }
            val enemies = async { fetch { api.enemyStats(id) } }
            mates.await() to enemies.await()
        }
        mutex.withLock {
            if (!current(ticket, id.value)) return@withLock
            _state.update { it.copy(mateStats = mates.getOrElse { _ -> it.mateStats }, enemyStats = enemies.getOrElse { _ -> it.enemyStats }) }
            persist()
        }
    }

    suspend fun partyTeammates(window: DateWindow = DateWindow()): List<PlayedWithStats> = call {
        _state.value.accountId?.let(SteamAccountId::ofAccountId)?.let { api.mateStats(it, window, sameParty = true) }.orEmpty()
    }

    suspend fun heroBenchmarks(window: DateWindow, mode: MatchModeFilter, minLobbyBadge: Int, maxLobbyBadge: Int): List<HeroBenchmark> =
        call { api.heroBenchmarks(mode, window, minLobbyBadge, maxLobbyBadge) }

    /**
     * Recovers archived final numbers, then fetches up to [MaxSummaryRequest] missing summaries in one request.
     * Matches the API did not return are not asked for again within an hour.
     * Returns how many summaries arrived; a failure keeps what was already known.
     */
    suspend fun refreshSummaries(matchIds: Collection<Long>): Int = withContext(ioDispatcher) {
        check(!closed)
        val (ticket, accountId) = session() ?: return@withContext 0
        val id = SteamAccountId.ofAccountId(accountId)!!
        val recovered = mutex.withLock {
            if (!current(ticket, accountId)) return@withContext 0
            val archived = matchIds.distinct().filterNot { it in _state.value.matchSummaries }
                .mapNotNull { archive.load(accountId, it)?.summaryFor(accountId) }.associateBy { it.matchId }
            if (archived.isNotEmpty()) {
                _state.update { it.copy(matchSummaries = it.matchSummaries + archived) }
                persist()
            }
            archived.size
        }
        val known = _state.value
        val wanted = matchIds.asSequence().distinct().filter { match ->
            match !in known.matchSummaries && (summaryMisses[match]?.let { now() - it >= 3600 } ?: true) &&
                known.matchDetails[match]?.players?.firstOrNull { it.accountId == accountId }?.statSnapshots.isNullOrEmpty() &&
                summariesInFlight.add(match)
        }.take(MaxSummaryRequest).toList()
        if (wanted.isEmpty()) return@withContext recovered
        try {
            val summaries = api.matchSummaries(id, wanted)
            mutex.withLock {
                if (!current(ticket, accountId)) return@withLock 0
                val received = summaries.associateBy { it.matchId }
                wanted.filter { it !in received }.forEach { summaryMisses[it] = now() }
                if (summaryMisses.size > 4096) summaryMisses.entries.sortedBy { it.value }.take(summaryMisses.size - 4096).forEach { summaryMisses.remove(it.key) }
                _state.update { it.copy(matchSummaries = received + it.matchSummaries) }
                if (received.isNotEmpty()) persist()
                recovered + received.size
            }
        } finally {
            summariesInFlight.removeAll(wanted.toSet())
        }
    }

    suspend fun playerMetrics(
        window: DateWindow, mode: MatchModeFilter, minLobbyBadge: Int? = null, maxLobbyBadge: Int? = null, heroId: Int? = null, account: SteamAccountId? = null,
    ): Map<String, MetricDistribution> = call { api.playerMetrics(mode, window, minLobbyBadge, maxLobbyBadge, heroId, account) }

    suspend fun heroCounters(account: SteamAccountId, window: DateWindow, mode: MatchModeFilter): List<HeroCounter> =
        call { api.heroCounters(account, mode, window) }

    suspend fun itemUsage(account: SteamAccountId, heroId: Int, window: DateWindow, mode: MatchModeFilter): List<ItemUsageStat> =
        call { api.itemStats(account, heroId, mode, window) }

    suspend fun heroCountsFor(accounts: List<SteamAccountId>, window: DateWindow, mode: MatchModeFilter): List<AccountHeroCount> =
        call { api.heroCountsFor(accounts, mode, window) }

    suspend fun rankedSeasons(): List<RankedSeason> = call { api.rankedSeasons() }

    suspend fun rankDistribution(window: DateWindow): List<RankDistributionEntry> = call { api.rankDistribution(window) }

    private suspend fun loadAssetsIfNeeded() {
        if (now() - assetsFetchedAt < 86_400) return
        val saved = store.loadAssets()
        if (saved != null) {
            mutex.withLock { _state.update { it.copy(itemAssets = saved.items, rankAssets = saved.ranks) } }
            assetsFetchedAt = saved.fetchedAt
        }
    }

    /** Items and ranks, unless both arrived within the last day and not [evenIfFresh]. */
    suspend fun refreshAssets(evenIfFresh: Boolean = false) = withContext(ioDispatcher) {
        check(!closed)
        val fresh = now() - assetsFetchedAt < 86_400 && _state.value.itemAssets.isNotEmpty() && _state.value.rankAssets.isNotEmpty()
        if (fresh && !evenIfFresh) return@withContext
        val (items, ranks) = coroutineScope {
            val items = async { fetch { api.items() } }
            val ranks = async { fetch { api.ranks() } }
            items.await() to ranks.await()
        }
        if (closed || items.isFailure && ranks.isFailure) return@withContext
        mutex.withLock {
            _state.update { it.copy(itemAssets = items.getOrElse { _ -> it.itemAssets }, rankAssets = ranks.getOrElse { _ -> it.rankAssets }) }
            if (items.isSuccess && ranks.isSuccess) assetsFetchedAt = now()
            try { store.saveAssets(DeadlockAssetsSnapshot(items = _state.value.itemAssets, ranks = _state.value.rankAssets, fetchedAt = assetsFetchedAt)) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { _state.update { it.copy(lastPersistenceError = e.message) } }
        }
    }

    internal suspend fun onCacheCandidate(bytes: ByteArray, writtenAt: Long = now()): Unit = withContext(ioDispatcher) {
        ingestMutex.withLock {
            val (ticket, accountId) = session() ?: return@withLock
            val ref = ReplayCacheUrl.findMetadataUrl(bytes) ?: return@withLock
            sharing.offer(ref, writtenAt)
            val key = "$accountId:${ref.url}"
            val old = attempts[key]
            if (old?.done != true && (old == null || now() >= old.retryAt)) {
                val result = fetch {
                    val decoded = MatchMetadataDecoder.decode(downloader.download(ref))
                    require(decoded.matchId == ref.matchId) { "Cache URL and metadata match IDs differ" }
                    decoded.copy(fetchedAt = now())
                }
                currentCoroutineContext().ensureActive()
                if (!current(ticket, accountId)) return@withLock
                val detail = result.getOrNull()
                val count = ((old?.count ?: 0) + 1).coerceAtMost(5)
                val requestedDelay = (result.exceptionOrNull() as? ReplayDownloadException)?.retryAfterMillis?.let { (it / 1000) + 1 } ?: 0
                val retryDelay = maxOf(requestedDelay, if (count == 5) 3600L else 30L shl (count - 1))
                if (detail != null && !mergeAndPersist(detail, ticket, accountId)) return@withLock
                attempts[key] = Attempt(count, now() + retryDelay, detail?.isComplete == true)
                trim(attempts, 4096)
                if (detail?.isComplete != true && count < 5 && _state.value.watcherEnabled) repoScope.launch {
                    delay(retryDelay * 1000)
                    if (current(ticket, accountId) && _state.value.watcherEnabled) onCacheCandidate(ref.url.toByteArray(), writtenAt)
                }
            }
        }
    }

    private suspend fun hasCompleteDetail(id: Long, ticket: Long, account: Long): Boolean {
        val archived = mutex.withLock {
            if (!current(ticket, account)) return false
            if (_state.value.matchDetails[id]?.isComplete == true) return true
            archive.load(account, id)
        }
        return archived?.isComplete == true && mergeAndPersist(archived, ticket, account)
    }

    private suspend fun mergeAndPersist(detail: MatchDetail, ticket: Long, id: Long): Boolean = mutex.withLock {
        if (!current(ticket, id)) return@withLock false
        val old = _state.value
        val merged = try {
            val previous = old.matchDetails[detail.matchId] ?: archive.load(id, detail.matchId)
            (if (previous == detail) detail else mergeMatchDetail(previous, detail)).also { if (it != previous) archive.save(id, it) }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { _state.update { it.copy(lastPersistenceError = e.message ?: "Stats could not be saved") }; return@withLock false }
        val history = merged.historyFor(id)?.let { mergeHistory(old.matchHistory, listOf(it)) } ?: old.matchHistory
        val details = (mapOf(detail.matchId to merged) + (retainedDetails - detail.matchId)).values.take(200).associateBy { it.matchId }
        val summaries = merged.summaryFor(id)?.let { old.matchSummaries + (it.matchId to it) } ?: old.matchSummaries
        if (!persist(old.copy(matchHistory = history, matchSummaries = summaries))) return@withLock false
        retainedDetails = details
        _state.update { it.copy(matchHistory = history, matchDetails = details, matchSummaries = summaries) }
        true
    }

    private suspend fun persist(state: DeadlockStatsState = _state.value): Boolean {
        val id = state.accountId ?: return false
        return try { store.save(state.toSnapshot(id)); _state.update { it.copy(lastPersistenceError = null) }; true }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { _state.update { it.copy(lastPersistenceError = e.message ?: "Stats could not be saved") }; false }
    }

    private suspend fun session(): Pair<Long, Long>? = mutex.withLock {
        if (closed) null else _state.value.accountId?.let { generation.get() to it }
    }

    private fun current(ticket: Long, id: Long) = !closed && generation.get() == ticket && _state.value.accountId == id
    private fun now() = clock.now().epochSeconds
    private fun stopWatcher() { watcherJob?.cancel(); watcherJob = null; watcher?.close() }
    @Synchronized override fun close() {
        if (closed) return
        closed = true; generation.incrementAndGet(); stopWatcher(); job.cancel(); downloader.close(); api.close()
    }
    private suspend fun <T> fetch(block: suspend () -> T): Result<T> = try { Result.success(block()) }
    catch (e: CancellationException) { throw e }
    catch (e: Exception) { Result.failure(e) }
    private suspend fun <T> call(block: suspend () -> T): T = withContext(ioDispatcher) { check(!closed); block() }
    private data class Attempt(val count: Int, val retryAt: Long, val done: Boolean)

    companion object {
        const val MaxSummaryRequest = DeadlockStatsApi.MaxSummaryBatch
    }
}

private fun <K, V> trim(map: MutableMap<K, V>, maximum: Int) { while (map.size > maximum) map.remove(map.keys.first()) }
private fun DeadlockStatsSnapshot.toState() = DeadlockStatsState(accountId, profile, matchHistory, rank, mateStats, enemyStats,
    matchSummaries = matchSummaries.filter { it.accountId == accountId }.associateBy { it.matchId }, fetchedAt = fetchedAt)
private fun DeadlockStatsState.toSnapshot(id: Long) = DeadlockStatsSnapshot(accountId = id, profile = profile, matchHistory = matchHistory, rank = rank,
    mateStats = mateStats, enemyStats = enemyStats,
    matchSummaries = matchSummaries.values.toList(), fetchedAt = fetchedAt)
