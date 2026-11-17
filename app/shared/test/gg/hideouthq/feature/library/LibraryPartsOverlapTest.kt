package gg.hideouthq.feature.library

import gg.hideouthq.AppContainer
import gg.hideouthq.core.data.FolderOpener
import gg.hideouthq.core.data.fake.InMemoryLibraryStore
import gg.hideouthq.core.data.fake.SampleGameRoot
import gg.hideouthq.core.data.fake.sampleGameFiles
import gg.hideouthq.core.data.fake.sampleLibraryManifest
import gg.hideouthq.core.library.FileImage
import gg.hideouthq.core.library.ManifestRecord
import gg.hideouthq.core.library.ResourceIndex
import gg.hideouthq.core.library.ResourceIndexSource
import gg.hideouthq.core.library.SetFile
import gg.hideouthq.core.library.VpkPart
import gg.hideouthq.core.library.relativePath
import gg.hideouthq.feature.withViewModels
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryPartsOverlapTest {
    private fun twoParts(record: ManifestRecord, sha256: String): ManifestRecord {
        val first = record.sets.single()
        return record.copy(sets = listOf(first, first.copy(componentKey = "extra/pak01_dir.vpk", files = listOf(SetFile(VpkPart.Directory, FileImage(1_000, sha256))))))
    }

    @Test
    fun aRowSaysItsPartsOverlapWhenTwoChangeOneFileWithOtherBytesWhetherTheModIsOnOrOff() = withViewModels { viewModels ->
        val sample = sampleLibraryManifest()
        val faunExtra = "9".repeat(64)
        val werecatExtra = "8".repeat(64)
        val records = sample.records.map { record ->
            when (record.installId) {
                "faun-celeste" -> twoParts(record, faunExtra)
                "werecat" -> twoParts(record, werecatExtra)
                else -> record
            }
        }
        val files = sampleGameFiles().apply {
            records.filter { it.installId == "faun-celeste" || it.installId == "werecat" }.forEach { record ->
                record.files.forEach { putFile(SampleGameRoot, it.relativePath(record), it.sizeBytes, it.sha256) }
            }
        }
        val trees = mapOf(
            "d".repeat(64) to mapOf("models/faun.vmdl_c" to 1L),
            faunExtra to mapOf("models/faun.vmdl_c" to 2L, "readme.txt" to 2L),
            "b".repeat(64) to mapOf("models/werecat.vmdl_c" to 1L, "readme.txt" to 1L),
            werecatExtra to mapOf("models/werecat_hat.vmdl_c" to 2L, "readme.txt" to 2L),
        )
        val container = AppContainer.fake(
            libraryStore = InMemoryLibraryStore(sample.copy(records = records)), files = files,
            resourceIndexSource = ResourceIndexSource { _, _, image -> ResourceIndex.Ready(trees[image.sha256].orEmpty()) },
        )
        val vm = LibraryViewModel(container.modLibrary, FolderOpener { _, _ -> true }, conflicts = container.filePriorities!!.conflicts()).also { viewModels.keep(it) }
        backgroundScope.launch { vm.uiState.collect {} }
        runCurrent()
        val overlap = vm.uiState.value.rows.associate { it.installId to it.partsOverlap }
        assertEquals(true, overlap["faun-celeste"], "the off mod's two parts both change the faun model")
        assertEquals(false, overlap["werecat"], "its parts change other models, and only a readme the packer adds differs")
        assertEquals(false, overlap["lilith"], "a mod of one part has no order to change")
    }
}
