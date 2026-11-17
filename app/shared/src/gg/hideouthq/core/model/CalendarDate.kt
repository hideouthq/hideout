package gg.hideouthq.core.model

import kotlin.time.Clock

/** A plain calendar date (UTC). The catalogue needs ordering and day counts, not time zones. */
data class CalendarDate(val year: Int, val month: Int, val day: Int) : Comparable<CalendarDate> {
    init {
        require(month in 1..12 && day in 1..31) { "Invalid date $year-$month-$day" }
    }

    /** Days since 1970-01-01 (proleptic Gregorian). */
    val epochDay: Long
        get() {
            val y = if (month <= 2) year - 1 else year
            val era = (if (y >= 0) y else y - 399) / 400
            val yearOfEra = y - era * 400
            val monthIndex = (month + 9) % 12
            val dayOfYear = (153 * monthIndex + 2) / 5 + day - 1
            val dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
            return era * 146_097L + dayOfEra - 719_468L
        }

    /** Unix seconds at the start of this UTC day. */
    val epochSeconds: Long get() = epochDay * SecondsPerDay

    fun daysUntil(other: CalendarDate): Long = other.epochDay - epochDay

    override fun compareTo(other: CalendarDate): Int = epochDay.compareTo(other.epochDay)

    companion object {
        private const val SecondsPerDay = 86_400L

        /** The inverse of [epochDay]. */
        fun fromEpochDay(epochDay: Long): CalendarDate {
            val shifted = epochDay + 719_468L
            val era = (if (shifted >= 0) shifted else shifted - 146_096L) / 146_097L
            val dayOfEra = shifted - era * 146_097L
            val yearOfEra = (dayOfEra - dayOfEra / 1_460 + dayOfEra / 36_524 - dayOfEra / 146_096) / 365
            val dayOfYear = dayOfEra - (365 * yearOfEra + yearOfEra / 4 - yearOfEra / 100)
            val monthIndex = (5 * dayOfYear + 2) / 153
            val day = (dayOfYear - (153 * monthIndex + 2) / 5 + 1).toInt()
            val month = (if (monthIndex < 10) monthIndex + 3 else monthIndex - 9).toInt()
            val year = (yearOfEra + era * 400 + if (month <= 2) 1 else 0).toInt()
            return CalendarDate(year, month, day)
        }

        /** The UTC date of a Unix timestamp in seconds. */
        fun fromEpochSeconds(seconds: Long): CalendarDate = fromEpochDay(seconds.floorDiv(SecondsPerDay))

        fun today(clock: Clock = Clock.System): CalendarDate = fromEpochSeconds(clock.now().epochSeconds)
    }
}
