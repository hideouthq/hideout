package gg.hideouthq.core.library

import gg.hideouthq.core.data.CacheStorage
import gg.hideouthq.core.data.Preferences
import gg.hideouthq.core.data.ProcessScan
import gg.hideouthq.core.data.fake.FakeDownloader
import gg.hideouthq.core.data.fake.FakeGameBanana
import gg.hideouthq.core.data.fake.FakeGameLocator
import gg.hideouthq.core.data.fake.FakeProcesses
import gg.hideouthq.core.data.fake.InMemoryGameFiles
import gg.hideouthq.core.data.fake.InMemoryLibraryStore
import gg.hideouthq.core.data.fake.MemoryStorage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ResourceIndexCacheTest {
    @Test
    fun contentHashSurvivesRenumberAndLocationChangesButChangedContentRebuilds() = runTest {
        val storage = MemoryStorage()
        var reads = 0
        val source = ResourceIndexSource { _, _, _ -> reads++; ResourceIndex.Ready(mapOf("models/$reads.vmdl_c" to reads.toLong())) }
        val original = priorityRecord("a", 1)
        val first = ResourceIndexCache(storage, source).read(original, original.files.single())
        val renumbered = original.placed(Placement.On(Slot(9)))
        assertEquals(first, ResourceIndexCache(storage, source).read(renumbered, renumbered.files.single()), "reuse across a restart and a renumber")
        val renamed = original.placed(Placement.Off)
        assertEquals(first, ResourceIndexCache(storage, source).read(renamed, renamed.files.single()), "reuse across a move to the off folder")
        assertEquals(1, reads)
        val changed = renamed.copy(sets = renamed.sets.map { set -> set.copy(files = set.files.map { it.copy(image = it.image.copy(sha256 = "f".repeat(64))) }) })
        assertEquals(ResourceIndex.Ready(mapOf("models/2.vmdl_c" to 2L)), ResourceIndexCache(storage, source).read(changed, changed.files.single()))
        assertEquals(2, reads)
    }

    @Test
    fun missingFailedAndCorruptCachesRebuildAndUnavailableReadsAreNotPersisted() = runTest {
        val record = priorityRecord("a", 1)
        val directory = record.files.single()
        val storage = MemoryStorage(mapOf("vpk-resources-v2-${directory.sha256}.json" to "broken"))
        var reads = 0
        val cache = ResourceIndexCache(storage, ResourceIndexSource { _, _, _ ->
            if (++reads == 1) ResourceIndex.Unavailable else ResourceIndex.Ready(mapOf("shared" to 1L))
        })
        assertIs<ResourceIndex.Unavailable>(cache.read(record, directory))
        assertEquals(ResourceIndex.Ready(mapOf("shared" to 1L)), cache.read(record, directory))
        assertEquals(ResourceIndex.Ready(mapOf("shared" to 1L)), cache.read(record, directory))
        assertEquals(2, reads)
        val failedStorage = object : CacheStorage {
            override suspend fun read(name: String): String? = error("no cache access")
            override suspend fun write(name: String, text: String) { error("disk full") }
        }
        assertEquals(ResourceIndex.Ready(mapOf("shared" to 1L)), ResourceIndexCache(failedStorage, ResourceIndexSource { _, _, _ -> ResourceIndex.Ready(mapOf("shared" to 1L)) }).read(record, directory))
        assertIs<ResourceIndex.Unavailable>(ResourceIndexCache(failedStorage, ResourceIndexSource { _, _, _ -> error("no file access") }).read(record, directory))
    }

    @Test
    fun brokenDisabledDuplicateDoesNotHideHealthyPeersOrPreventMovingAbove() = runTest {
        val healthy = listOf(priorityRecord("a", 1), priorityRecord("b", 2))
        val broken = priorityRecord("broken", null).let { record ->
            record.copy(sets = record.sets.map { set ->
                set.copy(files = set.files.map { it.copy(image = it.image.copy(sha256 = healthy.last().files.single().sha256)) })
            })
        }
        val root = healthy.first().gameRoot
        val files = InMemoryGameFiles().apply {
            putBytes(root, InMemoryGameFiles.GameInfo, "GameInfo { FileSystem { SearchPaths { Game citadel } } }".encodeToByteArray())
            healthy.forEach { record -> record.files.forEach { file -> putFile(root, file.relativePath(record), file.sizeBytes, file.sha256) } }
        }
        val store = InMemoryLibraryStore(LibraryManifest(records = healthy + broken))
        val library = ModLibrary(FakeGameLocator(listOf(root.path), mapOf(root.path to null)), Preferences(MemoryStorage()), store, files,
            InstallPorts(FakeGameBanana(), FakeDownloader(files), files, files, FakeProcesses(ProcessScan.Seen(game = true))), backgroundScope)
        val priorities = FilePriorities(library, ResourceIndexCache(MemoryStorage(), ResourceIndexSource { _, _, image -> indexOf(image, "shared") }))
        val report = priorities.inspect("a").stateIn(backgroundScope, SharingStarted.Eagerly, FilePriorityReport())
        library.start()
        runCurrent()
        assertTrue("broken" in assertIs<LibraryStatus.Ready>(library.state.value.library).damaged)
        assertEquals(listOf("b"), report.value.peers.map { it.installId })
        assertEquals(emptyList(), report.value.unchecked)
        priorities.moveAbove("b", "a")
        runCurrent()
        assertEquals(listOf("b", "a", "broken"), library.state.value.orderView?.sets)
        assertTrue(files.linkedPaths.isEmpty() && files.removedPaths.isEmpty())
    }

    @Test
    fun existingInstallsIndexAsynchronouslyAndMoveAboveSavesWhileRunningWithoutMovingFiles() = runTest {
        val records = listOf(priorityRecord("a", 1), priorityRecord("b", 2))
        val root = records.first().gameRoot
        val files = InMemoryGameFiles().apply {
            putBytes(root, InMemoryGameFiles.GameInfo, "GameInfo { FileSystem { SearchPaths { Game citadel } } }".encodeToByteArray())
            records.forEach { record -> record.files.forEach { file -> putFile(root, file.relativePath(record), file.sizeBytes, file.sha256) } }
        }
        val store = InMemoryLibraryStore(LibraryManifest(records = records))
        val library = ModLibrary(FakeGameLocator(listOf(root.path), mapOf(root.path to null)), Preferences(MemoryStorage()), store, files,
            InstallPorts(FakeGameBanana(), FakeDownloader(files), files, files, FakeProcesses(ProcessScan.Seen(game = true))), backgroundScope)
        val gate = CompletableDeferred<Unit>()
        val source = ResourceIndexSource { _, _, image -> gate.await(); indexOf(image, "shared") }
        val priorities = FilePriorities(library, ResourceIndexCache(MemoryStorage(), source))
        val report = priorities.inspect("a").stateIn(backgroundScope, SharingStarted.Eagerly, FilePriorityReport())
        library.start()
        runCurrent()
        assertIs<LibraryStatus.Ready>(library.state.value.library)
        assertTrue(library.state.value.canEditChoices)
        assertTrue(library.state.value.canInstall)
        assertEquals(listOf(FileIndexIssue("a", "a", FileIndexState.Reading), FileIndexIssue("b", "b", FileIndexState.Reading)), report.value.unchecked)
        library.reorder(library.state.value.orderView!!, listOf("b", "a"))
        runCurrent()
        assertEquals(listOf("b", "a"), store.manifest?.savedChoices?.target?.activeProfile?.orders?.get(root), "index work does not hold the choice writer")
        assertEquals(records, store.manifest?.records)
        gate.complete(Unit)
        runCurrent()
        assertEquals(FilePriorityRelation.OtherFirst, report.value.peers.single().chosen)
        assertEquals(FilePriorityRelation.SelectedFirst, report.value.peers.single().applied)
        priorities.moveAbove("a", "b")
        runCurrent()
        assertEquals(listOf("a", "b"), library.state.value.orderView?.sets)
        assertEquals(records, store.manifest?.records)
        assertEquals(FilePriorityRelation.SelectedFirst, report.value.peers.single().chosen)
        assertEquals(FilePriorityRelation.SelectedFirst, report.value.peers.single().applied)
        val settledSaves = store.saved.size
        priorities.moveAbove("a", "b")
        runCurrent()
        assertEquals(settledSaves, store.saved.size, "a stale move action does not reorder an already earlier mod")
        library.setEnabled("a", false)
        runCurrent()
        assertFalse(report.value.chosenEnabled)
        assertEquals(FilePriorityRelation.NotTogether, report.value.peers.single().chosen)
        assertEquals(records, store.manifest?.records)
        assertTrue(files.linkedPaths.isEmpty() && files.removedPaths.isEmpty())
    }
}
