package gg.hideouthq.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SeasonTest {
    private val halloween = Season(MonthDay(10, 1), MonthDay(11, 7))
    private val winter = Season(MonthDay(12, 1), MonthDay(1, 7))

    @Test
    fun aSeasonIncludesItsFirstAndLastDays() {
        assertFalse(CalendarDate(2026, 9, 30) in halloween)
        assertTrue(CalendarDate(2026, 10, 1) in halloween)
        assertTrue(CalendarDate(2026, 11, 7) in halloween)
        assertFalse(CalendarDate(2026, 11, 8) in halloween)
    }

    @Test
    fun aSeasonCanRunOverTheNewYear() {
        assertTrue(CalendarDate(2026, 12, 31) in winter)
        assertTrue(CalendarDate(2027, 1, 1) in winter)
        assertFalse(CalendarDate(2026, 7, 1) in winter)
    }

    @Test
    fun theEndShownIsTheLastDayOfTheSeasonUnderWay() {
        assertEquals(CalendarDate(2026, 11, 7), halloween.end(CalendarDate(2026, 10, 6)))
        assertEquals(CalendarDate(2026, 11, 7), halloween.end(CalendarDate(2026, 11, 7)))
        assertEquals(CalendarDate(2027, 1, 7), winter.end(CalendarDate(2026, 12, 20)))
        assertEquals(CalendarDate(2027, 1, 7), winter.end(CalendarDate(2027, 1, 3)))
    }
}
