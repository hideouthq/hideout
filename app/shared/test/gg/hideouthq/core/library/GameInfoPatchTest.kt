package gg.hideouthq.core.library

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GameInfoPatchTest {
    private val valve = (
        "\"GameInfo\"\r\n{\r\n\tgame\t\"Deadlock\"\r\n\tFileSystem\r\n\t{\r\n\t\tSearchPaths\r\n\t\t{\r\n" +
            "\t\t\t// These are optional language paths.\r\n\t\t\tGame_Language\t\tcitadel_*LANGUAGE*\r\n\r\n" +
            "\t\t\tGame_LowViolence\tcitadel_lv\r\n\r\n\t\t\tGame\t\t\t\tcitadel\r\n\t\t\tGame\t\t\t\tcore\r\n\t\t}\r\n\t}\r\n}\r\n"
        ).encodeToByteArray()

    private val anotherTool = (
        "// Lina 🜏 and friends\nGameInfo\n{\n    FileSystem\n    {\n        // Search paths are relative\n" +
            "// Deadlock Mod Manager - Start\n\n\t\tSearchPaths\n        {  \n            Game_Language       citadel_*LANGUAGE*\n" +
            "            Game                citadel/addons\n            Mod                 citadel\n            Write               citadel          \n" +
            "            Game                citadel\n            Game                core        \n        }\n// Deadlock Mod Manager - End\n    }\n}\n"
        ).encodeToByteArray()

    private val mods = HideoutLines(mods = true, customGames = false)
    private val games = HideoutLines(mods = false, customGames = true)
    private val both = HideoutLines(mods = true, customGames = true)
    private val none = HideoutLines(mods = false, customGames = false)

    private fun searchPaths(lines: String) =
        "GameInfo\n{\n\tFileSystem\n\t{\n\t\tSearchPaths\n\t\t{\n$lines\t\t}\n\t}\n}\n".encodeToByteArray()

    private fun with(bytes: ByteArray, lines: HideoutLines): ByteArray = when (val edit = GameInfoPatch.withBlock(bytes, lines)) {
        is GameInfoEdit.Patched -> edit.bytes
        GameInfoEdit.Unchanged -> bytes
        is GameInfoEdit.Refused -> throw AssertionError("refused: ${edit.problem}")
    }

    private fun ByteArray.text() = decodeToString()

    private fun read(bytes: ByteArray) = assertIs<SearchPathsCheck.Read>(GameInfoPatch.check(bytes))

    private fun modlockMounted(file: String, firstGame: String, mod: String = "citadel", write: String = "citadel"): ByteArray {
        val eol = if ("\r\n" in file) "\r\n" else "\n"
        val at = file.indexOf(firstGame).let { file.lastIndexOf(eol, it - 1) }
        val block = listOf("// MODLOCK DEV BEGIN", "Game\t\t\tcitadel/modlock_dev/741294efb7c63a17", "Mod\t\t\t\"$mod\"", "Write\t\t\t\"$write\"", "// MODLOCK DEV END").joinToString("") { "$eol\t\t\t$it" }
        return (file.substring(0, at) + block + file.substring(at)).encodeToByteArray()
    }

    private val valveFirstGame = "\t\t\tGame\t\t\t\tcitadel"

    @Test
    fun valvesOwnFileGetsOneMarkedBlockAheadOfTheBaseGameForWhatIsAskedFor() {
        fun expected(lines: String) = valve.text().replace("\t\t\tGame\t\t\t\tcitadel\r\n", "\t\t\t// Hideout start\r\n$lines\t\t\tMod\t\t\t\tcitadel\r\n\t\t\tWrite\t\t\t\tcitadel\r\n\t\t\t// Hideout end\r\n\t\t\tGame\t\t\t\tcitadel\r\n")
        val addons = "\t\t\tGame\t\t\t\tcitadel/addons\r\n"
        val customGames = "\t\t\tGame\t\t\t\tcitadel/hideout_games/menus\r\n\t\t\tGame\t\t\t\tcitadel/hideout_games/mode\r\n"
        assertEquals(expected(addons), with(valve, mods).text())
        assertEquals(expected(customGames), with(valve, games).text())
        assertEquals(expected(customGames + addons), with(valve, both).text())
        assertEquals(GameInfoEdit.Unchanged, GameInfoPatch.withBlock(valve, none))
    }

    @Test
    fun theCheckSaysWhoLoadsModsAndWhetherTheCustomGamesPathsAreThere() {
        assertEquals(SearchPathsCheck.Read(coveredBy = null, customGames = false), GameInfoPatch.check(valve))
        assertEquals(SearchPathsCheck.Read(LoadingOwner.Hideout, customGames = false), GameInfoPatch.check(with(valve, mods)))
        assertEquals(SearchPathsCheck.Read(coveredBy = null, customGames = true), GameInfoPatch.check(with(valve, games)))
        assertEquals(SearchPathsCheck.Read(LoadingOwner.Hideout, customGames = true), GameInfoPatch.check(with(valve, both)))
    }

    @Test
    fun everyBlockIsStableAndComesOffToTheExactBytesItWentInto() {
        val withBom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + valve
        for (file in listOf(valve, withBom, valve.text().replace("\r\n", "\n").encodeToByteArray())) {
            for (lines in listOf(mods, games, both)) {
                val once = with(file, lines)
                assertEquals(GameInfoEdit.Unchanged, GameInfoPatch.withBlock(once, lines), "$lines twice changes nothing")
                assertContentEquals(file, with(once, none), "$lines comes off")
            }
        }
    }

    @Test
    fun askingForOtherLinesRendersTheBlockAfresh() {
        assertContentEquals(with(valve, both), with(with(valve, mods), both))
        assertContentEquals(with(valve, games), with(with(valve, both), games))
        assertContentEquals(with(valve, mods), with(with(valve, games), mods))
    }

    @Test
    fun anotherToolsAddonsLineGetsNoNewAddonsLineOfHideoutsKeepsAnOldOneAndTheCustomGamesPathsStillGoIn() {
        assertEquals(SearchPathsCheck.Read(LoadingOwner.AnotherTool, customGames = false), GameInfoPatch.check(anotherTool))
        assertEquals(GameInfoEdit.Unchanged, GameInfoPatch.withBlock(anotherTool, mods))
        val withGames = with(anotherTool, both)
        val block = "            // Hideout start\n            Game                citadel/hideout_games/menus\n            Game                citadel/hideout_games/mode\n            // Hideout end\n"
        assertEquals(anotherTool.text().replace("            Game                citadel\n", block + "            Game                citadel\n"), withGames.text())
        assertEquals(SearchPathsCheck.Read(LoadingOwner.AnotherTool, customGames = true), GameInfoPatch.check(withGames))
        val beside = with(valve, both).text().replace("\t\t\t// Hideout start", "\t\t\tGame citadel/addons\r\n\t\t\t// Hideout start").encodeToByteArray()
        assertEquals(LoadingOwner.AnotherTool, read(beside).coveredBy)
        assertContentEquals(beside, with(beside, both), "beside another tool's addons line, Hideout's block keeps its own")
        assertContentEquals(
            with(valve, games).text().replace("\t\t\t// Hideout start", "\t\t\tGame citadel/addons\r\n\t\t\t// Hideout start").encodeToByteArray(),
            with(beside, games),
            "and drops it only when mods are off",
        )
        val modsBeside = with(valve, mods).text().replace("\t\t\t// Hideout start", "\t\t\tGame citadel/addons\r\n\t\t\t// Hideout start").encodeToByteArray()
        assertEquals(GameInfoEdit.Unchanged, GameInfoPatch.withBlock(modsBeside, mods))
    }

    @Test
    fun aModlockBlockStaysThroughEveryEditAndItsRolesNeverStandInForTheFilesOwn() {
        val mounted = modlockMounted(valve.text(), valveFirstGame)
        assertEquals(SearchPathsCheck.Read(coveredBy = null, customGames = false), GameInfoPatch.check(mounted))
        for (lines in listOf(mods, games, both)) {
            val edited = with(mounted, lines)
            assertContentEquals(modlockMounted(with(valve, lines).text(), "\t\t\t// Hideout start"), edited, "$lines keeps Modlock's block and adds Mod and Write")
            assertContentEquals(mounted, with(edited, none))
        }
        val roles = modlockMounted(valve.text(), valveFirstGame, mod = "citadel_tweaks", write = "core")
        assertTrue("\t\t\tMod\t\t\t\tcitadel\r\n" in with(roles, games).text())
    }

    @Test
    fun theSearchOrderNamesWhatDeadlockReadsAheadOfHideoutsMenus() {
        val mounted = modlockMounted(with(valve, both).text(), "\t\t\t// Hideout start")
        val order = assertIs<SearchOrderRead.Read>(GameInfoPatch.searchOrder(mounted))
        assertEquals(
            listOf(
                SearchEntry("citadel/modlock_dev/741294efb7c63a17", hideouts = false),
                SearchEntry("citadel/hideout_games/menus", hideouts = true),
                SearchEntry("citadel/hideout_games/mode", hideouts = true),
                SearchEntry("citadel/addons", hideouts = true),
                SearchEntry("citadel", hideouts = false),
            ),
            order.entries,
            "Game entries only, up to the base game",
        )
        assertEquals(listOf("citadel/modlock_dev/741294efb7c63a17"), order.aheadOfMenus)
        assertEquals(emptyList(), assertIs<SearchOrderRead.Read>(GameInfoPatch.searchOrder(with(valve, both))).aheadOfMenus)
        val noMenus = assertIs<SearchOrderRead.Read>(GameInfoPatch.searchOrder(modlockMounted(with(valve, mods).text(), "\t\t\t// Hideout start")))
        assertEquals(emptyList(), noMenus.aheadOfMenus, "with no menus nothing is ahead of them")
        assertEquals(SearchOrderRead.Problem(GameInfoProblem.NoBaseGameEntry), GameInfoPatch.searchOrder(searchPaths("\t\t\tGame core\n")))
    }

    @Test
    fun anEditedBlockIsNotHideoutsAndALoneHalfOfTheCustomGamesPathsIsNotAnIntactBlock() {
        val edited = searchPaths("\t\t\t// Hideout start\n\t\t\tGame citadel/addons\n\t\t\tGame mine\n\t\t\t// Hideout end\n\t\t\tGame citadel\n")
        assertEquals(LoadingOwner.AnotherTool, read(edited).coveredBy)
        assertContentEquals(edited, with(edited, none))
        val half = searchPaths("\t\t\t// Hideout start\n\t\t\tGame citadel/hideout_games/menus\n\t\t\t// Hideout end\n\t\t\tGame citadel\n")
        assertEquals(SearchPathsCheck.Read(coveredBy = null, customGames = false), GameInfoPatch.check(half))
        assertContentEquals(half, with(half, none))
    }

    @Test
    fun aBlockAnotherToolEmptiedIsReplacedNotJoinedByASecond() {
        val emptied = searchPaths("\t\t\t// Hideout start\n\t\t\t// Hideout end\n\t\t\tGame citadel/grimoire\n\t\t\tGame citadel\n")
        assertEquals(SearchPathsCheck.Read(coveredBy = null, customGames = false), GameInfoPatch.check(emptied))
        val fixed = with(emptied, mods)
        assertEquals(1, Regex("// Hideout start").findAll(fixed.text()).count(), fixed.text())
        assertEquals(SearchPathsCheck.Read(LoadingOwner.Hideout, customGames = false), GameInfoPatch.check(fixed))
        assertContentEquals(searchPaths("\t\t\tGame citadel/grimoire\n\t\t\tGame citadel\n"), with(fixed, none), "and taking it out leaves no markers")
    }

    @Test
    fun commentedConditionalAndLateLinesDoNotCount() {
        val commented = searchPaths("\t\t\t// Game citadel/addons\n\t\t\tGame citadel\n")
        val conditional = searchPaths("\t\t\tGame citadel/addons [\$OSX]\n\t\t\tGame citadel\n")
        val late = searchPaths("\t\t\tGame citadel\n\t\t\tGame citadel/addons\n")
        val wrongKey = searchPaths("\t\t\tMod citadel/addons\n\t\t\tGame citadel\n")
        listOf(commented, conditional, late, wrongKey).forEach {
            assertEquals(SearchPathsCheck.Read(coveredBy = null, customGames = false), GameInfoPatch.check(it), it.text())
            val fixed = with(it, both)
            assertEquals(SearchPathsCheck.Read(LoadingOwner.Hideout, customGames = true), GameInfoPatch.check(fixed))
            assertContentEquals(it, with(fixed, none))
        }
        val backslashes = searchPaths("\t\t\tGame \"citadel\\addons\"\n\t\t\tGame citadel\n")
        assertEquals(LoadingOwner.AnotherTool, read(backslashes).coveredBy)
    }

    @Test
    fun aBlockBehindTheBaseGameCountsForNothingAndIsMovedAheadOfIt() {
        val block = "\t\t\t// Hideout start\n\t\t\tGame citadel/hideout_games/menus\n\t\t\tGame citadel/hideout_games/mode\n\t\t\tGame citadel/addons\n\t\t\tMod citadel\n\t\t\tWrite citadel\n\t\t\t// Hideout end\n"
        val misplaced = searchPaths("\t\t\tGame citadel\n$block")
        assertEquals(SearchPathsCheck.Read(coveredBy = null, customGames = false), GameInfoPatch.check(misplaced))
        val fixed = with(misplaced, both)
        assertEquals(searchPaths("$block\t\t\tGame citadel\n").text(), fixed.text())
        assertEquals(GameInfoEdit.Unchanged, GameInfoPatch.withBlock(fixed, both))
    }

    @Test
    fun quotedEntriesKeepTheirQuoting() {
        val quoted = searchPaths("\t\t\t\"Game\"  \"citadel\"\n")
        assertEquals(
            searchPaths(
                "\t\t\t// Hideout start\n\t\t\t\"Game\"  \"citadel/hideout_games/menus\"\n\t\t\t\"Game\"  \"citadel/hideout_games/mode\"\n" +
                    "\t\t\t\"Game\"  \"citadel/addons\"\n\t\t\t\"Mod\"  \"citadel\"\n\t\t\t\"Write\"  \"citadel\"\n\t\t\t// Hideout end\n\t\t\t\"Game\"  \"citadel\"\n",
            ).text(),
            with(quoted, both).text(),
        )
    }

    @Test
    fun modAndWriteDefaultsAreAddedOnlyWhereNoUnconditionalEntryGivesThem() {
        mapOf(
            "\t\t\tMod elsewhere\n" to "\t\t\tWrite citadel\n",
            "\t\t\tMod one\n\t\t\tWrite two\n" to "",
            "\t\t\tMod elsewhere [\$OSX]\n" to "\t\t\tMod citadel\n\t\t\tWrite citadel\n",
            "\t\t\tGame+Mod+Write elsewhere\n" to "",
        ).forEach { (before, defaults) ->
            val original = searchPaths("$before\t\t\tGame citadel\n")
            val expected = searchPaths("$before\t\t\t// Hideout start\n\t\t\tGame citadel/addons\n$defaults\t\t\t// Hideout end\n\t\t\tGame citadel\n")
            assertEquals(expected.text(), with(original, mods).text(), before)
            assertContentEquals(original, with(with(original, mods), none), before)
        }
    }

    @Test
    fun filesWithoutOneClearSearchPathsAreProblemsNotZero() {
        assertEquals(SearchPathsCheck.Problem(GameInfoProblem.Unparseable), GameInfoPatch.check("GameInfo { FileSystem { ".encodeToByteArray()))
        assertEquals(SearchPathsCheck.Problem(GameInfoProblem.NoSearchPaths), GameInfoPatch.check("GameInfo { FileSystem { } }".encodeToByteArray()))
        val twice = "GameInfo { FileSystem { SearchPaths { Game citadel } SearchPaths { Game core } } }".encodeToByteArray()
        assertEquals(SearchPathsCheck.Problem(GameInfoProblem.AmbiguousSearchPaths), GameInfoPatch.check(twice))
        assertEquals(SearchPathsCheck.Problem(GameInfoProblem.NoBaseGameEntry), GameInfoPatch.check(searchPaths("\t\t\tGame core\n")))
        val sameLine = "GameInfo { FileSystem { SearchPaths { Game citadel } } }".encodeToByteArray()
        assertEquals(GameInfoEdit.Refused(GameInfoProblem.UnsupportedLayout), GameInfoPatch.withBlock(sameLine, games))
        assertEquals(GameInfoEdit.Refused(GameInfoProblem.NoBaseGameEntry), GameInfoPatch.withBlock(searchPaths("\t\t\tGame core\n"), mods))
        assertEquals(GameInfoEdit.Refused(GameInfoProblem.Unparseable), GameInfoPatch.withBlock("GameInfo { FileSystem { ".encodeToByteArray(), none))
    }

    private fun nested(onGameInfo: String, onFileSystem: String, onSearchPaths: String, lines: String) =
        "GameInfo$onGameInfo\n{\n\tFileSystem$onFileSystem\n\t{\n\t\tSearchPaths$onSearchPaths\n\t\t{\n$lines\t\t}\n\t}\n}\n".encodeToByteArray()

    @Test
    fun aConditionOnAnyAncestorOfTheEntriesIsNeitherCoverageNorAPlaceToPatch() {
        val lines = "\t\t\t// Hideout start\n\t\t\tGame citadel/addons\n\t\t\t// Hideout end\n\t\t\tGame citadel\n"
        listOf(" [\$OSX]", "\n[\$OSX]").flatMap { listOf(Triple(it, "", ""), Triple("", it, ""), Triple("", "", it)) }.forEach { (onGameInfo, onFileSystem, onSearchPaths) ->
            val bytes = nested(onGameInfo, onFileSystem, onSearchPaths, lines)
            assertEquals(SearchPathsCheck.Problem(GameInfoProblem.AmbiguousSearchPaths), GameInfoPatch.check(bytes), bytes.text())
            assertEquals(GameInfoEdit.Refused(GameInfoProblem.AmbiguousSearchPaths), GameInfoPatch.withBlock(bytes, both), bytes.text())
        }
    }

    @Test
    fun aConditionalHierarchyBesideAnUnconditionalOneIsAmbiguous() {
        val fileSystems = (
            "GameInfo\n{\n\tFileSystem [\$OSX]\n\t{\n\t\tSearchPaths\n\t\t{\n\t\t\tGame citadel/addons\n\t\t\tGame citadel\n\t\t}\n\t}\n" +
                "\tFileSystem\n\t{\n\t\tSearchPaths\n\t\t{\n\t\t\tGame citadel\n\t\t}\n\t}\n}\n"
            ).encodeToByteArray()
        val gameInfos = (
            "GameInfo [\$OSX]\n{\n\tFileSystem\n\t{\n\t\tSearchPaths\n\t\t{\n\t\t\tGame citadel/addons\n\t\t\tGame citadel\n\t\t}\n\t}\n}\n" +
                "GameInfo\n{\n\tFileSystem\n\t{\n\t\tSearchPaths\n\t\t{\n\t\t\tGame citadel\n\t\t}\n\t}\n}\n"
            ).encodeToByteArray()
        listOf(fileSystems, gameInfos).forEach { bytes ->
            assertEquals(SearchPathsCheck.Problem(GameInfoProblem.AmbiguousSearchPaths), GameInfoPatch.check(bytes), bytes.text())
            assertEquals(GameInfoEdit.Refused(GameInfoProblem.AmbiguousSearchPaths), GameInfoPatch.withBlock(bytes, mods), bytes.text())
        }
    }

    private val dmm = resourceBytes("dmm/gameinfo.gi")

    private val dmmMounts = listOf(
        "\r\n// Deadlock Mod Manager - Start",
        "\r\n            Game                citadel/dmm_localization_default",
        "\r\n            Game                citadel/addons/server_42",
        "\r\n            Game                citadel/addons/profile_1726000000000_ab12cd_ranked",
        "\r\n            Game                citadel/addons2/profile_1726000000000_ab12cd_ranked",
        "\r\n// Deadlock Mod Manager - End",
    )

    private val dmmRoots = ForeignRoot.keptBy(Manager.DeadlockModManager).toSet()
    private val grimoireRoots = ForeignRoot.keptBy(Manager.Grimoire).toSet()

    private fun takenOver(bytes: ByteArray, lines: HideoutLines, roots: Set<ForeignRoot> = dmmRoots): ByteArray =
        assertIs<GameInfoEdit.Patched>(GameInfoPatch.takeOver(bytes, lines, roots)).bytes

    @Test
    fun takingOverDmmDropsItsMountsAndMarkersAndAddsHideoutsBlockAheadOfTheBaseGame() {
        val withoutDmm = dmmMounts.fold(dmm.text()) { text, line -> text.replaceFirst(line, "") }
        val modded = withoutDmm.replace(
            "            Game                citadel\r\n",
            "            // Hideout start\r\n            Game                citadel/addons\r\n            // Hideout end\r\n            Game                citadel\r\n",
        )
        assertEquals(modded, takenOver(dmm, mods).text())
        assertEquals(withoutDmm, takenOver(dmm, none).text())
        listOf("citadel/deadworks_mods", "addonroot           citadel/deadworks_addons", "Mod                 core", "Write               citadel          \r\n",
            "// dmm-perf was \"400\"", "Deadlock Mod Manager · Performance BEGIN", "// grimoire-perf was").forEach {
            assertTrue(it in modded, "$it is kept")
        }
    }

    @Test
    fun takingOverATakenOverFileChangesNothing() {
        listOf(mods, games, both, none).forEach { lines ->
            val once = takenOver(dmm, lines)
            assertEquals(GameInfoEdit.Unchanged, GameInfoPatch.takeOver(once, lines, dmmRoots), lines.toString())
        }
        assertEquals(SearchPathsCheck.Read(LoadingOwner.Hideout, customGames = true), GameInfoPatch.check(takenOver(dmm, both)))
        assertEquals(SearchPathsCheck.Read(coveredBy = null, customGames = false), GameInfoPatch.check(takenOver(dmm, none)))
    }

    @Test
    fun grimoiresMountsGoAndDeadworksStays() {
        val grimoire = searchPaths(
            "\t\t\tGame\t\tcitadel/grimoire\n\t\t\tGame\t\tcitadel/addons\n\t\t\tGame\t\tcitadel/addons1\n\t\t\tGame\t\tcitadel/deadworks_addons/vpks\n" +
                "\t\t\tGame_LowViolence\tcitadel_lv\n\t\t\tGame\t\tcitadel\n\t\t\tGame\t\tcore\n\t\t\tMod\t\tcitadel\n\t\t\tWrite\t\tcitadel\n",
        )
        assertEquals(
            searchPaths(
                "\t\t\tGame\t\tcitadel/deadworks_addons/vpks\n\t\t\tGame_LowViolence\tcitadel_lv\n" +
                    "\t\t\t// Hideout start\n\t\t\tGame\t\tcitadel/addons\n\t\t\t// Hideout end\n" +
                    "\t\t\tGame\t\tcitadel\n\t\t\tGame\t\tcore\n\t\t\tMod\t\tcitadel\n\t\t\tWrite\t\tcitadel\n",
            ).text(),
            takenOver(grimoire, mods, grimoireRoots).text(),
        )
    }

    @Test
    fun aRewriteByTheManagerImportedFromIsTakenOverBackToTheFileTheImportLeft() {
        assertContentEquals(GrimoireImported, takenOver(grimoireFixed(GrimoireImported), mods, grimoireRoots), "Grimoire's Fix now")
        assertContentEquals(DmmImported, takenOver(DmmMarked, mods), "a toggle in Deadlock Mod Manager")
        val games = with(GrimoireImported, both)
        assertContentEquals(games, takenOver(grimoireFixed(games), both, grimoireRoots), "the custom games lines come back with the block")
    }

    @Test
    fun heldByNamesTheManagersWhoseOwnMarksShow() {
        assertEquals(setOf(Manager.Grimoire), GameInfoPatch.heldBy(grimoireFixed(GrimoireImported)))
        assertEquals(setOf(Manager.DeadlockModManager), GameInfoPatch.heldBy(DmmMarked), "by its markers")
        assertEquals(Manager.entries.toSet(), GameInfoPatch.heldBy(LoadsAddonsGameInfo), "addons alone is either's")
        assertEquals(emptySet(), GameInfoPatch.heldBy(GrimoireImported), "Hideout's own addons line is no other manager's")
    }

    @Test
    fun takingOverOneManagerLeavesAnothersMountsAndMarkers() {
        val both = searchPaths(
            "// Deadlock Mod Manager - Start\n\t\t\tGame\t\tcitadel/grimoire\n\t\t\tGame\t\tcitadel/addons/profile_1726_ab\n\t\t\tGame\t\tcitadel/dmm_localization_default\n" +
                "\t\t\tGame\t\tcitadel\n// Deadlock Mod Manager - End\n",
        )
        assertEquals(searchPaths("\t\t\tGame\t\tcitadel/grimoire\n\t\t\tGame\t\tcitadel\n").text(), takenOver(both, none, dmmRoots).text(), "Grimoire's mods keep loading after a DMM import")
        assertEquals(
            searchPaths(
                "// Deadlock Mod Manager - Start\n\t\t\tGame\t\tcitadel/addons/profile_1726_ab\n\t\t\tGame\t\tcitadel/dmm_localization_default\n" +
                    "\t\t\tGame\t\tcitadel\n// Deadlock Mod Manager - End\n",
            ).text(),
            takenOver(both, none, grimoireRoots).text(),
            "DMM's mounts and markers stay after a Grimoire import",
        )
    }

    @Test
    fun conditionalForeignMountsGoWithTheirTrailingComments() {
        val conditional = searchPaths("\t\t\tGame citadel\\Addons\\Profile_1726_ab [\$WIN64] // DMM's\n\t\t\tGame \"citadel/grimoire/\"\n\t\t\tGame citadel\n")
        assertEquals(searchPaths("\t\t\tGame citadel\n").text(), takenOver(conditional, none, ForeignRoot.entries.toSet()).text())
    }

    @Test
    fun hideoutsOwnBlockIsKeptAndTheLinesAskedForDecideIt() {
        val loading = with(valve, both)
        assertEquals(GameInfoEdit.Unchanged, GameInfoPatch.takeOver(loading, both, dmmRoots))
        assertContentEquals(valve, takenOver(loading, none))
        assertEquals(GameInfoEdit.Unchanged, GameInfoPatch.takeOver(valve, none, dmmRoots))
        assertContentEquals(loading, takenOver(valve, both))
    }

    @Test
    fun aForeignMountThatSharesItsLineOrAFileWithBlockRefusesIsLeftAlone() {
        val shared = "GameInfo { FileSystem { SearchPaths { Game citadel/addons2 Game citadel } } }".encodeToByteArray()
        assertEquals(GameInfoEdit.Refused(GameInfoProblem.UnsupportedLayout), GameInfoPatch.takeOver(shared, none, dmmRoots))
        val sameLineBase = searchPaths("\t\t\tGame citadel/grimoire\n\t\t\tWrite citadel Game citadel\n")
        assertEquals(GameInfoEdit.Refused(GameInfoProblem.UnsupportedLayout), GameInfoPatch.takeOver(sameLineBase, mods, grimoireRoots))
        assertEquals(GameInfoEdit.Refused(GameInfoProblem.Unparseable), GameInfoPatch.takeOver("GameInfo { FileSystem { ".encodeToByteArray(), mods, dmmRoots))
        assertEquals(GameInfoEdit.Refused(GameInfoProblem.NoBaseGameEntry), GameInfoPatch.takeOver(searchPaths("\t\t\tGame citadel/addons\n"), mods, dmmRoots))
    }

    @Test
    fun mountsAreTheFoldersUnderCitadelAheadOfTheBaseGameInSearchOrder() {
        assertEquals(
            MountsRead.Read(
                listOf("deadworks_mods", "dmm_localization_default", "addons/server_42", "addons/profile_1726000000000_ab12cd_ranked", "addons2/profile_1726000000000_ab12cd_ranked")
                    .map(::CitadelPath),
            ),
            GameInfoPatch.mounts(dmm),
        )
        val mixed = searchPaths(
            "\t\t\tGame citadel/Grimoire\n\t\t\tGame core_lv\n\t\t\tGame citadel/addons [\$OSX]\n\t\t\tGame \"citadel\\addons\\\"\n" +
                "\t\t\tMod citadel/addons2\n\t\t\tGame citadel\n\t\t\tGame citadel/addons3\n",
        )
        assertEquals(MountsRead.Read(listOf(CitadelPath("Grimoire"), CitadelPath.Addons)), GameInfoPatch.mounts(mixed))
        assertEquals(MountsRead.Problem(GameInfoProblem.NoSearchPaths), GameInfoPatch.mounts("GameInfo { FileSystem { } }".encodeToByteArray()))
    }
}
