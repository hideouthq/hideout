package gg.hideouthq.core.migration

import gg.hideouthq.core.data.GameInventory
import gg.hideouthq.core.data.VpkLook
import gg.hideouthq.core.data.fake.FakeVpk
import gg.hideouthq.core.data.fake.InMemoryGameFiles
import gg.hideouthq.core.library.CitadelPath
import gg.hideouthq.core.library.ForeignRoot
import gg.hideouthq.core.library.GameRoot
import gg.hideouthq.core.library.Imprint
import gg.hideouthq.core.library.LoadPosition
import gg.hideouthq.core.library.heldMod
import gg.hideouthq.core.library.resourceBytes
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GameSurveyTest {
    private val root = GameRoot("/games/Deadlock")
    private val citadel = "game/citadel"
    private val info = "GameInfo\n{\n\tFileSystem\n\t{\n\t\tSearchPaths\n\t\t{\n\t\t\tGame\tcitadel/grimoire\n\t\t\tGame\tcitadel/addons\n\t\t\tGame\tcitadel\n\t\t\tGame\tcore\n\t\t}\n\t}\n}\n".encodeToByteArray()
    private val global = FakeVpk.directory(chunkReads = mapOf(0 to 10L), seed = 1)
    private val hideouts = FakeVpk.directory(seed = 9)

    private val held = heldMod("h", root, hideouts)

    private val files = InMemoryGameFiles().apply {
        putBytes(root, "$citadel/gameinfo.gi", info)
        putBytes(root, "$citadel/grimoire/pak05_dir.vpk", global)
        putBytes(root, "$citadel/grimoire/pak05_000.vpk", ByteArray(10) { 3 })
        putBytes(root, "$citadel/grimoire/pak06_dir.vpk", FakeVpk.directory(chunkReads = mapOf(0 to 10L), seed = 2))
        putBytes(root, "$citadel/addons/pak01_dir.vpk", hideouts)
        putBytes(root, "$citadel/addons/pak02_dir.vpk", FakeVpk.directory(seed = 3))
        putBytes(root, "$citadel/addons/700_skin_dir.vpk", FakeVpk.directory(seed = 4))
        putBytes(root, "$citadel/addons/.dmm.json", "{}".encodeToByteArray())
        putBytes(root, "$citadel/addons/profile_x/pak01_dir.vpk", FakeVpk.directory(seed = 5))
        putBytes(root, "$citadel/addons/.disabled/off_dir.vpk", FakeVpk.directory(seed = 6))
        putBytes(root, "$citadel/addons/.dmm-clear/pak03_dir.vpk", FakeVpk.directory(seed = 7))
        putBytes(root, "$citadel/addons-backups/s/pak09_dir.vpk", FakeVpk.directory(seed = 8))
        putBytes(root, "$citadel/hideout_backups/files/pak09_dir.vpk", FakeVpk.directory(seed = 9))
        addLink(root, "$citadel/addons/replays")
        putBytes(root, "$citadel/addons/replays/pak04_dir.vpk", FakeVpk.directory(seed = 10))
    }

    private class Counting(private val files: InMemoryGameFiles) : GameInventory by files {
        val reads = mutableListOf<String>()

        override suspend fun vpk(root: GameRoot, path: CitadelPath): VpkLook = files.vpk(root, path).also { reads += path.value }
    }

    @Test
    fun everyFolderAnotherManagerUsesIsSurveyedAndOnlyMountedBarePakSetsLoad() = runTest {
        val inventory = Counting(files)
        val survey = assertIs<SurveyRead.Read>(surveyGame(inventory, root, listOf(held))).survey
        assertEquals(listOf(CitadelPath("grimoire"), CitadelPath("addons")), survey.mounts)
        assertEquals(
            mapOf(
                "grimoire/pak05_dir.vpk" to LoadPosition(0, 5),
                "addons/pak02_dir.vpk" to LoadPosition(1, 2),
                "addons/700_skin_dir.vpk" to null,
                "addons/profile_x/pak01_dir.vpk" to null,
                "addons/.disabled/off_dir.vpk" to null,
            ),
            survey.sets.associate { it.directory.path.value to it.position },
        )
        assertEquals(listOf("grimoire/pak05_dir.vpk", "grimoire/pak05_000.vpk"), survey.sets.first { it.root == ForeignRoot.Priority }.files.map { it.path.value })
        assertEquals(setOf(CitadelPath("addons/.dmm.json")), survey.manifests.keys)
        assertTrue("pak01_dir.vpk" in survey.addons && ".dmm-clear" in survey.addons)
        assertTrue(inventory.reads.none { "pak01_dir.vpk" in it && "profile" !in it }, "Hideout's own files are not read")
        assertTrue(inventory.reads.none { "dmm-clear" in it || "backups" in it || "replays" in it }, "staging, backups and links are never looked into: ${inventory.reads}")
        assertEquals(info.toList(), survey.gameInfo?.toList())

        val again = Counting(files)
        assertIs<SurveyRead.Read>(surveyGame(again, root, listOf(held), previous = survey))
        assertEquals(emptyList(), again.reads, "nothing unchanged is read twice")
        files.rewrite(root, "$citadel/addons/pak02_dir.vpk", FakeVpk.directory(seed = 11))
        val changed = Counting(files)
        assertIs<SurveyRead.Read>(surveyGame(changed, root, listOf(held), previous = survey))
        assertEquals(listOf("addons/pak02_dir.vpk"), changed.reads)
    }

    @Test
    fun anImprintedSetSaysWhoItIs() = runTest {
        val modinfo = resourceBytes("grimoire/modinfo.json")
        files.putBytes(root, "$citadel/addons/pak03_dir.vpk", FakeVpk.withRootFile("modinfo.json", modinfo))
        val set = assertIs<SurveyRead.Read>(surveyGame(files, root, listOf(held))).survey.sets.single { it.directory.path.value == "addons/pak03_dir.vpk" }
        assertEquals(Imprint.read(modinfo), set.imprint)
    }

    @Test
    fun aGameInfoThatCannotBeReadOrUnderstoodLeavesWhatLoadsUnknown() = runTest {
        files.putBytes(root, "$citadel/gameinfo.gi", "GameInfo\n{\n}\n".encodeToByteArray())
        assertIs<SurveyRead.Unreadable>(surveyGame(files, root, listOf(held)), "no SearchPaths")
        files.failFile(root, "$citadel/gameinfo.gi", "permission denied")
        assertEquals(SurveyRead.Unreadable("permission denied"), surveyGame(files, root, listOf(held)))
    }
}
