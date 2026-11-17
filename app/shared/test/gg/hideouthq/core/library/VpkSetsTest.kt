package gg.hideouthq.core.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class VpkSetsTest {
    private class Entry(val path: String, val archive: Int, val offset: Long, val length: Long, val preload: Int = 0)

    private fun embedded(path: String, offset: Long, length: Long) = Entry(path, EmbeddedIndex, offset, length)

    private fun vpk(entries: List<Entry>, embeddedBytes: Int = 0, version: Int = 2, trailingTreeBytes: Int = 0): ByteArray {
        val tree = Bytes()
        entries.groupBy { it.path.substringAfterLast('.') }.forEach { (extension, byExtension) ->
            tree.string(extension)
            byExtension.groupBy { it.path.substringBeforeLast('/', " ") }.forEach { (folder, inFolder) ->
                tree.string(folder)
                inFolder.forEach { entry ->
                    tree.string(entry.path.substringAfterLast('/').substringBeforeLast('.'))
                    tree.u32(0).u16(entry.preload).u16(entry.archive).u32(entry.offset).u32(entry.length).u16(0xFFFF)
                    repeat(entry.preload) { tree.byte(7) }
                }
                tree.byte(0)
            }
            tree.byte(0)
        }
        tree.byte(0)
        repeat(trailingTreeBytes) { tree.byte(1) }
        val header = Bytes().u32(0x55AA1234).u32(version.toLong()).u32(tree.size.toLong())
        if (version == 2) header.u32(embeddedBytes.toLong()).u32(0).u32(0).u32(0)
        return header.bytes() + tree.bytes() + ByteArray(embeddedBytes)
    }

    private fun read(bytes: ByteArray, fileSize: Long = bytes.size.toLong()): VpkRead = VpkFormat.read(bytes, fileSize)

    private var nextIndex = 0

    private fun staged(path: String, bytes: ByteArray) = StagedVpk(
        ref = StagedRef(StagingId("s1"), nextIndex++),
        path = path,
        sizeBytes = bytes.size.toLong(),
        sha256 = "a".repeat(64),
        directory = read(bytes),
    )

    private fun chunk(path: String, size: Int) = staged(path, ByteArray(size))

    @Test
    fun anEmbeddedDirectoryNamesNoChunks() {
        val bytes = vpk(listOf(embedded("materials/skin.vtex_c", 0, 100), embedded("models/ivy.vmdl_c", 100, 50)), embeddedBytes = 150)
        assertEquals(emptyMap(), assertIs<VpkRead.Parsed>(read(bytes)).chunkExtents)
    }

    @Test
    fun chunkReferencesAreTheIndexesTheTreeActuallyNames() {
        val bytes = vpk(listOf(Entry("a/x.vtex_c", 0, 10, 90), Entry("a/y.vtex_c", 2, 0, 40), Entry("a/z.vtex_c", 2, 40, 5)))
        assertEquals(mapOf(0 to 100L, 2 to 45L), assertIs<VpkRead.Parsed>(read(bytes)).chunkExtents)
    }

    @Test
    fun repeatedInternalPathsAreNotAReasonToRefuseASet() {
        val bytes = vpk(listOf(embedded("readme.txt", 0, 4), embedded("readme.txt", 4, 4)), embeddedBytes = 8)
        assertIs<VpkRead.Parsed>(read(bytes))
    }

    @Test
    fun aVersionOneDirectoryKeepsItsDataAfterTheTree() {
        val bytes = vpk(listOf(embedded("a.txt", 0, 10)), version = 1) + ByteArray(10)
        assertIs<VpkRead.Parsed>(read(bytes), "the entry reads the data after the tree")
    }

    @Test
    fun malformedDirectoriesAreUnreadable() {
        val good = vpk(listOf(embedded("a.txt", 0, 10)), embeddedBytes = 10)
        assertIs<VpkRead.Unreadable>(read(ByteArray(40) { 1 }), "no signature")
        assertIs<VpkRead.Unreadable>(read(good.copyOf(20), fileSize = good.size.toLong()), "truncated header and tree")
        assertIs<VpkRead.Unreadable>(read(vpk(listOf(embedded("a.txt", 0, 10)), embeddedBytes = 10, version = 3)), "unknown version")
        assertIs<VpkRead.Unreadable>(read(vpk(listOf(embedded("a.txt", 5, 10)), embeddedBytes = 10)), "embedded data past its end")
        assertIs<VpkRead.Unreadable>(read(vpk(listOf(embedded("a.txt", 0, 10)), embeddedBytes = 10, trailingTreeBytes = 3)), "bytes after the tree's end")
        val noTerminator = good.copyOf().also { it[good.size - 10 - 4] = 0 }
        assertIs<VpkRead.Unreadable>(read(noTerminator), "entry without its terminator")
        assertIs<VpkRead.Unreadable>(read(good.copyOf(good.size - 4)), "data section longer than the file")
        val treeTooLong = good.copyOf().also { it[8] = 0x7F }
        assertIs<VpkRead.Unreadable>(read(treeTooLong), "tree longer than the file")
    }

    @Test
    fun aDirectoryWithItsReferencedChunksIsOneSetNamedUnderOneNumber() {
        val dir = staged("Character/ivy_dir.vpk", vpk(listOf(Entry("m/a.vmdl_c", 0, 0, 100), Entry("m/b.vtex_c", 2, 0, 30))))
        val sets = assertIs<SetGrouping<StagedVpk>>(groupVpkSets(listOf(chunk("Character/ivy_002.vpk", 30), dir, chunk("Character/ivy_000.vpk", 100))))
        assertEquals(emptyList(), sets.rejected)
        val set = sets.sets.single()
        assertEquals("Character/ivy_dir.vpk", set.key)
        assertEquals(listOf("pak07_dir.vpk", "pak07_000.vpk", "pak07_002.vpk"), set.members.map { it.part.fileName(PakNumber(7).stem) })
    }

    @Test
    fun extraChunksOfTheSamePrefixStayWithTheSet() {
        val dir = staged("ivy_dir.vpk", vpk(listOf(Entry("a.vtex_c", 0, 0, 10))))
        val set = groupVpkSets(listOf(dir, chunk("ivy_000.vpk", 10), chunk("IVY_005.VPK", 3))).sets.single()
        assertEquals(listOf("pak12_dir.vpk", "pak12_000.vpk", "pak12_005.vpk"), set.members.map { it.part.fileName(PakNumber(12).stem) })
    }

    @Test
    fun aMissingOrShortReferencedChunkRefusesTheSet() {
        val dir = vpk(listOf(Entry("a.vtex_c", 0, 0, 10), Entry("b.vtex_c", 1, 0, 50)))
        val missing = groupVpkSets(listOf(staged("x/ivy_dir.vpk", dir), chunk("x/ivy_000.vpk", 10)))
        assertEquals(emptyList(), missing.sets)
        assertEquals(listOf(RejectedSet("x/ivy_dir.vpk", SetProblem.MissingChunks(listOf("ivy_001.vpk")))), missing.rejected)
        val short = groupVpkSets(listOf(staged("ivy_dir.vpk", dir), chunk("ivy_000.vpk", 10), chunk("ivy_001.vpk", 49)))
        assertEquals(listOf(RejectedSet("ivy_dir.vpk", SetProblem.ChunkTooShort("ivy_001.vpk", 50, 49))), short.rejected)
    }

    @Test
    fun aStandaloneFileMustCarryItsOwnData() {
        val single = staged("Gojo Full.vpk", vpk(listOf(embedded("a.txt", 0, 10)), embeddedBytes = 10))
        val set = groupVpkSets(listOf(single)).sets.single()
        assertEquals(listOf("pak03_dir.vpk"), set.members.map { it.part.fileName(PakNumber(3).stem) })
        val external = groupVpkSets(listOf(staged("lonely.vpk", vpk(listOf(Entry("a.txt", 0, 0, 10))))))
        assertEquals(listOf(RejectedSet("lonely.vpk", SetProblem.NeedsChunks)), external.rejected)
    }

    @Test
    fun aWholeVpkNamedLikeAChunkIsASetOfItsOwnUnlessItsDirectoryIsBesideIt() {
        val whole = vpk(listOf(embedded("models/vindicta.vmdl_c", 0, 10)), embeddedBytes = 10)
        val alone = groupVpkSets(listOf(staged("vindicta_skin_001.vpk", whole)))
        assertEquals(emptyList(), alone.rejected)
        assertEquals(listOf("pak04_dir.vpk"), alone.sets.single().members.map { it.part.fileName(PakNumber(4).stem) })
        val dir = staged("ivy_dir.vpk", vpk(listOf(Entry("a.vtex_c", 0, 0, whole.size.toLong()))))
        val beside = groupVpkSets(listOf(dir, staged("ivy_000.vpk", whole)))
        assertEquals(listOf("pak04_dir.vpk", "pak04_000.vpk"), beside.sets.single().members.map { it.part.fileName(PakNumber(4).stem) }, "beside its directory it is that set's chunk")
    }

    @Test
    fun aRenamedDirectoryTakesTheOneStrayChunkPrefixInItsFolderWhenItFitsAndOnlyForAnArchive() {
        val dir = vpk(listOf(Entry("a.vtex_c", 0, 0, 10), Entry("b.vtex_c", 1, 0, 4)))
        val files = listOf(staged("Main/mymod_dir.vpk", dir), chunk("Main/pak01_000.vpk", 10), chunk("Main/pak01_001.vpk", 4))
        val paired = groupVpkSets(files, pairStrays = true)
        assertEquals(emptyList(), paired.rejected)
        assertEquals(listOf("pak05_dir.vpk", "pak05_000.vpk", "pak05_001.vpk"), paired.sets.single().members.map { it.part.fileName(PakNumber(5).stem) })
        assertEquals(
            listOf(RejectedSet("Main/mymod_dir.vpk", SetProblem.MissingChunks(listOf("mymod_000.vpk", "mymod_001.vpk"))), RejectedSet("Main/pak01_000.vpk", SetProblem.Orphan), RejectedSet("Main/pak01_001.vpk", SetProblem.Orphan)),
            groupVpkSets(files).rejected, "files in the game keep the names the engine reads",
        )
        val short = groupVpkSets(listOf(staged("Main/mymod_dir.vpk", dir), chunk("Main/pak01_000.vpk", 10), chunk("Main/pak01_001.vpk", 3)), pairStrays = true)
        assertEquals(listOf("Main/mymod_dir.vpk", "Main/pak01_000.vpk", "Main/pak01_001.vpk"), short.rejected.map { it.key }, "chunks too short for the tree are not taken")
        val twoDirectories = groupVpkSets(files + staged("Main/other_dir.vpk", dir), pairStrays = true)
        assertEquals(emptyList(), twoDirectories.sets, "with two directories missing chunks in one folder, which owns them is a guess")
        val elsewhere = groupVpkSets(listOf(staged("Main/mymod_dir.vpk", dir), chunk("Other/pak01_000.vpk", 10), chunk("Other/pak01_001.vpk", 4)), pairStrays = true)
        assertEquals(emptyList(), elsewhere.sets, "chunks in another folder are not this directory's")
    }

    @Test
    fun severalSetsAreAllOfferedAndNoneIsPicked() {
        val one = vpk(listOf(embedded("a.txt", 0, 1)), embeddedBytes = 1)
        val grouping = groupVpkSets(listOf(staged("Blue/pak01_dir.vpk", one), staged("Red/pak01_dir.vpk", one), staged("extra.vpk", one)))
        assertEquals(listOf("Blue/pak01_dir.vpk", "Red/pak01_dir.vpk", "extra.vpk"), grouping.sets.map { it.key })
    }

    @Test
    fun orphanChunksUnreadableDirectoriesAndUnnameableIndexesAreRefused() {
        val grouping = groupVpkSets(
            listOf(
                chunk("left/a_000.vpk", 5),
                staged("bad_dir.vpk", ByteArray(64) { 3 }),
                staged("far_dir.vpk", vpk(listOf(Entry("a.txt", 1000, 0, 1)))),
            ),
        )
        assertEquals(emptyList(), grouping.sets)
        assertEquals(SetProblem.Orphan, grouping.rejected.single { it.key == "left/a_000.vpk" }.problem)
        assertIs<SetProblem.Unreadable>(grouping.rejected.single { it.key == "bad_dir.vpk" }.problem)
        assertEquals(SetProblem.ChunkIndexTooLarge(1000), grouping.rejected.single { it.key == "far_dir.vpk" }.problem)
    }

    private class Bytes {
        private val out = ArrayList<Byte>()
        val size get() = out.size

        fun byte(value: Int) = apply { out += value.toByte() }
        fun u16(value: Int) = apply { repeat(2) { byte((value shr (8 * it)) and 0xFF) } }
        fun u32(value: Long) = apply { repeat(4) { byte(((value shr (8 * it)) and 0xFF).toInt()) } }
        fun string(text: String) = apply { text.encodeToByteArray().forEach { out += it }; byte(0) }
        fun bytes() = out.toByteArray()
    }

    private companion object {
        const val EmbeddedIndex = 0x7FFF
    }
}
