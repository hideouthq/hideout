package gg.hideouthq.core.crosshair

import gg.hideouthq.core.autoexec.mine
import kotlin.test.Test
import kotlin.test.assertEquals

class CrosshairLinesTest {
    private val dmmBlock = listOf(
        "citadel_crosshair_color_r \"0\"", "citadel_crosshair_color_g \"255\"", "citadel_crosshair_color_b \"0\"",
        "citadel_crosshair_pip_border \"false\"", "citadel_crosshair_pip_gap_static \"false\"", "citadel_crosshair_pip_opacity \"1\"",
        "citadel_crosshair_pip_width \"1.5\"", "citadel_crosshair_pip_height \"3\"", "citadel_crosshair_pip_gap \"-7\"",
        "citadel_crosshair_dot_opacity \"0\"", "citadel_crosshair_dot_outline_opacity \"0\"",
    )

    @Test
    fun deadlockModManagersBlockReadsAsItsElevenSettingsOnTheGamesDefaults() {
        val dmm = setOf(
            CrosshairSetting.ColorR, CrosshairSetting.ColorG, CrosshairSetting.ColorB, CrosshairSetting.OutlineBorder, CrosshairSetting.GapStatic, CrosshairSetting.Opacity,
            CrosshairSetting.Width, CrosshairSetting.Height, CrosshairSetting.Gap, CrosshairSetting.DotOpacity, CrosshairSetting.DotOutlineOpacity,
        )
        assertEquals(ReadCrosshair(mine, dmm), CrosshairLines.read(dmmBlock), "the owner's DMM block reads whole")
    }

    @Test
    fun pipBorderIsABorderOfOneOrNoneOnlyWhenPipOutlineBorderIsNotThere() {
        val outlined = dmmBlock.map { it.replace("pip_border \"false\"", "pip_border \"true\"") }
        assertEquals(ConvarNumber.of(1.0), CrosshairLines.read(outlined).crosshair.lines.border.size)
        assertEquals(ConvarNumber.Zero, CrosshairLines.read(outlined + "citadel_crosshair_pip_outline_border \"0\"").crosshair.lines.border.size, "the variable the game has wins, before it or after")
        assertEquals(ConvarNumber.of(3.0), CrosshairLines.read(listOf("citadel_crosshair_pip_outline_border 3") + outlined).crosshair.lines.border.size)
    }

    @Test
    fun everyOneOfTheTwentyReads() {
        val whole = crosshair(
            CrosshairColor(10, 20, 30), length = 12.0, thickness = 3.0, gap = -2.0, opacity = 0.42, lineBorder = 3.0, lineBorderGap = 2.0, lineBorderOpacity = 0.33,
            dotSize = 9.0, dotOpacity = 0.81, dotBorder = 4.0, dotBorderGap = 1.0, dotBorderOpacity = 0.27, borderColor = CrosshairColor(200, 100, 50), fixedGap = true, collapse = true,
        )
        val lines = CrosshairSetting.entries.map { "${it.convar} \"${it.format(whole)}\"" }
        assertEquals(ReadCrosshair(whole, CrosshairSetting.entries.toSet()), CrosshairLines.read(lines))
    }

    @Test
    fun quotedAndUnquotedValuesSemicolonsAndCommentsReadAsTheConsoleRunsThem() {
        val read = CrosshairLines.read(
            listOf(
                "citadel_crosshair_pip_gap 5",
                "  CITADEL_CROSSHAIR_COLOR_G \"128\" // greener",
                "citadel_crosshair_pip_width 3; citadel_crosshair_pip_height \"8\"",
                "citadel_crosshair_pip_gap \"-2\"",
                "citadel_crosshair_outline_color_r \"40\"",
            ),
        )
        assertEquals(Crosshair.GameDefault.with(gap = -2.0, color = CrosshairColor(255, 128, 255), thickness = 3.0, length = 8.0).copy(borderColor = CrosshairColor(40, 0, 0)), read.crosshair, "the last value wins")
        assertEquals(setOf(CrosshairSetting.Gap, CrosshairSetting.ColorG, CrosshairSetting.Width, CrosshairSetting.Height, CrosshairSetting.OutlineColorR), read.set)
    }

    @Test
    fun aLineThatIsNotWhollyCrosshairSettingsSetsNothing() {
        val read = CrosshairLines.read(listOf("// Preset: Green dot", "citadel_crosshair_color_r 300", "citadel_crosshair_pip_gap 4; fps_max 240", "", "citadel_crosshair_pip_opacity \"0.85\""))
        assertEquals(ReadCrosshair(Crosshair.GameDefault.with(opacity = 0.85), setOf(CrosshairSetting.Opacity)), read)
    }
}
