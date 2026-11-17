package gg.hideouthq.core.library

import gg.hideouthq.core.data.FileStamp
import gg.hideouthq.core.data.FolderEntry
import kotlin.test.Test
import kotlin.test.assertEquals

class ConflictsTest {
    private fun managed(id: String, part: Int = 0) = Mounted.Managed(id, SetKey.of(InstalledSource.Local(id, "$id.zip"), "parts/${part}_dir.vpk"))

    private fun mount(number: Int, by: Mounted) = Mount(PakNumber(number), by)

    private fun tree(vararg files: Pair<String, Long>) = ResourceIndex.Ready(files.toMap())

    private val a = managed("a")
    private val b = managed("b")
    private val pak03 = Mounted.Foreign("pak03_dir.vpk", FileStamp(10, 1))

    @Test
    fun theLowerNumberShowsItsModelAndTheHigherOneLosesIt() {
        val report = conflictReport(listOf(mount(2, b), mount(1, a)), mapOf(a to tree("models/ivy.vmdl_c" to 1), b to tree("models/ivy.vmdl_c" to 2)))
        assertEquals(1, report.hiddenModels("b"))
        assertEquals(listOf<Mounted>(a), report.winnersOf("b"))
        assertEquals(0, report.hiddenModels("a"), "what loads first loses nothing")
    }

    @Test
    fun aModelWithTheSameBytesIsNoLoss() {
        val report = conflictReport(listOf(mount(1, a), mount(2, b)), mapOf(a to tree("models/ivy.vmdl_c" to 7), b to tree("models/ivy.vmdl_c" to 7)))
        assertEquals(ConflictReport(), report)
    }

    @Test
    fun aModsEarlierPartHidesTheSameModelInItsLaterOne() {
        val second = managed("a", part = 1)
        val report = conflictReport(listOf(mount(5, a), mount(6, second)), mapOf(a to tree("models/ivy.vmdl_c" to 1), second to tree("models/ivy.vmdl_c" to 2)))
        assertEquals(1, report.hiddenModels("a"))
        assertEquals(listOf<Mounted>(a), report.winnersOf("a"), "its own first part shows instead")
    }

    @Test
    fun aFileHideoutDoesNotOwnHidesModelsAndLosesThemLikeAnyOther() {
        val c = managed("c")
        val report = conflictReport(
            listOf(mount(2, a), mount(3, pak03), mount(4, c)),
            mapOf(a to tree("models/ivy.vmdl_c" to 1), pak03 to tree("models/ivy.vmdl_c" to 3, "models/haze.vmdl_c" to 3), c to tree("models/haze.vmdl_c" to 4)),
        )
        assertEquals(1, report.hiddenModels("c"))
        assertEquals(listOf<Mounted>(pak03), report.winnersOf("c"))
        assertEquals(listOf(ModelLoss("models/ivy.vmdl_c", mount(3, pak03), mount(2, a))), report.losses.filter { it.loser.by == pak03 }, "the foreign file loses to what loads before it")
    }

    @Test
    fun onlyModelsCountNeverPackerFilesOrOtherResources() {
        val paths = listOf("readme.txt", "addoninfo.txt", "materials/ivy/body.vmat_c", "sounds/ivy.vsnd_c")
        val report = conflictReport(listOf(mount(1, a), mount(2, b)), mapOf(a to tree(*paths.map { it to 1L }.toTypedArray()), b to tree(*paths.map { it to 2L }.toTypedArray())))
        assertEquals(ConflictReport(), report)
    }

    @Test
    fun aMountWhoseTreeCouldNotBeReadHidesNothing() {
        val c = managed("c")
        val report = conflictReport(listOf(mount(1, a), mount(2, c), mount(3, b)), mapOf(a to ResourceIndex.Unavailable, b to tree("models/ivy.vmdl_c" to 2)))
        assertEquals(0, report.hiddenModels("b"), "neither an unreadable tree nor one not read yet")
    }

    @Test
    fun aNewModsRivalsAreTheModsShowingItsModelsWithOtherBytesNeverAFileHideoutDoesNotOwn() {
        val c = managed("c")
        val incoming = listOf(tree("models/ivy.vmdl_c" to 9, "models/haze.vmdl_c" to 3, "readme.txt" to 9))
        val mounts = listOf(mount(1, a), mount(2, b), mount(3, pak03), mount(4, c))
        val files = mapOf(a to tree("models/ivy.vmdl_c" to 1), b to tree("models/haze.vmdl_c" to 3, "readme.txt" to 1), pak03 to tree("models/ivy.vmdl_c" to 5), c to tree("models/seven.vmdl_c" to 2))
        assertEquals(setOf("a"), modelRivals(incoming, mounts, files), "b holds the same haze and only a readme differs; c changes another model")
    }

    @Test
    fun addonsMountsEveryOnSetAtItsPlannedNumberAndEveryBarePakDirectoryHideoutDoesNotOwn() {
        val on = priorityRecord("on", 5, components = 2)
        val off = priorityRecord("off", null)
        val names = listOf("pak05_dir.vpk", "pak06_dir.vpk", "pak03_dir.vpk", "pak03_000.vpk", "623518_pak07_dir.vpk", "pak08_dir.vpk.hideout-t", "readme.txt")
        val addons = names.map { FolderEntry(it, EntryKind.File, FileStamp(1, 1)) }
        val mounts = mounts(listOf(on.placed(Placement.On(PakNumber(1))), off), addons, now = listOf(on, off))
        assertEquals(
            listOf(mount(1, Mounted.Managed("on", on.sets[0].key)), mount(2, Mounted.Managed("on", on.sets[1].key)), mount(3, Mounted.Foreign("pak03_dir.vpk", FileStamp(1, 1)))),
            mounts,
            "the mod's own files at 05 and 06 are where it is now, not files of someone else",
        )
    }
}
