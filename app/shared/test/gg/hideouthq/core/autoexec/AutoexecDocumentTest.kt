package gg.hideouthq.core.autoexec

import gg.hideouthq.core.library.resourceBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AutoexecDocumentTest {
    private fun parse(text: String) = AutoexecDocument.parse(text.encodeToByteArray())

    @Test
    fun aDocumentNothingChangedRendersTheBytesItRead() {
        val files = listOf(
            "fps_max 240\ncl_showfps 1\n",
            "fps_max 240\r\ncl_showfps 1\r\n",
            "fps_max 240\r\n// note\ncl_showfps 1\r\n\n",
            "fps_max 240\ncl_showfps 1",
            "\n\n",
            "say \"héllo 奶龙\"\ncitadel_crosshair_pip_gap 3",
        ).map { it.encodeToByteArray() } + listOf(
            resourceBytes("dmm/autoexec.cfg"),
            byteArrayOf(0xE9.toByte(), 0x0A, 0xFF.toByte(), 0xFE.toByte(), 0x0D, 0x0A, 0x80.toByte()),
            "// === Deadlock Mod Manager - Crosshair Settings (DO NOT EDIT) ===\n".encodeToByteArray() + byteArrayOf(0xC0.toByte()) + "\n".encodeToByteArray(),
        )
        for (bytes in files) assertContentEquals(bytes, AutoexecDocument.parse(bytes).render(), bytes.decodeToString())
        assertContentEquals(ByteArray(0), AutoexecDocument.parse(null).render())
    }

    @Test
    fun whatHideoutWritesStartsWithTheHeaderAndIsNeverEmpty() {
        assertEquals("${AutoexecDocument.Header}\n", AutoexecDocument.parse(null).writable().decodeToString())
        assertEquals("${AutoexecDocument.Header}\n\n\n", parse("\n\n").writable().decodeToString())
        assertEquals("${AutoexecDocument.Header}\r\nfps_max 240\r\n", parse("fps_max 240\r\n").writable().decodeToString(), "the header ends as the file's lines do")
        val headed = "${AutoexecDocument.Header}\nfps_max 240"
        assertEquals(headed, parse(headed).writable().decodeToString(), "a file that has it gets no second one")
        assertContentEquals("fps_max 240".encodeToByteArray(), parse("fps_max 240").render(), "reading never adds it")
    }

    @Test
    fun aCommentedNameAndOneValueIsACommandTurnedOffWhenTheNameIsKnownOrReadsLikeAConvar() {
        val document = parse(
            listOf(
                "// cl_showfps 1", "//fps_max 240", "  // my_own_var \"2\"", "// note to self", "// fps_max", "// bind F5 kill", "// Sensitivity 1.6",
                "volume 0.5", "bind F5 \"say hi\"", "exec mine", "fps_max 1; cl_showfps 0", "\"quoted\" 1",
            ).joinToString("\n"),
        )
        assertEquals(
            listOf(
                CommandRow(CommandRef("cl_showfps", 0, "// cl_showfps 1"), "1", on = false, canTurnOff = true),
                CommandRow(CommandRef("fps_max", 0, "//fps_max 240"), "240", on = false, canTurnOff = true),
                CommandRow(CommandRef("my_own_var", 0, "  // my_own_var \"2\""), "2", on = false, canTurnOff = true),
                CommandRow(CommandRef("volume", 0, "volume 0.5"), "0.5", on = true, canTurnOff = true),
                CommandRow(CommandRef("bind", 0, "bind F5 \"say hi\""), "F5 \"say hi\"", on = true, canTurnOff = false),
                CommandRow(CommandRef("exec", 0, "exec mine"), "mine", on = true, canTurnOff = false),
            ),
            document.commands,
        )
    }

    @Test
    fun aLineTheListTurnsOffReadsBackAsOff() {
        val names = CommandLibrary.Commands.map { it.name } + "my_own_var"
        val document = parse(names.joinToString("\n") { "$it 1" })
        val off = names.fold(document) { current, name -> checkNotNull(AutoexecEdit.SetOn(current.commands.first { it.ref.name == name }.ref, on = false).applyTo(current)) }
        val reread = AutoexecDocument.parse(off.render())
        assertEquals(names, reread.commands.map { it.ref.name })
        assertTrue(reread.commands.none { it.on })
        assertEquals(names.map { "// $it 1" }, reread.editorText.lines)
    }

    @Test
    fun aSectionRunsFromItsStartToTheFirstEndAfterItAndAStartWithNoEndClaimsNothing() {
        val open = parse("// === Deadlock Mod Manager - Crosshair Settings (DO NOT EDIT) ===\ncitadel_crosshair_color_r \"0\"\nbind x y\n")
        assertEquals(AutoexecLines(emptyList(), emptyList()), open.foreignLines(SectionMarkers.Known))
        assertEquals(listOf("citadel_crosshair_color_r", "bind"), open.commands.map { it.ref.name }, "its lines are lines of their own")
    }

    @Test
    fun aLoneCarriageReturnEndsALineAsTheOtherEndingsDo() {
        val cr = "fps_max 240\rcl_showfps 1\r// note\rvolume 0.5"
        val mixed = "a 1\r\nb 2\rc 3\n\rd 4\r"
        for (text in listOf(cr, mixed)) assertContentEquals(text.encodeToByteArray(), parse(text).render(), text)
        assertEquals(listOf("fps_max", "cl_showfps", "volume"), parse(cr).commands.map { it.ref.name })
        assertEquals(listOf("a", "b", "c", "d"), parse(mixed).commands.map { it.ref.name })
        val document = parse(cr)
        assertEquals("fps_max 300\rcl_showfps 1\r// note\rvolume 0.5", AutoexecEdit.SetValue(document.commands.first().ref, "300").applyTo(document)?.render()?.decodeToString(), "one line changes, and only it")
        assertEquals("$cr\rrate 1", AutoexecEdit.Add("rate", "1").applyTo(document).render().decodeToString(), "a new line ends as the file's lines do")
    }

    @Test
    fun aByteOrderMarkStaysFirstAndHidesNoCommand() {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val file = bom + "fps_max 240\n".encodeToByteArray()
        val document = AutoexecDocument.parse(file)
        assertContentEquals(file, document.render())
        assertEquals(listOf("fps_max"), document.commands.map { it.ref.name })
        assertEquals(listOf("fps_max 240"), document.editorText.lines)
        val written = bom + "${AutoexecDocument.Header}\nfps_max 240\n".encodeToByteArray()
        assertContentEquals(written, document.writable(), "the header goes after the mark")
        assertContentEquals(written, AutoexecDocument.parse(written).writable(), "a file that has both gets neither again")
    }

    @Test
    fun theTextViewLocksTheHeaderAndEverySection() {
        val document = parse(
            "${AutoexecDocument.Header}\nfps_max 240\n// my own crosshair\ncitadel_crosshair_pip_gap 3\n// keep it small\n" +
                "// === AUTOEXEC COMMANDS (Mod Manager) ===\nvolume 1\n// === END COMMANDS ===\nsay \"héllo\"\n",
        )
        assertEquals(
            EditorText(
                listOf(AutoexecDocument.Header, "fps_max 240", "// my own crosshair", "citadel_crosshair_pip_gap 3", "// keep it small", "// === AUTOEXEC COMMANDS (Mod Manager) ===", "volume 1", "// === END COMMANDS ===", "say \"héllo\""),
                listOf(LockedLines(0..0, LockOwner.Header), LockedLines(5..7, LockOwner.Tool(SectionMarkers.GrimoireCommands))),
            ),
            document.editorText,
        )
        assertEquals(4, document.commandCount, "three free commands and the section's one")
    }

    @Test
    fun anImportReadsDmmsAndGrimoiresSectionsAsTheyWereWritten() {
        val dmm = AutoexecDocument.parse(resourceBytes("dmm/autoexec.cfg"))
        assertEquals(11, dmm.foreignLines(listOf(SectionMarkers.DmmCrosshair, SectionMarkers.DmmMap)).crosshair.size)
        assertEquals(AutoexecLines(emptyList(), emptyList()), dmm.foreignLines(listOf(SectionMarkers.GrimoireCommands, SectionMarkers.GrimoireCrosshair)))
        assertEquals(AutoexecLines(emptyList(), emptyList()), AutoexecDocument.parse(null).foreignLines(SectionMarkers.Known))
        val grimoire = parse(
            "// my binds\nbind F5 \"say hi\"\n\n// === AUTOEXEC COMMANDS (Mod Manager) ===\r\nfps_max 240\nsay \"héllo 奶龙\"\n// === END COMMANDS ===\n\n" +
                "// === CROSSHAIR SETTINGS (Mod Manager) ===\n// Preset: Green dot\n\ncitadel_crosshair_pip_gap 5\n// === END CROSSHAIR ===\n",
        )
        assertEquals(
            AutoexecLines(listOf("// Preset: Green dot", "citadel_crosshair_pip_gap 5"), listOf("fps_max 240", "say \"héllo 奶龙\"")),
            grimoire.foreignLines(listOf(SectionMarkers.GrimoireCommands, SectionMarkers.GrimoireCrosshair)),
        )
    }
}
