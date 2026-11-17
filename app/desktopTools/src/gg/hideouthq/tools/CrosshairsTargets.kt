package gg.hideouthq.tools

import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsProperties
import gg.hideouthq.AppContainer
import gg.hideouthq.core.crosshair.ConvarNumber
import gg.hideouthq.core.crosshair.Crosshair
import gg.hideouthq.core.crosshair.CrosshairColor
import gg.hideouthq.core.crosshair.HeroSettings
import gg.hideouthq.core.crosshair.community.CommunityCrosshair
import gg.hideouthq.core.crosshair.community.CommunityCrosshairs
import gg.hideouthq.core.data.CacheStorage
import gg.hideouthq.core.data.DataError
import gg.hideouthq.core.data.HeroSettingsCopy
import gg.hideouthq.core.data.Loadable
import gg.hideouthq.core.data.fake.FakeCommunityCrosshairs
import gg.hideouthq.core.data.fake.FakeConfig
import gg.hideouthq.core.data.fake.InMemoryGameFiles
import gg.hideouthq.core.data.fake.InMemoryHeroSettings
import gg.hideouthq.core.data.fake.MemoryStorage
import gg.hideouthq.core.data.fake.sampleGameFiles
import gg.hideouthq.core.navigation.CrosshairsTab
import gg.hideouthq.core.navigation.Route
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

internal val crosshairsTargets: List<Target> = listOf(
    Target("crosshairs_browse", 1440, 1266) { CrosshairsApp() },
    Target("crosshairs_browse_filtered", 1440, 1000, interact = { choose("Dot only"); choose("Dark") }) { CrosshairsApp() },
    Target("crosshairs_browse_in_use", 1440, 1266, interact = { choose("Use in game") }) { CrosshairsApp() },
    Target("crosshairs_yours", 1440, 1126) { CrosshairsApp(CrosshairsTab.Yours) },
    Target("crosshairs_yours_preset", 1440, 1126, interact = { choose("Wide gap"); choose("Sky"); choose("6×") }) { CrosshairsApp(CrosshairsTab.Yours) },
    Target("crosshairs_yours_actual_size", 1440, 1126, interact = { choose("Grey"); choose("Actual size") }) { CrosshairsApp(CrosshairsTab.Yours) },
    Target("crosshairs_yours_editor", 1440, 1640, interact = { choose("Fixed gap") }) { CrosshairsApp(CrosshairsTab.Yours) },
    Target("crosshairs_browse_window", 1440, 900) { CrosshairsApp(community = Loadable.Ready(CrosshairsFixture.many)) },
    Target("crosshairs_browse_scrolled", 1440, 900, interact = { scrollAt(600f, 600f, 40) }) { CrosshairsApp(community = Loadable.Ready(CrosshairsFixture.many)) },
    Target("crosshairs_browse_scrolled_end", 1440, 900, interact = { scrollAt(600f, 600f, 400) }) { CrosshairsApp(community = Loadable.Ready(CrosshairsFixture.many)) },
    Target("crosshairs_browse_compact", 1000, 680) { CrosshairsApp(community = Loadable.Ready(CrosshairsFixture.many)) },
    Target("crosshairs_browse_compact_scrolled", 1000, 680, interact = { scrollAt(500f, 500f, 120) }) { CrosshairsApp(community = Loadable.Ready(CrosshairsFixture.many)) },
    Target("crosshairs_browse_compact_picked", 1000, 680, interact = { scrollAt(500f, 500f, 120); pickTileInView() }) { CrosshairsApp(community = Loadable.Ready(CrosshairsFixture.many)) },
    Target("crosshairs_yours_window", 1440, 900) { CrosshairsApp(CrosshairsTab.Yours) },
    Target("crosshairs_yours_window_scrolled", 1440, 900, interact = { scrollAt(1250f, 600f, 60) }) { CrosshairsApp(CrosshairsTab.Yours) },
    Target("crosshairs_yours_compact", 1000, 680, interact = { scrollAt(500f, 500f, 60) }) { CrosshairsApp(CrosshairsTab.Yours) },
    Target("crosshairs_browse_loading", 1440, 900) { CrosshairsApp(community = Loadable.Loading) },
    Target("crosshairs_browse_compact_loading", 1000, 680) { CrosshairsApp(community = Loadable.Loading) },
    Target("crosshairs_browse_offline", 1440, 900) { CrosshairsApp(community = Loadable.Failed(DataError.Offline)) },
)

private fun Script.scrollAt(x: Float, y: Float, notches: Int) {
    moveToDp(x, y)
    repeat(notches) { scroll(1f) }
    advance(900)
}

private fun Script.pickTileInView() {
    val window = scene.semanticsOwners.first().rootSemanticsNode.boundsInRoot
    moveTo(nodes().first { it.config.contains(SemanticsProperties.Selected) && it.boundsInRoot.width > 150f * scale && it.boundsInRoot.top > window.height / 2 && it.boundsInRoot.bottom < window.bottom })
    click()
    advance(1_200)
}

@Composable
private fun CrosshairsApp(tab: CrosshairsTab = CrosshairsTab.Browse, community: Loadable<List<CommunityCrosshair>> = Loadable.Ready(CrosshairsFixture.community)) =
    AppWith({ CrosshairsFixture.container(source = FakeCommunityCrosshairs(community)) }, Route.Crosshairs(tab))

internal object CrosshairsFixture {
    val community: List<CommunityCrosshair> = listOf(
        "Mint cross" to "kiri" to crosshair(0, 255, 140, gap = -6.0, length = 3.0, thickness = 1.5, opacity = 1.0, outline = false, dot = 0.0, dotOutline = 0.0, fixedGap = true),
        "Pinpoint" to "vexa" to crosshair(0, 255, 255, gap = 0.0, length = 4.0, thickness = 2.0, opacity = 0.0, outline = true, dot = 1.0, dotOutline = 1.0, fixedGap = true),
        "Plus" to "oddball" to crosshair(255, 255, 255, gap = -9.0, length = 2.0, thickness = 2.5, opacity = 1.0, outline = true, dot = 0.0, dotOutline = 0.0),
        "Open frame" to "lunarmoth" to crosshair(255, 150, 40, gap = 4.0, length = 6.0, thickness = 1.5, opacity = 1.0, outline = true, dot = 1.0, dotOutline = 0.5),
        "Highlighter" to "taro" to crosshair(255, 230, 0, gap = -3.0, length = 4.0, thickness = 3.0, opacity = 1.0, outline = true, dot = 0.0, dotOutline = 0.0),
        "Ghost" to "nyx" to crosshair(255, 255, 255, gap = -2.0, length = 5.0, thickness = 1.0, opacity = 0.55, outline = false, dot = 0.6, dotOutline = 0.0),
        "Tiny red" to "cass" to crosshair(255, 59, 59, gap = -7.0, length = 2.0, thickness = 1.0, opacity = 1.0, outline = true, dot = 0.0, dotOutline = 0.0),
        "Neon pink" to "juno" to crosshair(255, 80, 200, gap = -5.0, length = 3.5, thickness = 2.0, opacity = 1.0, outline = false, dot = 1.0, dotOutline = 0.0),
        "Long reach" to "p1xel" to crosshair(0, 255, 0, gap = 1.0, length = 9.0, thickness = 1.0, opacity = 0.9, outline = true, dot = 0.0, dotOutline = 0.0),
        "Lime dot" to "kiri" to crosshair(170, 255, 0, gap = 0.0, length = 3.0, thickness = 2.0, opacity = 0.0, outline = false, dot = 1.0, dotOutline = 0.8),
        "Violet" to "sable_" to crosshair(185, 140, 255, gap = -4.0, length = 4.0, thickness = 2.0, opacity = 1.0, outline = true, dot = 0.8, dotOutline = 0.5),
        "Quiet" to "monoline" to crosshair(200, 200, 200, gap = -5.0, length = 3.0, thickness = 1.0, opacity = 0.8, outline = false, dot = 0.0, dotOutline = 0.0),
    ).mapIndexed { index, (named, crosshair) ->
        val (name, creator) = named
        CommunityCrosshair("crosshair_mock${index + 1}", name, creator, Instant.parse("2026-10-${25 - index}T18:00:00Z"), likes = 12 - index, crosshair)
    }

    val many: List<CommunityCrosshair> = community + (2..5).flatMap { round ->
        community.map { it.copy(id = "${it.id}_$round", name = "${it.name} $round", createdAt = it.createdAt - (round * 14).days, likes = 0) }
    }

    val mine: Crosshair = crosshair(0, 255, 0, gap = -7.0, length = 3.0, thickness = 1.5, opacity = 1.0, outline = false, dot = 0.0, dotOutline = 0.0)

    val config = FakeConfig(autoexec = DesignAutoexec)

    fun settings(crosshair: Crosshair = mine): InMemoryHeroSettings =
        HeroSettings.New.with(crosshair).render().let { InMemoryHeroSettings(mapOf(HeroSettingsCopy.Game to it, InMemoryHeroSettings.Cloud to it)) }

    fun container(
        files: InMemoryGameFiles = sampleGameFiles(config),
        source: CommunityCrosshairs = FakeCommunityCrosshairs(community),
        data: CacheStorage = MemoryStorage(),
        settings: InMemoryHeroSettings = settings(),
    ): AppContainer = AppContainer.fake(config, files = files, communityCrosshairs = source, crosshairsData = data, heroSettings = settings)

    private fun crosshair(r: Int, g: Int, b: Int, gap: Double, length: Double, thickness: Double, opacity: Double, outline: Boolean, dot: Double, dotOutline: Double, fixedGap: Boolean = false): Crosshair {
        val game = Crosshair.GameDefault
        return game.copy(
            color = CrosshairColor(r, g, b),
            lines = game.lines.copy(ConvarNumber.of(length), ConvarNumber.of(thickness), ConvarNumber.of(gap), ConvarNumber.of(opacity), game.lines.border.copy(size = if (outline) game.lines.border.size else ConvarNumber.Zero)),
            dot = game.dot.copy(opacity = ConvarNumber.of(dot), border = game.dot.border.copy(opacity = ConvarNumber.of(dotOutline))),
            fixedGap = fixedGap,
        )
    }
}
