package gg.hideouthq.feature.stats.components

import gg.hideouthq.feature.stats.GraphView
import gg.hideouthq.feature.stats.MatchCurve
import gg.hideouthq.feature.stats.MatchDeath
import gg.hideouthq.feature.stats.MatchTimeline
import gg.hideouthq.feature.stats.ObjectiveEvent
import gg.hideouthq.feature.stats.ObjectiveKind
import gg.hideouthq.feature.stats.Team
import gg.hideouthq.feature.stats.TimelineSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MatchTimelineLayoutTest {
    @Test
    fun scalesLabelRoundSteps() {
        assertEquals(
            listOf(20_000.0 to true, 10_000.0 to false, 0.0 to true, -10_000.0 to false, -20_000.0 to true),
            leadScale(16_694.0).ticks.map { it.value to it.labelled },
        )
        assertEquals(listOf(40_000.0, 0.0, -40_000.0), leadScale(30_000.0).ticks.filter { it.labelled }.map { it.value }, "a larger lead takes the next round step")
        assertEquals(listOf(0.0, 20_000.0, 40_000.0), amountScale(46_692.0).ticks.map { it.value })
    }

    private fun objective(time: Int) = ObjectiveEvent(time, ObjectiveKind.Guardian, null, Team.Amber)

    @Test
    fun anOverlappingObjectiveDropsToTheSecondLineAndOnlyThenMovesRight() {
        val placed = placeObjectives(listOf(100, 110, 120, 200, 210).map(::objective), durationS = 1000, width = 1000f, spacing = 24f)
        assertEquals(listOf(100f to 0, 110f to 1, 124f to 0, 200f to 0, 210f to 1), placed.map { it.x to it.line })
    }

    @Test
    fun closeMarkersStackUpwardOneStepAtATime() {
        assertEquals(listOf(0, 1, 2, 0, 1, 0), stackLevels(listOf(100f, 110f, 120f, 200f, 205f, 240f), 26f))
    }

    @Test
    fun theHoverSnapsToTheNearestMinuteAndTheFinalSecondsHaveAStopAtTheEnd() {
        assertEquals(60, hoverStop(89.0, 2471))
        assertEquals(120, hoverStop(90.0, 2471))
        assertEquals(2460, hoverStop(2471.0, 2471), "41:11 is nearest 41:00, whose window reaches past the end")
        assertEquals(2460, hoverStop(2489.0, 2510))
        assertEquals(2510, hoverStop(2490.0, 2510), "past 41:30 in a 41:50 match the hover rests on the end")
        assertEquals(2510, hoverStop(2510.0, 2510))
        assertTrue(inWindow(2509, 2510, 2510), "an objective at 41:49 can be read")
        assertFalse(inWindow(2490, 2460, 2510))
        for (duration in listOf(2471, 2489, 2490, 2510, 2520)) {
            val stops = (0..duration).map { hoverStop(it.toDouble(), duration) }.toSet()
            for (second in 0..duration) {
                assertTrue(inWindow(second, hoverStop(second.toDouble(), duration), duration), "the stop at $second s of $duration s shows it")
                assertEquals(1, stops.count { inWindow(second, it, duration) }, "$second s of $duration s belongs to exactly one stop")
            }
        }
        val end = timeline(emptyList(), complete = true).copy(
            durationS = 2510, lead = MatchCurve.of(listOf(0 to 0.0, 2510 to 4_000.0)), objectives = listOf(ObjectiveEvent(2509, ObjectiveKind.Patron, null, Team.Sapphire)),
        )
        val readout = minuteReadout(end, GraphView.SoulsLead, null, null, Team.Sapphire, emptyMap(), hoverStop(2500.0, 2510))
        assertEquals(2510, readout.timeS)
        assertEquals(4_000.0, readout.value)
        assertEquals(listOf(2509), readout.objectives.map { it.timeS })
    }

    @Test
    fun theTooltipFlipsLeftPastTwoThirdsOrWhereTheRightHasNoRoom() {
        assertEquals(674f, tooltipStart(660f, 1000f, 244f, 14f, 56f, 16f), "right of the crosshair")
        assertEquals(422f, tooltipStart(680f, 1000f, 244f, 14f, 56f, 16f), "left of it past two-thirds")
        assertEquals(102f, tooltipStart(360f, 600f, 244f, 14f, 56f, 16f), "on a narrow plot, left once the right has too little room")
    }

    @Test
    fun theLastFiveMinuteLabelGivesWayToTheMatchLength() {
        assertEquals(listOf(0, 300, 600, 900, 1200, 1500, 1800, 2100), axisTimes(2471, 1056f, 48f), "40:00 would crowd 41:11")
        assertEquals(listOf(0, 300, 600, 900, 1200, 1500, 1800, 2100, 2400), axisTimes(2600, 1056f, 48f))
    }

    private fun timeline(deaths: List<MatchDeath>?, complete: Boolean) = MatchTimeline(
        durationS = 600,
        lead = MatchCurve.of(listOf(0 to 0.0, 300 to 1_000.0, 600 to -500.0)),
        averageSouls = MatchCurve.of(listOf(0 to 0.0, 600 to 6_000.0)),
        averageDamage = MatchCurve.of(listOf(0 to 0.0, 600 to 3_000.0)),
        objectives = listOf(ObjectiveEvent(310, ObjectiveKind.Walker, null, Team.Sapphire)),
        deaths = deaths,
        deathsComplete = complete,
        summary = TimelineSummary(null, null, null, 0, 0, null),
    )

    private val teams = mapOf(1L to Team.Sapphire, 2L to Team.Amber, 3L to Team.Amber)

    @Test
    fun aMinuteCountsKillsOnlyWhenEveryDeathIsRecordedAndNeverCreditsAnUnknownKiller() {
        val deaths = listOf(MatchDeath(290, 2, 1, 10), MatchDeath(295, 3, null, 10), MatchDeath(305, 1, 2, 12), MatchDeath(400, 3, 1, 5))
        val full = minuteReadout(timeline(deaths, complete = true), GraphView.SoulsLead, null, 1, Team.Sapphire, teams, 300)
        assertEquals(1_000.0, full.value)
        assertNull(full.average)
        assertEquals(1 to 1, full.kills, "player 3's death at 4:55 has no killer, so no one is credited")
        assertEquals(listOf(true to 290, false to 305), full.marks.map { it.kill to it.timeS })
        assertEquals(listOf(310), full.objectives.map { it.timeS })
        assertNull(minuteReadout(timeline(deaths, complete = false), GraphView.SoulsLead, null, 1, Team.Sapphire, teams, 300).kills, "an incomplete record cannot count a minute")
        val quiet = minuteReadout(timeline(deaths, complete = true), GraphView.YourSouls, null, 1, Team.Sapphire, teams, 540)
        assertTrue(quiet.quiet)
        assertEquals(5_400.0, quiet.average!!, 1e-6)
        assertEquals(listOf(305..317), deadBands(timeline(deaths, true), 1))
        assertEquals(listOf(400 to true), marksOf(timeline(deaths, true), 1).filter { it.timeS > 300 && it.kill }.map { it.timeS to it.kill })
    }

    @Test
    fun aDeathInASnapshotGapIsNotPlacedOnTheGraphButStaysInTheHover() {
        val gapped = timeline(listOf(MatchDeath(200, 1, 2, 10), MatchDeath(440, 1, 2, 10)), complete = true).copy(
            lead = MatchCurve.of(listOf(0 to 0.0, 300 to 1_000.0, 360 to null, 600 to -500.0)),
        )
        val marks = marksOf(gapped, 1)
        assertEquals(listOf(200), plottedMarks(marks, gapped.lead).map { (mark, _) -> mark.timeS }, "7:20 falls between 5:00 and 10:00, where a measurement is missing")
        assertEquals(gapped.lead.valueAt(200.0), plottedMarks(marks, gapped.lead).single().second)
        val hover = minuteReadout(gapped, GraphView.SoulsLead, null, 1, Team.Sapphire, teams, hoverStop(440.0, 600))
        assertNull(hover.value)
        assertEquals(listOf(440), hover.marks.map { it.timeS }, "the hover still lists it")
        assertEquals(emptyList(), plottedMarks(marks, null), "no line, no placed markers")
    }

    @Test
    fun theCurvePassesThroughItsPointsNeverOvershootsAndHasNoValueInAGap() {
        val curve = MatchCurve.of(listOf(0 to 0.0, 180 to 1_875.0, 360 to -691.0, 540 to 2_776.0, 720 to null, 900 to -1_985.0, 1200 to -16_150.0))
        assertEquals(2, curve.runs.size)
        assertEquals(1_875.0, curve.valueAt(180.0))
        assertEquals(-691.0, curve.valueAt(360.0))
        assertNull(curve.valueAt(700.0), "between 9:00 and 15:00 a measurement is missing")
        assertTrue((0..540).all { curve.valueAt(it.toDouble())!! in -691.0 - 1e-6..2_776.0 + 1e-6 })
        assertTrue((180..360).all { curve.valueAt(it.toDouble())!! in -691.0 - 1e-6..1_875.0 + 1e-6 }, "each segment stays between its ends")
        assertEquals(listOf(3, 1), curve.segments().map { it.segments.size })
    }
}
