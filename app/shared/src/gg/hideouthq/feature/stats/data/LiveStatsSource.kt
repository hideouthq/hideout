package gg.hideouthq.feature.stats.data

import gg.hideouthq.core.data.CatalogRepository
import gg.hideouthq.core.data.DataError
import gg.hideouthq.core.data.Loadable
import gg.hideouthq.core.data.deadlockstats.DateWindow
import gg.hideouthq.core.data.deadlockstats.DeadlockStatsState
import gg.hideouthq.core.data.deadlockstats.LocalSteamIdentity
import gg.hideouthq.core.data.deadlockstats.MatchListFilter
import gg.hideouthq.core.data.deadlockstats.MatchModeFilter
import gg.hideouthq.core.data.deadlockstats.PlayedWithView
import gg.hideouthq.core.data.deadlockstats.PlayerProfile
import gg.hideouthq.core.data.deadlockstats.RankDistributionEntry
import gg.hideouthq.core.data.deadlockstats.RankedSeason
import gg.hideouthq.core.data.deadlockstats.SteamAccountId
import gg.hideouthq.core.data.deadlockstats.matchIds
import gg.hideouthq.core.data.deadlockstats.matchRow
import gg.hideouthq.core.data.deadlockstats.mostRecentIdentity
import gg.hideouthq.core.network.RemoteException
import gg.hideouthq.feature.stats.AccountLookup
import gg.hideouthq.feature.stats.Coverage
import gg.hideouthq.feature.stats.LocalAccount
import gg.hideouthq.feature.stats.LookupFailure
import gg.hideouthq.feature.stats.MatchDetailState
import gg.hideouthq.feature.stats.RankCohort
import gg.hideouthq.feature.stats.RankVisual
import gg.hideouthq.feature.stats.SearchResult
import gg.hideouthq.feature.stats.SearchState
import gg.hideouthq.feature.stats.SocialContent
import gg.hideouthq.feature.stats.StatsContent
import gg.hideouthq.feature.stats.StatsFilter
import gg.hideouthq.feature.stats.StatsMode
import gg.hideouthq.feature.stats.StatsPeriod
import gg.hideouthq.feature.stats.StatsSelection
import gg.hideouthq.feature.stats.StatsSource
import gg.hideouthq.feature.stats.StatsTab
import gg.hideouthq.feature.stats.SyncStatus
import gg.hideouthq.feature.stats.TabContent
import gg.hideouthq.feature.stats.TeammateRow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import java.time.ZoneId
import kotlin.time.Clock

/**
 * The app's Stats content: the selected session's saved stats projected for each page selection, with the extra API requests
 * a tab needs. A page visiting another account goes to a visiting session, so nothing of one account reaches another's
 * screens. Relative times are worded again every minute. Projections run off the main thread.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LiveStatsSource(
    private val service: StatsService,
    private val catalog: CatalogRepository,
    private val clock: Clock = Clock.System,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val projectionDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : StatsSource {
    override val savedFilter: Flow<StatsFilter?> = service.state.map { state ->
        state.preferences?.let { prefs ->
            StatsFilter(
                mode = StatsMode.entries.firstOrNull { it.name == prefs.mode } ?: StatsMode.All,
                period = StatsPeriod.entries.firstOrNull { it.name == prefs.period } ?: StatsPeriod.Month,
            )
        }
    }.distinctUntilChanged()

    override fun saveFilter(filter: StatsFilter) = service.saveFilters(filter.mode.name, filter.period.name)

    override fun refresh(visiting: Long?) {
        service.retryReference()
        sessionFor(visiting)?.refresh()
    }

    override fun retry(visiting: Long?) {
        service.retryReference()
        sessionFor(visiting)?.retryFailed()
    }

    override fun checkMatch(matchId: Long, visiting: Long?) {
        sessionFor(visiting)?.checkMatch(matchId)
    }

    override fun useAccount(accountId: Long) = service.useAccount(accountId)

    override fun setEnabled(enabled: Boolean) = service.setEnabled(enabled)

    private fun sessionFor(visiting: Long?): StatsSession? {
        val tracked = service.state.value.tracked
        return if (visiting == null || visiting == tracked?.accountId) tracked else service.visitedSession(visiting)
    }

    private sealed interface Target {
        data object Waiting : Target
        data object Off : Target
        data class Setup(val identities: List<LocalSteamIdentity>?) : Target
        data class Opening(val accountId: Long) : Target
        data class Own(val session: StatsSession) : Target
        data class Visit(val accountId: Long) : Target
    }

    private fun target(state: StatsServiceState, visiting: Long?): Target {
        val prefs = state.preferences
        return when {
            prefs == null -> Target.Waiting
            !prefs.enabled -> Target.Off
            visiting != null && visiting != prefs.accountId -> Target.Visit(visiting)
            prefs.accountId == null -> Target.Setup(state.identities)
            state.tracked == null -> Target.Opening(prefs.accountId)
            else -> Target.Own(state.tracked)
        }
    }

    private fun targets(visiting: Long?): Flow<Target> = service.state.map { target(it, visiting) }.distinctUntilChanged()

    override fun content(selection: StatsSelection, visiting: Long?): Flow<StatsContent> = targets(visiting).flatMapLatest { target ->
        when (target) {
            Target.Waiting -> emptyFlow()
            Target.Off -> flowOf(StatsContent.Off)
            is Target.Setup -> flowOf(StatsContent.Setup(target.identities?.let { Loadable.Ready(suggestion(it)) } ?: Loadable.Loading))
            is Target.Opening -> flowOf(StatsContent.Active(target.accountId, null, SyncStatus(refreshing = true), null, null, null, empty = true))
            is Target.Own -> sessionContent(target.session, selection, isYou = true)
            is Target.Visit -> service.visiting(target.accountId) { sessionContent(it, selection, isYou = false) }
        }
    }.distinctUntilChanged().flowOn(projectionDispatcher)

    private data class ServiceInfo(val seasons: Loadable<List<RankedSeason>>, val distribution: Loadable<List<RankDistributionEntry>>, val identities: List<LocalSteamIdentity>?)

    private class Comparison(val tier: Int, val cohort: RankCohort)

    private class Base(
        val data: DeadlockStatsState,
        val sync: SessionSync,
        val reference: StatsReference,
        val clock: StatsClock,
        val info: ServiceInfo,
        val period: PeriodKey?,
    ) {
        val window: DateWindow? = period?.window(clock.now)
    }

    private fun ticks(intervalMillis: Long = TickMillis): Flow<Long> = flow {
        while (true) {
            emit(clock.now().epochSeconds)
            delay(intervalMillis)
        }
    }

    private fun sessionContent(session: StatsSession, selection: StatsSelection, isYou: Boolean): Flow<StatsContent> {
        val infos = service.state.map { ServiceInfo(it.seasons, it.distribution, it.identities) }.distinctUntilChanged()
        return combine(session.data, session.sync, catalog.heroes.onStart { emit(emptyList()) }, infos, ticks()) { data, sync, heroes, info, now ->
            Base(data, sync, StatsReference(heroes, data.itemAssets, data.rankAssets), StatsClock(now, zone), info, selection.filter.period.key(now, info.seasons.valueOrNull))
        }.flatMapLatest { base ->
            val tab = if (base.period == null) flowOf(null) else tabContent(session, selection, base, base.period, base.window!!)
            tab.map { active(session, selection, base, it, isYou) }
        }
    }

    private fun active(session: StatsSession, selection: StatsSelection, base: Base, tab: TabContent?, isYou: Boolean): StatsContent.Active {
        val data = base.data
        val mode = selection.filter.mode.toFilter()
        val window = base.window
        val ids = window?.let { data.matchIds(MatchListFilter(mode = mode, window = it)) }
        val enriched = ids?.count { it in data.matchSummaries || data.matchDetails[it]?.players?.any { p -> p.accountId == session.accountId && p.statSnapshots.isNotEmpty() } == true }
        val localName = base.info.identities?.firstOrNull { SteamAccountId.ofSteamId64(it.steamId64)?.value == session.accountId }?.personaName
        val seasons = base.info.seasons
        return StatsContent.Active(
            accountId = session.accountId,
            header = headerOf(
                data, isYou, localName, selection.filter.period, window, seasons.valueOrNull, base.info.distribution.valueOrNull, base.reference, base.clock,
            ),
            status = SyncStatus(
                refreshing = base.sync.refreshing || !base.sync.opened,
                progress = if (ids != null && enriched != null && base.sync.enriching && enriched < ids.size) Coverage(enriched, ids.size) else null,
                failed = base.sync.failed,
            ),
            matchCount = ids?.size,
            heroCount = window?.let { heroAggregates(data, mode, it).size },
            tab = tab,
            empty = data.matchHistory.isEmpty() && data.fetchedAt["history"] == null,
            season = seasons.map { StatsPeriod.Season.key(base.clock.now, it) != null },
            isYou = isYou,
        )
    }

    private fun tabContent(session: StatsSession, selection: StatsSelection, base: Base, period: PeriodKey, window: DateWindow): Flow<TabContent> {
        val data = base.data
        val mode = selection.filter.mode.toFilter()
        val comparison = comparison(data, selection.filter.mode, base.reference)
        val cohort = comparison?.cohort
        return when (selection.tab) {
            StatsTab.Overview -> {
                enrichPeriod(session, data, mode, window)
                val (mates, _) = socialRows(data, mode, window)
                combine(
                    comparison?.let { session.query(LobbyMetricsQuery(period, it.tier)) } ?: flowOf(Loadable.Ready(emptyMap())),
                    teammates(session, data, mode, window, mates, partyOnly = false, limit = 4, base.clock),
                ) { metrics, (teammates, names) ->
                    TabContent.Overview(overviewContent(data, mode, window, OverviewExtras(cohort, metrics, teammates, names.map { }), base.reference, base.clock))
                }
            }
            StatsTab.Matches -> {
                val content = matchesContent(data, mode, window, selection.heroFilter, selection.result, selection.shown, base.reference, base.clock)
                session.enrich(content.days.flatMap { day -> day.rows.map { it.matchId } })
                flowOf(TabContent.Matches(content))
            }
            StatsTab.Heroes -> {
                enrichPeriod(session, data, mode, window)
                val aggregates = heroAggregates(data, mode, window)
                val selected = selectedHero(aggregates, selection.selectedHero)
                combine(
                    comparison?.let { session.query(HeroBenchmarksQuery(period, it.tier)) } ?: flowOf(Loadable.Ready(emptyList())),
                    comparison?.takeIf { selected != null }?.let { session.query(LobbyMetricsQuery(period, it.tier, selected)) } ?: flowOf(Loadable.Ready(emptyMap())),
                    selected?.let { session.query(ItemUsageQuery(period, mode, it)) } ?: flowOf(Loadable.Ready(emptyList())),
                    session.query(CountersQuery(period, mode)),
                ) { benchmarks, metrics, items, counters ->
                    TabContent.Heroes(heroesContent(aggregates, selected, selection.heroSort, HeroesExtras(cohort, benchmarks, metrics, items, counters), base.reference))
                }
            }
            StatsTab.Teammates -> {
                val (mates, enemies) = socialRows(data, mode, window)
                val enemyIds = enemies.map { it.otherAccountId }
                combine(
                    teammates(session, data, mode, window, mates, selection.partyOnly, MaxTeammates, base.clock),
                    profiles(session, enemyIds),
                    if (enemies.isEmpty()) flowOf(Loadable.Ready(emptyMap())) else session.query(UsualHeroesQuery(enemyIds.sorted())),
                ) { (teammates, mateNames), enemyNames, usual ->
                    TabContent.Teammates(
                        SocialContent(
                            teammates = teammates,
                            opponents = opponentRows(enemies, enemyNames.valueOrNull.orEmpty(), usual.valueOrNull.orEmpty(), base.reference),
                            names = allOf(mateNames, enemyNames),
                            usualHeroes = usual.map { },
                        ),
                    )
                }
            }
        }
    }

    private fun comparison(data: DeadlockStatsState, mode: StatsMode, reference: StatsReference): Comparison? {
        val tier = data.rank?.badge?.takeIf { it > 0 }?.div(10) ?: return null
        if (mode == StatsMode.Normal) return null
        return Comparison(tier, RankCohort(RankVisual(tier * 10, reference.rank(tier * 10 + 1)?.tierName).label, rankedOnly = mode == StatsMode.All))
    }

    private fun teammates(
        session: StatsSession,
        data: DeadlockStatsState,
        mode: MatchModeFilter,
        window: DateWindow,
        mates: List<PlayedWithView>,
        partyOnly: Boolean,
        limit: Int,
        clock: StatsClock,
    ): Flow<Pair<Loadable<List<TeammateRow>>, Loadable<Map<Long, PlayerProfile>>>> = session.query(PartyQuery).flatMapLatest { party ->
        when (party) {
            is Loadable.Ready -> {
                val partySet = partyIds(data, mode, window, party.value)
                val shown = visibleMates(mates, partySet, partyOnly, limit)
                profiles(session, shown.map { it.otherAccountId }).map { names ->
                    Loadable.Ready(teammateRows(shown, partySet, names.valueOrNull.orEmpty(), clock)) to names
                }
            }
            Loadable.Loading -> flowOf(Loadable.Loading to Loadable.Ready(emptyMap()))
            is Loadable.Failed -> flowOf(party to Loadable.Ready(emptyMap()))
        }
    }

    private fun enrichPeriod(session: StatsSession, data: DeadlockStatsState, mode: MatchModeFilter, window: DateWindow) {
        val current = data.matchIds(MatchListFilter(mode = mode, window = window))
        val previous = window.previous()?.let { data.matchIds(MatchListFilter(mode = mode, window = it)) }.orEmpty()
        session.enrich(current.take(MaxEnrichedPerPeriod) + previous.take(MaxEnrichedPerPeriod / 2))
    }

    private fun profiles(session: StatsSession, ids: List<Long>): Flow<Loadable<Map<Long, PlayerProfile>>> =
        if (ids.isEmpty()) flowOf(Loadable.Ready(emptyMap())) else session.query(ProfilesQuery(ids.distinct().sorted().take(100)))

    override fun match(matchId: Long, visiting: Long?): Flow<MatchDetailState> = targets(visiting).flatMapLatest { target ->
        when (target) {
            Target.Waiting -> emptyFlow()
            Target.Off -> flowOf(MatchDetailState.Off)
            is Target.Setup -> flowOf(MatchDetailState.NoAccount)
            is Target.Opening -> flowOf(MatchDetailState.Loading)
            is Target.Own -> matchContent(target.session, matchId)
            is Target.Visit -> service.visiting(target.accountId) { matchContent(it, matchId) }
        }
    }.distinctUntilChanged().flowOn(projectionDispatcher)

    private fun matchContent(session: StatsSession, matchId: Long): Flow<MatchDetailState> {
        val current = session.data.value.matchDetails[matchId]?.isComplete == true
        val load = if (current) flowOf(Loadable.Ready(Unit)) else session.query(DetailQuery(matchId))
        return combine(session.data, load, catalog.heroes.onStart { emit(emptyList()) }, ticks()) { data, loaded, heroes, now -> Triple(data, loaded, heroes to now) }
            .flatMapLatest { (data, loaded, extra) ->
                val (heroes, now) = extra
                val detail = data.matchDetails[matchId]
                val pendingRow = if (detail == null) data.matchRow(matchId) else null
                val reference = StatsReference(heroes, data.itemAssets, data.rankAssets)
                when {
                    detail != null -> profiles(session, detail.players.map { it.accountId }).map { names ->
                        val content = matchDetailContent(data, matchId, session.accountId, names.valueOrNull.orEmpty(), reference, StatsClock(now, zone))
                        if (data.itemAssets.isNotEmpty() && (content?.unknownItems ?: 0) > 0) session.resolveUnknownItems()
                        content?.let { MatchDetailState.Ready(it.copy(names = names.map { }), refreshing = loaded is Loadable.Loading) } ?: MatchDetailState.Failed(notFound = true)
                    }
                    pendingRow != null -> {
                        session.watchMatchDetail(matchId, pendingRow.endTimeS())
                        ticks(PendingTickMillis).map { pendingNow ->
                            val content = checkNotNull(pendingMatchContent(data, matchId, reference, StatsClock(pendingNow, zone), checking = loaded is Loadable.Loading))
                            MatchDetailState.Pending(content)
                        }
                    }
                    loaded is Loadable.Failed -> flowOf(MatchDetailState.Failed(notFound = loaded.error == DataError.NotFound))
                    else -> flowOf(MatchDetailState.Loading)
                }
            }
    }

    override fun search(query: String): Flow<SearchState> = flow {
        val text = query.trim()
        if (text.isEmpty()) {
            emit(SearchState())
            return@flow
        }
        if (service.state.value.preferences?.enabled == false) {
            emit(SearchState(text, off = true))
            return@flow
        }
        val matchId = text.filter { it.isDigit() || it == ' ' }.takeIf { it == text }?.replace(" ", "")?.toLongOrNull()?.takeIf { it in 1..MaxMatchId }
        val base = listOfNotNull(matchId?.let { SearchResult.Match(it) })
        emit(SearchState(text, base, searching = true))
        when (val found = lookUp(text)) {
            is AccountLookup.Found -> emit(SearchState(text, base + found.players.map { SearchResult.Player(it) }))
            is AccountLookup.Failed -> emit(SearchState(text, base, failed = found.reason))
            else -> emit(SearchState(text, base))
        }
    }

    override suspend fun lookUp(query: String): AccountLookup {
        val text = accountQuery(query) ?: return AccountLookup.Idle
        return try {
            val players = service.searchPlayers(text).distinctBy { it.accountId }.map { it.toVisual(it.accountId) }
            if (players.isEmpty()) AccountLookup.NotFound else AccountLookup.Found(players)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val numeric = text.toLongOrNull() != null
            AccountLookup.Failed(
                when {
                    (e as? RemoteException)?.error == DataError.Offline -> LookupFailure.Offline
                    !numeric -> LookupFailure.NameSearchUnavailable
                    else -> LookupFailure.Unavailable
                },
            )
        }
    }

    private companion object {
        const val MaxEnrichedPerPeriod = 200
        const val MaxMatchId = 10_000_000_000L
        const val TickMillis = 60_000L
        /** How often a pending match's page reads the clock again, so it turns slow and then unavailable on time. */
        const val PendingTickMillis = 5_000L
    }
}

internal fun allOf(vararg parts: Loadable<*>): Loadable<Unit> =
    parts.filterIsInstance<Loadable.Failed>().firstOrNull() ?: if (parts.any { it is Loadable.Loading }) Loadable.Loading else Loadable.Ready(Unit)

internal fun suggestion(identities: List<LocalSteamIdentity>): LocalAccount? = identities.mostRecentIdentity()?.let { identity ->
    SteamAccountId.ofSteamId64(identity.steamId64)?.let { LocalAccount(it.value, identity.steamId64, identity.personaName?.trim()?.takeIf(String::isNotEmpty)) }
}

/**
 * What to look up for typed text: the number in a Steam profile link, a SteamID3 like `[U:1:123]`, the name in a custom
 * profile link (searched as a name, since resolving it needs Steam's own API), or the text itself. Null for nothing.
 */
internal fun accountQuery(raw: String): String? {
    val text = raw.trim()
    if (text.isEmpty()) return null
    Regex("""steamcommunity\.com/profiles/(\d{17})""").find(text)?.let { return it.groupValues[1] }
    Regex("""\[U:1:(\d+)]""").find(text)?.let { return it.groupValues[1] }
    Regex("""steamcommunity\.com/id/([^/?#\s]+)""").find(text)?.let { return it.groupValues[1].take(128) }
    return text.take(128)
}
