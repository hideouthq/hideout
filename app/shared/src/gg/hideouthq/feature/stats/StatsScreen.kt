package gg.hideouthq.feature.stats

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import gg.hideouthq.core.data.Loadable
import gg.hideouthq.core.designsystem.component.ButtonSize
import gg.hideouthq.core.designsystem.component.ButtonVariant
import gg.hideouthq.core.designsystem.component.EmptyState
import gg.hideouthq.core.designsystem.component.HideoutButton
import gg.hideouthq.core.designsystem.component.OverlayScrollbar
import gg.hideouthq.core.designsystem.component.ScrollColumn
import gg.hideouthq.core.designsystem.component.rememberScrollbarAdapter
import gg.hideouthq.core.designsystem.icon.HideoutIcons
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import gg.hideouthq.core.ui.OnScrollToTop
import gg.hideouthq.core.ui.PaneScaffold
import gg.hideouthq.core.ui.SearchTopBar
import gg.hideouthq.core.ui.transition.staggeredEnter
import gg.hideouthq.feature.stats.components.CompactHeader
import gg.hideouthq.feature.stats.components.FirstRun
import gg.hideouthq.feature.stats.components.HeaderSkeleton
import gg.hideouthq.feature.stats.components.HeroesTab
import gg.hideouthq.feature.stats.components.MatchColumns
import gg.hideouthq.feature.stats.components.OverviewTab
import gg.hideouthq.feature.stats.components.StatsCard
import gg.hideouthq.feature.stats.components.StatsHeaderCard
import gg.hideouthq.feature.stats.components.StatsLabels
import gg.hideouthq.feature.stats.components.StatsSearchPanel
import gg.hideouthq.feature.stats.components.StatsTabBar
import gg.hideouthq.feature.stats.components.TabSkeleton
import gg.hideouthq.feature.stats.components.TeammatesTab
import gg.hideouthq.feature.stats.components.matchesTab
import gg.hideouthq.feature.stats.components.statsType
import gg.hideouthq.feature.stats.data.valueOrNull
import gg.hideouthq.resources.Res
import gg.hideouthq.resources.stats_back_to_you
import gg.hideouthq.resources.stats_load_failed_message
import gg.hideouthq.resources.stats_load_failed_title
import gg.hideouthq.resources.stats_load_failed_visit_title
import gg.hideouthq.resources.stats_no_matches_message
import gg.hideouthq.resources.stats_no_matches_title
import gg.hideouthq.resources.stats_no_season_message
import gg.hideouthq.resources.stats_no_season_title
import gg.hideouthq.resources.stats_off_message
import gg.hideouthq.resources.stats_off_title
import gg.hideouthq.resources.stats_retry
import gg.hideouthq.resources.stats_search_placeholder
import gg.hideouthq.resources.stats_season_failed_message
import gg.hideouthq.resources.stats_season_failed_title
import gg.hideouthq.resources.stats_season_loading
import gg.hideouthq.resources.stats_turn_on
import gg.hideouthq.resources.stats_visiting
import org.jetbrains.compose.resources.stringResource

interface StatsPageActions {
    fun selectTab(tab: StatsTab)
    fun setMode(mode: StatsMode)
    fun setPeriod(period: StatsPeriod)
    fun setHeroFilter(heroId: Int?)
    fun setResult(result: ResultFilter)
    fun showMore()
    fun openHero(heroId: Int)
    fun selectHero(heroId: Int)
    fun setHeroSort(sort: HeroSort)
    fun setPartyOnly(partyOnly: Boolean)
    fun showOverview()
    fun refresh()
    fun retry()
    fun setQuery(text: String)
    fun lookUp(text: String)
    fun clearLookup()
    fun useAccount(accountId: Long)
    fun turnOn()
}

interface StatsActions : StatsPageActions {
    fun openMatch(matchId: Long)
    fun openPlayer(accountId: Long)
    fun browseSkins(catalogId: String)
    fun backToYou()
}

@Composable
fun StatsScreen(state: StatsScreenState, actions: StatsActions, modifier: Modifier = Modifier) {
    val list = rememberLazyListState()
    OnScrollToTop {
        actions.showOverview()
        list.animateScrollToItem(0)
    }
    PaneScaffold(
        modifier = modifier,
        topBar = { SearchTopBar(state.search.query, actions::setQuery, stringResource(Res.string.stats_search_placeholder)) },
        scrolled = list.firstVisibleItemIndex > 0 || list.firstVisibleItemScrollOffset > 0,
        staggered = true,
    ) {
        Box(Modifier.fillMaxSize()) {
            when (val content = state.content) {
                null -> Unit
                StatsContent.Off -> Centered {
                    EmptyState(HideoutIcons.NavStats, stringResource(Res.string.stats_off_title), stringResource(Res.string.stats_off_message)) {
                        HideoutButton(stringResource(Res.string.stats_turn_on), actions::turnOn, variant = ButtonVariant.Tonal)
                    }
                }
                is StatsContent.Setup -> BoxWithConstraints(Modifier.fillMaxSize()) {
                    val viewport = maxHeight
                    ScrollColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                        Box(Modifier.fillMaxWidth().heightIn(min = viewport - 16.dp), contentAlignment = Alignment.Center) {
                            FirstRun(content.suggestion, state.lookup, actions)
                        }
                    }
                }
                is StatsContent.Active -> ActiveStats(content, state, actions, list)
            }
            StatsSearchPanel(
                state.search, actions::openMatch, actions::openPlayer,
                Modifier.align(Alignment.TopCenter).padding(top = 4.dp, start = 16.dp, end = 16.dp),
            )
        }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().padding(bottom = 56.dp), contentAlignment = Alignment.Center) { content() }
}

@Composable
private fun ActiveStats(content: StatsContent.Active, state: StatsScreenState, actions: StatsActions, list: LazyListState) {
    val selection = state.selection ?: return
    if (content.empty && content.status.failed && !content.status.refreshing) {
        Centered {
            EmptyState(
                HideoutIcons.Warning, stringResource(if (content.isYou) Res.string.stats_load_failed_title else Res.string.stats_load_failed_visit_title),
                stringResource(Res.string.stats_load_failed_message),
            ) { HideoutButton(stringResource(Res.string.stats_retry), actions::refresh, variant = ButtonVariant.Tonal) }
        }
        return
    }
    val header = content.header
    val tabsIndex = if (state.visiting != null) 2 else 1
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val columns = MatchColumns.of(maxWidth - 48.dp)
        LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 4.dp)) {
            if (state.visiting != null) item("visiting") { VisitingNote(header, actions::backToYou) }
            item("header") {
                Box(Modifier.padding(bottom = 12.dp)) {
                    if (header == null || content.empty) HeaderSkeleton(content.status.progress)
                    else StatsHeaderCard(header, content.status, actions::refresh, Modifier.staggeredEnter(0))
                }
            }
            item("tabs") { TabBar(content, selection, actions, Modifier.padding(bottom = 12.dp).staggeredEnter(1)) }
            tabBody(content, selection, actions, columns)
        }
        OverlayScrollbar(rememberScrollbarAdapter(list), Modifier.align(Alignment.TopEnd))
        val scrolledPast by remember(list, tabsIndex) {
            derivedStateOf { list.firstVisibleItemIndex > tabsIndex || (list.firstVisibleItemIndex == tabsIndex && list.firstVisibleItemScrollOffset > 0) }
        }
        AnimatedVisibility(
            scrolledPast && header != null, Modifier.align(Alignment.TopCenter),
            enter = fadeIn(HideoutTheme.motion.effectsFast()) + slideInVertically(HideoutTheme.motion.spatialFast()) { -it / 3 },
            exit = fadeOut(HideoutTheme.motion.exitFade()) + slideOutVertically(HideoutTheme.motion.spatialFast()) { -it / 3 },
        ) {
            Column(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                header?.let { CompactHeader(it, Modifier.padding(top = 2.dp)) }
                TabBar(content, selection, actions)
            }
        }
    }
}

@Composable
private fun TabBar(content: StatsContent.Active, selection: StatsSelection, actions: StatsActions, modifier: Modifier = Modifier) {
    StatsTabBar(
        tab = selection.tab, filter = selection.filter, matchCount = content.matchCount, heroCount = content.heroCount,
        seasonOffered = content.season.valueOrNull != false,
        onTab = actions::selectTab, onMode = actions::setMode, onPeriod = actions::setPeriod, modifier = modifier,
    )
}

private fun LazyListScope.tabBody(content: StatsContent.Active, selection: StatsSelection, actions: StatsActions, columns: MatchColumns) {
    val tab = content.tab
    val season = content.season
    when {
        selection.filter.period == StatsPeriod.Season && !content.empty && season.valueOrNull != true -> item("season") { SeasonState(season, actions::retry) }
        tab == null || content.empty || tab.tabOf() != selection.tab -> item("skeleton-${selection.tab}") { TabSkeleton(selection.tab, fadeInItem()) }
        content.matchCount == 0 -> item("no-matches") { NoMatchesCard(fadeInItem()) }
        tab is TabContent.Matches -> matchesTab(tab.content, selection.heroFilter, selection.result, columns, actions)
        else -> item("tab-${selection.tab}") {
            Box(fadeInItem().padding(bottom = 12.dp)) {
                when (tab) {
                    is TabContent.Overview -> OverviewTab(tab.content, selection.filter.mode, actions)
                    is TabContent.Heroes -> HeroesTab(tab.content, selection.filter, selection.selectedHero, selection.heroSort, actions)
                    is TabContent.Teammates -> TeammatesTab(tab.content, selection.partyOnly, actions::setPartyOnly, actions::retry)
                    is TabContent.Matches -> Unit
                }
            }
        }
    }
}

@Composable
private fun LazyItemScope.fadeInItem(): Modifier = Modifier.animateItem(fadeInSpec = HideoutTheme.motion.effectsDefault(), placementSpec = null, fadeOutSpec = null)

@Composable
private fun NoMatchesCard(modifier: Modifier = Modifier) {
    StatsCard(modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        EmptyState(HideoutIcons.StatsMatches, stringResource(Res.string.stats_no_matches_title), stringResource(Res.string.stats_no_matches_message))
    }
}

@Composable
private fun SeasonState(season: Loadable<Boolean>, onRetry: () -> Unit) {
    StatsCard(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        when (season) {
            Loadable.Loading -> Box(Modifier.fillMaxWidth().heightIn(min = 240.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(Res.string.stats_season_loading), style = statsType().caption, color = MaterialTheme.colorScheme.outline)
            }
            is Loadable.Failed -> EmptyState(HideoutIcons.Warning, stringResource(Res.string.stats_season_failed_title), stringResource(Res.string.stats_season_failed_message)) {
                HideoutButton(stringResource(Res.string.stats_retry), onRetry, variant = ButtonVariant.Tonal)
            }
            is Loadable.Ready -> EmptyState(HideoutIcons.Period, stringResource(Res.string.stats_no_season_title), stringResource(Res.string.stats_no_season_message))
        }
    }
}

private fun TabContent.tabOf(): StatsTab = when (this) {
    is TabContent.Overview -> StatsTab.Overview
    is TabContent.Matches -> StatsTab.Matches
    is TabContent.Heroes -> StatsTab.Heroes
    is TabContent.Teammates -> StatsTab.Teammates
}

@Composable
private fun VisitingNote(header: StatsHeader?, onBack: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            header?.let { stringResource(Res.string.stats_visiting, StatsLabels.playerName(it.player)) } ?: "",
            style = statsType().caption, color = scheme.onSurfaceVariant, modifier = Modifier.weight(1f),
        )
        HideoutButton(stringResource(Res.string.stats_back_to_you), onBack, variant = ButtonVariant.Text, size = ButtonSize.Small)
    }
}
