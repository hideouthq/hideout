package gg.hideouthq.tools

import gg.hideouthq.AppContainer
import gg.hideouthq.HideoutAppState
import gg.hideouthq.core.data.CatalogRepository
import gg.hideouthq.core.data.FileListing
import gg.hideouthq.core.data.RemoteFile
import gg.hideouthq.core.data.fake.FakeConfig
import gg.hideouthq.core.data.fake.FakeDownloader
import gg.hideouthq.core.data.fake.FakeGameBanana
import gg.hideouthq.core.data.fake.FakeVpk
import gg.hideouthq.core.data.fake.InMemoryGameFiles
import gg.hideouthq.core.data.fake.InMemoryLibraryStore
import gg.hideouthq.core.data.fake.LibrarySeed
import gg.hideouthq.core.data.fake.SampleGameRoot
import gg.hideouthq.core.data.fake.sampleGameFiles
import gg.hideouthq.core.data.gamebanana.FactsAnswer
import gg.hideouthq.core.data.gamebanana.FileFacts
import gg.hideouthq.core.data.gamebanana.SubmissionFileOption
import gg.hideouthq.core.library.ArchiveIdentity
import gg.hideouthq.core.library.FileImage
import gg.hideouthq.core.library.FilesObservation
import gg.hideouthq.core.library.InstalledSet
import gg.hideouthq.core.library.InstalledSource
import gg.hideouthq.core.library.LibraryManifest
import gg.hideouthq.core.library.LibraryStatus
import gg.hideouthq.core.library.ManifestCodec
import gg.hideouthq.core.library.ManifestRecord
import gg.hideouthq.core.library.OwnedDownload
import gg.hideouthq.core.library.Slot
import gg.hideouthq.core.library.Placement
import gg.hideouthq.core.library.SetFile
import gg.hideouthq.core.library.SetFolder
import gg.hideouthq.core.library.SetOrigin
import gg.hideouthq.core.library.Sha256
import gg.hideouthq.core.library.SubmissionIdentity
import gg.hideouthq.core.library.SubmissionKind
import gg.hideouthq.core.library.SubmissionRef
import gg.hideouthq.core.library.VersionPosition
import gg.hideouthq.core.library.VpkPart
import gg.hideouthq.core.library.relativePath
import gg.hideouthq.core.navigation.Route
import gg.hideouthq.tools.preview.advanceUntil
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

private const val Bride = "Bride of Fire"
private const val Steps = "Quiet Steps"
private const val Hud = "Old Hud"
private const val Md5A = "ab965746d197e296c253fb552dc1fd47"
private const val Md5B = "8797b6cdb331371ef015e78a9f79bad9"

private class UpdateFixture {
    val files: InMemoryGameFiles = InMemoryGameFiles().apply {
        putBytes(SampleGameRoot, InMemoryGameFiles.GameInfo, sampleGameFiles(FakeConfig(seed = LibrarySeed.Empty)).gameInfo(SampleGameRoot)!!)
    }
    val titles = mapOf(1 to Bride, 2 to Steps, 3 to Hud)
    val old = mapOf(1 to FakeVpk.directory(seed = 41), 2 to FakeVpk.directory(seed = 42), 3 to FakeVpk.directory(seed = 43))
    val new = mapOf(1 to FakeVpk.directory(seed = 51), 2 to FakeVpk.directory(seed = 52))
    val records = listOf(record(1, Bride, enabled = true), record(2, Steps, enabled = false), record(3, Hud, enabled = true, version = VersionPosition(1, 2)))
    val store = InMemoryLibraryStore(
        LibraryManifest(records = records, downloads = records.map { OwnedDownload((it.sets.single().source as InstalledSource.Remote).identity, it.sets.single().download!!) }),
    )
    val banana = FakeGameBanana()
    val observed = MutableStateFlow(emptyMap<SubmissionRef, FilesObservation>())
    var refreshes = 0
    val downloader = FakeDownloader(files)
    val container: AppContainer

    init {
        records.forEachIndexed { index, record ->
            val download = record.sets.single().download!!
            files.putBytes(SampleGameRoot, record.files.single().relativePath(record), old.getValue(index + 1))
            files.putArchive(download.path, emptyList(), FileImage(download.sizeBytes, download.sha256))
        }
        offer(1, Md5B)
        offer(2, Md5B)
        banana.listings = banana.listings + (ref(3) to FileListing.Available(Hud, listOf(remote(3, 9_004, Md5A))))
        val base = AppContainer.fake(files = files, libraryStore = store, gameBanana = banana, downloader = { downloader })
        check(!base.previews.available)
        val catalog = object : CatalogRepository by base.catalog {
            override val previewsAvailable = false
            override val installedFiles = observed
            override fun refresh() {
                refreshes++
            }
        }
        container = AppContainer(catalog, base.launcher, base.soundPlayer, base.images, base.previews, base.modLibrary, base.preferences, base.folderPicker, base.folderOpener, base.about, migration = base.migration, autoexec = base.autoexec, crosshairs = base.crosshairs, communityCrosshairs = base.communityCrosshairs)
        observeAll()
    }

    fun state() = HideoutAppState(container, Route.Library)

    fun ref(n: Int) = SubmissionRef(SubmissionKind.Mod, 900_000L + n)

    private fun remote(n: Int, fileId: Long, md5: String) =
        RemoteFile(fileId, "file$fileId.zip", 1_000, md5, null, 1, 1_777_000_000, "https://gamebanana.com/dl/$fileId")

    fun offer(n: Int, md5: String) {
        banana.listings = banana.listings + (ref(n) to FileListing.Available(titles.getValue(n), listOf(remote(n, 9_000L + n, md5))))
        new[n]?.let { downloader.serve(9_000L + n, listOf("pak01_dir.vpk" to it)) }
    }

    fun observeAll() {
        observed.value = live().associate { record ->
            val ref = (record.sets.single().source as InstalledSource.Remote).identity.ref
            val listing = banana.listings.getValue(ref) as FileListing.Available
            val facts = FileFacts(0, null, null, files = listing.files.map { SubmissionFileOption(it.id, it.md5, it.fileName, null, it.sizeBytes) }, filesComplete = true)
            ref to FilesObservation(FactsAnswer.Read(facts), targets = setOf((record.sets.single().source as InstalledSource.Remote).identity))
        }
    }

    fun live(): List<ManifestRecord> = (container.modLibrary.state.value.library as LibraryStatus.Ready).records

    fun record(title: String): ManifestRecord = live().single { it.title == title }

    fun md5(title: String): String? = (record(title).sets.single().source as InstalledSource.Remote).identity.fileMd5

    fun bytes(title: String): FileImage? {
        val record = record(title)
        val name = record.files.single().name
        return if (record.enabled) files.addonsFiles(SampleGameRoot)[name] else files.filesIn(SampleGameRoot, SetFolder.Off(record.modId))[name]
    }

    fun saved(): String = ManifestCodec.encode(store.manifest!!)

    private fun record(n: Int, title: String, enabled: Boolean, version: VersionPosition = VersionPosition(1, 1)): ManifestRecord {
        val identity = SubmissionIdentity(SubmissionKind.Mod, 900_000L + n, 9_000L + n, Md5A)
        return ManifestRecord(
            installId = "update$n", gameRoot = SampleGameRoot, title = title, placement = if (enabled) Placement.On(Slot(n)) else Placement.Off,
            sets = listOf(
                InstalledSet(
                    source = InstalledSource.Remote(identity), originalName = "installed$n.zip", variant = null,
                    origin = SetOrigin.Downloaded(ArchiveIdentity("""C:\Users\Player\Downloads\Hideout\installed$n.zip""", 4_000L + n, "%064x".format(n))),
                    version = version,
                    files = listOf(SetFile(VpkPart.Directory, Sha256.image(old.getValue(n)))),
                    componentKey = "pak01_dir.vpk",
                ),
            ),
        )
    }
}

internal fun runLibraryUpdateChecks(outDir: File) {
    val fixture = UpdateFixture()
    val library = fixture.container.modLibrary
    runCheck(outDir, "update_workflow", fixture.state(), widthDp = 1440, heightDp = 900) {
        advance(800)
        check(hasText("2 updates available") && labelled("1 to review").size == 1) { "The header should count this game's updates and link to the mod to review" }
        check(labelled("Keep all current files").size == 1) { "Keep all current files sits beside the count to review" }
        check(nodes().any { it.hasLabel("Update all (2)") }) { "Update all offers both mods it may update" }
        check(hasText("Update available")) { "A row should say it has an update" }
        check(hasText("Review files")) { "$Hud's file is gone from a mod that listed two, so it needs reviewing" }
        check(!hasText("Up to date")) { "Never up to date while a mod needs reviewing" }
        capture("list_updates")

        clickLabel("1 to review")
        advance(600)
        check(hasText("Review files") && !hasText("Update available") && hasText("To review")) { "The count to review filters to $Hud, under its own pill" }
        capture("review_filter")
        clickLabel("Updates")
        advance(600)
        check(!hasText("Review files")) { "The Updates pill lists only what Update all updates, not $Hud to review" }
        capture("updates_filter")
        clickLabel("1 to review")
        advance(600)
        val hudRow = nodes().first { it.hasLabel(Hud) }.boundsInRoot
        moveToDp((hudRow.left + 140 * scale) / scale, hudRow.center.y / scale)
        click()
        advance(600)
        check(hasText("Its page offers other files now. Change them, or keep these.")) { "Details offers the review for $Hud too" }
        capture("review_details")
        clickLabel("All")
        advance(600)

        val gate = CompletableDeferred<Unit>()
        fixture.downloader.gates[9_001] = gate
        val savedBefore = fixture.saved()
        clickLabel("Update all (2)")
        advanceUntil("$Steps, which is off, updated while $Bride downloads") { fixture.md5(Steps) == Md5B }
        check(fixture.bytes(Steps) == Sha256.image(fixture.new.getValue(2))) { "$Steps' new bytes are in its off folder" }
        check(fixture.saved() != savedBefore && Md5B in fixture.saved()) { "The manifest saved $Steps' new file" }
        check(fixture.md5(Bride) == Md5A) { "$Bride is still the old file while it downloads" }
        check(library.state.value.operations.values.single().installId == "update1") { "$Bride's update runs on its own installation" }
        advanceUntil("$Bride's progress on its row") { hasText("Updating · Downloading 500 B of 1 KB") || hasText("Updating") }
        check(nodes().any { it.hasLabel("Cancel update of $Bride") }) { "Its row offers Cancel" }
        capture("updating_list")

        clickLabel("Cards")
        advance(800)
        check(nodes().any { it.hasLabel("Cancel update of $Bride") }) { "Its card offers Cancel too" }
        capture("updating_cards")
        clickLabel("Cancel update of $Bride")
        gate.complete(Unit)
        advanceUntil("the cancelled update to end") { library.state.value.operations.isEmpty() }
        check(fixture.md5(Bride) == Md5A && fixture.bytes(Bride) == Sha256.image(fixture.old.getValue(1))) { "Cancelling keeps $Bride as it was" }
        capture("cancelled_cards")

        clickLabel("List")
        advance(800)
        fixture.observeAll()
        advance(400)
        fixture.downloader.gates.clear()
        clickLabel("Update $Bride")
        advanceUntil("$Bride updated") { fixture.md5(Bride) == Md5B }
        check(fixture.bytes(Bride) == Sha256.image(fixture.new.getValue(1))) { "$Bride's new bytes are in addons" }
        check(fixture.record(Bride).numbers == listOf(Slot(1))) { "$Bride keeps its place in the load order" }
        capture("updated")

        check(nodes().any { it.hasLabel("Change the files of $Hud") }) { "A row to review offers Change files on the row, not only in its menu" }
        capture("review_row")
        clickLabel("Cards")
        advance(800)
        check(nodes().any { it.hasLabel("Keep the current files of $Hud") }) { "Its card offers Keep current files too" }
        capture("review_card")
        clickLabel("List")
        advance(800)
        clickLabel("Keep the current files of $Hud")
        advanceUntil("$Hud's files kept over the review") { fixture.record(Hud).reviewed == setOf(9_004L) }
        check(!hasText("Review files") && !hasText("to review")) { "Keeping $Hud's files quiets its review" }
        capture("review_kept")

        clickLabel("Check for updates")
        check(fixture.refreshes == 1) { "Check for updates asks the catalogue's check, as Refresh does" }
    }
    println("Library update check 'update_workflow' passed")
}
