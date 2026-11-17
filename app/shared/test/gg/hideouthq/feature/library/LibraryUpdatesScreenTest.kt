package gg.hideouthq.feature.library

import androidx.lifecycle.ViewModelStore
import gg.hideouthq.AppContainer
import gg.hideouthq.core.data.CatalogFreshness
import gg.hideouthq.core.data.CatalogRepository
import gg.hideouthq.core.data.CheckProgress
import gg.hideouthq.core.data.GameBananaClient
import gg.hideouthq.core.data.FileListing
import gg.hideouthq.core.data.FolderOpener
import gg.hideouthq.core.data.InstallStatus
import gg.hideouthq.core.data.LocalArchiveSnapshot
import gg.hideouthq.core.data.LocalFile
import gg.hideouthq.core.data.LocalImport
import gg.hideouthq.core.data.LocalImportResult
import gg.hideouthq.core.data.LocalRefusal
import gg.hideouthq.core.data.Preferences
import gg.hideouthq.core.data.RemoteFile
import gg.hideouthq.core.data.fake.FakeDownloader
import gg.hideouthq.core.data.fake.FakeGameBanana
import gg.hideouthq.core.data.fake.FakeGameLocator
import gg.hideouthq.core.data.fake.FakeProcesses
import gg.hideouthq.core.data.fake.FakeVpk
import gg.hideouthq.core.data.fake.InMemoryGameFiles
import gg.hideouthq.core.data.fake.InMemoryLibraryStore
import gg.hideouthq.core.data.fake.MemoryStorage
import gg.hideouthq.core.data.gamebanana.FactsAnswer
import gg.hideouthq.core.data.gamebanana.FileFacts
import gg.hideouthq.core.data.gamebanana.SubmissionFileOption
import gg.hideouthq.core.library.ArchiveIdentity
import gg.hideouthq.core.library.FileImage
import gg.hideouthq.core.library.FilesObservation
import gg.hideouthq.core.library.GameRoot
import gg.hideouthq.core.library.InstallPorts
import gg.hideouthq.core.library.InstallInput
import gg.hideouthq.core.library.InstallRequest
import gg.hideouthq.core.library.InstalledSource
import gg.hideouthq.core.library.OperationStage
import gg.hideouthq.core.library.LibraryManifest
import gg.hideouthq.core.library.ManifestRecord
import gg.hideouthq.core.library.ModLibrary
import gg.hideouthq.core.library.ProfileBook
import gg.hideouthq.core.library.ProfileId
import gg.hideouthq.core.library.SetFile
import gg.hideouthq.core.library.SubmissionIdentity
import gg.hideouthq.core.library.SubmissionKind
import gg.hideouthq.core.library.SubmissionRef
import gg.hideouthq.core.library.VersionPosition
import gg.hideouthq.core.library.VpkPart
import gg.hideouthq.core.library.archive
import gg.hideouthq.core.library.oneSetRecord
import gg.hideouthq.core.library.relativePath
import gg.hideouthq.feature.withViewModels
import gg.hideouthq.preview.sha256Hex
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryUpdatesScreenTest {
    private val root = GameRoot("/games/Deadlock")
    private val elsewhere = GameRoot("/games/Second")
    private val bytes = ByteArray(40) { 4 }

    private fun record(number: Int, enabled: Boolean = true, gameRoot: GameRoot = root) = oneSetRecord(
        installId = "set$number",
        gameRoot = gameRoot,
        source = InstalledSource.Remote(SubmissionIdentity(SubmissionKind.Mod, 100L + number, number * 10L, Md5A)),
        title = "Mod $number",
        variant = null,
        archive = ArchiveIdentity("/data/downloads/m$number.zip", 900, "$number".repeat(64)),
        number = number.takeIf { enabled },
        files = listOf(SetFile(VpkPart.Directory, FileImage(bytes.size.toLong(), sha256Hex(bytes)))),
        version = VersionPosition(1, 1),
        originalName = "mod$number.zip",
    )

    private fun ref(number: Int) = SubmissionRef(SubmissionKind.Mod, 100L + number)

    private fun listed(record: ManifestRecord, vararg files: SubmissionFileOption) = FilesObservation(
        FactsAnswer.Read(FileFacts(0, null, null, files = files.toList(), filesComplete = true)),
        targets = record.sets.map { (it.source as InstalledSource.Remote).identity }.toSet(),
    )

    private fun file(id: Long, md5: String?, name: String = "file$id.zip") = SubmissionFileOption(id, md5, name, null, 1_000)

    private inner class Screen(val records: List<ManifestRecord>, unreadable: String?) {
        val files = InMemoryGameFiles()
        val banana = FakeGameBanana()
        val downloader = FakeDownloader(files)
        val processes = FakeProcesses()
        val observed = MutableStateFlow(emptyMap<SubmissionRef, FilesObservation>())
        val freshness = MutableStateFlow(CatalogFreshness())
        val busy = MutableStateFlow<Instant?>(null)

        var holding: CompletableDeferred<Unit>? = null

        val reading = CompletableDeferred<LocalImportResult>()
        val store = InMemoryLibraryStore(LibraryManifest(records = records, profiles = ProfileBook.main(records)), forceUnreadable = unreadable)
        lateinit var library: ModLibrary
        lateinit var vm: LibraryViewModel

        init {
            records.forEach { record ->
                files.putBytes(record.gameRoot, record.files.single().relativePath(record), bytes)
                files.putArchive(record.archive.path, emptyList(), gg.hideouthq.core.library.FileImage(record.archive.sizeBytes, record.archive.sha256))
            }
        }

        fun TestScope.open(viewModels: ViewModelStore) {
            library = ModLibrary(
                FakeGameLocator(listOf(root.path), mapOf(root.path to null)), Preferences(MemoryStorage()), store, files,
                InstallPorts(object : GameBananaClient by banana {
                    override suspend fun files(submission: SubmissionRef): FileListing {
                        holding?.await()
                        return banana.files(submission)
                    }
                }, downloader, files, files, processes, localImport = object : LocalImport {
                    override suspend fun snapshot(file: LocalFile): LocalImportResult = reading.await()
                    override suspend fun release(snapshot: LocalArchiveSnapshot) = Unit
                }), backgroundScope, gameBananaBusy = busy,
            ).also { it.start() }
            val catalog = object : CatalogRepository by AppContainer.fake().catalog {
                override val installedFiles = observed
                override val freshness = this@Screen.freshness
            }
            vm = LibraryViewModel(library, FolderOpener { _, _ -> true }, catalog).also { viewModels.put("library", it) }
            backgroundScope.launch { vm.uiState.collect {} }
            runCurrent()
        }

        fun row(number: Int) = vm.uiState.value.rows.single { it.installId == "set$number" }

        fun offers(number: Int, md5: String) {
            banana.listings = banana.listings + (ref(number) to FileListing.Available("Mod $number", listOf(RemoteFile(number * 10L, "file${number}0.zip", 1_000, md5, null, 1, 1, "https://gamebanana.com/dl/$number"))))
            downloader.serve(number * 10L, listOf("pak01_dir.vpk" to FakeVpk.directory(seed = number)))
        }
    }

    private fun screen(records: List<ManifestRecord>, unreadable: String? = null, test: suspend TestScope.(Screen) -> Unit) = withViewModels { viewModels ->
        val screen = Screen(records, unreadable)
        with(screen) { open(viewModels.store) }
        test(screen)
    }

    private val mixed = listOf(record(1), record(2, enabled = false), record(3), record(4), record(5), record(6, gameRoot = elsewhere))

    private fun Screen.observeMixed() {
        observed.value = mapOf(
            ref(1) to listed(records[0], file(10, Md5B)),
            ref(2) to listed(records[1], file(20, Md5B)),
            ref(3) to listed(records[2], file(29, Md5A)),
            ref(5) to listed(records[4], file(50, Md5A)),
            ref(6) to listed(records[5], file(60, Md5B)),
        )
    }

    @Test
    fun rowsSayWhatTheLastCheckFoundAndTheHeaderNeverSaysUpToDateWhileAnyIsUnknown() = screen(mixed) { screen ->
        assertTrue((1..5).all { screen.row(it).update == UpdateState.NotChecked }, "nothing observed yet")
        assertEquals(UpdatesUi(unchecked = 5, checkable = true), screen.vm.uiState.value.updates, "the other game's mod is not counted")

        screen.observeMixed()
        runCurrent()
        assertEquals(
            listOf(UpdateState.Available, UpdateState.Available, UpdateState.Review, UpdateState.NotChecked, UpdateState.Current, null),
            (1..6).map { screen.row(it).update },
        )
        assertNotNull(screen.row(1).updatePlan)
        assertNull(screen.row(3).updatePlan)
        assertEquals(UpdatesUi(available = 2, review = 1, unchecked = 1, checkable = true), screen.vm.uiState.value.updates)
        assertNull(screen.row(6).updatePlan, "this game cannot update another game's mod")
        val state = screen.vm.uiState.value
        val pill = state.filters.single { it.filter == LibraryFilter.Updates }
        assertEquals(2, pill.count, "the pill counts the mods Update all updates, as the bar does, not the one to review")

        screen.vm.setFilter(LibraryFilter.Updates)
        runCurrent()
        assertEquals(listOf("set1", "set2"), screen.vm.uiState.value.rows.filter { it.visible }.map { it.installId })
        screen.vm.setFilter(LibraryFilter.All)
        runCurrent()
        assertTrue(screen.row(6).visible)
    }

    @Test
    fun keepCurrentFilesQuietsAReviewUntilItsPageListsOtherFiles() = screen(mixed) { screen ->
        screen.observeMixed()
        runCurrent()
        assertEquals(UpdateState.Review, screen.row(3).update)
        screen.vm.keepCurrentFiles("set3")
        runCurrent()
        assertEquals(UpdateState.Current, screen.row(3).update, "the row no longer asks for a review")
        assertEquals(UpdatesUi(available = 2, unchecked = 1, checkable = true), screen.vm.uiState.value.updates)
        assertEquals(setOf(29L), screen.store.manifest!!.records.single { it.installId == "set3" }.reviewed, "the choice is saved with the mod")

        screen.observed.value += ref(3) to listed(screen.records[2], file(29, Md5A), file(31, Md5A))
        runCurrent()
        assertEquals(UpdateState.Review, screen.row(3).update, "a file added to its page asks again")
    }

    @Test
    fun theCountToReviewFiltersToThoseModsAndKeepAllQuietsEveryOneOnOrOff() = screen(mixed + record(7, enabled = false)) { screen ->
        screen.observeMixed()
        screen.observed.value += ref(7) to listed(screen.records[6], file(69, Md5A))
        runCurrent()
        assertEquals(2, screen.vm.uiState.value.updates.review)
        screen.vm.setFilter(LibraryFilter.Review)
        runCurrent()
        assertEquals(listOf("set3", "set7"), screen.vm.uiState.value.rows.filter { it.visible }.map { it.installId })
        assertEquals(FilterPill(LibraryFilter.Review, 2), screen.vm.uiState.value.filters.single { it.filter == LibraryFilter.Review }, "the filter shows, with its count, while chosen")
        screen.vm.keepAllCurrentFiles()
        runCurrent()
        assertEquals(listOf(UpdateState.Current, UpdateState.Current), listOf(screen.row(3).update, screen.row(7).update))
        assertEquals(setOf(69L), screen.store.manifest!!.records.single { it.installId == "set7" }.reviewed, "the off mod's choice is saved too")
    }

    @Test
    fun anUnreadableLibraryOffersNoUpdatesAndUninstallsNothing() = screen(listOf(record(1)), unreadable = "a newer Hideout wrote it") { screen ->
        screen.observed.value = mapOf(ref(1) to listed(screen.records[0], file(10, Md5B)))
        runCurrent()
        val state = screen.vm.uiState.value
        assertNotNull(state.blocked)
        assertEquals(emptyList(), state.rows)
        assertEquals(UpdatesUi(), state.updates)
        screen.vm.updateAll()
        screen.vm.uninstall("set1")
        runCurrent()
        assertEquals(emptyMap(), screen.library.state.value.operations)
        assertEquals(emptyList(), screen.store.saved, "nothing is written over the unreadable manifest")
    }

    @Test
    fun updateAllStartsEveryUpdateInThisGameWhateverTheSearchOnOrOff() = screen(mixed) { screen ->
        screen.observeMixed()
        screen.vm.setQuery("nothing matches this")
        runCurrent()
        screen.vm.updateAll()
        runCurrent()
        assertEquals(setOf("mod-101", "mod-102"), screen.library.state.value.operations.keys, "hidden by the search, off, or both, but not the one to review nor the other game's")
    }

    @Test
    fun anUpdateRunsOnItsOwnRow() = screen(mixed) { screen ->
        screen.observeMixed()
        screen.offers(1, Md5B)
        val gate = CompletableDeferred<Unit>()
        screen.downloader.gate = gate
        runCurrent()
        screen.vm.update(screen.row(1).updatePlan!!)
        runCurrent()

        val running = assertNotNull(screen.row(1).operation)
        assertIs<DownloadPhase.Transferring>(running.phase)
        assertTrue(running.canCancel)
        assertTrue(screen.row(1).busy)
        assertEquals(emptyList(), screen.vm.uiState.value.downloads, "an installed mod's update is on its row, not under Downloading")

        gate.complete(Unit)
        runCurrent()
        assertNull(screen.row(1).operation)
        val updated = (screen.library.state.value.library as gg.hideouthq.core.library.LibraryStatus.Ready).records.single { it.installId == "set1" }
        assertEquals(Md5B, (updated.sets.single().source as InstalledSource.Remote).identity.fileMd5)
        assertEquals(UpdateState.NotChecked, screen.row(1).update, "the new file is not vouched for by the answer about the old one")
    }

    @Test
    fun aFailedUpdateLeavesItsRowOfferingTheUpdateAgain() = screen(listOf(record(1))) { screen ->
        screen.observed.value = mapOf(ref(1) to listed(screen.records[0], file(10, Md5B)))
        runCurrent()
        screen.vm.update(screen.row(1).updatePlan!!)
        runCurrent()

        assertIs<OperationStage.Failed>(screen.library.state.value.operations["mod-101"]?.stage, "the shell's notice says why")
        assertNull(screen.row(1).operation)
        assertFalse(screen.row(1).busy)
        assertEquals(emptyList(), screen.vm.uiState.value.downloads)

        screen.offers(1, Md5B)
        screen.vm.update(assertNotNull(screen.row(1).updatePlan))
        runCurrent()
        assertNull(screen.row(1).operation)
        val updated = (screen.library.state.value.library as gg.hideouthq.core.library.LibraryStatus.Ready).records.single { it.installId == "set1" }
        assertEquals(Md5B, (updated.sets.single().source as InstalledSource.Remote).identity.fileMd5, "Update from the row tries again")
    }

    private companion object {
        const val Md5A = "ab965746d197e296c253fb552dc1fd47"
        const val Md5B = "8797b6cdb331371ef015e78a9f79bad9"
    }

    @Test
    fun whileGameBananaHoldsACheckTheLineUnderTheTitleSaysUntilWhenAndAnInstallItHoldsSaysSoOnItsRow() = screen(mixed) { screen ->
        val until = Instant.parse("2026-11-11T18:05:00Z")
        screen.freshness.value = CatalogFreshness(running = CheckProgress(), busyUntil = until)
        runCurrent()
        assertEquals(UpdatesUi(unchecked = 5, checking = true, busyUntil = until, checkable = true), screen.vm.uiState.value.updates)

        screen.holding = CompletableDeferred()
        screen.offers(8, Md5A)
        screen.library.install(ref(8).modId)
        runCurrent()
        assertEquals(DownloadPhase.Preparing, screen.vm.uiState.value.downloads.single().phase)
        screen.busy.value = until
        runCurrent()
        val row = screen.vm.uiState.value.downloads.single()
        assertEquals(DownloadPhase.Busy(until), row.phase)
        assertTrue(row.canCancel, "the row can stop what GameBanana holds")
        screen.holding!!.complete(Unit)
        screen.busy.value = null
        runCurrent()
    }

    @Test
    fun aFileTheUserHandedOverSaysItIsReadAndGameBananaNeverHoldsIt() = screen(mixed) { screen ->
        screen.busy.value = Instant.parse("2026-11-11T18:05:00Z")
        screen.library.install(InstallRequest(InstallInput.Local(LocalFile("/picked/ivy.zip", "ivy.zip", 100)), setOf(ProfileId("main"))))
        runCurrent()
        val row = screen.vm.uiState.value.downloads.single()
        assertEquals(DownloadPhase.Reading, row.phase)
        assertEquals(InstallStatus.Preparing, screen.library.status(row.modId).first())
        screen.reading.complete(LocalImportResult.Refused(LocalRefusal.Unreadable))
        screen.busy.value = null
        runCurrent()
    }
}
