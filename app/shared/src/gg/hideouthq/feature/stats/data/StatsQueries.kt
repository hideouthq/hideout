package gg.hideouthq.feature.stats.data

import gg.hideouthq.core.data.DataError
import gg.hideouthq.core.data.Loadable
import gg.hideouthq.core.data.deadlockstats.AccountHeroCount
import gg.hideouthq.core.data.deadlockstats.DateWindow
import gg.hideouthq.core.data.deadlockstats.DeadlockStatsRepository
import gg.hideouthq.core.data.deadlockstats.HeroBenchmark
import gg.hideouthq.core.data.deadlockstats.HeroCounter
import gg.hideouthq.core.data.deadlockstats.ItemUsageStat
import gg.hideouthq.core.data.deadlockstats.MatchModeFilter
import gg.hideouthq.core.data.deadlockstats.MetricDistribution
import gg.hideouthq.core.data.deadlockstats.PlayedWithStats
import gg.hideouthq.core.data.deadlockstats.PlayerProfile
import gg.hideouthq.core.data.deadlockstats.SteamAccountId
import gg.hideouthq.core.network.RemoteException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/** A request beyond the saved dashboard, run for one session's account at [now] and cached by its own equality. */
sealed interface StatsQuery<T> {
    suspend fun run(repository: DeadlockStatsRepository, account: SteamAccountId, now: Long, automatic: Boolean = false): T
}

/**
 * Lobby badges from the first to the last badge of [tier]. Only ranked lobbies have a badge, so averages "at your rank"
 * are always asked of ranked matches, whatever the mode filter.
 */
private fun badgeBand(tier: Int) = tier * 10 to tier * 10 + 9

/** Per-match means in ranked lobbies at [tier], optionally for one hero. */
data class LobbyMetricsQuery(val period: PeriodKey, val tier: Int, val heroId: Int? = null) : StatsQuery<Map<String, MetricDistribution>> {
    override suspend fun run(repository: DeadlockStatsRepository, account: SteamAccountId, now: Long, automatic: Boolean): Map<String, MetricDistribution> {
        val (min, max) = badgeBand(tier)
        return repository.playerMetrics(period.window(now), MatchModeFilter.Ranked, min, max, heroId)
    }
}

/** Every hero's results in ranked lobbies at [tier], for win rate next to that rank. */
data class HeroBenchmarksQuery(val period: PeriodKey, val tier: Int) : StatsQuery<List<HeroBenchmark>> {
    override suspend fun run(repository: DeadlockStatsRepository, account: SteamAccountId, now: Long, automatic: Boolean): List<HeroBenchmark> {
        val (min, max) = badgeBand(tier)
        return repository.heroBenchmarks(period.window(now), MatchModeFilter.Ranked, min, max)
    }
}

data class ItemUsageQuery(val period: PeriodKey, val mode: MatchModeFilter, val heroId: Int) : StatsQuery<List<ItemUsageStat>> {
    override suspend fun run(repository: DeadlockStatsRepository, account: SteamAccountId, now: Long, automatic: Boolean) = repository.itemUsage(account, heroId, period.window(now), mode)
}

data class CountersQuery(val period: PeriodKey, val mode: MatchModeFilter) : StatsQuery<List<HeroCounter>> {
    override suspend fun run(repository: DeadlockStatsRepository, account: SteamAccountId, now: Long, automatic: Boolean) = repository.heroCounters(account, period.window(now), mode)
}

/** Mates the API marks as having queued with the account, over the whole history; filters apply to their match ids. */
data object PartyQuery : StatsQuery<List<PlayedWithStats>> {
    override suspend fun run(repository: DeadlockStatsRepository, account: SteamAccountId, now: Long, automatic: Boolean) = repository.partyTeammates()
}

/** Names and avatars for up to 100 accounts in one request. */
data class ProfilesQuery(val accountIds: List<Long>) : StatsQuery<Map<Long, PlayerProfile>> {
    override suspend fun run(repository: DeadlockStatsRepository, account: SteamAccountId, now: Long, automatic: Boolean): Map<Long, PlayerProfile> =
        repository.profiles(accountIds.mapNotNull(SteamAccountId::ofAccountId).take(100)).associateBy { it.accountId }
}

/** Each account's most played hero over its whole recorded history, ties to the lower hero id. */
data class UsualHeroesQuery(val accountIds: List<Long>) : StatsQuery<Map<Long, Int>> {
    override suspend fun run(repository: DeadlockStatsRepository, account: SteamAccountId, now: Long, automatic: Boolean): Map<Long, Int> =
        repository.heroCountsFor(accountIds.mapNotNull(SteamAccountId::ofAccountId).take(100), DateWindow(), MatchModeFilter.All)
            .groupBy(AccountHeroCount::accountId)
            .mapValues { (_, rows) -> rows.sortedWith(compareByDescending<AccountHeroCount> { it.matchesPlayed }.thenBy { it.heroId }).first().heroId }
}

/** One match's full metadata, merged into the session's saved details. */
data class DetailQuery(val matchId: Long) : StatsQuery<Unit> {
    override suspend fun run(repository: DeadlockStatsRepository, account: SteamAccountId, now: Long, automatic: Boolean) = repository.fetchMatchDetail(matchId, prioritizeSteam = !automatic).getOrThrow()
}

internal fun Throwable.toDataError(): DataError = (this as? RemoteException)?.error ?: DataError.Unavailable

private val MaxAge = 30.minutes
private val FailedRetryAfter = 5.minutes

internal class QueryCache(
    private val scope: CoroutineScope,
    private val run: suspend (StatsQuery<*>, Long, Boolean) -> Any?,
    private val clock: Clock,
) {
    private class Entry(val flow: MutableStateFlow<Loadable<Any?>>) {
        var fetchedAt = Long.MIN_VALUE
        var job: Job? = null
        var stale = false
        var retryJob: Job? = null
        var retryPolicy: ((Long) -> Duration?)? = null
        var exhausted = false
    }

    private val entries = HashMap<StatsQuery<*>, Entry>()

    @Suppress("UNCHECKED_CAST")
    fun <T> get(query: StatsQuery<T>): StateFlow<Loadable<T>> = synchronized(entries) {
        val entry = entries.getOrPut(query) { Entry(MutableStateFlow(Loadable.Loading)) }
        val running = entry.job?.isCompleted == false
        val age = clock.now().epochSeconds - entry.fetchedAt
        val expired = when (entry.flow.value) {
            is Loadable.Ready -> age > MaxAge.inWholeSeconds
            is Loadable.Failed -> age >= FailedRetryAfter.inWholeSeconds
            Loadable.Loading -> false
        }
        if (!entry.exhausted && !running && (entry.job == null || entry.stale || expired)) launch(query, entry)
        entry.flow.asStateFlow() as StateFlow<Loadable<T>>
    }

    fun markStale() = synchronized(entries) { entries.values.forEach { it.stale = true } }

    fun retryFailed() = synchronized(entries) {
        entries.toList().forEach { (query, entry) ->
            if (entry.flow.value is Loadable.Failed && entry.job?.isCompleted != false) launch(query, entry)
        }
    }

    fun retryNow(query: StatsQuery<*>) = synchronized(entries) {
        val entry = entries.getOrPut(query) { Entry(MutableStateFlow(Loadable.Loading)) }
        if (entry.job?.isCompleted != false) launch(query, entry)
    }

    fun ensureRetry(query: StatsQuery<*>, nextDelay: (now: Long) -> Duration?) = synchronized(entries) {
        val entry = entries.getOrPut(query) { Entry(MutableStateFlow(Loadable.Loading)) }
        if (entry.retryPolicy == null) entry.retryPolicy = nextDelay
        scheduleRetry(query, entry)
    }

    private fun scheduleRetry(query: StatsQuery<*>, entry: Entry) {
        if (entry.exhausted || entry.job?.isCompleted == false || entry.retryJob?.isCompleted == false) return
        val nextDelay = entry.retryPolicy ?: return
        val pause = nextDelay(clock.now().epochSeconds) ?: run { entry.exhausted = true; return }
        entry.retryJob = scope.launch {
            delay(pause)
            synchronized(entries) {
                if (entry.retryJob !== coroutineContext.job) return@launch
                entry.retryJob = null
                if (nextDelay(clock.now().epochSeconds) == null) entry.exhausted = true
                else if (entry.job?.isCompleted != false) launch(query, entry, automatic = true)
            }
        }
    }

    private fun launch(query: StatsQuery<*>, entry: Entry, automatic: Boolean = false) {
        entry.stale = false
        entry.fetchedAt = clock.now().epochSeconds
        val job = scope.launch {
            val result = try {
                Loadable.Ready(run(query, clock.now().epochSeconds, automatic))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Loadable.Failed(e.toDataError())
            }
            synchronized(entries) {
                entry.flow.update { previous -> if (result is Loadable.Failed && previous is Loadable.Ready) previous else result }
            }
        }
        entry.job = job
        val timer = entry.retryJob
        entry.retryJob = null
        timer?.cancel()
        job.invokeOnCompletion { cause ->
            synchronized(entries) { if (cause == null && entry.job === job) scheduleRetry(query, entry) }
        }
        if (entry.flow.value !is Loadable.Ready) entry.flow.value = Loadable.Loading
    }
}

internal val <T> Loadable<T>.valueOrNull: T? get() = (this as? Loadable.Ready)?.value

internal inline fun <T, R> Loadable<T>.map(transform: (T) -> R): Loadable<R> = when (this) {
    Loadable.Loading -> Loadable.Loading
    is Loadable.Failed -> this
    is Loadable.Ready -> Loadable.Ready(transform(value))
}
