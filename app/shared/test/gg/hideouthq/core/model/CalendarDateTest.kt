package gg.hideouthq.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CalendarDateTest {
    @Test
    fun epochDayMatchesKnownDates() {
        assertEquals(0L, CalendarDate(1970, 1, 1).epochDay)
        assertEquals(20_732L, CalendarDate(2026, 10, 6).epochDay)
    }

    @Test
    fun daysUntilCountsAcrossMonths() {
        assertEquals(6L, CalendarDate(2026, 9, 30).daysUntil(CalendarDate(2026, 10, 6)))
        assertTrue(CalendarDate(2026, 10, 2) < CalendarDate(2026, 10, 4))
    }

    @Test
    fun epochDaysTurnBackIntoTheSameDates() {
        listOf(CalendarDate(1970, 1, 1), CalendarDate(2000, 2, 29), CalendarDate(2026, 10, 6), CalendarDate(1969, 12, 31)).forEach {
            assertEquals(it, CalendarDate.fromEpochDay(it.epochDay))
        }
    }

    @Test
    fun timestampsGiveTheirUtcDate() {
        assertEquals(CalendarDate(2026, 10, 3), CalendarDate.fromEpochSeconds(1_791_037_608))
        assertEquals(CalendarDate(1969, 12, 31), CalendarDate.fromEpochSeconds(-1))
    }
}
