package gg.hideouthq.feature.stats.data

import gg.hideouthq.core.data.deadlockstats.DateWindow
import gg.hideouthq.core.data.deadlockstats.MatchModeFilter
import gg.hideouthq.core.data.deadlockstats.RankedSeason
import gg.hideouthq.feature.stats.Moment
import gg.hideouthq.feature.stats.StatsMode
import gg.hideouthq.feature.stats.StatsPeriod
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

internal fun StatsMode.toFilter(): MatchModeFilter = when (this) {
    StatsMode.All -> MatchModeFilter.All
    StatsMode.Ranked -> MatchModeFilter.Ranked
    StatsMode.Normal -> MatchModeFilter.Normal
}

/**
 * A period as requests are keyed by it: the same through every hour of a rolling period, and this season by its start,
 * so a cached answer carries over the hour it was asked in and is refreshed on the cache's own schedule.
 */
data class PeriodKey(val period: StatsPeriod, val seasonStart: Long? = null) {
    init {
        require((period == StatsPeriod.Season) == (seasonStart != null)) { "only this season has a start, and it needs one" }
    }

    /** The start-time window at [now]. It ends at the next whole hour, so a match that started a minute ago counts. */
    fun window(now: Long): DateWindow {
        val end = (now / 3600 + 1) * 3600
        fun last(days: Int) = DateWindow((end - days * 86_400L).toInt(), end.toInt())
        return when (period) {
            StatsPeriod.Week -> last(7)
            StatsPeriod.Month -> last(30)
            StatsPeriod.Season -> DateWindow(seasonStart!!.toInt(), end.toInt())
            StatsPeriod.AllTime -> DateWindow()
        }
    }
}

internal fun StatsPeriod.key(now: Long, seasons: List<RankedSeason>?): PeriodKey? = when (this) {
    StatsPeriod.Season -> seasons?.firstNotNullOfOrNull { it.startContaining(now) }?.let { PeriodKey(this, it) }
    else -> PeriodKey(this)
}

internal class StatsClock(val now: Long, private val zone: ZoneId) {
    private val today: LocalDate = date(now)

    fun date(epochSeconds: Long): LocalDate = Instant.ofEpochSecond(epochSeconds).atZone(zone).toLocalDate()

    private fun at(epochSeconds: Long): ZonedDateTime = Instant.ofEpochSecond(epochSeconds).atZone(zone)

    fun ago(epochSeconds: Long, older: (Long) -> Moment = ::dayOf): Moment {
        val seconds = (now - epochSeconds).coerceAtLeast(0)
        return when {
            seconds < 60 -> Moment.JustNow
            seconds < 3600 -> Moment.MinutesAgo((seconds / 60).toInt())
            date(epochSeconds) == today -> Moment.HoursAgo((seconds / 3600).toInt())
            else -> older(epochSeconds)
        }
    }

    fun rowTime(epochSeconds: Long): Moment = ago(epochSeconds) { clock(it) }

    fun listTime(epochSeconds: Long): Moment = ago(epochSeconds) { if (date(it) == today.minusDays(1)) clock(it) else dayOf(it) }

    fun clock(epochSeconds: Long): Moment = at(epochSeconds).let { Moment.ClockTime(it.hour, it.minute) }

    fun dayOf(epochSeconds: Long): Moment {
        val day = date(epochSeconds)
        val back = ChronoUnit.DAYS.between(day, today)
        return when {
            back == 0L -> Moment.Today
            back == 1L -> Moment.Yesterday
            back in 2..6 -> Moment.Weekday(day.dayOfWeek.value)
            else -> Moment.Date(day.dayOfMonth, day.monthValue)
        }
    }

    fun heading(epochSeconds: Long): Moment {
        val day = date(epochSeconds)
        return when (ChronoUnit.DAYS.between(day, today)) {
            0L -> Moment.Today
            1L -> Moment.Yesterday
            else -> Moment.WeekdayDate(day.dayOfWeek.value, day.dayOfMonth, day.monthValue)
        }
    }

    fun started(epochSeconds: Long): Moment {
        val time = at(epochSeconds)
        val day = when (ChronoUnit.DAYS.between(time.toLocalDate(), today)) {
            0L -> Moment.Today
            1L -> Moment.Yesterday
            else -> Moment.Date(time.dayOfMonth, time.monthValue)
        }
        return Moment.At(day, time.hour, time.minute)
    }

    fun shortDate(epochSeconds: Long): Moment = date(epochSeconds).let { Moment.Date(it.dayOfMonth, it.monthValue) }

    fun monthYear(epochSeconds: Long): Moment = date(epochSeconds).let { Moment.MonthYear(it.monthValue, it.year) }

    fun isToday(epochSeconds: Long) = date(epochSeconds) == today
}
