package gg.hideouthq.core.crosshair

import gg.hideouthq.core.library.resourceBytes
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CrosshairCodesTest {
    private val mint = crosshair(CrosshairColor(0, 255, 140), length = 4.5, thickness = 1.5, gap = -6.0, opacity = 0.95, fixedGap = true, dotOpacity = 0.35, dotBorderOpacity = 0.6)

    @Test
    fun aCodeReadsBackTheCrosshairAndNameItWasMadeFrom() {
        val code = CrosshairCodes.encode(mint, "Mint cross")
        assertTrue(code.startsWith("HX1.") && code.none { it == '=' || it == '+' || it == '/' || it.isWhitespace() }, code)
        assertEquals(ImportedCrosshair("Mint cross", mint), CrosshairCodes.parse("  $code\n"))
        for (preset in CrosshairPreset.entries) assertEquals(ImportedCrosshair(preset.title, preset.crosshair), CrosshairCodes.parse(CrosshairCodes.encode(preset.crosshair, preset.title)))
    }

    @Test
    fun aCodeCarriesTheWholeReticle() {
        val whole = crosshair(
            CrosshairColor(10, 20, 30), length = 12.0, thickness = 3.0, gap = -2.0, opacity = 0.42, lineBorder = 3.0, lineBorderGap = 2.0, lineBorderOpacity = 0.33,
            dotSize = 9.0, dotOpacity = 0.81, dotBorder = 4.0, dotBorderGap = 1.0, dotBorderOpacity = 0.27, borderColor = CrosshairColor(200, 100, 50), fixedGap = true, collapse = true,
        )
        assertEquals(ImportedCrosshair("Whole", whole), CrosshairCodes.parse(CrosshairCodes.encode(whole, "Whole")))
    }

    @Test
    fun aCodeThatLeavesSettingsOutTakesTheGamesDefaultsAndOneWithNoneIsNothing() {
        fun code(json: String) = "HX1." + Base64.getUrlEncoder().withoutPadding().encodeToString(json.encodeToByteArray())
        assertEquals(ImportedCrosshair(null, Crosshair.GameDefault.with(gap = -3.0)), CrosshairCodes.parse(code("""{"pip_gap":"-3","later_setting":"9"}""")))
        assertNull(CrosshairCodes.parse(code("""{"name":"empty"}""")))
        assertNull(CrosshairCodes.parse("HX1.not base64!"))
    }

    @Test
    fun deadlockModManagersJsonReadsPlainOrAsBase64AsAConfigOrAWholeRow() {
        val config = """{"gap":-6,"width":1.5,"height":4.5,"pipOpacity":0.95,"dotOpacity":0.35,"dotOutlineOpacity":0.6,"color":{"r":0,"g":255,"b":140},"pipBorder":true,"pipGapStatic":true,"hero":"Default"}"""
        assertEquals(ImportedCrosshair(null, mint), CrosshairCodes.parse(config))
        assertEquals(ImportedCrosshair(null, mint), CrosshairCodes.parse(Base64.getEncoder().encodeToString(config.encodeToByteArray())))
        assertEquals(ImportedCrosshair("Mint cross", mint), CrosshairCodes.parse("""{"id":"crosshair_x","name":"Mint cross","config":$config,"likes":0}"""))
        assertNull(CrosshairCodes.parse("""{"color":{"r":0,"g":300,"b":0}}"""), "a colour out of range is not a crosshair")
        assertNull(CrosshairCodes.parse("""{"unrelated":true}"""))
    }

    @Test
    fun deadlockModManagersKeyValueStringReadsWithPipBorderOrPipOutlineBorderAlike() {
        val string = "citadel_crosshair_color_r 0; citadel_crosshair_color_g 255; citadel_crosshair_color_b 140; citadel_crosshair_pip_border true; " +
            "citadel_crosshair_pip_gap_static true; citadel_crosshair_pip_opacity 0.95; citadel_crosshair_pip_width 1.5; citadel_crosshair_pip_height 4.5; " +
            "citadel_crosshair_pip_gap -6; citadel_crosshair_dot_opacity 0.35; citadel_crosshair_dot_outline_opacity 0.6;"
        assertEquals(ImportedCrosshair(null, mint), CrosshairCodes.parse(string))
        assertEquals(ImportedCrosshair(null, mint), CrosshairCodes.parse(string.replace("pip_border true", "pip_outline_border 1")))
    }

    @Test
    fun pastedLinesQuotedOrNotWithCommentsAndOtherCommandsRead() {
        val pasted = """
            |// my crosshair
            |citadel_crosshair_pip_gap "-6"
            |citadel_crosshair_color_g 200 // a bit darker
            |citadel_crosshair_dot_size 8
            |fps_max 240
        """.trimMargin()
        assertEquals(ImportedCrosshair(null, Adjustable.DotSize.set(Crosshair.GameDefault.with(gap = -6.0, color = CrosshairColor(255, 200, 255)), 8.0)), CrosshairCodes.parse(pasted))
    }

    @Test
    fun aWholeAutoexecCfgReadsTheCrosshairItRunsLast() {
        val file = resourceBytes("dmm/autoexec.cfg").decodeToString() + "\ncitadel_crosshair_pip_gap 2\nbind F5 kill\n"
        assertEquals(ConvarNumber.of(2.0), CrosshairCodes.parse(file)?.crosshair?.lines?.gap)
        assertEquals(CrosshairColor(0, 255, 0), CrosshairCodes.parse(file)?.crosshair?.color, "the rest comes from DMM's block above it")
    }

    @Test
    fun textWithNoCrosshairSettingIsNothing() {
        for (text in listOf("", "   ", "hello", "fps_max 240", "// citadel_crosshair_pip_gap 4", "{\"name\":\"x\"}", "citadel_crosshair_pip_gap")) assertNull(CrosshairCodes.parse(text), text)
    }
}
