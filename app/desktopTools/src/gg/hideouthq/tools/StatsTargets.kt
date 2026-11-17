package gg.hideouthq.tools

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import gg.hideouthq.App
import gg.hideouthq.core.data.Loadable
import gg.hideouthq.core.data.Preferences
import gg.hideouthq.core.navigation.Route
import gg.hideouthq.core.ui.LocalWindowChrome
import gg.hideouthq.rememberHideoutAppState
import gg.hideouthq.tools.stats.FixtureStatsSource
import gg.hideouthq.tools.stats.StatsDesignFixture
import gg.hideouthq.tools.stats.StatsFixtureState
import kotlinx.coroutines.runBlocking

private const val TabSettleMillis = 1_400L

internal val statsTargets: List<Target> = listOf(
    Target("stats_18_overview", 1440, 1360) { StatsApp() },
    Target("stats_18b_overview_window", 1440, 900) { StatsApp() },
    Target("stats_18c_matches", 1440, 1300, interact = { openTab("Matches") }) { StatsApp() },
    Target("stats_match", 1440, 2000) { StatsApp(Route.StatsMatch(StatsDesignFixture.MatchId)) },
    Target("stats_match_graph_hover", 1440, 1340, interact = { hoverGraph(1362f / 2052f) }) { StatsApp(Route.StatsMatch(StatsDesignFixture.MatchId)) },
    Target("stats_match_graph_hover_souls", 1440, 1340, interact = { clickLabel("Your souls"); advance(TabSettleMillis); hoverGraph(0.45f) }) {
        StatsApp(Route.StatsMatch(StatsDesignFixture.MatchId))
    },
    Target("stats_match_combat", 1440, 2000, interact = { clickLabel("Combat"); advance(TabSettleMillis) }) { StatsApp(Route.StatsMatch(StatsDesignFixture.MatchId)) },
    Target("stats_matches_hero_search", 1440, 1300, interact = { openTab("Matches"); clickLabel("All heroes"); advance(600); type("se"); advance(600) }) { StatsApp() },
    Target("stats_18f_heroes", 1440, 1300, interact = { openTab("Heroes") }) { StatsApp() },
    Target("stats_18g_teammates", 1440, 960, interact = { openTab("Teammates") }) { StatsApp() },
    Target("stats_18h_teammates_party", 1440, 960, interact = { openTab("Teammates"); clickLabel("Party only"); advance(TabSettleMillis) }) { StatsApp() },
    Target("stats_18i_first_run", 1440, 900) { StatsApp(state = StatsFixtureState.FirstRun) },
    Target("stats_narrow_overview", 1100, 800, scale = 1f) { StatsApp() },
    Target("stats_narrow_overview_full", 1100, 1900, scale = 1f) { StatsApp() },
    Target("stats_narrow_matches", 1100, 1300, scale = 1f, interact = { openTab("Matches") }) { StatsApp() },
    Target("stats_narrow_match", 1100, 2200, scale = 1f) { StatsApp(Route.StatsMatch(StatsDesignFixture.MatchId)) },
    Target("stats_narrow_heroes", 1100, 1700, scale = 1f, interact = { openTab("Heroes") }) { StatsApp() },
    Target("stats_narrow_teammates", 1100, 1200, scale = 1f, interact = { openTab("Teammates") }) { StatsApp() },
    Target("stats_narrow_first_run", 1100, 800, scale = 1f) { StatsApp(state = StatsFixtureState.FirstRun) },
    Target("stats_compact_overview", 1000, 680, scale = 1f) { StatsApp() },
    Target("rail_stats_overview", 840, 700, scale = 1f) { StatsApp() },
    Target("rail_stats_match", 840, 2600, scale = 1f) { StatsApp(Route.StatsMatch(StatsDesignFixture.MatchId)) },
    Target("stats_state_loading", 1440, 900) { StatsApp(state = StatsFixtureState.Loading) },
    Target("stats_state_load_failed", 1440, 900) { StatsApp(state = StatsFixtureState.LoadFailed) },
    Target("stats_state_no_matches", 1440, 900, interact = { openTab("Matches") }) { StatsApp(state = StatsFixtureState.NoMatches) },
    Target("stats_state_off", 1440, 900) { StatsApp(state = StatsFixtureState.Off, saved = { setStatsEnabled(false) }) },
    Target("stats_settings_game", 1440, 1400) { StatsApp(Route.Settings, saved = { setStatsAccount(StatsDesignFixture.AccountId) }) },
    Target("stats_settings_game_off", 1440, 1400) { StatsApp(Route.Settings, saved = { setStatsAccount(StatsDesignFixture.AccountId); setStatsEnabled(false) }) },
    Target("stats_state_pinned_tabs", 1440, 900, interact = { moveToDp(900f, 600f); repeat(6) { scroll(6f) }; advance(TabSettleMillis) }) { StatsApp() },
    Target("stats_state_extras_loading", 1440, 1360) { StatsApp(state = StatsFixtureState.ExtrasLoading) },
    Target("stats_state_extras_failed", 1440, 1360) { StatsApp(state = StatsFixtureState.ExtrasFailed) },
    Target("stats_state_extras_failed_heroes", 1440, 1300, interact = { openTab("Heroes") }) { StatsApp(state = StatsFixtureState.ExtrasFailed) },
    Target("stats_state_extras_failed_teammates", 1440, 960, interact = { openTab("Teammates") }) { StatsApp(state = StatsFixtureState.ExtrasFailed) },
    Target("stats_state_season_failed", 1440, 900, interact = { choosePeriod("This season") }) { StatsApp(state = StatsFixtureState.SeasonFailed) },
    Target("stats_state_normal", 1440, 1360, interact = { openTab("Normal") }) { StatsApp() },
    Target("stats_state_normal_heroes", 1440, 1300, interact = { openTab("Normal"); openTab("Heroes") }) { StatsApp() },
    Target("stats_state_no_matches_overview", 1440, 900) { StatsApp(state = StatsFixtureState.NoMatches) },
    Target("stats_state_first_run_reading", 1440, 900) { StatsApp(state = StatsFixtureState.FirstRun, prepare = { suggestion.value = Loadable.Loading }) },
    Target("stats_match_off", 1440, 900) { StatsApp(Route.StatsMatch(StatsDesignFixture.MatchId), StatsFixtureState.Off, saved = { setStatsEnabled(false) }) },
    Target("stats_match_no_account", 1440, 900) { StatsApp(Route.StatsMatch(StatsDesignFixture.MatchId), StatsFixtureState.FirstRun) },
)

@Composable
private fun StatsApp(
    start: Route = Route.Stats(),
    state: StatsFixtureState = StatsFixtureState.Ready,
    saved: suspend Preferences.() -> Unit = {},
    prepare: FixtureStatsSource.() -> Unit = {},
) {
    CompositionLocalProvider(LocalWindowChrome provides RenderedWindowChrome) {
        val container = remember {
            FixtureStatsSource(state).apply(prepare).container().also { runBlocking { it.preferences.saved() } }
        }
        App(rememberHideoutAppState(container, start))
    }
}

internal fun Script.openTab(label: String) {
    clickLabel(label)
    advance(TabSettleMillis)
}

internal fun Script.choosePeriod(label: String) {
    clickLabel("Last 30 days")
    advance(600)
    clickLabel(label)
    advance(TabSettleMillis)
}

internal fun Script.hoverGraph(fraction: Float) {
    val graph = nodes().first { it.hasLabel("Graph") }.boundsInRoot
    val left = graph.left + GraphPlotLeftDp * scale
    moveToDp((left + (graph.right - left) * fraction) / scale, graph.center.y / scale)
    advance(600)
}

private const val GraphPlotLeftDp = 56f
