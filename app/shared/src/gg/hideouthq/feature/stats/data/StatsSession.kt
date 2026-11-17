package gg.hideouthq.feature.stats.data

import gg.hideouthq.core.data.Loadable
import gg.hideouthq.core.data.deadlockstats.DeadlockStatsRepository
import gg.hideouthq.core.data.deadlockstats.DeadlockStatsState
import gg.hideouthq.core.data.deadlockstats.SteamAccountId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/** Whether this session is reading, refreshing or enriching, and whether its last refresh failed. */
data class SessionSync(
    /** The saved copy has been read, so an empty history is really empty. */
    val opened: Boolean = false,
    val refreshing: Boolean = false,
    /** The last refresh could not fetch the history or profile; the saved copy is what shows. */
    val failed: Boolean = false,
    /** Final numbers for some listed matches are on their way. */
    val enriching: Boolean = false,
)

/**
 * One account's stats: its repository, refreshes and cached extra requests. A tracked session is the user's own account,
 * saved between runs and fed by the Steam cache watcher; a visiting one is someone found through search, kept in memory.
 * Closing it stops all of its work and its watcher.
 */
class StatsSession internal constructor(
    val accountId: Long,
    val tracked: Boolean,
    internal val repository: DeadlockStatsRepository,
    parent: CoroutineScope,
    private val clock: Clock,
) : AutoCloseable {
    private val scope = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))
    private val account = requireNotNull(SteamAccountId.ofAccountId(accountId))
    private val _sync = MutableStateFlow(SessionSync())
    private val cache = QueryCache(scope, { query, now, automatic -> query.run(repository, account, now, automatic) }, clock)
    private val askedSummaries = LinkedHashSet<Long>()
    private var failedSummaries: List<Long> = emptyList()
    private var summariesFailedAt: Long? = null
    private var refreshJob: Job? = null
    private val enrichments = AtomicInteger()
    private val unknownItemsAsked = AtomicBoolean(false)
    @Volatile private var closed = false

    val data: StateFlow<DeadlockStatsState> get() = repository.state
    val sync: StateFlow<SessionSync> = _sync.asStateFlow()

    internal suspend fun open() {
        repository.selectAccount(account)
        repository.configure(watcherEnabled = tracked)
        _sync.update { it.copy(opened = true) }
    }

    /**
     * Refetches the dashboard, mates and enemies, and reference data once a day, all at once. Unless [force], skips a
     * refresh when the match history arrived recently; a failed history is asked again. Cached extra requests are asked
     * again when the history changed; a refresh you ask for ([force]) also asks again for those that failed.
     */
    fun refresh(force: Boolean = true, unlessNewerThan: Duration = Duration.ZERO) {
        if (closed) return
        if (force && tracked) repository.refreshSteamHistory()
        if (force) retryFailed()
        if (refreshJob?.isActive == true) return
        val historyAt = data.value.fetchedAt["history"]
        if (!force && historyAt != null && clock.now().epochSeconds - historyAt < unlessNewerThan.inWholeSeconds) return
        refreshJob = scope.launch {
            _sync.update { it.copy(refreshing = true) }
            var arrived = false
            val history = data.value.matchHistory
            try {
                coroutineScope {
                    launch { arrived = repository.refresh() }
                    launch { repository.refreshSocial() }
                    repository.refreshAssets()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                arrived = false
            } finally {
                synchronized(askedSummaries) {
                    askedSummaries.clear()
                    summariesFailedAt = null
                }
                if (data.value.matchHistory != history) cache.markStale()
                _sync.update { it.copy(refreshing = false, failed = !arrived) }
            }
        }
    }

    /** A cached extra request for this account. */
    fun <T> query(query: StatsQuery<T>): StateFlow<Loadable<T>> = cache.get(query)

    fun watchMatchDetail(matchId: Long, endTimeS: Long?) {
        if (closed) return
        cache.ensureRetry(DetailQuery(matchId)) { now -> matchRetryDelay(now, endTimeS, data.value.matchDetails[matchId]?.isComplete == true) }
    }

    fun checkMatch(matchId: Long) = cache.retryNow(DetailQuery(matchId))

    /** Fetches again now whatever failed: a match, rank averages, builds, parties, names, the last batch of final numbers. */
    fun retryFailed() {
        cache.retryFailed()
        val again = synchronized(askedSummaries) {
            summariesFailedAt = null
            failedSummaries.also { failedSummaries = emptyList() }
        }
        if (again.isNotEmpty()) enrich(again)
    }

    /**
     * Asks once per refresh for final numbers of [matchIds] still without them, at most a bounded batch at a time. After
     * a batch fails nothing is asked for [SummaryRetryAfter], unless [retryFailed] asks again sooner.
     */
    fun enrich(matchIds: List<Long>) {
        if (closed) return
        val known = data.value
        val wanted = synchronized(askedSummaries) {
            val failedAt = summariesFailedAt
            if (failedAt != null && clock.now().epochSeconds - failedAt < SummaryRetryAfter.inWholeSeconds) return
            matchIds.filter { it !in known.matchSummaries && it !in askedSummaries && known.matchDetails[it]?.players?.none { p -> p.accountId == accountId } != false }
                .take(DeadlockStatsRepository.MaxSummaryRequest)
                .also { askedSummaries += it }
        }
        if (wanted.isEmpty()) return
        enrichments.incrementAndGet()
        _sync.update { it.copy(enriching = true) }
        scope.launch {
            try {
                repository.refreshSummaries(wanted)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                synchronized(askedSummaries) {
                    askedSummaries -= wanted.toSet()
                    failedSummaries = wanted
                    summariesFailedAt = clock.now().epochSeconds
                }
            } finally {
                val left = enrichments.decrementAndGet()
                _sync.update { it.copy(enriching = left > 0) }
            }
        }
    }

    /** A match's build names an item the saved list lacks, as after a patch: the list is asked for again, once a session. */
    fun resolveUnknownItems() {
        if (closed || !unknownItemsAsked.compareAndSet(false, true)) return
        scope.launch {
            try {
                repository.refreshAssets(evenIfFresh = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        scope.cancel()
        repository.close()
    }

    private companion object {
        val SummaryRetryAfter = 2.minutes
    }
}
