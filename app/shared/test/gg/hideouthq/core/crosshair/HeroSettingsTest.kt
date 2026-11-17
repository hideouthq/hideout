package gg.hideouthq.core.crosshair

import gg.hideouthq.core.library.resourceBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class HeroSettingsTest {
    /** The game's own file as Steam Cloud keeps it: LF, no last newline. */
    private val cloud = resourceBytes("deadlock/citadel_hero_settings.lst").decodeToString()

    /** The same file as the game keeps it in its own folder: CRLF. */
    private val game = cloud.replace("\n", "\r\n")

    private val cyan = crosshair(
        CrosshairColor(0, 255, 255), length = 3.0, thickness = 1.0, gap = -7.0, opacity = 1.0, lineBorder = 0.0, lineBorderGap = 3.0, lineBorderOpacity = 0.25,
        dotSize = 6.0, dotOpacity = 0.95, dotBorder = 1.0, dotBorderGap = 2.0, dotBorderOpacity = 0.4, borderColor = CrosshairColor(12, 34, 56), fixedGap = true, collapse = true,
    )

    private fun parse(text: String) = checkNotNull(HeroSettings.parse(text.encodeToByteArray()))

    private fun expected(text: String, crosshair: Crosshair) = CrosshairSetting.entries.fold(text) { now, setting ->
        now.replace(Regex("(\\t${setting.key} = )\"[^\"]*\""), "$1\"${setting.format(crosshair)}\"")
    }

    @Test
    fun theGamesFileHoldsTheGamesDefaultCrosshair() {
        assertEquals(Crosshair.GameDefault, parse(cloud).crosshair)
        assertEquals(Crosshair.GameDefault, parse(game).crosshair)
    }

    @Test
    fun aCrosshairChangesTheTwentyValuesAndNotOneOtherByteOfEitherCopy() {
        for (text in listOf(cloud, game)) {
            val written = parse(text).with(cyan)
            assertEquals(expected(text, cyan), written.render().decodeToString(), "only the values change, the line endings kept")
            assertEquals(cyan, written.crosshair)
            assertEquals(text, written.with(Crosshair.GameDefault).render().decodeToString(), "the defaults put back give the game's bytes")
        }
        val values = parse(cloud).with(cyan).render().decodeToString().lines().filter { "crosshair_" in it }
        assertEquals(
            listOf(
                "\t\tcrosshair_themed = \"false\"", "\t\tcrosshair_pip_gap_static = \"true\"", "\t\tcrosshair_pip_gap_collapse = \"true\"", "\t\tcrosshair_pip_width = \"1\"",
                "\t\tcrosshair_pip_height = \"3\"", "\t\tcrosshair_pip_gap = \"-7\"", "\t\tcrosshair_pip_opacity = \"1\"", "\t\tcrosshair_pip_outline_border = \"0\"",
            ),
            values.take(8),
            "written as the game writes them, quoted, numbers plain, crosshair_themed as it was",
        )
    }

    @Test
    fun theSameCrosshairWritesNothing() {
        val settings = parse(game)
        assertSame(settings, settings.with(Crosshair.GameDefault))
        val spelled = parse(game.replace("\"0.5\"", "\"0.50\""))
        assertEquals(game.replace("\"0.5\"", "\"0.50\""), spelled.with(Crosshair.GameDefault).render().decodeToString(), "0.50 is 0.5, so its spelling stays")
    }

    @Test
    fun unknownKeysCommentsAndEveryHerosOverrideSurvive() {
        val text = cloud
            .replace("\t\tmouse_sensitivity = \"0.65\"\n", "\t\tmouse_sensitivity = \"0.65\"\n\t\tcrosshair_future_setting = \"3\"\n\t\t// kept\n\t\tsomething_new = [ 1, 2 ]\n")
            .replace("\theroes = \n\t{\n\t}", "\theroes = \n\t{\n\t\thero_atlas = \n\t\t{\n\t\t\tcrosshair_pip_gap = \"9\"\n\t\t}\n\t}")
        val written = parse(text).with(cyan).render().decodeToString()
        assertEquals(expected(text.replace("\t\t\tcrosshair_pip_gap = \"9\"", "HERO"), cyan).replace("HERO", "\t\t\tcrosshair_pip_gap = \"9\""), written)
    }

    @Test
    fun aSettingTheBlockLacksGoesInAtItsEndIndentedAndEndedAsItsLines() {
        val lacking = game.replace("\t\tcrosshair_pip_gap_collapse = \"false\"\r\n", "").replace(Regex("\\t\\tcrosshair_outline_color_[rgb] = \"0\"\\r\\n"), "")
        val written = parse(lacking).with(cyan).render().decodeToString()
        val added = "\t\tcrosshair_pip_gap_collapse = \"true\"\r\n\t\tcrosshair_outline_color_r = \"12\"\r\n\t\tcrosshair_outline_color_g = \"34\"\r\n\t\tcrosshair_outline_color_b = \"56\"\r\n"
        assertEquals(expected(lacking, cyan).replace("\t}\r\n\theroes", "$added\t}\r\n\theroes"), written)
        assertEquals(cyan, parse(written).crosshair)
        assertEquals(Crosshair.GameDefault, parse(lacking).crosshair, "a setting not there is the game's")
    }

    @Test
    fun aBlockThatClosesOnTheLineOfItsLastValueTakesTheSettingsItLacksBeforeItsBrace() {
        val compact = "{ base = { crosshair_pip_gap = \"4\" mouse_sensitivity = \"0.65\" } heroes = { } }"
        val written = parse(compact).with(cyan).render().decodeToString()
        assertEquals(cyan, parse(written).crosshair)
        assertTrue(written.startsWith("{ base = { crosshair_pip_gap = \"-7\" mouse_sensitivity = \"0.65\" \n\tcrosshair_pip_gap_static = \"true\"\n"), written)
        assertTrue(written.endsWith("\tcrosshair_outline_color_b = \"56\"\n} heroes = { } }"), written)
    }

    @Test
    fun aFileNotSavedYetIsMadeInTheGamesFormat() {
        val made = HeroSettings.New.with(cyan).render().decodeToString()
        val header = cloud.lines().first()
        assertEquals(header, made.lines().first())
        assertEquals(cyan, parse(made).crosshair)
        val withoutOthers = cloud.lines().filterNot { it.trim().startsWith("crosshair_themed") || it.trim().startsWith("mouse_") || it.trim().startsWith("cancel_") }.joinToString("\n")
        assertEquals(expected(withoutOthers, cyan), made, "the game's layout, holding only the crosshair")
    }

    @Test
    fun valuesReadQuotedOrBareAndAValueThatIsNoneOfASettingsIsTheGames() {
        val bare = cloud.replace("crosshair_pip_gap = \"4\"", "crosshair_pip_gap = -3").replace("crosshair_pip_width = \"2\"", "crosshair_pip_width = \"wide\"")
        assertEquals(Crosshair.GameDefault.with(gap = -3.0), parse(bare).crosshair)
    }

    @Test
    fun textThatIsNotTheGamesSettingsIsNeverTakenForThem() {
        for (text in listOf("", "not kv3", "{ base = { crosshair_pip_gap = \"4\" } } trailing", "{ heroes = { } }", "{ base = \"flat\" }", "{ base = { crosshair_pip_gap = \"4\"")) {
            assertNull(HeroSettings.parse(text.encodeToByteArray()), text)
        }
    }
}
