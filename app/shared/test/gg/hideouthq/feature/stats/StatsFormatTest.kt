package gg.hideouthq.feature.stats

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StatsFormatTest {
    @Test
    fun numbersReadAsTheDesignWritesThem() {
        assertEquals("1,124", StatsFormat.count(1124))
        assertEquals("41.2k", StatsFormat.thousands(41_210))
        assertEquals("209.4k", StatsFormat.thousands(209_400))
        assertEquals("0.9k", StatsFormat.thousands(900))
        assertEquals("5.3", StatsFormat.decimal(21.0 / 4), "half up, as KDA 5.25 reads 5.3")
        assertEquals("11.0", StatsFormat.decimal(11.0))
        assertEquals("58", StatsFormat.percentOf(37.0 / 64))
        assertEquals("34:12", StatsFormat.duration(2052))
        assertEquals("1:02:03", StatsFormat.duration(3723))
        assertEquals("186", StatsFormat.hours(186 * 3600L + 1200))
        assertEquals("41 236 118", StatsFormat.matchId(41_236_118))
        assertEquals("116 629 206", StatsFormat.matchId(116_629_206))
        assertEquals("76561198041273508", StatsFormat.steamId(81_007_780), "the Steam ID people see on their profile")
        assertEquals("+17", StatsFormat.signedPoints(17.2))
        assertEquals("−12", StatsFormat.signedPoints(-12.0))
    }

    @Test
    fun durationsKeepLatinDigitsWhereTheSystemWritesOthers() {
        val system = Locale.getDefault(Locale.Category.FORMAT)
        Locale.setDefault(Locale.Category.FORMAT, Locale.forLanguageTag("fa-IR"))
        try {
            assertEquals("34:12", StatsFormat.duration(2052))
            assertEquals("1:02:03", StatsFormat.duration(3723))
        } finally {
            Locale.setDefault(Locale.Category.FORMAT, system)
        }
    }

    @Test
    fun changesShowTheirSizeAndRoundingToNothingHidesThem() {
        assertEquals("4", StatsFormat.change(Change(4.2, ChangeUnit.Points)))
        assertEquals("3", StatsFormat.change(Change(-3.4, ChangeUnit.Percent)))
        assertEquals("0.3", StatsFormat.change(Change(0.26, ChangeUnit.Plain)))
        assertTrue(StatsFormat.isFlat(Change(0.4, ChangeUnit.Points)))
        assertFalse(StatsFormat.isFlat(Change(0.06, ChangeUnit.Plain)))
    }

    @Test
    fun standInsUseTwoLettersLikeTheDesign() {
        assertEquals("Se", heroInitials("Seven"))
        assertEquals("Mo", heroInitials("Mo & Krill"))
        assertEquals("Mc", heroInitials("McGinnis"))
        assertEquals("EC", itemInitials("Extra Charge"))
        assertEquals("EH", itemInitials("Extra Health"))
        assertEquals("Sp", itemInitials("Spellslinger"))
        assertEquals("Sp", ItemVisual(1, "Extra Spirit", ItemCategory.Spirit, code = "Sp").initials)
        assertEquals("M", PlayerVisual(1, "mira").initial)
        assertEquals("?", PlayerVisual(1, "  ").initial)
        assertEquals("X", PlayerVisual(1, "0xMoth").initial, "the first letter, as the design shows")
        assertEquals("4", PlayerVisual(1, "404").initial)
    }

    @Test
    fun aRankReadsItsTierNameAndSubRank() {
        assertEquals("Emissary 4", RankVisual(74, "Emissary").label)
        assertEquals(7, RankVisual(74, "Emissary").tier)
        assertEquals("Rank 11 2", RankVisual(112, null).label)
    }
}
