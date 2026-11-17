package gg.hideouthq.core.library

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
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

private class RecordingResourceIndexSource : ResourceIndexSource {
    val calls = mutableListOf<Pair<GameRoot, String>>()

    override suspend fun read(root: GameRoot, relativePath: String, image: FileImage): ResourceIndex {
        calls += root to relativePath
        return indexOf(image, "shared")
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
private fun TestScope.libraryOf(records: List<ManifestRecord>, foundRoot: GameRoot): ModLibrary {
    val files = InMemoryGameFiles().apply {
        putBytes(foundRoot, InMemoryGameFiles.GameInfo, "GameInfo { FileSystem { SearchPaths { Game citadel } } }".encodeToByteArray())
        records.forEach { record -> record.files.forEach { file -> putFile(record.gameRoot, file.relativePath(record), file.sizeBytes, file.sha256) } }
    }
    val store = InMemoryLibraryStore(LibraryManifest(records = records))
    return ModLibrary(FakeGameLocator(listOf(foundRoot.path), mapOf(foundRoot.path to null)), Preferences(MemoryStorage()), store, files,
        InstallPorts(FakeGameBanana(), FakeDownloader(files), files, files, FakeProcesses(ProcessScan.Seen(game = true))), backgroundScope)
}

@OptIn(ExperimentalCoroutinesApi::class)
class FilePrioritiesIndexingTest {
    @Test
    fun inspectingReadsOnlySelectedAndItsEnabledPeersInTheCurrentGameRoot() = runTest {
        val root = GameRoot("/games/Deadlock")
        val otherRoot = GameRoot("/other/Deadlock")
        val a = priorityRecord("a", 1)
        val b = priorityRecord("b", 2)
        val off = priorityRecord("off", null)
        val foreign = priorityRecord("x", 4).copy(gameRoot = otherRoot)
        val library = libraryOf(listOf(a, b, off, foreign), root)
        val source = RecordingResourceIndexSource()
        val priorities = FilePriorities(library, ResourceIndexCache(MemoryStorage(), source))
        val report = priorities.inspect("a").stateIn(backgroundScope, SharingStarted.Eagerly, FilePriorityReport())
        library.start()
        runCurrent()
        assertEquals(listOf("b"), report.value.peers.map { it.installId }, "the disabled and foreign-root records are not peers")
        assertEquals(
            setOf(root to a.files.single().relativePath(a), root to b.files.single().relativePath(b)),
            source.calls.toSet(),
            "only the selected record and its current-root peer are read, not the disabled or foreign-root records",
        )
    }

    @Test
    fun indexesAreReadOnlyForAnInspectionAndReusedWhenItReopens() = runTest {
        val records = listOf(priorityRecord("a", 1), priorityRecord("b", 2))
        val library = libraryOf(records, records.first().gameRoot)
        val source = RecordingResourceIndexSource()
        val priorities = FilePriorities(library, ResourceIndexCache(MemoryStorage(), source))
        library.start()
        runCurrent()
        library.setEnabled("a", false)
        runCurrent()
        library.setEnabled("a", true)
        runCurrent()
        assertEquals(emptyList(), source.calls, "no priority-index reads happen at startup or during idle library updates")
        val firstJob = launch { priorities.inspect("a").collect {} }
        runCurrent()
        val firstReads = source.calls.size
        assertEquals(2, firstReads, "the selected record and its peer are each read once")
        firstJob.cancel()
        runCurrent()
        val secondJob = launch { priorities.inspect("a").collect {} }
        runCurrent()
        assertEquals(firstReads, source.calls.size, "indexes already known from the first inspection are not re-read")
        secondJob.cancel()
    }

    @Test
    fun theConflictCountFollowsADragBeforeDeadlockCloses() = runTest {
        val a = priorityRecord("a", 1)
        val b = priorityRecord("b", 2)
        val library = libraryOf(listOf(a, b), a.gameRoot)
        val source = ResourceIndexSource { _, _, image -> ResourceIndex.Ready(mapOf("models/ivy.vmdl_c" to image.sha256.hashCode().toLong())) }
        val report = FilePriorities(library, ResourceIndexCache(MemoryStorage(), source)).conflicts().stateIn(backgroundScope, SharingStarted.Eagerly, ConflictReport())
        library.start()
        runCurrent()
        assertEquals(listOf(0, 1), listOf(report.value.hiddenModels("a"), report.value.hiddenModels("b")), "b loads after a")
        library.reorder(library.state.value.orderView!!, listOf("b", "a"))
        runCurrent()
        assertEquals(listOf(a, b), (library.state.value.library as LibraryStatus.Ready).records, "Deadlock runs, so no file moved")
        assertEquals(listOf(1, 0), listOf(report.value.hiddenModels("a"), report.value.hiddenModels("b")), "the count follows the saved order")
    }

    @Test
    fun cancellingAnInspectionStopsItsIndexingWorkAtOnce() = runTest {
        val records = listOf(priorityRecord("a", 1), priorityRecord("b", 2))
        val library = libraryOf(records, records.first().gameRoot)
        val gate = CompletableDeferred<Unit>()
        var reads = 0
        val source = ResourceIndexSource { _, _, image -> reads++; gate.await(); indexOf(image, "shared") }
        val priorities = FilePriorities(library, ResourceIndexCache(MemoryStorage(), source))
        library.start()
        runCurrent()
        val job = launch { priorities.inspect("a").collect {} }
        runCurrent()
        assertEquals(1, reads, "the first candidate's read is in flight, gated on the source")
        job.cancel()
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        assertEquals(1, reads, "cancelling the inspection stops the in-flight read's continuation and the next candidate's read")
    }
}
