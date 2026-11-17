package gg.hideouthq.core.autoexec

import gg.hideouthq.core.library.AutoexecTakeover
import gg.hideouthq.core.library.resourceBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AutoexecEditTest {
    private fun parse(text: String) = AutoexecDocument.parse(text.encodeToByteArray())

    private fun AutoexecDocument.text() = render().decodeToString()

    private fun AutoexecEdit.on(text: String): String? = applyTo(parse(text))?.text()

    private val section = "${SectionMarkers.GrimoireCommands.start}\nsensitivity 1.6\n${SectionMarkers.GrimoireCommands.end}\n"

    @Test
    fun aValueChangesAloneItsQuotesAndCommentKept() {
        assertEquals("fps_max 300 // cap\r\nvolume \"0.2\"\r\n", AutoexecEdit.SetValue(CommandRef("fps_max", 0, "fps_max 240 // cap"), "300").on("fps_max 240 // cap\r\nvolume \"0.2\"\r\n"))
        assertEquals("fps_max 240 // cap\r\nvolume \"0.7\"\r\n", AutoexecEdit.SetValue(CommandRef("volume", 0, "volume \"0.2\""), "0.7").on("fps_max 240 // cap\r\nvolume \"0.2\"\r\n"))
        assertEquals("a 1\nfps_max 1\nfps_max 3", AutoexecEdit.SetValue(CommandRef("fps_max", 1, "fps_max 2"), "3").on("a 1\nfps_max 1\nfps_max 2"), "the second line of a name, its missing last newline kept")
        assertNull(AutoexecEdit.SetValue(CommandRef("fps_max", 1, "fps_max 2"), "3").on("fps_max 1\n"), "a line that is gone does not fit")
    }

    @Test
    fun aValueThatWouldTurnAnOffLineIntoACommentLeavesTheLineAsItIs() {
        val file = "// fps_max 240\nvolume 1\n"
        val ref = CommandRef("fps_max", 0, "// fps_max 240")
        for (value in listOf("", "1 2", "\"30")) assertEquals(file, AutoexecEdit.SetValue(ref, value).on(file), "'$value' would make the line a plain comment")
        assertEquals("// fps_max 144\nvolume 1\n", AutoexecEdit.SetValue(ref, "144").on(file))
        assertEquals("fps_max\nvolume 1\n", AutoexecEdit.SetValue(CommandRef("fps_max", 0, "fps_max 240"), "").on("fps_max 240\nvolume 1\n"), "a line that runs is a command with no value too")
    }

    @Test
    fun editingALineThatIsNotUtf8KeepsItsOtherBytes() {
        fun ansi(text: String) = text.toByteArray(Charsets.ISO_8859_1)
        val document = AutoexecDocument.parse(ansi("fps_max 144 // vitesse \u00E9\n"))
        val row = document.commands.single()
        assertEquals(listOf("fps_max 144 // vitesse \u00E9"), document.editorText.lines)
        assertContentEquals(ansi("fps_max 240 // vitesse \u00E9\n"), assertNotNull(AutoexecEdit.SetValue(row.ref, "240").applyTo(document)).render())
        assertContentEquals(ansi("// fps_max 144 // vitesse \u00E9\n"), assertNotNull(AutoexecEdit.SetOn(row.ref, on = false).applyTo(document)).render())
        val typed = assertNotNull(TextHunk.between(document.editorText, "fps_max 1440 // vitesse \u00E9"))
        assertContentEquals(ansi("fps_max 1440 // vitesse \u00E9\n"), assertNotNull(typed.applyTo(document)).render())
    }

    @Test
    fun turningALineOffThenOnGivesItBackByteForByte() {
        val file = "  cl_showfps 1 // corner\r\nfps_max 240"
        val off = assertNotNull(AutoexecEdit.SetOn(CommandRef("cl_showfps", 0, "  cl_showfps 1 // corner"), on = false).on(file))
        assertEquals("  // cl_showfps 1 // corner\r\nfps_max 240", off)
        assertEquals(off, AutoexecEdit.SetOn(CommandRef("cl_showfps", 0, "  // cl_showfps 1 // corner"), on = false).on(off), "turning it off again changes nothing")
        assertEquals(file, AutoexecEdit.SetOn(CommandRef("cl_showfps", 0, "  // cl_showfps 1 // corner"), on = true).on(off))
    }

    @Test
    fun removingTakesOneLineAndTheLibrarysCheckTakesEveryLineOfTheName() {
        val file = "fps_max 240\n// fps_max 144\nvolume 1\nFPS_MAX 60\n"
        assertEquals("// fps_max 144\nvolume 1\nFPS_MAX 60\n", AutoexecEdit.Remove(CommandRef("fps_max", 0, "fps_max 240")).on(file))
        assertEquals("volume 1\n", AutoexecEdit.RemoveAll("fps_max").on(file))
        assertEquals(file, AutoexecEdit.RemoveAll("rate").on(file))
    }

    @Test
    fun aRowsEditFindsItsLineByWhatItHoldsSoItHitsThatLineOnceAndOnlyIt() {
        val file = "fps_max 240\nvolume 1\nfps_max 144\n"
        val (first, _, last) = parse(file).commands.map { it.ref }
        val removed = assertNotNull(AutoexecEdit.Remove(first).on(file))
        assertEquals("volume 1\nfps_max 144\n", removed)
        assertEquals(removed, AutoexecEdit.Remove(first).on(removed), "removing it again takes no other line")
        val above = "fps_max 60\n$file"
        assertEquals("fps_max 60\nfps_max 240\nvolume 1\nfps_max 30\n", AutoexecEdit.SetValue(last, "30").on(above), "a line of the name added above it moves nothing")
        assertEquals("fps_max 60\nvolume 1\nfps_max 144\n", AutoexecEdit.Remove(first).on(above))
        val set = assertNotNull(AutoexecEdit.SetValue(last, "30").on(file))
        assertEquals(set, AutoexecEdit.SetValue(last, "30").on(set), "a value set already fits as it is")
        assertEquals(set.replace("fps_max 30", "// fps_max 30"), AutoexecEdit.SetOn(parse(set).commands.last().ref, on = false).on(set))
    }

    @Test
    fun addingLandsAfterTheLastCommandBeforeAnotherManagersSectionAndTwiceGivesOneLine() {
        val file = "${AutoexecDocument.Header}\nvolume 1\n$section// my notes\n"
        val added = assertNotNull(AutoexecEdit.Add("fps_max", "240").on(file))
        assertEquals("${AutoexecDocument.Header}\nvolume 1\nfps_max 240\n$section// my notes\n", added)
        assertEquals(added, AutoexecEdit.Add("fps_max", "240").on(added))
        assertEquals("${AutoexecDocument.Header}\nfps_max 240\n", AutoexecEdit.Add("fps_max", "240").on("${AutoexecDocument.Header}\n"), "after the header when there is no command")
        assertEquals("fps_max 240\n$section", AutoexecEdit.Add("fps_max", "240").on(section), "first, before the section, with neither")
        assertEquals("volume 1\nfps_max 300", AutoexecEdit.Add("fps_max", "300").on("volume 1\n// fps_max 144"), "a line turned off is turned on with the value")
        assertEquals("volume 1\ncl_showfps 1", AutoexecEdit.Add("cl_showfps", "1").on("volume 1"), "a last line without an ending gets one, and the new last line goes without")
        assertEquals(AutoexecEdit.Add("fps_max", "240"), AutoexecEdit.Add.parse("  fps_max 240 "))
        assertEquals(AutoexecEdit.Add("bind", "F5 \"say hi\""), AutoexecEdit.Add.parse("bind F5 \"say hi\""))
        assertEquals(AutoexecEdit.Add("+jump", ""), AutoexecEdit.Add.parse("+jump"))
        assertEquals(listOf(null, null, null), listOf("", "// fps_max 240", "\"x\" 1").map(AutoexecEdit.Add::parse))
    }

    @Test
    fun anotherManagersCommandsBecomePlainLinesWhereTheyWere() {
        val file = "bind a b\r\n// === AUTOEXEC COMMANDS (Mod Manager) ===\r\nsay \"héllo 奶龙\"\r\n\r\n// === END COMMANDS ===\r\necho after"
        assertEquals("bind a b\r\nsay \"héllo 奶龙\"\r\n\r\necho after", parse(file).takeOver(AutoexecTakeover.Sections(listOf(SectionMarkers.GrimoireCommands)))?.text())
        assertNull(parse(file).takeOver(AutoexecTakeover.Sections(listOf(SectionMarkers.DmmMap))))
    }

    @Test
    fun anImportsTakeoverLeavesEveryCrosshairLineAloneAndASecondOneChangesNothing() {
        val dmm = listOf(SectionMarkers.DmmCrosshair, SectionMarkers.DmmMap)
        val grimoire = listOf(SectionMarkers.GrimoireCommands, SectionMarkers.GrimoireCrosshair)
        assertNull(AutoexecDocument.parse(resourceBytes("dmm/autoexec.cfg")).takeOver(AutoexecTakeover.Sections(dmm)), "DMM's crosshair section stays as it is")

        val crosshair = "// === CROSSHAIR SETTINGS (Mod Manager) ===\n// Preset: Green dot\ncitadel_crosshair_pip_gap 5\ncitadel_crosshair_color_g 255\n// === END CROSSHAIR ===\n"
        val file = "// my binds\nbind F5 \"say hi\"\n\n// === AUTOEXEC COMMANDS (Mod Manager) ===\nfps_max 240\ncl_showfps 1\n// === END COMMANDS ===\n\n${crosshair}\necho after\n"
        val taken = assertNotNull(parse(file).takeOver(AutoexecTakeover.Sections(grimoire)))
        assertEquals("// my binds\nbind F5 \"say hi\"\n\nfps_max 240\ncl_showfps 1\n\n${crosshair}\necho after\n", taken.text())
        assertNull(taken.takeOver(AutoexecTakeover.Sections(grimoire)))
        assertNull(parse(file).takeOver(AutoexecTakeover.Sections(dmm)), "only the source's sections are taken")
        val open = "// === AUTOEXEC COMMANDS (Mod Manager) ===\nfps_max 240\nbind x y\n"
        assertNull(parse(open).takeOver(AutoexecTakeover.Sections(grimoire)), "a start with no end takes nothing")
    }

    @Test
    fun aBundlesCommandsGoInOnceAsTheUsersOwn() {
        val commands = listOf("fps_max 240", "bind x \"y\"")
        val fresh = assertNotNull(AutoexecDocument.parse(null).takeOver(AutoexecTakeover.Lines(commands)))
        assertEquals("fps_max 240\nbind x \"y\"\n", fresh.text())
        assertNull(fresh.takeOver(AutoexecTakeover.Lines(commands)))
        assertEquals("exec mine\nfps_max 240\nbind x \"y\"", parse("exec mine").takeOver(AutoexecTakeover.Lines(commands))?.text())
        assertNull(AutoexecDocument.parse(null).takeOver(AutoexecTakeover.Lines(emptyList())), "nothing to bring changes nothing")
    }

    @Test
    fun aTextHunkLandsOnlyWhereItsLinesAndTheLinesAroundItStillAre() {
        val file = "${AutoexecDocument.Header}\nfps_max 240\n${section}volume 1\n"
        val document = parse(file)
        val typed = document.editorText.text.replace("fps_max 240", "fps_max 300\ncl_showfps 1")
        val hunk = assertNotNull(TextHunk.between(document.editorText, typed))
        assertEquals(TextHunk(0, null, listOf("fps_max 240"), listOf("fps_max 300", "cl_showfps 1"), SectionMarkers.GrimoireCommands.start), hunk, "counted from below the header")
        assertEquals("${AutoexecDocument.Header}\nfps_max 300\ncl_showfps 1\n${section}volume 1\n", hunk.on(file))
        assertNull(hunk.on(file.replace("fps_max 240", "fps_max 200")), "its line changed elsewhere: the file wins")
        assertNull(hunk.on(file.replace("fps_max 240\n", "rate 1\nfps_max 240\n")), "a line added above it moved it")

        val inSection = TextHunk.between(document.editorText, document.editorText.text.replace("sensitivity 1.6", "sensitivity 2"))
        assertNull(assertNotNull(inSection).on(file), "a section's lines are locked")
        val aboveHeader = TextHunk.between(document.editorText, "rate 1\n" + document.editorText.text)
        assertNull(assertNotNull(aboveHeader).on(file), "the header stays first")
        val appended = TextHunk.between(document.editorText, document.editorText.text + "\nrate 1")
        assertEquals(file + "rate 1\n", assertNotNull(appended).on(file))

        val empty = AutoexecDocument.parse(null)
        assertNull(TextHunk.between(empty.editorText, ""))
        assertEquals("fps_max 240\n", assertNotNull(TextHunk.between(empty.editorText, "fps_max 240")).applyTo(empty)?.text())
        assertEquals("a 1\nb 2", assertNotNull(TextHunk.between(parse("a 1\nb 1").editorText, "a 1\nb 2")).on("a 1\nb 1"), "the last line keeps having no ending")
    }

    @Test
    fun replayMakesEveryEditThatStillFitsOnTheFileAsItIsNowAndLeavesOutAnyThatDoesNot() {
        val shown = parse("fps_max 240\ncl_showfps 1\n")
        val off = AutoexecEdit.SetOn(CommandRef("cl_showfps", 0, "cl_showfps 1"), on = false)
        val edits = listOf(
            off,
            assertNotNull(TextHunk.between(assertNotNull(off.applyTo(shown)).editorText, "fps_max 300\n// cl_showfps 1")),
            AutoexecEdit.Add("volume", "0.5"),
        )
        val onDisk = parse("rate 786432\nfps_max 240\ncl_showfps 1\n")
        assertEquals("rate 786432\nfps_max 240\n// cl_showfps 1\nvolume 0.5\n", replay(onDisk, edits).text(), "a line added in another editor stays, and the hunk that moved is dropped")
        assertEquals(parse("fps_max 300\n// cl_showfps 1\nvolume 0.5\n"), replay(shown, edits), "on the file they were made on, every edit fits")
    }
}
