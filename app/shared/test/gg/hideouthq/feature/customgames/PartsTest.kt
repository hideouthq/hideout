package gg.hideouthq.feature.customgames

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PartsTest {
    @Test
    fun megabytesKeepOneDecimalUnderTen() {
        assertEquals("7.7", megabytes(7_700_000))
        assertEquals("12", megabytes(12_048_000))
        assertEquals("0", megabytes(0))
        assertEquals("2", megabytes(2_000_000))
    }

    @Test
    fun tryAgainIsOfferedOnlyWhereItCanHelp() {
        assertTrue(tryAgainHelps(Problem.TookTooLong))
        assertFalse(tryAgainHelps(Problem.InviteNotWorking), "a dead invite has nothing to try again")
    }

    @Test
    fun aLongPathKeepsItsEndsAroundAnEllipsis() {
        val path = "/home" + "a".repeat(200) + "XYZ"
        assertEquals("/homeaaaaa…aaaaaaaXYZ", middleTruncate(path))
        assertEquals("/mode", middleTruncate("/mode"))
    }
}
