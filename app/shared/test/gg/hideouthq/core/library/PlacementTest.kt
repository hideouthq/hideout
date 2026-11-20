package gg.hideouthq.core.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlacementTest {
    private val root = GameRoot("/games/Deadlock")
    private val source = InstalledSource.Remote(SubmissionIdentity(SubmissionKind.Mod, 721_908, 1_829_854, null))

    private fun files(tag: String, vararg chunks: Int) = listOf(directory(Sha256.image("$tag-dir".encodeToByteArray()))) +
        chunks.map { SetFile(VpkPart.Chunk(it), Sha256.image("$tag-$it".encodeToByteArray())) }

    private fun set(component: String, vararg chunks: Int) = InstalledSet(source, null, null, SetOrigin.Imported(Sha256.image(ByteArray(1))), null, component, files(component, *chunks))

    private val hood = set("hood_dir.vpk", 0, 2)
    private val hat = set("hat/hat_dir.vpk")
    private val mod = ManifestRecord("m", root, "Mod", Placement.On(Slot(46)), listOf(hood, hat))

    @Test
    fun aModThatIsOnNamesEachSetByItsNumberAndOneThatIsOffByItsKey() {
        assertEquals(listOf("pak46_dir.vpk", "pak46_000.vpk", "pak46_002.vpk", "pak47_dir.vpk"), mod.files.map { it.name })
        assertEquals(listOf(Slot(46), Slot(47)), mod.numbers)
        assertEquals(Slot(47), mod.numberOf(hat))
        assertEquals(SetFolder.Addons, mod.setFolder())

        val off = mod.placed(Placement.Off)
        val (hoodStem, hatStem) = hood.key.offStem("m") to hat.key.offStem("m")
        assertEquals(listOf("${hoodStem}_dir.vpk", "${hoodStem}_000.vpk", "${hoodStem}_002.vpk", "${hatStem}_dir.vpk"), off.files.map { it.name })
        assertEquals(emptyList(), off.numbers)
        assertNull(off.numberOf(hat))
        assertEquals(SetFolder.Off("mod-721908"), off.setFolder())
        assertTrue(off.files.all { off.setFolder().admits(it.name) } && mod.files.all { mod.setFolder().admits(it.name) }, "every name is one its folder admits")
        assertEquals(off.placed(Placement.Off, listOf(hat, hood)).files.map { it.name }.toSet(), off.files.map { it.name }.toSet(), "an off mod's names hold no order")
        assertEquals(listOf(hood, hat), mod.files.map(mod::setOf).distinct())
    }

    @Test
    fun aModPastAddonsIsNamedFromPak01InTheNextLoadingFolder() {
        val past = mod.placed(Placement.On(Slot(100)))
        assertEquals(SetFolder.On(2), past.setFolder())
        assertEquals(listOf("pak01_dir.vpk", "pak01_000.vpk", "pak01_002.vpk", "pak02_dir.vpk"), past.files.map { it.name })
        assertEquals("game/citadel/hideout_addons2/pak02_dir.vpk", past.files.last().relativePath(past))
        assertTrue(past.files.all { past.setFolder().admits(it.name) })
    }

    @Test
    fun aBlockAcrossTwoFoldersAPartOutOfOrderAndTwoSetsOfOneKeyAreRefused() {
        assertFailsWith<IllegalArgumentException>("47 and 48 would end past 99") { mod.placed(Placement.On(Slot(99))) }
        assertFailsWith<IllegalArgumentException>("one set in hideout_addons2, one in hideout_addons3") { mod.placed(Placement.On(Slot(198))) }
        mod.placed(Placement.On(Slot(98)))
        assertFailsWith<IllegalArgumentException>("a chunk before the directory") { hood.copy(files = hood.files.reversed()) }
        assertFailsWith<IllegalArgumentException>("a chunk twice") { hood.copy(files = hood.files + hood.files.last()) }
        assertFailsWith<IllegalArgumentException>("no directory") { hood.copy(files = hood.files.drop(1)) }
        assertFailsWith<IllegalArgumentException>("one component twice") { mod.copy(sets = listOf(hood, hood)) }
    }

    @Test
    fun aMoveKeepsTheModAndItsSetsAndChangesSomething() {
        assertFailsWith<IllegalArgumentException>("a no-op") { Move(mod, mod) }
        assertFailsWith<IllegalArgumentException>("another set") { Move(mod, mod.placed(mod.placement, listOf(hood, set("other_dir.vpk")))) }
        assertFailsWith<IllegalArgumentException>("other bytes") { Move(mod, mod.copy(sets = listOf(hood, hat.copy(files = files("changed"))))) }
        assertFailsWith<IllegalArgumentException>("another title") { Move(mod, mod.copy(title = "Other").placed(Placement.Off)) }
    }

    @Test
    fun aMoveRenamesTheSetsWhoseNamesChangeAndPairsTheirFilesPartByPart() {
        val off = mod.placed(Placement.Off)
        val turnedOff = Move(mod, off)
        assertEquals(2, turnedOff.moving.size, "a toggle moves every set")
        assertEquals(mod.files.zip(off.files), turnedOff.moving.flatten())

        val renumbered = Move(mod, mod.placed(Placement.On(Slot(10))))
        assertEquals(listOf(listOf("pak46_dir.vpk" to "pak10_dir.vpk", "pak46_000.vpk" to "pak10_000.vpk", "pak46_002.vpk" to "pak10_002.vpk"), listOf("pak47_dir.vpk" to "pak11_dir.vpk")), renumbered.moving.map { set -> set.map { (from, to) -> from.name to to.name } })

        val swapped = Move(mod, mod.placed(mod.placement, listOf(hat, hood)))
        assertEquals(listOf(listOf("pak46_dir.vpk" to "pak47_dir.vpk", "pak46_000.vpk" to "pak47_000.vpk", "pak46_002.vpk" to "pak47_002.vpk"), listOf("pak47_dir.vpk" to "pak46_dir.vpk")), swapped.moving.map { set -> set.map { (from, to) -> from.name to to.name } }, "an on mod's parts swap numbers")

        val shifted = Move(mod, mod.placed(Placement.On(Slot(47)), listOf(hat, hood)))
        assertEquals(listOf(listOf("pak46_dir.vpk" to "pak48_dir.vpk", "pak46_000.vpk" to "pak48_000.vpk", "pak46_002.vpk" to "pak48_002.vpk")), shifted.moving.map { set -> set.map { (from, to) -> from.name to to.name } }, "a set that keeps its number does not move")

        assertEquals(emptyList(), Move(off, off.placed(Placement.Off, listOf(hat, hood))).moving, "an off mod's parts reorder moves no file")
    }

    @Test
    fun aMoveToTheSameNamesInTheNextLoadingFolderMovesEverySet() {
        val past = mod.placed(Placement.On(Slot(145)))
        val moved = Move(mod, past)
        assertEquals(mod.files.map { it.name }, past.files.map { it.name }, "pak46 and pak47 in both folders")
        assertEquals(mod.files.zip(past.files), moved.moving.flatten())
        assertEquals(listOf("game/citadel/addons/pak46_dir.vpk" to "game/citadel/hideout_addons2/pak46_dir.vpk"), moved.moving.flatten().take(1).map { (from, to) -> from.relativePath(mod) to to.relativePath(past) })
    }
}
