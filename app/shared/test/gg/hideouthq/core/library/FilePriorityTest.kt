package gg.hideouthq.core.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FilePriorityTest {
    private fun snapshot(records: List<ManifestRecord>) = FilePrioritySnapshot(records.map { it.installId }, records.filter { it.enabled }.mapTo(mutableSetOf()) { it.installId })

    private fun indexes(vararg entries: Pair<ManifestRecord, Set<String>>): Map<String, ResourceIndex> = entries.associate { (record, paths) ->
        val directory = record.files.single { it.isDirectory }
        directory.sha256 to indexOf(directory.image, *paths.toTypedArray())
    }

    @Test
    fun onlyFilesBothChangeWithDifferentBytesCountAndPackerFilesNever() {
        val ivy = priorityRecord("ivy", 1).copy(title = "Toon Ivy")
        val lizard = priorityRecord("lizard", 2).copy(title = "Lizard Wizard")
        val records = listOf(ivy, lizard)
        val packer = listOf("materials/default/default_ao.vtex_c", "readme.txt", "docs/License.md", "modinfo.json", "addoninfo.txt", "grimoire_meta.json", "panorama/image_compiler.vdata_c")
        fun index(record: ManifestRecord, own: Long) = record.files.single().image.sha256 to ResourceIndex.Ready(
            (packer.map { it.lowercase() } + "materials/ivy/skin.vmat_c").associateWith { own } + ("sounds/same.vsnd_c" to 7L),
        )
        val result = filePriority("lizard", records, snapshot(records), snapshot(records), mapOf(index(ivy, 1), index(lizard, 2)))
        assertEquals(FilePriorityReport(true, true, listOf(FilePriorityPeer(
            "ivy", "Toon Ivy", listOf("materials/ivy/skin.vmat_c"), FilePriorityRelation.OtherFirst, FilePriorityRelation.OtherFirst,
        ))), result, "byte-identical files and packer files are not shared")
        val same = filePriority("lizard", records, snapshot(records), snapshot(records), mapOf(index(ivy, 1), index(lizard, 1)))
        assertEquals(emptyList(), same.peers, "two mods that ship the same bytes look alike whichever loads first")
    }

    @Test
    fun peersSharingAModelComeFirstAndTheirModelsLeadTheirPaths() {
        val selected = priorityRecord("selected", 1)
        val textures = priorityRecord("textures", 2)
        val model = priorityRecord("model", 3)
        val records = listOf(selected, textures, model)
        val result = filePriority("selected", records, snapshot(records), snapshot(records), indexes(
            selected to setOf("a.vtex_c", "b.vtex_c", "z.vmdl_c"), textures to setOf("a.vtex_c", "b.vtex_c"), model to setOf("b.vtex_c", "z.vmdl_c"),
        ))
        assertEquals(listOf("model", "textures"), result.peers.map { it.installId })
        assertEquals(listOf("z.vmdl_c", "b.vtex_c"), result.peers.first().paths)
    }

    @Test
    fun aPairKeptOnTogetherReadsAsKeptFromEitherRecord() {
        val a = priorityRecord("a", 1)
        val b = priorityRecord("b", 2).copy(keptBeside = setOf("a"))
        val c = priorityRecord("c", 3)
        val records = listOf(a, b, c)
        val known = indexes(a to setOf("shared"), b to setOf("shared"), c to setOf("shared"))
        assertEquals(mapOf("b" to true, "c" to false), filePriority("a", records, snapshot(records), snapshot(records), known).peers.associate { it.installId to it.kept })
        assertEquals(mapOf("a" to true, "c" to false), filePriority("b", records, snapshot(records), snapshot(records), known).peers.associate { it.installId to it.kept })
    }

    @Test
    fun choicesAndAppliedOrderAreIndependent() {
        val a = priorityRecord("a", 1)
        val b = priorityRecord("b", 2)
        val records = listOf(a, b)
        val chosen = FilePrioritySnapshot(listOf("b", "a"), setOf("a", "b"))
        val result = filePriority("a", records, chosen, snapshot(records), indexes(a to setOf("shared"), b to setOf("shared")))
        assertEquals(FilePriorityPeer("b", "b", listOf("shared"), FilePriorityRelation.OtherFirst, FilePriorityRelation.SelectedFirst), result.peers.single())
    }

    @Test
    fun modsNeverEnabledTogetherAreNotPeersEvenDuringAProfileSwitch() {
        val a = priorityRecord("a", 1)
        val b = priorityRecord("b", null)
        val records = listOf(a, b)
        val chosen = FilePrioritySnapshot(listOf("a", "b"), setOf("b"))
        val known = indexes(a to setOf("shared"), b to setOf("shared"))
        val result = filePriority("b", records, chosen, snapshot(records), known)
        assertTrue(result.chosenEnabled)
        assertFalse(result.appliedEnabled)
        assertEquals(emptyList(), result.peers)
        assertEquals(emptyList(), result.unchecked)
        assertEquals(emptyList(), filePriority("a", records, chosen, snapshot(records), known).peers)
    }

    @Test
    fun turningAModOffRetainsOnlyItsCurrentlyAppliedRelationships() {
        val a = priorityRecord("a", 1)
        val b = priorityRecord("b", 2)
        val records = listOf(a, b)
        val chosen = FilePrioritySnapshot(listOf("a", "b"), setOf("b"))
        val result = filePriority("a", records, chosen, snapshot(records), indexes(a to setOf("shared"), b to setOf("shared")))
        assertFalse(result.chosenEnabled)
        assertTrue(result.appliedEnabled)
        assertEquals(FilePriorityPeer("b", "b", listOf("shared"), FilePriorityRelation.NotTogether, FilePriorityRelation.SelectedFirst), result.peers.single())
    }

    @Test
    fun disabledModsAndTheirMissingIndexesDoNotCompete() {
        val a = priorityRecord("a", 1)
        val off = priorityRecord("off", null)
        val records = listOf(a, off)
        val known = indexes(a to setOf("shared"))
        assertEquals(FilePriorityReport(), filePriority("off", records, snapshot(records), snapshot(records), known))
        val result = filePriority("a", records, snapshot(records), snapshot(records), known)
        assertEquals(emptyList(), result.peers)
        assertEquals(emptyList(), result.unchecked)
    }

    @Test
    fun componentPathsAreDeduplicatedPerInstallationWithoutSelfPeers() {
        val a = priorityRecord("a", 1, components = 2)
        val b = priorityRecord("b", 3)
        val records = listOf(a, b)
        val known = records.flatMap { it.sets }.associate { it.files.single().image.sha256 to indexOf(it.files.single().image, "shared") }
        val result = filePriority("a", records, snapshot(records), snapshot(records), known)
        assertEquals(listOf(FilePriorityPeer("b", "b", listOf("shared"), FilePriorityRelation.SelectedFirst, FilePriorityRelation.SelectedFirst)), result.peers)
        assertEquals(emptyList(), filePriority("a", listOf(a), snapshot(listOf(a)), snapshot(listOf(a)), known).peers)
    }

    @Test
    fun unreadableAndReadingIndexesStayExplicitWithoutHidingKnownPeers() {
        val a = priorityRecord("a", 1)
        val b = priorityRecord("b", 2)
        val c = priorityRecord("c", 3)
        val d = priorityRecord("d", 4)
        val records = listOf(a, b, c, d)
        val known = indexes(a to setOf("shared"), b to setOf("shared")) + (c.files.single().image.sha256 to ResourceIndex.Unavailable)
        val result = filePriority("a", records, snapshot(records), snapshot(records), known)
        assertEquals(listOf("b"), result.peers.map { it.installId })
        assertEquals(listOf(FileIndexIssue("c", "c", FileIndexState.Unavailable), FileIndexIssue("d", "d", FileIndexState.Reading)), result.unchecked)
    }

    @Test
    fun aPartlyIndexedModIsNotPresentedAsFullyCompared() {
        val a = priorityRecord("a", 1, components = 2)
        val b = priorityRecord("b", 3)
        val records = listOf(a, b)
        val known = mapOf(a.sets.first().files.single().image.sha256 to indexOf(a.sets.first().files.single().image, "shared"),
            b.files.single().image.sha256 to indexOf(b.files.single().image, "shared"))
        val result = filePriority("a", records, snapshot(records), snapshot(records), known)
        assertEquals(emptyList(), result.peers)
        assertEquals(listOf(FileIndexIssue("a", "a", FileIndexState.Reading)), result.unchecked)
    }

    @Test
    fun anUnavailableInstallationDoesNotInvalidateHealthyCachedMetadata() {
        val a = priorityRecord("a", 1)
        val b = priorityRecord("b", 2)
        val broken = a.placed(Placement.On(Slot(3))).copy(installId = "broken")
        val records = listOf(a, b, broken)
        val result = filePriority("a", records, snapshot(records), snapshot(records),
            indexes(a to setOf("shared"), b to setOf("shared")), unavailableInstalls = setOf("broken"))
        assertEquals(listOf("b"), result.peers.map { it.installId })
        assertEquals(listOf(FileIndexIssue("broken", "a", FileIndexState.Unavailable)), result.unchecked)
    }

    @Test
    fun peersUseInstallIdsAndSortByPathCountThenOrder() {
        val selected = priorityRecord("selected", 1)
        val a = priorityRecord("a", 2).copy(title = "Same title")
        val b = priorityRecord("b", 3).copy(title = "Same title")
        val c = priorityRecord("c", 4)
        val records = listOf(selected, a, b, c)
        val result = filePriority("selected", records, snapshot(records), snapshot(records), indexes(
            selected to setOf("one", "two"), a to setOf("one"), b to setOf("one", "two"), c to setOf("two"),
        ))
        assertEquals(listOf("b", "a", "c"), result.peers.map { it.installId })
        assertEquals(listOf("one", "two"), result.peers.first().paths)
    }

    @Test
    fun moveAbovePreservesEveryOtherModsOrderAndRejectsANoop() {
        val order = listOf("unrelated", "a", "hidden", "b", "selected")
        assertEquals(listOf("unrelated", "a", "hidden", "selected", "b"), moveAboveOrder("selected", "b", order))
        assertNull(moveAboveOrder("a", "selected", order))
        assertNull(moveAboveOrder("a", "a", order))
        assertNull(moveAboveOrder("missing", "a", order))
        assertNull(moveAboveOrder("a", "missing", order))
    }
}

internal fun indexOf(image: FileImage, vararg paths: String): ResourceIndex = ResourceIndex.Ready(paths.associateWith { image.sha256.hashCode().toLong() })

internal fun priorityRecord(id: String, first: Int?, components: Int = 1): ManifestRecord = ManifestRecord(
    id, GameRoot("/games/Deadlock"), id, placedAt(first),
    (0 until components).map { index ->
        InstalledSet(
            InstalledSource.Local(id, "$id.zip"), null, null, SetOrigin.Imported(FileImage(1, "a".repeat(64))), null, "parts/${index}_dir.vpk",
            listOf(SetFile(VpkPart.Directory, Sha256.image("$id/$index".encodeToByteArray()))),
        )
    },
)
