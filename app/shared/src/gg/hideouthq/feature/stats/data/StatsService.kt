package gg.hideouthq.feature.stats.data

import gg.hideouthq.core.data.Loadable
import gg.hideouthq.core.data.Preferences
import gg.hideouthq.core.data.StatsPreferences
import gg.hideouthq.core.data.deadlockstats.DateWindow
import gg.hideouthq.core.data.deadlockstats.DeadlockStatsRepository
import gg.hideouthq.core.data.deadlockstats.LocalSteamIdentity
import gg.hideouthq.core.data.deadlockstats.PlayerProfile
import gg.hideouthq.core.data.deadlockstats.RankDistributionEntry
import gg.hideouthq.core.data.deadlockstats.RankedSeason
import gg.hideouthq.core.data.deadlockstats.SteamTrackingInstallation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/** What Stats needs from the platform: repositories over its HTTP client and storage, and Steam's local identities. */
interface StatsPlatform {
    val steamTrackingInstaller: gg.hideouthq.core.data.deadlockstats.SteamTrackingInstaller? get() = null
    /** The user's own account: saved between runs, with the Steam cache watcher available. */
    fun openTracked(scope: CoroutineScope): DeadlockStatsRepository

    /** Someone else's account opened from search: in memory, never watching. */
    fun openVisitor(scope: CoroutineScope): DeadlockStatsRepository

    /** Identities in this computer's Steam login file, read from disk. */
    fun localIdentities(): List<LocalSteamIdentity>
}

data class StatsServiceState(
    /** Null until the saved choices have been read. */
    val preferences: StatsPreferences? = null,
    val tracked: StatsSession? = null,
    /** Null while Steam's login file is read, which happens each time account setup is entered. */
    val identities: List<LocalSteamIdentity>? = null,
    /** Ranked seasons' dates; a failed refetch keeps the last ones that arrived. */
    val seasons: Loadable<List<RankedSeason>> = Loadable.Loading,
    val distribution: Loadable<List<RankDistributionEntry>> = Loadable.Loading,
    val steamInstallation: SteamTrackingInstallation = SteamTrackingInstallation(),
)

/**
 * Stats for the life of the app. It follows the saved choices: off closes every session, which stops refreshes and the
 * Steam cache watcher; on with an account keeps that account's session open, refreshed on arrival and every
 * [RefreshInterval]. Visiting another account opens a separate in-memory session for as long as a screen shows it.
 * Only your session shares match IDs with deadlock-api, and only while the player has that turned on.
 */
class StatsService(
    private val platform: StatsPlatform,
    private val preferences: Preferences,
    parentScope: CoroutineScope,
    private val clock: Clock = Clock.System,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AutoCloseable {
    private class Visit(val session: StatsSession) {
        var users = 0
    }

    private class ReferenceFetch {
        var job: Job? = null
        var askedAt: Long? = null
        var arrivedAt: Long? = null
    }

    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))
    private val lock = Mutex()
    private val _state = MutableStateFlow(StatsServiceState())
    private val visits = LinkedHashMap<Long, Visit>(4, 0.75f, true)
    private val seasonsFetch = ReferenceFetch()
    private val distributionFetch = ReferenceFetch()
    private var periodic: Job? = null
    private var inSetup = false
    private var lookup: DeadlockStatsRepository? = null

    val state: StateFlow<StatsServiceState> = _state.asStateFlow()

    init {
        scope.launch { if (preferences.statsPreferences().steamTrackingEnabled) inspectSteamTracking() }
        scope.launch { preferences.gameFolderChanges.collect { if (preferences.stats.value?.steamTrackingEnabled == true) inspectSteamTracking() } }
        scope.launch {
            preferences.statsPreferences()
            preferences.stats.filterNotNull().collect { apply(it) }
        }
    }

    fun setEnabled(enabled: Boolean) {
        scope.launch { preferences.setStatsEnabled(enabled) }
    }

    fun inspectSteamTracking() {
        scope.launch {
            val result = try { platform.steamTrackingInstaller?.inspect() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { SteamTrackingInstallation(reason = e.message) }
            if (result != null) {
                val previous = _state.value.steamInstallation
                if (previous.installed && (!result.installed || previous.selectedId != result.selectedId))
                    preferences.setSteamTrackingEnabled(false)
                _state.update { it.copy(steamInstallation = result) }
            }
        }
    }

    /**
     * Installs Steam tracking and turns it on once that worked. Returns the installation as it then stands, with the
     * failure's reason when it did not work. It runs in the service's scope, so a caller that stops waiting does not
     * stop it halfway.
     */
    suspend fun installSteamTracking(): SteamTrackingInstallation = scope.async {
        val installer = platform.steamTrackingInstaller ?: return@async _state.value.steamInstallation
        try {
            val result = installer.install()
            _state.update { it.copy(steamInstallation = result) }
            if (result.supported && result.installed) preferences.setSteamTrackingEnabled(true)
            result
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { _state.updateAndGet { it.copy(steamInstallation = it.steamInstallation.copy(reason = e.message)) }.steamInstallation }
    }.await()

    fun selectSteamInstallation(id: String) {
        scope.launch {
            preferences.setSteamTrackingEnabled(false)
            try {
                platform.steamTrackingInstaller?.select(id)?.let { result -> _state.update { it.copy(steamInstallation = result) } }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { _state.update { it.copy(steamInstallation = it.steamInstallation.copy(reason = e.message)) } }
        }
    }

    fun disableSteamTracking() {
        scope.launch {
            preferences.setSteamTrackingEnabled(false)
            try {
                platform.steamTrackingInstaller?.uninstall()?.let { result -> _state.update { it.copy(steamInstallation = result) } }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { _state.update { it.copy(steamInstallation = it.steamInstallation.copy(reason = e.message)) } }
        }
    }

    fun backfillSteamHistory() { _state.value.tracked?.repository?.backfillSteamHistory() }

    /** Shows [accountId]'s stats as your own from now on, and remembers the choice. */
    fun useAccount(accountId: Long) {
        scope.launch { preferences.setStatsAccount(accountId) }
    }

    fun saveFilters(mode: String, period: String) {
        scope.launch { preferences.setStatsFilters(mode, period) }
    }

    /**
     * [content] of [accountId]'s session for as long as the returned flow is collected: the tracked session when it is
     * yours, else a visiting one, opened on first use. A session a screen shows is never closed under it; once no screen
     * shows it, it stays open for going back until more than [MaxIdleVisits] others are idle. Nothing while Stats is off.
     */
    fun <T> visiting(accountId: Long, content: (StatsSession) -> Flow<T>): Flow<T> = flow {
        val session = acquire(accountId) ?: return@flow
        try {
            emitAll(content(session))
        } finally {
            if (!session.tracked) release(accountId)
        }
    }

    /** A visiting session open for [accountId], if any. */
    fun visitedSession(accountId: Long): StatsSession? = synchronized(visits) { visits[accountId]?.session }

    /** Asks again for reference data that failed or is a day old, unless it is already on its way. */
    fun retryReference() {
        val repository = _state.value.tracked?.repository ?: synchronized(visits) { visits.values.firstOrNull()?.session?.repository } ?: return
        loadReference(repository, retryFailed = true)
    }

    /** One search for players by name, account id or SteamID64. Works before an account is chosen. */
    suspend fun searchPlayers(query: String): List<PlayerProfile> {
        val repository = lock.withLock {
            check(_state.value.preferences?.enabled == true) { "Stats is off" }
            _state.value.tracked?.repository ?: lookup ?: platform.openVisitor(scope).also { lookup = it }
        }
        return repository.searchPlayers(query)
    }

    private suspend fun acquire(accountId: Long): StatsSession? = lock.withLock {
        val current = _state.value
        if (current.preferences?.enabled != true) return@withLock null
        current.tracked?.takeIf { it.accountId == accountId }?.let { return@withLock it }
        synchronized(visits) { visits[accountId]?.also { it.users++ } }?.let { return@withLock it.session }
        val session = StatsSession(accountId, tracked = false, platform.openVisitor(scope), scope, clock)
        session.open()
        synchronized(visits) { visits[accountId] = Visit(session).also { it.users = 1 } }
        session.refresh()
        loadReference(session.repository)
        session
    }

    private fun release(accountId: Long) = synchronized(visits) {
        val visit = visits[accountId] ?: return@synchronized
        visit.users--
        val idle = visits.entries.filter { it.value.users <= 0 }
        idle.take((idle.size - MaxIdleVisits).coerceAtLeast(0)).forEach { (id, oldest) ->
            visits.remove(id)
            oldest.session.close()
        }
    }

    suspend fun clearSaved() {
        val prefs = preferences.statsPreferences()
        apply(prefs, clearSaved = true)
    }

    private suspend fun apply(prefs: StatsPreferences, clearSaved: Boolean = false) = lock.withLock {
        _state.update { it.copy(preferences = prefs) }
        if (clearSaved) {
            val account = prefs.accountId?.let(gg.hideouthq.core.data.deadlockstats.SteamAccountId::ofAccountId)
            val repository = _state.value.tracked?.repository ?: platform.openTracked(scope)
            _state.value.tracked?.close()
            repository.close()
            periodic?.cancel()
            periodic = null
            _state.update { it.copy(tracked = null) }
            if (account != null) repository.clearSaved(account)
        }
        val wanted = prefs.accountId.takeIf { prefs.enabled }
        _state.value.tracked?.takeIf { it.accountId != wanted }?.let { stale ->
            stale.close()
            _state.update { it.copy(tracked = null) }
        }
        val setup = prefs.enabled && prefs.accountId == null
        if (setup && !inSetup) readIdentities()
        inSetup = setup
        if (!prefs.enabled) {
            closeVisits()
            lookup?.close()
            lookup = null
            periodic?.cancel()
            periodic = null
            return@withLock
        }
        if (wanted == null) {
            periodic?.cancel()
            periodic = null
            return@withLock
        }
        _state.value.tracked?.repository?.let { repository ->
            repository.configureSteamTracking(prefs.steamTrackingEnabled)
            repository.configureSharing(prefs.shareMatchIdsSince)
            return@withLock
        }
        openTracked(prefs, wanted, refresh = !clearSaved)
    }

    private suspend fun openTracked(prefs: StatsPreferences, accountId: Long, refresh: Boolean) {
        val repository = platform.openTracked(scope).also { it.configureSharing(prefs.shareMatchIdsSince) }
        val session = StatsSession(accountId, tracked = true, repository, scope, clock).also { it.open() }
        synchronized(visits) { visits.remove(accountId) }?.session?.close()
        _state.update { it.copy(tracked = session) }
        session.repository.configureSteamTracking(prefs.steamTrackingEnabled)
        if (refresh) session.refresh(force = false, unlessNewerThan = 5.minutes)
        loadReference(session.repository)
        periodic?.cancel()
        periodic = scope.launch {
            while (isActive) {
                delay(RefreshInterval)
                session.refresh(force = false, unlessNewerThan = RefreshInterval / 2)
                loadReference(session.repository)
            }
        }
    }

    private fun readIdentities() {
        _state.update { it.copy(identities = null) }
        scope.launch {
            val identities = withContext(ioDispatcher) { runCatching { platform.localIdentities() }.getOrDefault(emptyList()) }
            _state.update { it.copy(identities = identities) }
        }
    }

    private fun loadReference(repository: DeadlockStatsRepository, retryFailed: Boolean = false) {
        fetchReference(seasonsFetch, retryFailed, repository::rankedSeasons) { value -> _state.update { it.copy(seasons = value(it.seasons)) } }
        fetchReference(distributionFetch, retryFailed, { repository.rankDistribution(DateWindow()) }) { value -> _state.update { it.copy(distribution = value(it.distribution)) } }
    }

    private fun <T> fetchReference(
        fetch: ReferenceFetch,
        retryFailed: Boolean,
        load: suspend () -> T,
        publish: ((Loadable<T>) -> Loadable<T>) -> Unit,
    ) = synchronized(fetch) {
        if (fetch.job?.isActive == true) return@synchronized
        val now = clock.now().epochSeconds
        val arrived = fetch.arrivedAt
        val asked = fetch.askedAt
        val due = when {
            asked == null -> true
            arrived != null && arrived >= asked -> now - arrived >= ReferenceMaxAge.inWholeSeconds
            else -> retryFailed || now - asked >= FailedRetryAfter.inWholeSeconds
        }
        if (!due) return@synchronized
        fetch.askedAt = now
        if (retryFailed) publish { previous -> if (previous is Loadable.Failed) Loadable.Loading else previous }
        fetch.job = scope.launch {
            val result = try {
                Loadable.Ready(load())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Loadable.Failed(e.toDataError())
            }
            if (result is Loadable.Ready) synchronized(fetch) { fetch.arrivedAt = clock.now().epochSeconds }
            publish { previous -> if (result is Loadable.Failed && previous is Loadable.Ready) previous else result }
        }
    }

    private fun closeVisits() = synchronized(visits) {
        visits.values.forEach { it.session.close() }
        visits.clear()
    }

    override fun close() {
        scope.cancel()
        _state.value.tracked?.close()
        closeVisits()
        lookup?.close()
    }

    private companion object {
        const val MaxIdleVisits = 2
        val RefreshInterval = 20.minutes
        val ReferenceMaxAge = 24.hours
        val FailedRetryAfter = 15.minutes
    }
}
