package gg.hideouthq.feature.install

import androidx.lifecycle.ViewModelStore
import gg.hideouthq.AppContainer
import gg.hideouthq.core.data.CatalogRepository
import gg.hideouthq.core.data.FileListing
import gg.hideouthq.core.data.InstallStatus
import gg.hideouthq.core.data.RemoteFile
import gg.hideouthq.core.data.fake.FakeDownloader
import gg.hideouthq.core.data.fake.FakeGameBanana
import gg.hideouthq.core.data.fake.FakeVpk
import gg.hideouthq.core.data.fake.InMemoryLibraryStore
import gg.hideouthq.core.data.fake.sampleLibraryManifest
import gg.hideouthq.core.data.preview.PreviewFixtures
import gg.hideouthq.core.library.ArchiveIdentity
import gg.hideouthq.core.library.ChoiceAnswer
import gg.hideouthq.core.library.FileImage
import gg.hideouthq.core.library.GameRoot
import gg.hideouthq.core.library.InstallInput
import gg.hideouthq.core.library.InstallRequest
import gg.hideouthq.core.library.InstalledSource
import gg.hideouthq.core.library.LibraryStatus
import gg.hideouthq.core.library.ManifestRecord
import gg.hideouthq.core.library.OperationStage
import gg.hideouthq.core.library.Placement
import gg.hideouthq.core.library.ProfileBook
import gg.hideouthq.core.library.ResourceIndex
import gg.hideouthq.core.library.ResourceIndexSource
import gg.hideouthq.core.library.SetFile
import gg.hideouthq.core.library.SubmissionIdentity
import gg.hideouthq.core.library.SubmissionKind
import gg.hideouthq.core.library.SubmissionRef
import gg.hideouthq.core.library.VpkPart
import gg.hideouthq.core.library.choice
import gg.hideouthq.core.library.oneSetRecord
import gg.hideouthq.core.library.only
import gg.hideouthq.core.model.PreviewVariant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class InstallChoiceViewModelTest {
    private val lilith = SubmissionRef(SubmissionKind.Mod, 551_210)
    private val ivy = SubmissionRef(SubmissionKind.Mod, 628_739)
    private val blackBeard = SubmissionRef(SubmissionKind.Mod, 700_001)

    private fun file(id: Long, name: String, downloads: Long, size: Long = 31_200_000, description: String? = null, md5: String? = PreviewFixtures.Md5) =
        RemoteFile(id, name, size, md5, description, downloads, addedAt = 1_777_795_519, downloadUrl = "https://gamebanana.com/dl/$id")

    private val default = file(1, "lilith_hell_warden.zip", 2_600, description = "Default")
    private val werewolf = file(2, "lilith_werewolf_transform.zip", 1_100, 34_600_000, description = "Werewolf Transform")
    private val cabezas = file(3, "lilith_hell_warden_cfda7.zip", 412, 27_400_000)

    private val beardAudio = file(20, "black_beard_dynamo_v2_audio.zip", 40, description = "THE MODEL + THE AUDIO")
    private val beardNoFurAudio = file(21, "black_beard_dynamo_no_fur_v2_audio.zip", 30, description = "THE MODEL + THE AUDIO")
    private val beardPlain = file(22, "black_beard_dynamo_v2.zip", 20, description = "JUST THE MODEL")
    private val beardNoFur = file(23, "black_beard_dynamo_no_fur_v2.zip", 10, description = "JUST THE MODEL")

    private fun variant(fileId: Long, md5: String? = PreviewFixtures.Md5) =
        PreviewVariant(fileId, md5, null, null, PreviewFixtures.entry(PreviewFixtures.manifest(fileId = fileId, md5 = md5)))

    private inner class World(
        variants: List<PreviewVariant> = listOf(variant(1), variant(2)),
        silentCatalogue: Boolean = false,
        elsewhere: List<ManifestRecord> = emptyList(),
        indexes: ResourceIndexSource? = null,
    ) {
        val banana = FakeGameBanana(
            mapOf(
                lilith to FileListing.Available("Lilith, the Hell Warden", listOf(cabezas, werewolf, default)),
                ivy to FileListing.Available("Bubblegum ivy V2", listOf(file(10, "bg_ivy.zip", 5), file(11, "bg_ivy_alt.zip", 5))),
                blackBeard to FileListing.Available("Black Beard Dynamo", listOf(beardAudio, beardNoFurAudio, beardPlain, beardNoFur)),
            ),
        )
        lateinit var downloader: FakeDownloader
        val container = AppContainer.fake(
            libraryStore = InMemoryLibraryStore(sampleLibraryManifest().let { (it.records + elsewhere).let { records -> it.copy(records = records, profiles = ProfileBook.main(records)) } }),
            gameBanana = banana,
            downloader = { files -> FakeDownloader(files).also { downloader = it } },
            resourceIndexSource = indexes,
        )
        val catalog: CatalogRepository = object : CatalogRepository by container.catalog {
            override val mods = if (silentCatalogue) emptyFlow() else container.catalog.mods.map { mods ->
                mods.map { if (it.id == "lilith") it.copy(id = lilith.modId, previewVariants = variants) else it }
            }
            override val heroes = if (silentCatalogue) emptyFlow() else container.catalog.heroes
        }
        val library = container.modLibrary

        fun serve(fileId: Long, vararg entries: Pair<String, ByteArray>) = downloader.serve(fileId, entries.toList())

        fun record(mod: SubmissionRef) = (library.state.value.library as LibraryStatus.Ready).records.single { it.modId == mod.modId }
    }

    private fun withViewModel(world: () -> World = { World() }, block: suspend TestScope.(World, InstallChoiceViewModel) -> Unit) = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val it = world()
            val vm = InstallChoiceViewModel(it.library, it.catalog)
            store.put("install", vm)
            runCurrent()
            block(it, vm)
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }

    private fun InstallChoiceViewModel.dialog(): InstallDialogState = assertNotNull(uiState.value)

    private fun TestScope.install(world: World, mod: SubmissionRef) {
        world.library.install(mod.modId)
        runCurrent()
    }

    private val model = "models/heroes/silver/silver.vmdl_c"

    private val sampleModels = ResourceIndexSource { _, _, image -> if (image.sha256 == "a".repeat(64)) ResourceIndex.Ready(mapOf(model to 1L)) else ResourceIndex.Unavailable }

    @Test
    fun theSwapQuestionNamesTheHeroOnlyWhenTheCatalogueKnowsTheMod() = withViewModel({ World(indexes = sampleModels) }) { world, vm ->
        for ((mod, fileId, hero) in listOf(Triple(lilith, 1L, "Silver"), Triple(ivy, 10L, null))) {
            world.serve(fileId, "skin.vpk" to FakeVpk.of(mapOf(model to "another ${mod.modId}".encodeToByteArray())))
            install(world, mod)
            val files = vm.dialog().id
            vm.confirm(files)
            runCurrent()
            vm.finished(files)
            val asked = assertNotNull(vm.swap.value, "${mod.modId} changes the model the sample's Lilith shows")
            assertEquals(hero, asked.heroName, "the hero comes from the catalogue, else the title is unnamed")
            assertEquals(listOf("Lilith, the Hell Warden"), asked.others)
            assertFalse(asked.skins, "the sample's Lilith is not in the catalogue, so the title names it rather than calling it a skin")
            world.library.answer(asked.id, ChoiceAnswer.Cancel)
            runCurrent()
        }
    }

    @Test
    fun aFileQuestionShowsTheFreshFilesMostDownloadedFirstWithTheCatalogueHeader() = withViewModel { world, vm ->
        install(world, lilith)
        val dialog = vm.dialog()
        assertEquals(Question.Files, dialog.question)
        assertEquals(lilith.modId, dialog.modId)
        assertEquals(listOf("Default", "Werewolf Transform", "Lilith hell warden"), dialog.rows.map { it.name }, "descriptions first, then the cleaned file name")
        assertTrue(dialog.rows.all { it.path == null }, "a file row shows no archive name")
        assertEquals(listOf(true, false, false), dialog.rows.map { it.mostDownloaded })
        assertEquals(setOf(VersionPick.File(1)), dialog.selected, "the most downloaded is preselected")
        assertEquals(setOf(world.library.state.value.profiles!!.active), dialog.selectedProfiles)
        assertEquals(listOf(31_200_000L), dialog.toDownload.map { it.sizeBytes })
        assertFalse(dialog.replacing)
        assertTrue(dialog.canConfirm)
        assertEquals(DialogHeader("Lilith, the Hell Warden", null, dialog.header.coverTone), dialog.header)
    }

    @Test
    fun filesThatShareADescriptionAreNamedByFileAndTheSavedLabelAgreesWithWhatWasShown() = withViewModel { world, vm ->
        world.serve(20, "a.vpk" to FakeVpk.directory(seed = 20))
        world.serve(21, "b.vpk" to FakeVpk.directory(seed = 21))
        install(world, blackBeard)
        val dialog = vm.dialog()
        assertEquals(listOf(20L, 21L, 22L, 23L), dialog.rows.map { (it.pick as VersionPick.File).fileId }, "ids stay GameBanana's own, popularity order")
        assertEquals(
            listOf("V2 audio", "No fur V2 audio", "V2", "No fur V2"),
            dialog.rows.map { it.name },
            "a description more than one file shares names none of them; each falls back to its own cleaned file name",
        )
        vm.toggle(dialog.id, VersionPick.File(21))
        vm.confirm(dialog.id)
        runCurrent()
        val record = world.record(blackBeard)
        assertEquals(mapOf(20L to "V2 audio", 21L to "No fur V2 audio"), record.sets.associate { it.fileId!! to it.variant }, "the saved label agrees with what the dialog showed")
    }

    @Test
    fun aModInstalledInAnotherGameFolderIsAFreshInstallHere() = withViewModel({
        World(elsewhere = listOf(oneSetRecord(
            installId = "elsewhere", gameRoot = GameRoot("/games/Second"),
            source = InstalledSource.Remote(SubmissionIdentity(SubmissionKind.Mod, lilith.id, default.id, default.md5)),
            title = "Lilith, the Hell Warden", variant = "Default", archive = ArchiveIdentity("/downloads/lilith.zip", 100, "a".repeat(64)),
            number = null, files = listOf(SetFile(VpkPart.Directory, FileImage(10, "b".repeat(64)))),
        )))
    }) { world, vm ->
        install(world, lilith)
        val dialog = vm.dialog()
        assertFalse(dialog.replacing, "the copy in another game folder is not what this install changes")
        assertEquals(emptySet(), dialog.installed)
        assertTrue(dialog.canConfirm)
    }

    @Test
    fun aSilentCatalogueNeverHoldsTheDialogBack() = withViewModel({ World(silentCatalogue = true) }) { world, vm ->
        install(world, lilith)
        val dialog = vm.dialog()
        assertEquals(DialogHeader("Lilith, the Hell Warden"), dialog.header, "GameBanana's title alone, and no preview anyone could not check")
        assertTrue(dialog.rows.all { it.preview == null })
        assertEquals(3, dialog.rows.size)
    }

    @Test
    fun previewIsOfferedOnlyForAnExactBundleOfThatFile() = withViewModel({
        World(variants = listOf(variant(1), variant(2, md5 = "0".repeat(32))))
    }) { world, vm ->
        install(world, lilith)
        val rows = vm.dialog().rows.associateBy { (it.pick as VersionPick.File).fileId }
        assertEquals(PreviewTarget(lilith.modId, 1, PreviewFixtures.Md5), rows.getValue(1).preview)
        assertNull(rows.getValue(2).preview, "a bundle of another upload under the same id")
        assertNull(rows.getValue(3).preview, "a file with no bundle")
    }

    @Test
    fun theMostDownloadedTagNeedsStrictlyTheMostDownloads() = withViewModel { world, vm ->
        install(world, ivy)
        assertEquals(listOf(false, false), vm.dialog().rows.map { it.mostDownloaded })
    }

    @Test
    fun severalFilesAreTickedAndInstallInRowOrder() = withViewModel { world, vm ->
        world.serve(2, "a.vpk" to FakeVpk.directory(seed = 2))
        world.serve(3, "b.vpk" to FakeVpk.directory(seed = 3))
        install(world, lilith)
        val id = vm.dialog().id
        vm.toggle(id, VersionPick.File(1))
        assertEquals(emptySet(), vm.dialog().selected, "the most downloaded can be unticked too")
        assertFalse(vm.dialog().canConfirm, "nothing ticked installs nothing")
        vm.confirm(id)
        runCurrent()
        assertIs<OperationStage.ChoosingFile>(world.library.state.value.operations.getValue(lilith.modId).stage)
        assertEquals(emptySet(), vm.dialog().selected, "an empty draft stays empty rather than falling back to the default")

        vm.toggle(id, VersionPick.File(3))
        vm.toggle(id, VersionPick.File(2))
        vm.setProfile(id, world.library.state.value.profiles!!.active, false)
        assertEquals(setOf(VersionPick.File(2), VersionPick.File(3)) to emptySet(), vm.dialog().let { it.selected to it.selectedProfiles })
        assertEquals(listOf(VersionPick.File(2), VersionPick.File(3)), vm.dialog().selectedRows.map { it.pick }, "row order, whatever order they were ticked in")
        assertEquals(listOf(34_600_000L, 27_400_000L), vm.dialog().toDownload.map { it.sizeBytes })

        vm.confirm(id)
        vm.confirm(id)
        runCurrent()
        assertEquals(listOf(2L, 3L), world.downloader.requested, "in row order; a second confirm installs nothing more")
        assertEquals(InstallStatus.Installed(enabled = false), world.library.status(lilith.modId).first())
        val record = world.record(lilith)
        assertEquals(listOf(2L, 3L), record.sets.map { it.fileId }, "one mod, the higher row winning")
        assertEquals(Placement.Off, record.placement, "off, so holding no number")
    }

    @Test
    fun changingFilesStartsFromWhatTheModHasAndOffersOnlyARealChange() = withViewModel { world, vm ->
        world.serve(1, "a.vpk" to FakeVpk.directory(seed = 1))
        world.serve(2, "b.vpk" to FakeVpk.directory(seed = 2))
        install(world, lilith)
        vm.confirm(vm.dialog().id)
        runCurrent()
        vm.finished(vm.dialog().id)
        val installed = world.record(lilith)
        assertEquals(listOf(1L), installed.sets.map { it.fileId })
        world.downloader.requested.clear()

        world.library.changeFiles(installed.installId)
        runCurrent()
        val dialog = vm.dialog()
        assertTrue(dialog.replacing)
        assertEquals(setOf(VersionPick.File(1)), dialog.selected, "it starts from the file the mod has")
        assertEquals(setOf(VersionPick.File(1)), dialog.installed)
        assertEquals(listOf(true, false, false), dialog.rows.map { it.installed })
        assertFalse(dialog.canConfirm, "the files it has already are no change")
        vm.toggle(dialog.id, VersionPick.File(2))
        assertTrue(vm.dialog().canConfirm)
        assertEquals(listOf(VersionPick.File(2)), vm.dialog().toDownload.map { it.pick }, "only the new file downloads")
        vm.confirm(dialog.id)
        runCurrent()
        assertEquals(listOf(2L), world.downloader.requested)
        val changed = world.record(lilith)
        assertEquals(installed.installId, changed.installId)
        assertEquals(listOf(1L, 2L), changed.sets.map { it.fileId })
        assertEquals(installed.sets.single(), changed.sets.first(), "the kept file is untouched")
    }

    @Test
    fun changeFilesKeepingAFileWithAPartLeftOutAsksAboutItsPartsAgain() = withViewModel { world, vm ->
        world.serve(1, "pak01_dir.vpk" to FakeVpk.directory(seed = 1), "sounds/pak02_dir.vpk" to FakeVpk.directory(seed = 2))
        install(world, lilith)
        vm.confirm(vm.dialog().id)
        runCurrent()
        vm.finished(vm.dialog().id)
        val parts = vm.dialog()
        assertEquals(Question.Sets, parts.question)
        vm.toggle(parts.id, VersionPick.Set("sounds/pak02_dir.vpk"))
        vm.confirm(parts.id)
        runCurrent()
        vm.finished(parts.id)
        val installed = world.record(lilith)
        assertEquals(listOf("pak01_dir.vpk"), installed.sets.map { it.componentKey })

        world.library.changeFiles(installed.installId)
        runCurrent()
        val files = vm.dialog()
        assertEquals(files.installed, files.selected)
        assertTrue(files.canConfirm, "the file it has, with a part left out, may be confirmed as it is")
        vm.confirm(files.id)
        runCurrent()
        vm.finished(files.id)
        val again = vm.dialog()
        assertEquals(Question.Sets, again.question, "its parts are asked about again")
        assertEquals(setOf(VersionPick.Set("pak01_dir.vpk")), again.selected, "the part left out starts unticked")
        vm.toggle(again.id, VersionPick.Set("sounds/pak02_dir.vpk"))
        vm.confirm(again.id)
        runCurrent()
        val changed = world.record(lilith)
        assertEquals(listOf("pak01_dir.vpk", "sounds/pak02_dir.vpk"), changed.sets.map { it.componentKey })
        assertEquals(emptyMap(), changed.declined)
    }

    @Test
    fun aFileGameBananaNoLongerListsIsStillOfferedToKeep() = withViewModel { world, vm ->
        world.serve(10, "a.vpk" to FakeVpk.directory(seed = 1))
        world.serve(11, "b.vpk" to FakeVpk.directory(seed = 2))
        world.library.install(InstallRequest(InstallInput.Remote(ivy, setOf(10)), emptySet()))
        runCurrent()
        val installed = world.record(ivy)
        world.banana.listings = world.banana.listings + (ivy to FileListing.Available("Bubblegum ivy V2", listOf(file(11, "bg_ivy_alt.zip", 5))))

        world.library.changeFiles(installed.installId)
        runCurrent()
        val dialog = vm.dialog()
        assertEquals(listOf(VersionPick.File(10), VersionPick.File(11)), dialog.rows.map { it.pick }, "retained files keep their priority, even when delisted")
        assertEquals(listOf(false, true), dialog.rows.map { it.listed })
        assertEquals(setOf(VersionPick.File(10)), dialog.selected)
        vm.toggle(dialog.id, VersionPick.File(11))
        vm.confirm(dialog.id)
        runCurrent()
        val changed = world.record(ivy)
        assertEquals(listOf(10L, 11L), changed.sets.map { it.fileId }, "the retained file keeps priority over the newly added one")
    }

    @Test
    fun componentsStartSelectedAndCanBeDeselectedAndOrderedWithoutSplittingChunks() = withViewModel { world, vm ->
        world.serve(10, "Blue/pak01_dir.vpk" to FakeVpk.directory(seed = 1), "Red/pak01_dir.vpk" to FakeVpk.directory(seed = 2), "Red/pak01_000.vpk" to ByteArray(1))
        install(world, ivy)
        val fileQuestion = vm.dialog()
        vm.setProfile(fileQuestion.id, world.library.state.value.profiles!!.active, false)
        vm.dialog().selected.forEach { vm.toggle(fileQuestion.id, it) }
        vm.toggle(fileQuestion.id, VersionPick.File(10))
        assertEquals(setOf(VersionPick.File(10)), vm.dialog().selected)
        vm.confirm(fileQuestion.id)
        runCurrent()
        assertTrue(vm.dialog().closing, "the file question stays on screen, closing, until it has gone")
        vm.finished(fileQuestion.id)
        runCurrent()

        val sets = vm.dialog()
        assertEquals(Question.Sets, sets.question)
        assertTrue(sets.id.value > fileQuestion.id.value)
        assertEquals("bg_ivy.zip", sets.download)
        assertEquals(listOf("Blue", "Red"), sets.rows.map { it.name })
        assertEquals(listOf(VersionPick.Set("Blue/pak01_dir.vpk"), VersionPick.Set("Red/pak01_dir.vpk")), sets.rows.map { it.pick })
        assertTrue(sets.rows.all { it.path == null }, "parts with different names show no path")
        assertEquals(sets.rows.map { it.pick }.toSet(), sets.selected)
        assertTrue(sets.selectedProfiles.isEmpty(), "the file answer's profiles carry into the set question")
        assertTrue(sets.canConfirm)
        vm.selectAll(sets.id)
        assertTrue(vm.dialog().selected.isEmpty())
        assertFalse(vm.dialog().canConfirm)
        vm.confirm(sets.id)
        runCurrent()
        assertIs<OperationStage.ChoosingSet>(world.library.state.value.operations.getValue(ivy.modId).stage)

        vm.selectAll(sets.id)
        vm.movePart(sets.id, VersionPick.Set("Red/pak01_dir.vpk"), -1)
        assertEquals(listOf("Red/pak01_dir.vpk", "Blue/pak01_dir.vpk"), vm.dialog().selectedRows.map { (it.pick as VersionPick.Set).key })
        vm.confirm(sets.id)
        runCurrent()
        val record = assertNotNull((world.library.state.value.library as LibraryStatus.Ready).records.firstOrNull { it.modId == ivy.modId })
        assertFalse(record.enabled)
        assertEquals(3, record.files.size)
        assertEquals(listOf("Red/pak01_dir.vpk", "Blue/pak01_dir.vpk"), record.sets.map { it.componentKey })
        assertEquals(listOf(2, 1), record.sets.map { it.files.size })
    }

    @Test
    fun partsThatChangeTheSameFilesShowAsOneGroupAndTickingTwoCountsTheFilesTheyShare() = withViewModel { world, vm ->
        fun colour(name: String) = FakeVpk.of(mapOf("materials/vindicta/bra.vmat_c" to name.encodeToByteArray(), "materials/vindicta/panties.vmat_c" to name.encodeToByteArray()))
        world.serve(10, "Red/pak04_dir.vpk" to colour("red"), "Necklace/pak01_dir.vpk" to FakeVpk.directory(seed = 3), "Blue/pak02_dir.vpk" to colour("blue"))
        world.library.install(InstallRequest(InstallInput.Remote(ivy, setOf(10)), setOf(world.library.state.value.profiles!!.active)))
        runCurrent()
        val sets = vm.dialog()
        assertEquals(listOf("Necklace", "Blue", "Red"), sets.rows.map { it.name })
        assertEquals(listOf(null, 0, 0), sets.rows.map { it.group })
        assertEquals(setOf(VersionPick.Set("Necklace/pak01_dir.vpk"), VersionPick.Set("Blue/pak02_dir.vpk")), sets.selected, "one colour, the author's lowest, starts ticked")
        assertEquals(emptyMap(), sets.shared)
        vm.toggle(sets.id, VersionPick.Set("Red/pak04_dir.vpk"))
        assertEquals(mapOf(0 to 2), vm.dialog().shared, "two ticked colours share both materials")
    }

    @Test
    fun anAuthorsLowNumberedAddOnStartsTickedWithTheBaseItChangesAndSaysWhichShowsWhereTheyOverlap() = withViewModel { world, vm ->
        fun variant(name: String) = FakeVpk.of(listOf("models/heroes/paige/paige.vmdl_c", "materials/paige/body.vmat_c", "materials/paige/hair.vtex_c", "materials/paige/eyes.vtex_c")
            .associateWith { name.encodeToByteArray() })
        val cowPrint = FakeVpk.of(mapOf("materials/paige/body.vmat_c" to "cow".encodeToByteArray()))
        world.serve(10, "Pink/pak12_dir.vpk" to variant("pink"), "CowPrint/pak01_dir.vpk" to cowPrint, "Mint/pak14_dir.vpk" to variant("mint"))
        world.library.install(InstallRequest(InstallInput.Remote(ivy, setOf(10)), setOf(world.library.state.value.profiles!!.active)))
        runCurrent()
        val sets = vm.dialog()
        assertEquals(listOf("CowPrint", "Pink", "Mint"), sets.rows.map { it.name })
        assertEquals(listOf(null, 0, 0), sets.rows.map { it.group }, "only the variants, which replace the same model, are one pick")
        assertEquals(setOf(VersionPick.Set("CowPrint/pak01_dir.vpk"), VersionPick.Set("Pink/pak12_dir.vpk")), sets.selected, "the add-on starts ticked with the base")
        assertEquals(Covers("Pink", 1, above = true), sets.rows.first().covers, "the add-on shows over the one file of the base it changes")
        vm.movePart(sets.id, VersionPick.Set("CowPrint/pak01_dir.vpk"), 1)
        assertEquals(Covers("Pink", 1, above = false), vm.dialog().rows.single { it.name == "CowPrint" }.covers, "moved below the base, the base shows there")
    }

    @Test
    fun cancelSettlesTheQuestionAndKeepsTheClosingDialogUntilItHasGone() = withViewModel { world, vm ->
        install(world, lilith)
        val shown = vm.dialog()
        vm.cancel(shown.id)
        runCurrent()
        assertNull(world.library.state.value.operations[lilith.modId], "the library is no longer waiting")
        assertEquals(shown.copy(closing = true), vm.dialog())
        vm.toggle(shown.id, VersionPick.File(2))
        vm.confirm(shown.id)
        assertEquals(shown.copy(closing = true), vm.dialog(), "a closing dialog takes no more input")
        vm.finished(shown.id)
        runCurrent()
        assertNull(vm.uiState.value)
        assertEquals(emptyList(), world.downloader.requested)
    }

    @Test
    fun theOldestQuestionShowsFirstAndALaterOneNeverReplacesIt() = withViewModel { world, vm ->
        install(world, lilith)
        val first = vm.dialog()
        install(world, ivy)
        assertEquals(first.id, vm.dialog().id)
        val second = assertNotNull(world.library.state.value.operations[ivy.modId]?.stage?.choice)
        vm.cancel(second)
        vm.selectAll(second)
        runCurrent()
        assertTrue(world.library.state.value.operations[ivy.modId]?.stage?.choice == second, "actions for a question not on screen answer nothing")

        vm.cancel(first.id)
        runCurrent()
        assertEquals(first.id, vm.dialog().id, "the next question waits while the first is closing")
        vm.finished(first.id)
        runCurrent()
        assertEquals(second, vm.dialog().id)
        assertFalse(vm.dialog().closing)
    }

    @Test
    fun settlingNeverFlashesTheNextQuestionBeforeTheSettledOneIsClosing() = withViewModel { world, vm ->
        install(world, lilith)
        install(world, ivy)
        val first = vm.dialog()
        val seen = mutableListOf<InstallDialogState?>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect { seen += it } }
        vm.preview(first.id, 2)
        runCurrent()
        assertTrue(seen.all { it?.id == first.id }, "every emission is still the first question, open or closing: ${seen.map { it?.id to it?.closing }}")
        assertEquals(PreviewTarget(lilith.modId, 2, PreviewFixtures.Md5), seen.last()?.preview)
    }

    @Test
    fun previewCancelsTheQuestionAndCarriesTheExactFileToOpen() = withViewModel { world, vm ->
        install(world, lilith)
        val id = vm.dialog().id
        vm.preview(id, 3)
        assertFalse(vm.dialog().closing, "a row without a preview opens nothing")
        vm.preview(id, 2)
        runCurrent()
        assertNull(world.library.state.value.operations[lilith.modId])
        assertEquals(PreviewTarget(lilith.modId, 2, PreviewFixtures.Md5), vm.dialog().preview)
        assertTrue(vm.dialog().closing)
        assertEquals(emptyList(), world.downloader.requested)
    }
}
