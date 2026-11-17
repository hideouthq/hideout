package gg.hideouthq.feature.stats.data

import gg.hideouthq.core.data.deadlockstats.DateWindow
import gg.hideouthq.core.data.deadlockstats.RankedSeason
import gg.hideouthq.core.data.deadlockstats.SeasonInterval
import gg.hideouthq.feature.stats.AxisLabel
import gg.hideouthq.feature.stats.Moment
import gg.hideouthq.feature.stats.StatsPeriod
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class StatsTimeTest {
    private val zone = ZoneId.of("Europe/London")
    private fun at(text: String) = LocalDateTime.parse(text).atZone(zone).toEpochSecond()
    private val now = at("2026-10-13T16:32:10")
    private val clock = StatsClock(now, zone)

    @Test
    fun periodsKeepTheirKeyThroughTheHoursAndEndAtTheNextOne() {
        val key = assertNotNull(StatsPeriod.Month.key(now, null))
        assertEquals(key, StatsPeriod.Month.key(now + 5 * 3600, null), "requests keep their answer as the hour changes")
        val month = key.window(now)
        assertEquals(at("2026-10-13T17:00:00").toInt(), month.toExclusive)
        assertEquals(30 * 86_400L, month.lengthSeconds)
        assertEquals(month, key.window(now + 1_000), "a refresh twenty minutes later asks the same")
        assertEquals(7 * 86_400L, PeriodKey(StatsPeriod.Week).window(now).lengthSeconds)
        assertEquals(DateWindow(), PeriodKey(StatsPeriod.AllTime).window(now))
        assertNull(StatsPeriod.Season.key(now, null), "a season without dates is unknown, not guessed")
        val season = RankedSeason("Beta Season 1", 8, listOf(SeasonInterval(at("2026-07-30T18:00:00"), at("2026-11-05T22:00:00"))))
        assertEquals(PeriodKey(StatsPeriod.Season, at("2026-07-30T18:00:00")), StatsPeriod.Season.key(now, listOf(season)), "this season is keyed by its start")
        assertNull(StatsPeriod.Season.key(now, listOf(season.copy(intervals = listOf(SeasonInterval(0, 100))))), "no current season")
    }

    @Test
    fun timesAreWordedRelativeTodayThenByTheClockOrDay() {
        assertEquals(Moment.JustNow, clock.ago(now - 20))
        assertEquals(Moment.MinutesAgo(12), clock.ago(now - 12 * 60))
        assertEquals(Moment.HoursAgo(2), clock.rowTime(at("2026-10-13T14:32:00")))
        assertEquals(Moment.ClockTime(21, 40), clock.rowTime(at("2026-10-12T21:40:00")))
        assertEquals(Moment.ClockTime(21, 40), clock.listTime(at("2026-10-12T21:40:00")), "yesterday by the clock in the overview")
        assertEquals(Moment.Weekday(7), clock.listTime(at("2026-10-11T22:18:00")), "older reads its day")
        assertEquals(Moment.Date(2, 10), clock.dayOf(at("2026-10-02T20:00:00")))
        assertEquals(Moment.Today, clock.heading(at("2026-10-13T09:00:00")))
        assertEquals(Moment.Yesterday, clock.heading(at("2026-10-12T09:00:00")))
        assertEquals(Moment.WeekdayDate(7, 11, 10), clock.heading(at("2026-10-11T09:00:00")))
        assertEquals(Moment.At(Moment.Today, 14, 32), clock.started(at("2026-10-13T14:32:00")))
        assertEquals(Moment.MonthYear(2, 2025), clock.monthYear(at("2025-02-14T12:00:00")))
    }

    @Test
    fun theDayBoundaryIsTheUsersOwnNotUtc() {
        val tokyo = StatsClock(LocalDateTime.parse("2026-10-13T00:30:00").toEpochSecond(ZoneOffset.ofHours(9)), ZoneId.of("Asia/Tokyo"))
        val lateEvening = LocalDateTime.parse("2026-10-12T23:50:00").toEpochSecond(ZoneOffset.ofHours(9))
        assertEquals(Moment.Yesterday, tokyo.heading(lateEvening))
    }

    @Test
    fun rankAxisLabelsStepAWeekAcrossThirtyDaysAndEndToday() {
        val end = now
        val start = end - 30 * 86_400L
        val labels = axisLabels(start, end, clock)
        assertEquals(listOf(0f, 7f / 30, 14f / 30, 21f / 30, 1f), labels.map { it.fraction })
        assertEquals(AxisLabel(1f, Moment.Today), labels.last())
        assertEquals(Moment.Date(13, 9), labels.first().moment)
    }
}
