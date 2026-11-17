package gg.hideouthq.core.crosshair.community

import gg.hideouthq.core.crosshair.crosshair
import gg.hideouthq.core.crosshair.with
import gg.hideouthq.core.crosshair.ConvarNumber
import gg.hideouthq.core.crosshair.Crosshair
import gg.hideouthq.core.crosshair.CrosshairColor
import gg.hideouthq.core.library.resourceBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

class BrowseQueryTest {
    private val all = parseDmmCrosshairs(resourceBytes("dmm/crosshairs.json").decodeToString())
    private val now = Instant.parse("2026-10-26T00:00:00Z")

    private fun matches(query: BrowseQuery) = query.matches(all, now)

    @Test
    fun everyRowOfTheRealLibraryReadsWithItsSettingsAsTheyCame() {
        assertEquals(398, all.size)
        val newest = all.single { it.id == "crosshair_01m5tqmts9ecr8wxtkgbf2v1w7" }
        assertEquals(
            CommunityCrosshair(
                "crosshair_01m5tqmts9ecr8wxtkgbf2v1w7", "Funny", "ZEDLAV", Instant.parse("2026-10-25T23:51:59.531Z"), likes = 0,
                crosshair(length = 6.8, thickness = 6.3, gap = -20.0, opacity = 1.0, lineBorder = 0.0, dotOpacity = 1.0, dotBorderOpacity = 1.0),
            ),
            newest,
        )
        assertTrue(all.any { it.crosshair.lines.length == ConvarNumber.of(100.0) }, "values out of the sliders' ranges are kept")
    }

    @Test
    fun popularIsLikesThenNewestThisWeekIsTheLastSevenDaysAndNewIsNewestFirst() {
        val popular = matches(BrowseQuery(sort = CrosshairSort.Popular))
        assertEquals(listOf("crosshair_01ka6eswnseh2betqcqw4yeknj", "crosshair_01m5tqmts9ecr8wxtkgbf2v1w7", "crosshair_01m5t671eyecs80mp5xqe6ryve"), popular.take(3).map { it.id })
        val week = matches(BrowseQuery(sort = CrosshairSort.ThisWeek))
        assertEquals(55, week.size)
        assertTrue(week.zipWithNext().all { (a, b) -> a.createdAt >= b.createdAt })
        val new = matches(BrowseQuery(sort = CrosshairSort.New))
        assertEquals("crosshair_01m5tqmts9ecr8wxtkgbf2v1w7" to "crosshair_01ka6ekmgceh2betq5d1780em4", new.first().id to new.last().id)
    }

    @Test
    fun everyWordMustBeInTheNameOrTheCreatorIgnoringCase() {
        assertEquals(24, matches(BrowseQuery(text = "DOT")).size)
        assertEquals(setOf("Owen"), matches(BrowseQuery(text = "owen")).map { it.creator }.toSet())
        assertEquals(8, matches(BrowseQuery(text = " owen ")).size)
        assertTrue(matches(BrowseQuery(text = "red dot")).all { "red" in "${it.name} ${it.creator}".lowercase() && "dot" in "${it.name} ${it.creator}".lowercase() })
        assertEquals(0, matches(BrowseQuery(text = "no such crosshair at all")).size)
    }

    @Test
    fun everyStylePickedMustHold() {
        val counts = CrosshairStyle.entries.associateWith { matches(BrowseQuery(styles = setOf(it))).size }
        assertEquals(mapOf(CrosshairStyle.Small to 55, CrosshairStyle.DotOnly to 15, CrosshairStyle.WithDot to 175, CrosshairStyle.Outline to 100, CrosshairStyle.FixedGap to 153), counts)
        assertEquals(7, matches(BrowseQuery(styles = setOf(CrosshairStyle.Small, CrosshairStyle.Outline))).size)
        assertEquals(398, matches(BrowseQuery()).size, "none picked is All")
    }

    @Test
    fun aRepeatedIdKeepsItsFirstRow() {
        val rows = """[
            {"id":"a","name":"First","createdAt":"2026-10-01T00:00:00Z","config":{"gap":1}},
            {"id":"b","name":"Other","createdAt":"2026-10-01T00:00:00Z","config":{"gap":2}},
            {"id":"a","name":"Again","createdAt":"2026-10-02T00:00:00Z","config":{"gap":3}}
        ]"""
        assertEquals(listOf("a" to "First", "b" to "Other"), parseDmmCrosshairs(rows).map { it.id to it.name })
    }

    @Test
    fun aRowThatDoesNotReadAsACrosshairIsLeftOut() {
        val rows = """[
            {"id":"a","name":"Fine","userName":"x","createdAt":"2026-10-01T00:00:00Z","config":{"gap":1,"color":{"r":1,"g":2,"b":3}}},
            {"id":"b","name":"No config","createdAt":"2026-10-01T00:00:00Z"},
            {"id":"c","name":"Bad colour","createdAt":"2026-10-01T00:00:00Z","config":{"color":{"r":256,"g":0,"b":0}}},
            {"id":"d","name":"No date","config":{"gap":1}},
            {"id":"e","name":"  ","createdAt":"2026-10-01T00:00:00Z","config":{"gap":1}},
            {"id":"f","name":"Wrong type","createdAt":"2026-10-01T00:00:00Z","config":{"gap":"wide"}},
            {"id":"g","name":"Wrong likes","createdAt":"2026-10-01T00:00:00Z","likes":"many","config":{"gap":1}},
            "not a row",
            {"id":"i","name":"Huge","createdAt":"2026-10-01T00:00:00Z","config":{"height":1e300}},
            {"id":"h","name":"After them","createdAt":"2026-10-01T00:00:00Z","config":{"gap":2}}
        ]"""
        val read = parseDmmCrosshairs(rows)
        assertEquals(listOf("a", "h"), read.map { it.id }, "a row of the wrong shape costs only itself")
        assertEquals(Crosshair.GameDefault.with(gap = 1.0, color = CrosshairColor(1, 2, 3)), read.first().crosshair, "a setting a row leaves out is the game's")
    }
}
