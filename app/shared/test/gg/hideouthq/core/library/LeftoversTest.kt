package gg.hideouthq.core.library

import gg.hideouthq.core.data.FileStamp
import gg.hideouthq.core.data.FolderEntry
import kotlin.test.Test
import kotlin.test.assertEquals

class LeftoversTest {
    private val second = SetFolder.Loading[1]

    private fun on(name: String, size: Long = 10, folder: SetFolder.On = SetFolder.Addons, kind: EntryKind = EntryKind.File) =
        OnEntry(folder, FolderEntry(name, kind, FileStamp(size, 0).takeIf { kind == EntryKind.File }))

    @Test
    fun onlyBarePakDirectoriesLoadAndEachTakesTheChunksOfItsNumber() {
        val loading = listOf(
            on("pak05_001.vpk", 300), on("pak05_dir.vpk", 1), on("pak05_000.vpk", 200),
            on("631628_pak07_dir.vpk"), on("set-3fa94c0d12be_dir.vpk"), on("pak5_dir.vpk"), on("pak06_000.vpk"),
            on("PAK08_DIR.VPK", 4), on("pak09_dir.vpk", kind = EntryKind.Link), on("pak10_dir.vpk", kind = EntryKind.Directory),
            on("pak01_dir.vpk"),
        )

        val found = leftovers(loading, owned = setOf("game/citadel/addons/pak01_dir.vpk"))

        assertEquals(listOf("pak05_dir.vpk", "PAK08_DIR.VPK"), found.map { it.name })
        val (five, eight) = found
        assertEquals(listOf(VpkPart.Directory, VpkPart.Chunk(0), VpkPart.Chunk(1)), five.files.map { it.part })
        assertEquals(listOf("addons/pak05_dir.vpk", "addons/pak05_000.vpk", "addons/pak05_001.vpk"), five.paths.map { it.value })
        assertEquals(501, five.sizeBytes)
        assertEquals(Slot(5), five.slot)
        assertEquals(listOf("PAK08_DIR.VPK"), eight.files.map { it.name }, "the game mounts a directory whatever its case")
    }

    @Test
    fun aFolderPastAddonsHasItsOwnNumbersAndHideoutsOwnNamesThereStayOut() {
        val loading = listOf(on("pak03_dir.vpk", folder = second), on("pak03_000.vpk", folder = second), on("pak04_dir.vpk", folder = second), on("pak03_dir.vpk"))

        val found = leftovers(loading, owned = setOf("game/citadel/hideout_addons2/pak04_dir.vpk"))

        assertEquals(listOf(Slot(3), Slot.of(second, PakNumber(3))), found.map { it.slot }, "addons loads first")
        assertEquals(listOf(listOf("addons/pak03_dir.vpk"), listOf("hideout_addons2/pak03_dir.vpk", "hideout_addons2/pak03_000.vpk")), found.map { set -> set.paths.map { it.value } })
    }

    @Test
    fun aChunkOfHideoutsOwnNumberIsNotAnotherSets() {
        val loading = listOf(on("pak02_dir.vpk"), on("pak02_000.vpk"), on("pak02_001.vpk"))

        assertEquals(emptyList(), leftovers(loading, owned = setOf("game/citadel/addons/pak02_dir.vpk", "game/citadel/addons/pak02_000.vpk")))
    }
}
