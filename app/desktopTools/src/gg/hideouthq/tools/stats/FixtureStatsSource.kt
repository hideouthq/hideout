package gg.hideouthq.tools.stats

import gg.hideouthq.AppContainer
import gg.hideouthq.core.data.DataError
import gg.hideouthq.core.data.Loadable
import gg.hideouthq.core.data.Preferences
import gg.hideouthq.feature.stats.AccountLookup
import gg.hideouthq.feature.stats.Coverage
import gg.hideouthq.feature.stats.HeroesContent
import gg.hideouthq.feature.stats.LocalAccount
import gg.hideouthq.feature.stats.MatchDetailState
import gg.hideouthq.feature.stats.MatchesContent
import gg.hideouthq.feature.stats.Outcome
import gg.hideouthq.feature.stats.OverviewContent
import gg.hideouthq.feature.stats.RankCohort
import gg.hideouthq.feature.stats.ResultFilter
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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking

enum class StatsFixtureState { Ready, FirstRun, Loading, LoadFailed, NoMatches, Off, ExtrasLoading, ExtrasFailed, SeasonFailed }

@OptIn(ExperimentalCoroutinesApi::class)
class FixtureStatsSource(initial: StatsFixtureState = StatsFixtureState.Ready) : StatsSource {
    val state = MutableStateFlow(initial)

    val suggestion = MutableStateFlow<Loadable<LocalAccount?>>(Loadable.Ready(StatsDesignFixture.suggestion))
    val requests = mutableListOf<StatsSelection>()
    val used = mutableListOf<Long>()
    val retries = mutableListOf<Long?>()

    val matchContent = MutableStateFlow(StatsDesignFixture.match)
    private val filter = MutableStateFlow<StatsFilter?>(StatsFilter())
    private val preferences = MutableStateFlow<Preferences?>(null)

    fun follow(preferences: Preferences) {
        this.preferences.value = preferences
    }

    fun container(stats: StatsSource = this): AppContainer = AppContainer.fake(stats = stats).also { follow(it.preferences) }

    private val switchedOn: Flow<Boolean> = preferences.flatMapLatest { it?.stats?.map { saved -> saved?.enabled != false } ?: flowOf(true) }

    override val savedFilter: Flow<StatsFilter?> = filter

    override fun saveFilter(filter: StatsFilter) {
        this.filter.value = filter
    }

    override fun content(selection: StatsSelection, visiting: Long?): Flow<StatsContent> = combine(state, switchedOn, suggestion) { fixture, on, suggested ->
        synchronized(requests) { requests += selection }
        when (if (on) fixture else StatsFixtureState.Off) {
            StatsFixtureState.Off -> StatsContent.Off
            StatsFixtureState.FirstRun -> StatsContent.Setup(suggested)
            StatsFixtureState.Loading -> StatsContent.Active(StatsDesignFixture.AccountId, null, SyncStatus(refreshing = true, progress = Coverage(120, 248)), null, null, null, empty = true)
            StatsFixtureState.LoadFailed -> StatsContent.Active(StatsDesignFixture.AccountId, null, SyncStatus(failed = true), null, null, null, empty = true)
            StatsFixtureState.NoMatches -> active(visiting, matchCount = 0, heroCount = 0, tab = tab(selection, fixture))
            StatsFixtureState.SeasonFailed -> if (selection.filter.period == StatsPeriod.Season) {
                active(visiting, matchCount = null, heroCount = null, tab = null).copy(season = Loadable.Failed(DataError.Unavailable))
            } else {
                active(visiting, 64, 6, tab(selection, fixture)).copy(season = Loadable.Failed(DataError.Unavailable))
            }
            else -> active(visiting, 64, 6, tab(selection, fixture))
        }
    }

    private fun active(visiting: Long?, matchCount: Int?, heroCount: Int?, tab: TabContent?) = StatsContent.Active(
        accountId = visiting ?: StatsDesignFixture.AccountId,
        header = StatsDesignFixture.header,
        status = SyncStatus(),
        matchCount = matchCount,
        heroCount = heroCount,
        tab = tab,
        isYou = visiting == null,
    )

    private fun cohort(mode: StatsMode): RankCohort? = when (mode) {
        StatsMode.Normal -> null
        StatsMode.Ranked -> RankCohort("Archon", rankedOnly = false)
        StatsMode.All -> RankCohort("Archon", rankedOnly = true)
    }

    private fun tab(selection: StatsSelection, fixture: StatsFixtureState): TabContent {
        val extras: Loadable<Nothing>? = when (fixture) {
            StatsFixtureState.ExtrasLoading -> Loadable.Loading
            StatsFixtureState.ExtrasFailed -> Loadable.Failed(DataError.Unavailable)
            else -> null
        }
        val cohort = cohort(selection.filter.mode)
        return when (selection.tab) {
            StatsTab.Overview -> TabContent.Overview(overview(cohort, extras))
            StatsTab.Matches -> TabContent.Matches(if (fixture == StatsFixtureState.NoMatches) noMatches() else matches(selection))
            StatsTab.Heroes -> TabContent.Heroes(heroes(selection, cohort, extras))
            StatsTab.Teammates -> TabContent.Teammates(social(selection, extras))
        }
    }

    private fun overview(cohort: RankCohort?, extras: Loadable<Nothing>?): OverviewContent = StatsDesignFixture.overview.let { overview ->
        overview.copy(
            cohort = cohort,
            averages = extras ?: overview.averages,
            teammates = extras ?: overview.teammates,
            names = extras ?: overview.names,
        )
    }

    private fun heroes(selection: StatsSelection, cohort: RankCohort?, extras: Loadable<Nothing>?): HeroesContent {
        val heroes = StatsDesignFixture.heroes
        val selected = heroes.rows.firstOrNull { it.hero.id == selection.selectedHero }?.hero
        val panel = heroes.panel?.let { panel ->
            val chosen = if (selected == null || selected == panel.hero) panel else panel.copy(hero = selected, matches = heroes.rows.first { it.hero == selected }.matches)
            if (extras == null) chosen else chosen.copy(averages = extras, build = extras, matchups = extras)
        }
        return heroes.copy(cohort = cohort, vsRank = extras ?: heroes.vsRank, panel = panel)
    }

    private fun social(selection: StatsSelection, extras: Loadable<Nothing>?): SocialContent {
        val social = StatsDesignFixture.social
        if (extras != null) {
            val opponents = social.opponents.map { it.copy(player = it.player.copy(name = ""), usualHero = null) }
            return SocialContent(teammates = extras, opponents = opponents, names = extras, usualHeroes = extras)
        }
        val teammates = (social.teammates as Loadable.Ready).value
        return if (selection.partyOnly) social.copy(teammates = Loadable.Ready(teammates.filter { it.party })) else social
    }

    private fun noMatches() = MatchesContent(emptyList(), null, 0, 0, 0, emptyList(), 0)

    private fun matches(selection: StatsSelection): MatchesContent {
        val all = StatsDesignFixture.matches.copy(selectedHero = StatsDesignFixture.heroOptions.firstOrNull { it.id == selection.heroFilter })
        if (selection.heroFilter == null && selection.result == ResultFilter.All) return all
        val days = all.days.map { day ->
            day.copy(rows = day.rows.filter { row ->
                (selection.heroFilter == null || row.hero?.id == selection.heroFilter) && when (selection.result) {
                    ResultFilter.All -> true
                    ResultFilter.Wins -> row.outcome == Outcome.Win
                    ResultFilter.Losses -> row.outcome == Outcome.Loss
                }
            })
        }.filter { it.rows.isNotEmpty() }
        val rows = days.flatMap { it.rows }
        return all.copy(days = days, total = rows.size, wins = rows.count { it.outcome == Outcome.Win }, losses = rows.count { it.outcome == Outcome.Loss }, shown = rows.size)
    }

    override fun match(matchId: Long, visiting: Long?): Flow<MatchDetailState> = combine(state, switchedOn, matchContent) { fixture, on, match ->
        when {
            !on || fixture == StatsFixtureState.Off -> MatchDetailState.Off
            fixture == StatsFixtureState.FirstRun -> MatchDetailState.NoAccount
            matchId == match.matchId -> MatchDetailState.Ready(match)
            else -> MatchDetailState.Failed(notFound = true)
        }
    }

    override fun search(query: String): Flow<SearchState> = switchedOn.map { on ->
        val text = query.trim()
        if (!on || state.value == StatsFixtureState.Off) {
            SearchState(text, off = true)
        } else {
            SearchState(
                text,
                listOfNotNull(text.toLongOrNull()?.let { SearchResult.Match(it) }) +
                    listOf(StatsDesignFixture.Mirai, StatsDesignFixture.Lowtide).filter { it.name.contains(text, ignoreCase = true) }.map { SearchResult.Player(it) },
            )
        }
    }

    override suspend fun lookUp(query: String): AccountLookup = AccountLookup.Found(listOf(StatsDesignFixture.Mira))

    override fun refresh(visiting: Long?) = Unit

    override fun retry(visiting: Long?) {
        retries += visiting
        if (state.value == StatsFixtureState.ExtrasFailed || state.value == StatsFixtureState.SeasonFailed) state.value = StatsFixtureState.Ready
    }

    override fun checkMatch(matchId: Long, visiting: Long?) = Unit

    override fun useAccount(accountId: Long) {
        used += accountId
        state.value = StatsFixtureState.Ready
    }

    override fun setEnabled(enabled: Boolean) {
        val followed = preferences.value
        if (followed != null) runBlocking { followed.setStatsEnabled(enabled) }
        state.value = if (enabled) StatsFixtureState.Ready else StatsFixtureState.Off
    }

    val savedNow: StatsFilter? get() = filter.value
}
