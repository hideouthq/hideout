package gg.hideouthq.core.crosshair

import kotlin.test.Test
import kotlin.test.assertEquals

class CrosshairTest {
    @Test
    fun aNumberIsWrittenAsTheShortestTextThatReadsBackEqual() {
        val written = mapOf(3.0 to "3", 1.5 to "1.5", -7.0 to "-7", 0.35 to "0.35", -0.5 to "-0.5", 0.0 to "0", 255.0 to "255", 0.818933 to "0.8189")
        for ((value, text) in written) {
            assertEquals(text, ConvarNumber.of(value).toString(), "$value")
            assertEquals(ConvarNumber.of(value), ConvarNumber.parse(text), "$text reads back as it was written")
        }
        assertEquals(ConvarNumber.of(0.15), ConvarNumber.of(0.05 + 0.1), "a float's error does not make two values differ")
        assertEquals(listOf(null, null, null), listOf("", "true", "NaN").map(ConvarNumber::parse))
        assertEquals(ConvarNumber.of(0.5), ConvarNumber.parse(" .5 "))
    }

    @Test
    fun aNumberBeyondTenThousandEitherWayIsNoCrosshairValue() {
        assertEquals(listOf(ConvarNumber.of(10_000.0), ConvarNumber.of(-10_000.0)), listOf("10000", "-10000").map(ConvarNumber::parse))
        assertEquals(listOf<ConvarNumber?>(null, null, null), listOf("10000.5", "-1e5", "1e300").map(ConvarNumber::parse))
        val read = CrosshairLines.read(listOf("citadel_crosshair_pip_gap 1e300", "citadel_crosshair_pip_width 3"))
        assertEquals(setOf(CrosshairSetting.Width), read.set, "a line beyond it sets nothing")
    }

    @Test
    fun aSliderSnapsToItsStepAndStaysInItsRangeAndOnlyItsOwnSettingMoves() {
        val wide = Crosshair.GameDefault.with(length = 100.0, gap = -60.0)
        assertEquals(ConvarNumber.of(100.0), Adjustable.Length.get(wide), "a value out of range shows as it is")
        val moved = Adjustable.Gap.set(wide, -7.4)
        assertEquals(wide.with(gap = -7.0), moved, "only the gap moves, the length stays out of range")
        assertEquals(ConvarNumber.of(50.0), Adjustable.Length.get(Adjustable.Length.set(wide, 51.0)))
        assertEquals(ConvarNumber.of(1.0), Adjustable.Thickness.get(Adjustable.Thickness.set(wide, -2.0)), "the game's lines are a pixel at least")
        assertEquals(ConvarNumber.of(4.0), Adjustable.Length.get(Adjustable.Length.set(wide, 4.3)))
        assertEquals(ConvarNumber.of(0.35), Adjustable.Opacity.get(Adjustable.Opacity.set(wide, 0.3499999)))
        assertEquals("0.36", Adjustable.DotOpacity.get(Adjustable.DotOpacity.set(wide, 0.364)).toString())
        assertEquals(ConvarNumber.of(100.0), Adjustable.DotBorderGap.get(Adjustable.DotBorderGap.set(wide, 250.0)))
    }

    @Test
    fun aColourReadsAndWritesAsHex() {
        assertEquals("#00FF8C", CrosshairColor(0, 255, 140).hex)
        assertEquals(CrosshairColor(0, 255, 140), CrosshairColor.parseHex("#00ff8c"))
        assertEquals(CrosshairColor(255, 230, 0), CrosshairColor.parseHex(" FFE600 "))
        assertEquals(listOf(null, null, null), listOf("#0f8", "#00FF8", "#00GG00").map(CrosshairColor::parseHex))
    }
}
