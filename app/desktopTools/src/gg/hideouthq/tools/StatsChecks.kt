@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package gg.hideouthq.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import gg.hideouthq.AppContainer
import gg.hideouthq.AppContent
import gg.hideouthq.HideoutAppState
import gg.hideouthq.core.data.Loadable
import gg.hideouthq.core.designsystem.component.HideoutMenu
import gg.hideouthq.core.designsystem.component.MenuEntry
import gg.hideouthq.core.designsystem.icon.HideoutIcons
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import gg.hideouthq.core.model.ModCategory
import gg.hideouthq.core.navigation.Route
import gg.hideouthq.core.ui.LocalWindowChrome
import gg.hideouthq.feature.settings.SettingsTab
import gg.hideouthq.feature.stats.MatchesPageSize
import gg.hideouthq.feature.stats.Moment
import gg.hideouthq.feature.stats.StatsFilter
import gg.hideouthq.feature.stats.StatsMode
import gg.hideouthq.feature.stats.StatsPeriod
import gg.hideouthq.feature.stats.StatsSelection
import gg.hideouthq.feature.stats.StatsTab
import gg.hideouthq.feature.stats.components.MatchTimelineTags
import gg.hideouthq.tools.stats.FixtureStatsSource
import gg.hideouthq.tools.stats.StatsDesignFixture
import gg.hideouthq.tools.stats.StatsFixtureState
import kotlinx.coroutines.runBlocking
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

private const val Settle = 1_400L
private const val SidebarWidthDp = 256f
private const val SearchPauseMillis = 400L

private class StatsCheck(val name: String, val run: (File) -> Unit)

fun main(args: Array<String>) {
    val checks = listOf(
        StatsCheck("stats_sidebar_tabs", ::checkSidebarAndTabs),
        StatsCheck("stats_forward_history", ::checkForwardHistory),
        StatsCheck("stats_filters_everywhere", ::checkFiltersEverywhere),
        StatsCheck("stats_match_drill", ::checkMatchDrill),
        StatsCheck("stats_match_updates", ::checkMatchUpdates),
        StatsCheck("stats_hero", ::checkHero),
        StatsCheck("stats_graph_hover", ::checkGraphHover),
        StatsCheck("stats_menu_keyboard", ::checkMenuKeyboard),
        StatsCheck("stats_menu_scroll", ::checkMenuScroll),
        StatsCheck("stats_party_only", ::checkPartyOnly),
        StatsCheck("stats_first_run", ::checkFirstRun),
        StatsCheck("stats_settings_switch", ::checkSettingsSwitch),
        StatsCheck("stats_search", ::checkSearch),
        StatsCheck("stats_setup_late_account", ::checkLateAccount),
        StatsCheck("stats_extras_retry", ::checkExtrasRetry),
        StatsCheck("stats_season_retry", ::checkSeasonRetry),
        StatsCheck("stats_no_matches_tabs", ::checkNoMatchesTabs),
        StatsCheck("stats_match_states", ::checkMatchStates),
        StatsCheck("stats_show_more", ::checkShowMore),
        StatsCheck("stats_header_widths", ::checkHeaderWidths),
    )
    val selected = selectTargets(args.getOrNull(1), checks.map { it.name })
    val outDir = File(args.firstOrNull() ?: "build/screenshots/stats-checks")
    checks.filter { it.name in selected }.forEach { check ->
        check.run(outDir)
        println("Stats check '${check.name}' passed")
    }
    println("All ${selected.size} stats checks passed.")
}

private class StatsApp(state: StatsFixtureState = StatsFixtureState.Ready, start: Route = Route.Stats(), account: Long? = null) {
    val source = FixtureStatsSource(state)
    val container: AppContainer = source.container().also { container ->
        runBlocking { if (account != null) container.preferences.setStatsAccount(account) else container.preferences.statsPreferences() }
    }
    val app = HideoutAppState(container, start)

    val lastRequest: StatsSelection get() = synchronized(source.requests) { source.requests.last() }
    val statsOn: Boolean get() = container.preferences.stats.value?.enabled != false
}

private fun Script.inSidebar(node: SemanticsNode) = node.boundsInRoot.center.x / scale < SidebarWidthDp

private fun Script.sidebarItem(label: String): SemanticsNode? = labelled(label).singleOrNull { inSidebar(it) }

private fun Script.openSidebar(label: String) {
    clickNode(checkNotNull(sidebarItem(label)) { "the sidebar should offer $label" })
    advance(Settle)
}

private fun Script.clickRowWith(text: String) {
    val rows = nodes().filter { node ->
        node.config.contains(SemanticsActions.OnClick) && !inSidebar(node) &&
            node.config.getOrElseNullable(SemanticsProperties.Text) { null }?.any { it.text == text } == true
    }
    check(rows.size == 1) { "Expected one clickable row with '$text', found ${rows.size}" }
    clickNode(rows.single())
    advance(Settle)
}

private fun Script.searchFor(text: String) {
    key(Key.K, ctrl = true)
    type(text)
    val start = System.nanoTime()
    fun waited() = (System.nanoTime() - start) / 1_000_000
    while (waited() < SearchPauseMillis || shows("Searching…")) {
        check(waited() < 5_000) { "the search for '$text' never answered" }
        runUiQueue(16)
        advance(16)
    }
    advance(Settle)
}

private fun Script.toggle(title: String): SemanticsNode =
    nodes().single { it.hasLabel(title) && it.config.contains(SemanticsProperties.ToggleableState) && !inSidebar(it) }

private fun Script.isOn(title: String) = toggle(title).config[SemanticsProperties.ToggleableState] == ToggleableState.On

private fun Script.tab(label: String) {
    openTab(label)
    checkSelected(label, true)
}

private fun checkSidebarAndTabs(outDir: File) {
    val fixture = StatsApp(start = Route.Discover)
    runCheck(outDir, "stats_sidebar_tabs", fixture.app) {
        check(sidebarItem("Stats") != null) { "Stats sits in the sidebar" }
        val library = checkNotNull(sidebarItem("Library")).boundsInRoot
        check(checkNotNull(sidebarItem("Stats")).boundsInRoot.top > library.top) { "Stats sits below Library" }
        openSidebar("Stats")
        check(fixture.app.navigator.current.route == Route.Stats()) { "the sidebar opens your own stats" }
        checkSelected("Overview", true)
        check(fixture.lastRequest.tab == StatsTab.Overview)
        capture("overview")
        for (tab in listOf("Matches", "Heroes", "Teammates")) {
            tab(tab)
            check(fixture.lastRequest.tab.name == tab) { "$tab asks for its own content" }
            capture(tab.lowercase())
        }
        val entry = fixture.app.navigator.current.id
        openSidebar("Stats")
        check(fixture.app.navigator.current.id == entry) { "Stats again stays on the page instead of pushing a copy" }
        checkSelected("Overview", true)
    }
}

private fun checkForwardHistory(outDir: File) {
    val fixture = StatsApp(start = Route.Discover)
    runCheck(outDir, "stats_forward_history", fixture.app, heightDp = 1300) {
        openSidebar("Stats")
        val entry = fixture.app.navigator.current.id
        clickRowWith("57%")
        checkSelected("Heroes", true)
        check(shows("Skins for Haze"))
        val request = fixture.lastRequest
        check(request.tab == StatsTab.Heroes && request.selectedHero != null)
        capture("edited_stats")

        key(Key.DirectionLeft, alt = true)
        advance(Settle)
        check(fixture.app.navigator.current.route == Route.Discover)
        check(fixture.app.navigator.settledEntryId == fixture.app.navigator.current.id)
        check(!shows("Skins for Haze")) { "Forward Stats must not remain composed" }
        capture("settled_back")
        key(Key.DirectionRight, alt = true)
        advance(Settle)
        check(fixture.app.navigator.current.id == entry) { "Forward must return to the same Stats visit" }
        check(fixture.app.navigator.settledEntryId == entry)
        checkSelected("Heroes", true)
        check(shows("Skins for Haze")) { "Forward must restore the selected hero" }
        check(fixture.lastRequest == request) { "Forward must retain Stats choices, got ${fixture.lastRequest}" }
        capture("forward_restored")
    }
}

private fun checkFiltersEverywhere(outDir: File) {
    val fixture = StatsApp()
    runCheck(outDir, "stats_filters_everywhere", fixture.app) {
        check(fixture.lastRequest.filter == StatsFilter()) { "all modes over 30 days to start" }
        clickLabel("Ranked")
        advance(Settle)
        checkSelected("Ranked", true)
        clickLabel("Last 30 days")
        advance(600)
        capture("period_menu")
        clickLabel("Last 7 days")
        advance(Settle)
        val chosen = StatsFilter(StatsMode.Ranked, StatsPeriod.Week)
        check(fixture.source.savedNow == chosen) { "mode and period are remembered, got ${fixture.source.savedNow}" }
        check(fixture.lastRequest.filter == chosen) { "the overview asks again with them" }
        capture("overview_ranked_week")
        for (tab in listOf("Matches", "Heroes", "Teammates")) {
            tab(tab)
            check(fixture.lastRequest.tab.name == tab && fixture.lastRequest.filter == chosen) { "$tab carries the same filters, got ${fixture.lastRequest}" }
            checkSelected("Ranked", true)
            check(shows("Last 7 days")) { "$tab shows the same period" }
        }
        capture("teammates_ranked_week")
        openSidebar("Library")
        openSidebar("Stats")
        checkSelected("Ranked", true)
        check(shows("Last 7 days") && fixture.lastRequest.filter == chosen) { "a new visit opens with the remembered filters" }
        capture("reopened")
    }
}

private fun checkMatchDrill(outDir: File) {
    val fixture = StatsApp()
    runCheck(outDir, "stats_match_drill", fixture.app, heightDp = 1340) {
        clickRowWith("41.2k")
        check(fixture.app.navigator.current.route == Route.StatsMatch(StatsDesignFixture.MatchId)) { "a recent match opens its page" }
        check(checkNotNull(sidebarItem("Stats")).config.getOrElseNullable(SemanticsProperties.Selected) { null } != false) { "the match keeps Stats lit" }
        listOf("Souls lead", "Scoreboard", "Build order", "Your kills and deaths", "Hidden King", "Archmother", "Open on deadlock-api").forEach {
            check(shows(it)) { "the match page shows $it" }
        }
        capture("match")
        hoverGraph(1362f / 2052f)
        val tooltip = nodes().firstOrNull { it.hasTag(MatchTimelineTags.Tooltip) }
        check(tooltip != null && shows("23:00") && shows("Took mid-boss") && shows("22:42")) { "the hover reads the minute nearest the mid-boss and lists it" }
        capture("graph_readout")
        clickLabel("Your souls")
        advance(Settle)
        checkSelected("Your souls", true)
        capture("your_souls")
        clickRowWith("okto")
        advance(Settle)
        check(shows("okto’s kills and deaths") && nodes().count { it.hasLabel("Build order") } == 1) { "opening okto closes your row and marks okto's kills and deaths" }
        capture("opponent_open")
        clickRowWith("okto")
        advance(Settle)
        check(!shows("Build order") && shows("Your kills and deaths")) { "closing it leaves every row closed and the markers yours again" }
        clickLabel("Combat")
        advance(Settle)
        check(shows("Kill share") && shows("Headshots")) { "the Combat columns" }
        capture("combat_closed")
        clickLabel("Matches")
        advance(Settle)
        check(fixture.app.navigator.current.route == Route.Stats()) { "the breadcrumb goes back to Stats" }
        checkSelected("Overview", true)

        tab("Matches")
        clickRowWith("41.2k")
        check(fixture.app.navigator.current.route == Route.StatsMatch(StatsDesignFixture.MatchId))
        key(Key.DirectionLeft, alt = true)
        advance(Settle)
        checkSelected("Matches", true)
        capture("back_to_matches")
    }
}

private fun checkMatchUpdates(outDir: File) {
    val match = StatsDesignFixture.match
    val fixture = StatsApp(start = Route.StatsMatch(match.matchId))
    runCheck(outDir, "stats_match_updates", fixture.app, heightDp = 1340) {
        check(shows("Build order")) { "your row starts open" }
        clickRowWith("Mira")
        check(!shows("Build order") && shows("Your kills and deaths")) { "closing your row leaves every row closed" }
        fixture.source.matchContent.value = match.copy(started = Moment.At(Moment.Today, 20, 15))
        advance(Settle)
        check(!shows("Build order")) { "new data for the match does not reopen a row closed by hand" }
        fixture.source.matchContent.value = match.copy(timeline = checkNotNull(match.timeline).copy(deaths = null, deathsComplete = false))
        advance(Settle)
        check(shows("Souls lead") && !shows("Your kills and deaths")) { "without a death record no kills or deaths are marked" }
        capture("no_deaths")
    }
    val spectator = StatsApp(start = Route.StatsMatch(match.matchId))
    spectator.source.matchContent.value = match.copy(spectator = true, teams = match.teams.map { team -> team.copy(players = team.players.map { it.copy(isYou = false) }) })
    runCheck(outDir, "stats_match_spectator", spectator.app, heightDp = 1340) {
        check(shows("Scoreboard") && !shows("Build order") && !shows("Your souls")) { "a match you weren't in opens no row and has no graph of yours" }
        capture("spectator")
    }
}

private fun checkHero(outDir: File) {
    val fixture = StatsApp()
    runCheck(outDir, "stats_hero", fixture.app, heightDp = 1300) {
        clickRowWith("57%")
        checkSelected("Heroes", true)
        val haze = StatsDesignFixture.heroes.rows.first { it.hero.name == "Haze" }.hero
        check(fixture.lastRequest.tab == StatsTab.Heroes && fixture.lastRequest.selectedHero == haze.id) { "a hero opens Heroes with it chosen, got ${fixture.lastRequest}" }
        check(shows("Skins for Haze")) { "the panel follows the chosen hero" }
        capture("haze")
        clickLabel("Skins for Haze")
        advance(Settle)
        val route = fixture.app.navigator.current.route
        check(route is Route.Browse && route.category == ModCategory.Skins && route.heroId == haze.catalogId) { "Skins opens Browse for the hero, got $route" }
        key(Key.DirectionLeft, alt = true)
        advance(Settle)
        checkSelected("Heroes", true)
        check(fixture.lastRequest.selectedHero == haze.id) { "coming back keeps the hero chosen" }
    }
}

private fun checkGraphHover(outDir: File) {
    val duration = checkNotNull(StatsDesignFixture.match.timeline).durationS
    for (width in listOf(1440, 840)) {
        val fixture = StatsApp(start = Route.StatsMatch(StatsDesignFixture.MatchId))
        runCheck(outDir, "stats_graph_hover_$width", fixture.app, widthDp = width, heightDp = 1340) {
            val graph = nodes().first { it.hasLabel("Graph") }.boundsInRoot
            val left = graph.left + 56 * scale
            for (step in 0..40) {
                val x = left + (graph.right - left) * (0.30f + step * 0.01f)
                moveToDp(x / scale, (graph.top + graph.height * 0.35f) / scale)
                advance(100)
                val tooltip = nodes().firstOrNull { it.hasTag(MatchTimelineTags.Tooltip) }?.boundsInRoot
                check(tooltip != null) { "the tooltip shows at ${width}dp, step $step" }
                val minute = ((x - left) / (graph.right - left) * duration / 60).roundToInt()
                val crosshair = left + (graph.right - left) * minute * 60 / duration
                check(crosshair !in tooltip.left..tooltip.right) { "the tooltip is clear of the crosshair at ${width}dp, step $step" }
                check(tooltip.left >= graph.left && tooltip.right <= graph.right + 16 * scale) { "the tooltip stays within the card at ${width}dp, step $step" }
                if (step == 12) capture("hover")
            }
        }
    }
    val fixture = StatsApp(start = Route.StatsMatch(StatsDesignFixture.MatchId))
    val frame = mutableStateOf(1440)
    runScript(
        outDir, "stats_graph_hover_resize", 1880, 1000,
        {
            CompositionLocalProvider(LocalWindowChrome provides RenderedWindowChrome) {
                HideoutTheme { Box(Modifier.width(frame.value.dp).fillMaxHeight()) { AppContent(fixture.app) } }
            }
        },
        scale = 1f, outputDirectory = File(outDir, "stats_graph_hover_resize"), windowKeys = WindowKeys(fixture.app.keys::onPreviewKeyEvent, fixture.app.keys::onKeyEvent),
    ) {
        advance(Settle)
        hoverGraph(1362f / 2052f)
        check(shows("23:00")) { "the hover reads the minute nearest the mid-boss" }
        frame.value = 1880
        advance(800)
        hoverGraph(1362f / 2052f)
        check(shows("23:00")) { "resized, the same place on the plot keeps its time" }
        capture("resized")
        val graph = nodes().first { it.hasLabel("Graph") }.boundsInRoot
        moveToDp(graph.center.x / scale, graph.bottom / scale + 30)
        advance(100)
        check(!shows("23:00")) { "leaving the plot dismisses the hover" }
    }
}

private fun SemanticsNode.hasTag(tag: String): Boolean = config.getOrElseNullable(SemanticsProperties.TestTag) { null } == tag

private fun Script.focusedText(): String? = focused()?.text()

private fun checkMenuKeyboard(outDir: File) {
    val fixture = StatsApp()
    runCheck(outDir, "stats_menu_keyboard", fixture.app) {
        tab("Matches")
        clickLabel("All heroes")
        advance(600)
        key(Key.Escape)
        advance(600)
        check(!shows("Find a hero") && focusedText() == "All heroes") { "Esc closes the hero menu and puts focus back on its pill, was ${focusedText()}" }
        clickLabel("All heroes")
        advance(600)
        type("s")
        advance(600)
        capture("searching")
        key(Key.Enter)
        advance(Settle)
        val seven = StatsDesignFixture.heroOptions.single { it.name == "Seven" }.id
        check(fixture.lastRequest.heroFilter == seven && !shows("Find a hero")) { "Enter picks Seven and closes the menu" }
        check(focusedText() == "Seven") { "focus is back on the pill, now reading Seven, was ${focusedText()}" }
    }
}

private fun checkMenuScroll(outDir: File) {
    runScript(
        outDir, "stats_menu_scroll", 420, 640,
        {
            HideoutTheme {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
                    Box(Modifier.size(240.dp, 40.dp)) { HideoutMenu((1..30).map { MenuEntry.Item("Option $it", HideoutIcons.Check, {}) }, {}, "Long menu") }
                }
            }
        },
        scale = 1f, outputDirectory = File(outDir, "stats_menu_scroll"),
    ) {
        advance(600)
        val menu = nodes().first { it.hasLabel("Long menu") }.boundsInRoot
        fun option(n: Int) = nodes().first { it.text() == "Option $n" }.boundsInRoot
        check(menu.height < 30 * 40) { "thirty options do not make a menu taller than the window" }
        // Bounds are clipped to what shows, so a row cut by the list's edge is shorter than a whole one.
        check(option(10).height < option(1).height - 1) { "the last row in view is cut, so there is plainly more: ${option(10)} in $menu" }
        repeat(14) { key(Key.DirectionDown) }
        advance(300)
        capture("scrolled")
        check(focusedText() == "Option 15") { "Down moves through the options, was ${focusedText()}" }
        check(option(15).top >= menu.top && option(15).bottom <= menu.bottom && option(1).bottom <= menu.top) { "the focused option scrolls fully into view: ${option(15)} in $menu" }
        val frame = ImageIO.read(dir.listFiles()!!.single { it.name.endsWith("_scrolled.png") })
        val thumbColumn = (menu.right - 8).toInt()
        val background = frame.getRGB((menu.left + 120).toInt(), (menu.bottom - 4).toInt()) and 0xFFFFFF
        check((menu.top.toInt() until menu.bottom.toInt()).any { y -> brightness(frame.getRGB(thumbColumn, y)) > brightness(background) + 30 }) {
            "the scrollbar shows while the menu scrolls"
        }
    }
    var picked = false
    runScript(
        outDir, "stats_menu_short_edge", 420, 640,
        {
            HideoutTheme {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
                    Box(Modifier.size(240.dp, 40.dp)) {
                        HideoutMenu(listOf(MenuEntry.Item("Pick me", HideoutIcons.Check, { picked = true })), {}, "Short menu")
                    }
                }
            }
        },
        scale = 1f, outputDirectory = File(outDir, "stats_menu_short_edge"),
    ) {
        advance(600)
        val row = nodes().first { it.text() == "Pick me" && it.config.contains(SemanticsActions.OnClick) }.boundsInRoot
        moveToDp((row.right - 3) / scale, row.center.y / scale)
        click()
        advance(100)
        check(picked) { "a non-scrolling menu row is clickable at its right edge" }
    }
}

private fun brightness(rgb: Int) = ((rgb shr 16) and 0xFF) + ((rgb shr 8) and 0xFF) + (rgb and 0xFF)

private fun checkPartyOnly(outDir: File) {
    val fixture = StatsApp()
    runCheck(outDir, "stats_party_only", fixture.app, heightDp = 960) {
        tab("Teammates")
        check(shows("Juniper") && !isOn("Party only")) { "every teammate to start" }
        clickNode(toggle("Party only"))
        advance(Settle)
        check(isOn("Party only") && fixture.lastRequest.partyOnly) { "Party only asks for party members" }
        check(shows("Mirai") && shows("lowtide") && !shows("Juniper")) { "only the API's party members stay" }
        check(shows("punk_ghost")) { "opponents are unaffected" }
        capture("party_only")
    }
}

private fun checkFirstRun(outDir: File) {
    val fixture = StatsApp(StatsFixtureState.FirstRun)
    runCheck(outDir, "stats_first_run", fixture.app) {
        check(shows("Use this account") && shows("Steam ID 76561198041273508")) { "the Steam account on this PC is offered" }
        check(fixture.source.used.isEmpty()) { "nothing is chosen until asked" }
        check(!hasText("Rank and form") && !hasText("deadlock-api.com")) { "the first screen is one line and the account, with no cards or footnote before it" }
        capture("offered")
        clickLabel("Use a different account")
        advance(600)
        check(!shows("Use this account") && shows("Steam ID, profile link or name")) { "another account is looked up by id, link or name" }
        capture("different_account")
        clickLabel("Use the Steam account on this PC")
        advance(600)
        check(fixture.source.used.isEmpty()) { "going back still chooses nothing" }
        clickLabel("Use this account")
        advance(Settle)
        check(fixture.source.used == listOf(StatsDesignFixture.AccountId)) { "the offered account is used once chosen, got ${fixture.source.used}" }
        checkSelected("Overview", true)
        capture("chosen")
    }
}

private fun checkSettingsSwitch(outDir: File) {
    val fixture = StatsApp(account = StatsDesignFixture.AccountId)
    runCheck(outDir, "stats_settings_switch", fixture.app, heightDp = 1300) {
        openSidebar("Settings")
        check(fixture.app.settingsTab == SettingsTab.Game) { "Settings opens on the Game tab" }
        fixture.app.settingsTab = SettingsTab.Stats
        advance(Settle)
        check(isOn("Show Stats") && shows("Steam ID 76561198041273508")) { "Settings shows Stats on and the account" }
        check(!shows("Steam tracking")) { "the Steam bridge is not on the Stats tab" }
        capture("settings_on")
        fixture.app.settingsTab = SettingsTab.Developer
        advance(Settle)
        check(shows("Steam tracking") && shows("For people working on Hideout. Stats work without it.")) { "the Developer tab shows the Steam bridge" }
        capture("settings_developer")
        fixture.app.settingsTab = SettingsTab.Stats
        advance(Settle)

        val share = "Share my match IDs with deadlock-api"
        check(!isOn(share) && fixture.container.preferences.stats.value?.shareMatchIds == false) { "sharing match IDs starts off" }
        clickNode(toggle(share))
        advance(Settle)
        check(isOn(share) && fixture.container.preferences.stats.value?.shareMatchIds == true) { "the switch turns sharing on and saves it" }
        capture("settings_share_on")
        clickNode(toggle(share))
        advance(Settle)
        check(!isOn(share) && fixture.container.preferences.stats.value?.shareMatchIds == false) { "and off again" }

        clickNode(toggle("Show Stats"))
        advance(Settle)
        check(!fixture.statsOn && !isOn("Show Stats")) { "the switch turns Stats off and saves it" }
        check(sidebarItem("Stats") == null) { "Stats leaves the sidebar while off" }
        capture("settings_off")
        key(Key.DirectionLeft, alt = true)
        advance(Settle)
        check(fixture.app.navigator.current.route == Route.Stats() && shows("Stats is off")) { "a Stats page reached while off says so" }
        val asked = synchronized(fixture.source.requests) { fixture.source.requests.size }
        capture("off_page")
        clickLabel("Turn on Stats")
        advance(Settle)
        check(fixture.statsOn && sidebarItem("Stats") != null) { "Turn on Stats saves it and brings the sidebar item back" }
        checkSelected("Overview", true)
        check(synchronized(fixture.source.requests) { fixture.source.requests.size } > asked) { "the page loads again" }
        capture("on_again")

        openSidebar("Settings")
        val accountRow = nodes().first { it.hasLabel("Steam account") }.boundsInRoot.center.y
        val change = labelled("Change").minBy { abs(it.boundsInRoot.center.y - accountRow) }
        clickNode(change)
        advance(Settle)
        check(fixture.container.preferences.stats.value?.accountId == null) { "Change forgets the account so Stats asks again" }
        check(fixture.app.navigator.current.route == Route.Stats()) { "and opens Stats to choose" }
    }
}

private fun checkSearch(outDir: File) {
    val fixture = StatsApp()
    runCheck(outDir, "stats_search", fixture.app) {
        searchFor("41236118")
        check(shows("Match 41 236 118")) { "a number offers the match" }
        capture("match_result")
        clickLabel("Match 41 236 118")
        advance(Settle)
        check(fixture.app.navigator.current.route == Route.StatsMatch(StatsDesignFixture.MatchId)) { "the result opens the match" }

        searchFor("mir")
        capture("player_result")
        clickRowWith("Mirai")
        check(fixture.app.navigator.current.route == Route.Stats(visiting = StatsDesignFixture.Mirai.accountId)) { "a player result opens their stats" }
        check(shows("Back to your stats")) { "another player's page says how to get back" }
        check(sidebarItem("Stats") != null)
        capture("visiting")
        openSidebar("Stats")
        check(fixture.app.navigator.current.route == Route.Stats()) { "the sidebar item means your own stats" }
        key(Key.DirectionLeft, alt = true)
        advance(Settle)
        clickLabel("Back to your stats")
        advance(Settle)
        check(fixture.app.navigator.current.route == Route.Stats()) { "back to your own stats" }
    }
}

private fun checkLateAccount(outDir: File) {
    val fixture = StatsApp(StatsFixtureState.FirstRun)
    fixture.source.suggestion.value = Loadable.Loading
    runCheck(outDir, "stats_setup_late_account", fixture.app) {
        check(!shows("Use this account") && !shows("Steam ID, profile link or name")) { "while the login file is read, neither an account nor the search shows" }
        capture("reading")
        fixture.source.suggestion.value = Loadable.Ready(null)
        advance(Settle)
        check(shows("Steam ID, profile link or name") && !shows("Use the Steam account on this PC")) { "with no account on this PC, the search shows" }
        capture("none_found")
        fixture.source.suggestion.value = Loadable.Ready(StatsDesignFixture.suggestion)
        advance(Settle)
        check(shows("Use this account") && shows("Steam ID 76561198041273508")) { "an account found later is still offered" }
        check(fixture.source.used.isEmpty()) { "and still not chosen for the user" }
        capture("found_later")
    }
}

private fun checkExtrasRetry(outDir: File) {
    val falseEmpty = listOf(
        "No rank average", "None of these heroes has a rank average in this view.", "No purchase data for this hero in this view.", "None yet",
        "No party games in this view.", "No teammates with two or more games in this view.", "Unknown player",
    )
    val fixture = StatsApp(StatsFixtureState.ExtrasLoading)
    runCheck(outDir, "stats_extras_retry", fixture.app, heightDp = 1360) {
        for (tab in listOf("Overview", "Heroes", "Teammates")) {
            if (tab != "Overview") tab(tab)
            check(falseEmpty.none(::shows)) { "$tab says nothing is there while it is on its way: ${falseEmpty.filter(::shows)}" }
            check(!shows("Couldn’t load")) { "$tab has not failed yet" }
            capture("loading_${tab.lowercase()}")
        }
        fixture.source.state.value = StatsFixtureState.ExtrasFailed
        for (tab in listOf("Teammates", "Heroes", "Overview")) {
            if (tab != "Teammates") tab(tab) else advance(Settle)
            check(falseEmpty.none(::shows)) { "$tab says nothing is there after a failure: ${falseEmpty.filter(::shows)}" }
            check(shows("Couldn’t load") && labelled("Retry").isNotEmpty()) { "$tab says what failed, with Retry" }
            capture("failed_${tab.lowercase()}")
        }
        check(!shows("Ranked Archon avg 2.9"))
        clickNode(labelled("Retry").first())
        advance(Settle)
        check(fixture.source.retries == listOf<Long?>(null)) { "Retry asks again for your own stats, got ${fixture.source.retries}" }
        check(shows("Ranked Archon avg 2.9") && !shows("Couldn’t load")) { "what the retry brings replaces the failure" }
        capture("retried")
    }
}

private fun checkSeasonRetry(outDir: File) {
    val fixture = StatsApp(StatsFixtureState.SeasonFailed)
    runCheck(outDir, "stats_season_retry", fixture.app) {
        choosePeriod("This season")
        check(fixture.lastRequest.filter.period == StatsPeriod.Season && fixture.source.savedNow?.period == StatsPeriod.Season) { "the season is asked for and remembered" }
        check(shows("Couldn’t load this season’s dates") && shows("This season")) { "the chosen period says its dates failed" }
        check(!shows("Last 30 days") && !shows("37 W · 27 L")) { "no other period's numbers stand in" }
        capture("failed")
        clickLabel("Retry")
        advance(Settle)
        check(fixture.source.retries.size == 1) { "Retry asks again" }
        check(shows("This season") && shows("37 W · 27 L") && fixture.lastRequest.filter.period == StatsPeriod.Season) { "the season's numbers show once its dates arrive" }
        capture("retried")
    }
}

private fun checkNoMatchesTabs(outDir: File) {
    val emptyCards = listOf("Recent matches", "Your heroes", "Most games together", "Most played", "Party only", "Win rate next to Archon players")
    val fixture = StatsApp(StatsFixtureState.NoMatches)
    runCheck(outDir, "stats_no_matches_tabs", fixture.app) {
        for (tab in listOf("Overview", "Matches", "Heroes", "Teammates")) {
            if (tab != "Overview") tab(tab)
            check(nodes().count { it.hasLabel("No matches in this view") } == 1) { "$tab says once that this view has no matches" }
            check(emptyCards.none(::shows)) { "$tab shows no empty cards: ${emptyCards.filter(::shows)}" }
            capture(tab.lowercase())
        }
    }
}

private fun checkMatchStates(outDir: File) {
    val off = StatsApp(start = Route.StatsMatch(StatsDesignFixture.MatchId))
    runBlocking { off.container.preferences.setStatsEnabled(false) }
    runCheck(outDir, "stats_match_states", off.app) {
        check(shows("Stats is off") && !shows("Couldn’t load this match") && !shows("Souls lead")) { "the match page says Stats is off" }
        capture("off")
        searchFor("mir")
        check(shows("Stats is off. Turn it on to find players and matches.") && !shows("Mirai")) { "search says Stats is off instead of searching" }
        capture("search_off")
        key(Key.Escape)
        advance(600)
        clickLabel("Turn on Stats")
        advance(Settle)
        check(off.statsOn && shows("Souls lead")) { "turning Stats on loads the match" }
        capture("on")
    }
    val none = StatsApp(StatsFixtureState.FirstRun, start = Route.StatsMatch(StatsDesignFixture.MatchId))
    runCheck(outDir, "stats_match_states_no_account", none.app) {
        check(shows("Choose your account first") && !shows("Couldn’t load this match")) { "with no account the match page asks for one" }
        check(!shows("/")) { "a breadcrumb without a match date has no trailing separator" }
        capture("no_account")
        clickLabel("Choose account")
        advance(Settle)
        check(none.app.navigator.current.route == Route.Stats() && shows("Use this account")) { "Choose account opens setup" }
        check(none.source.used.isEmpty())
    }
}

private fun checkShowMore(outDir: File) {
    val fixture = StatsApp()
    runCheck(outDir, "stats_show_more", fixture.app, heightDp = 1300) {
        tab("Matches")
        check(fixture.lastRequest.shown == MatchesPageSize)
        clickLabel("Show more")
        advance(Settle)
        check(fixture.lastRequest.shown == 2 * MatchesPageSize) { "Show more asks for another page, got ${fixture.lastRequest.shown}" }
        clickLabel("Ranked")
        advance(Settle)
        check(fixture.lastRequest.shown == MatchesPageSize && fixture.lastRequest.filter.mode == StatsMode.Ranked) { "a new filter starts from the first page" }
    }
}

private fun checkHeaderWidths(outDir: File) {
    val texts = setOf("248", "139", "109", "56%", "3.1", "186 h", "21", "Matches", "Wins", "Losses", "Win rate", "KDA", "Played", "Heroes", "ALL TIME", "Tracked")
    for (width in listOf(1440, 1100, 1000, 840)) {
        val fixture = StatsApp()
        runCheck(outDir, "stats_header_widths", fixture.app, widthDp = width, heightDp = 700) {
            val tabsTop = nodes().first { it.hasLabel("Overview") && it.config.contains(SemanticsProperties.Selected) }.boundsInRoot.top
            val strip = unmergedNodes().filter { node ->
                !node.boundsInRoot.isEmpty && node.boundsInRoot.bottom <= tabsTop && node.config.getOrElseNullable(SemanticsProperties.Text) { null }?.singleOrNull()?.text in texts
            }
            check(strip.size == texts.size) { "at $width dp the strip shows every total once, found ${strip.map { it.text() }}" }
            strip.forEach { node ->
                val layout = node.textLayout()
                // Unwrapped text lays out as wide as it may be, so what counts is whether all of it fits its size.
                val needed = ceil(layout.multiParagraph.intrinsics.maxIntrinsicWidth)
                check(needed <= layout.size.width && !layout.didOverflowHeight && layout.lineCount == 1) {
                    "at $width dp '${node.text()}' is cut or wrapped: needs $needed px, has ${layout.size.width}"
                }
                check(node.boundsInRoot.right <= width * scale) { "at $width dp '${node.text()}' runs off the window" }
            }
            val rows = strip.groupBy { it.boundsInRoot.top.toInt() }.values
            rows.forEach { row ->
                row.sortedBy { it.boundsInRoot.left }.zipWithNext().forEach { (a, b) ->
                    check(a.boundsInRoot.right <= b.boundsInRoot.left) { "at $width dp '${a.text()}' runs into '${b.text()}'" }
                }
            }
            capture("w$width")
        }
    }
}

private fun Script.unmergedNodes(): List<SemanticsNode> = scene.semanticsOwners.flatMap { descendants(it.unmergedRootSemanticsNode) }

private fun SemanticsNode.text(): String? = config.getOrElseNullable(SemanticsProperties.Text) { null }?.joinToString { it.text }

private fun SemanticsNode.textLayout(): TextLayoutResult {
    val results = mutableListOf<TextLayoutResult>()
    checkNotNull(config[SemanticsActions.GetTextLayoutResult].action).invoke(results)
    return results.single()
}
