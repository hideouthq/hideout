package gg.hideouthq.feature.moddetail

import gg.hideouthq.core.analytics.*
import gg.hideouthq.AppContainer
import gg.hideouthq.core.data.CatalogRepository
import gg.hideouthq.core.data.DataError
import gg.hideouthq.core.data.InstallStatus
import gg.hideouthq.core.data.Loadable
import gg.hideouthq.core.data.Preferences
import gg.hideouthq.core.data.ViewerFiles
import gg.hideouthq.core.data.fake.FakeCatalogRepository
import gg.hideouthq.core.data.fake.FakeConfig
import gg.hideouthq.core.data.fake.FakeSoundSamplePlayer
import gg.hideouthq.core.data.fake.FakeStore
import gg.hideouthq.core.data.fake.MemoryStorage
import gg.hideouthq.core.data.fake.NoCacheStorage
import gg.hideouthq.core.data.preview.FixturePreviewSource
import gg.hideouthq.core.data.preview.PreviewFixtures
import gg.hideouthq.core.data.preview.PreviewRepository
import gg.hideouthq.core.data.preview.PreviewSource
import gg.hideouthq.core.data.preview.noPreviews
import gg.hideouthq.core.model.Backdrop
import gg.hideouthq.core.model.CalendarDate
import gg.hideouthq.core.model.CoverImage
import gg.hideouthq.core.model.Mod
import gg.hideouthq.core.model.ModCategory
import gg.hideouthq.core.model.PreviewVariant
import gg.hideouthq.core.model.SceneLighting
import gg.hideouthq.core.model.SceneSettings
import gg.hideouthq.core.navigation.PreviewRequest
import gg.hideouthq.feature.ViewModels
import gg.hideouthq.feature.moddetail.viewer.PreviewModel
import gg.hideouthq.feature.moddetail.viewer.SoundStageState
import gg.hideouthq.feature.moddetail.viewer.ViewerCategory
import gg.hideouthq.feature.withViewModels
import gg.hideouthq.preview.AnimationCategory
import gg.hideouthq.preview.IndexEntry
import gg.hideouthq.preview.PreviewClip
import gg.hideouthq.preview.problemsAgainst
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ModDetailViewModelTest {
    @Test
    fun theInstallationFollowsTheLibrary() = withViewModels { viewModels ->
        val catalog = FakeCatalogRepository(FakeStore(FakeConfig()))
        val installations = AppContainer.fake().modLibrary
        val vm = viewModels.keep(ModDetailViewModel("mod-551201", catalog, installations, FakeSoundSamplePlayer(backgroundScope), noPreviews()))
        backgroundScope.launch { vm.uiState.collect() }
        runCurrent()

        assertFalse(vm.uiState.value.loading)
        assertEquals(InstallStatus.Installed(true), vm.uiState.value.installation.status)
        vm.setProfile(installations.state.value.profiles!!.active, false)
        runCurrent()
        assertEquals(InstallStatus.Installed(false), vm.uiState.value.installation.status)
        assertTrue(vm.uiState.value.installation.selectedProfiles.isEmpty())
    }

    @Test
    fun aSoundPagePlaysPausesAndMovesThroughItsPreview() = withViewModels { viewModels ->
        val catalog = FakeCatalogRepository(FakeStore(FakeConfig()))
        val player = FakeSoundSamplePlayer(backgroundScope)
        val vm = viewModels.keep(ModDetailViewModel("announcer-pack", catalog, AppContainer.fake().modLibrary, player, noPreviews()))
        backgroundScope.launch { vm.uiState.collect() }
        backgroundScope.launch { vm.samplePosition.collect() }
        runCurrent()
        assertEquals(SoundStageState(available = true), vm.uiState.value.sound)

        vm.toggleSample()
        advanceTimeBy(1_050)
        runCurrent()
        assertTrue(vm.uiState.value.sound!!.playing)
        assertEquals(1_000, vm.samplePosition.value)

        vm.toggleSample()
        vm.seekSample(0.5f)
        vm.setSampleLooping(true)
        runCurrent()
        assertFalse(vm.uiState.value.sound!!.playing)
        assertTrue(vm.uiState.value.sound!!.looping)
        assertEquals(4_000, vm.samplePosition.value)

        vm.stopSample()
        runCurrent()
        assertEquals(null, player.nowPlaying.value)
    }

    @Test
    fun aPreviewedPageGetsItsVerifiedManifestAndAMismatchedOneIsRefused() = withViewModels { viewModels ->
        val manifest = PreviewFixtures.manifest()
        val entry = PreviewFixtures.entry(manifest)
        val honest = PreviewFixtures.text(manifest)
        for ((text, ready) in listOf(honest to true, honest.replace("\"id\":101", "\"id\":102") to false)) {
            val vm = lilith(viewModels, FakeCatalogRepository(FakeStore(FakeConfig(previews = mapOf("lilith" to entry)))), FixturePreviewSource("", mapOf(entry.bundleId to text)))
            runCurrent()

            val state = vm.uiState.value
            assertEquals(entry, state.header?.preview)
            assertTrue(state.header!!.has3dPreview)
            if (ready) {
                assertEquals(Loadable.Ready(PreviewModel(entry, manifest)), state.preview)
                assertEquals(manifest.clips, state.stageModel?.clips)
            } else {
                assertEquals(Loadable.Failed(DataError.Unavailable), state.preview)
                assertEquals(null, state.stageModel)
            }
        }
    }

    @Test
    fun theViewerPlaysFromTheVerifiedBundlesClips() = withViewModels { viewModels ->
        val base = PreviewFixtures.manifest()
        val manifest = base.copy(
            clips = listOf(PreviewClip("idle", 1_000, AnimationCategory.Idle), PreviewClip("walk", 800, AnimationCategory.Movement)),
            pictures = base.pictures.map { it.copy(clip = "idle", timeMillis = 500) },
        )
        val entry = PreviewFixtures.entry(manifest)
        val catalog = FakeCatalogRepository(FakeStore(FakeConfig(previews = mapOf("lilith" to entry))))
        val vm = lilith(viewModels, catalog, FixturePreviewSource("", mapOf(entry.bundleId to PreviewFixtures.text(manifest))))
        runCurrent()
        val catalogue = checkNotNull(vm.uiState.value.stageModel).catalogue

        assertEquals(ViewerCategory.Idle, vm.uiState.value.viewer.activeCategory(catalogue))
        vm.pickCategory(ViewerCategory.Movement)
        runCurrent()
        assertEquals("walk", vm.uiState.value.viewer.clipId)
        assertEquals(ViewerCategory.Movement, vm.uiState.value.viewer.openMenu(catalogue))
    }

    @Test
    fun anIndexRefreshNeverPairsTheNewEntryWithTheOldManifest() = withViewModels { viewModels ->
        val old = PreviewFixtures.manifest(fileId = 101)
        val new = PreviewFixtures.manifest(fileId = 102)
        val oldEntry = PreviewFixtures.entry(old)
        val newEntry = PreviewFixtures.entry(new)
        val newManifest = CompletableDeferred<String>()
        val source = object : PreviewSource {
            override suspend fun indexText(held: String?): String? = error("The view model never reads the index")
            override suspend fun manifestText(entry: IndexEntry): String = when (entry.bundleId) {
                old.bundleId -> PreviewFixtures.text(old)
                new.bundleId -> newManifest.await()
                else -> error("No such bundle")
            }
        }
        val store = FakeStore(FakeConfig(previews = mapOf("lilith" to oldEntry)))
        val catalog = FakeCatalogRepository(store)
        val vm = lilith(viewModels, catalog, source, mods = LaggingCollections(catalog))
        val seen = mutableListOf<ModDetailUiState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect { seen += it } }
        advanceTimeBy(Settle)
        runCurrent()
        assertEquals(PreviewModel(oldEntry, old), vm.uiState.value.stageModel)

        store.mods.update { mods -> mods.map { if (it.id == "lilith") it.copy(preview = newEntry) else it } }
        advanceTimeBy(Settle)
        runCurrent()
        assertEquals(newEntry, vm.uiState.value.header?.preview)
        assertNull(vm.uiState.value.stageModel, "The new entry's manifest has not arrived")

        newManifest.complete(PreviewFixtures.text(new))
        advanceTimeBy(Settle)
        runCurrent()
        assertEquals(PreviewModel(newEntry, new), vm.uiState.value.stageModel)

        val models = seen.mapNotNull { state -> state.stageModel?.let { state.header?.preview to it } }
        assertTrue(models.size >= 2, "Expected the old and the new model among ${seen.size} states")
        for ((header, model) in models) {
            assertEquals(header, model.entry, "A stage model's entry is not the page's entry")
            assertEquals(emptyList(), model.entry.problemsAgainst(model.manifest), "A stage model pairs an entry with another bundle's manifest")
        }
    }

    @Test
    fun pickingAVariantResolvesItsOwnManifestSwitchingCancelsTheOldLoadAndARemovedFileInvalidates() = withViewModels { viewModels ->
        val manifestA = PreviewFixtures.manifest(fileId = 101)
        val manifestB = PreviewFixtures.manifest(fileId = 102)
        val entryA = PreviewFixtures.entry(manifestA)
        val entryB = PreviewFixtures.entry(manifestB)
        val variantA = PreviewVariant(101, manifestA.input.file.md5, "a.zip", null, entryA)
        val variantB = PreviewVariant(102, manifestB.input.file.md5, "b.zip", null, entryB)
        val manifestBText = CompletableDeferred<String>()
        val source = object : PreviewSource {
            override suspend fun indexText(held: String?): String? = error("The view model never reads the index")
            override suspend fun manifestText(entry: IndexEntry): String = when (entry.bundleId) {
                entryA.bundleId -> PreviewFixtures.text(manifestA)
                entryB.bundleId -> manifestBText.await()
                else -> error("No such bundle")
            }
        }
        val store = FakeStore(FakeConfig())
        store.mods.update { mods -> mods.map { if (it.id == "lilith") it.copy(preview = null, previewVariants = listOf(variantA, variantB)) else it } }
        val vm = lilith(viewModels, FakeCatalogRepository(store), source)
        runCurrent()

        assertNull(vm.uiState.value.header?.preview)
        assertEquals(101L, vm.uiState.value.selectedFile?.fileId, "the initial version chip is an explicit selection")
        assertEquals(PreviewModel(entryA, manifestA), vm.uiState.value.stageModel)

        vm.pickVariant(variantA.selection())
        runCurrent()
        assertEquals(PreviewModel(entryA, manifestA), vm.uiState.value.stageModel)

        vm.pickVariant(variantB.selection())
        runCurrent()
        assertNull(vm.uiState.value.stageModel, "variant A's model must not leak while variant B's manifest is still loading")

        manifestBText.complete(PreviewFixtures.text(manifestB))
        runCurrent()
        assertEquals(PreviewModel(entryB, manifestB), vm.uiState.value.stageModel)

        store.mods.update { mods -> mods.map { if (it.id == "lilith") it.copy(previewVariants = listOf(variantA)) else it } }
        runCurrent()
        assertEquals(102L, vm.uiState.value.selectedFile?.fileId, "the selection itself is not silently moved to another file")
        assertNull(vm.uiState.value.header?.preview, "the selected file is no longer current")
        assertNull(vm.uiState.value.stageModel, "a removed selection must invalidate, not fall back to a different file")
    }

    @Test
    fun theDefaultReadyPreviewSkipsAnUnpreparedAuxiliaryFileButExplicitChoicesDoNot() = withViewModels { viewModels ->
        val manifest = PreviewFixtures.manifest(fileId = 102)
        val entry = PreviewFixtures.entry(manifest)
        val store = FakeStore(FakeConfig())
        store.mods.update { mods -> mods.map { mod ->
            if (mod.id != "lilith") mod else mod.copy(preview = entry, has3dPreview = true, previewVariants = listOf(
                PreviewVariant(101, null, "optional_sound.zip", null, null),
                PreviewVariant(102, manifest.input.file.md5, "model.zip", null, entry),
            ))
        } }
        val vm = lilith(viewModels, FakeCatalogRepository(store), FixturePreviewSource("", mapOf(entry.bundleId to PreviewFixtures.text(manifest))))
        runCurrent()
        assertEquals(102L, vm.uiState.value.selectedFile?.fileId)
        assertEquals(PreviewModel(entry, manifest), vm.uiState.value.stageModel)
        vm.pickVariant(FileSelection(101))
        runCurrent()
        assertEquals(101L, vm.uiState.value.selectedFile?.fileId)
        assertNull(vm.uiState.value.stageModel)
    }

    @Test
    fun aPageOpenedForAnExactFileShowsItFromTheFirstFrameAndLaterRequestsMoveOnlyForward() = withViewModels { viewModels ->
        val manifestA = PreviewFixtures.manifest(fileId = 101)
        val manifestB = PreviewFixtures.manifest(fileId = 102)
        val entryA = PreviewFixtures.entry(manifestA)
        val entryB = PreviewFixtures.entry(manifestB)
        val source = FixturePreviewSource("", mapOf(entryA.bundleId to PreviewFixtures.text(manifestA), entryB.bundleId to PreviewFixtures.text(manifestB)))
        val store = FakeStore(FakeConfig())
        val variants = listOf(
            PreviewVariant(101, manifestA.input.file.md5, "a.zip", null, entryA),
            PreviewVariant(102, manifestB.input.file.md5, "b.zip", null, entryB),
        )
        store.mods.update { mods -> mods.map { if (it.id == "lilith") it.copy(preview = null, previewVariants = variants) else it } }
        val opened = PreviewRequest(102, manifestB.input.file.md5, serial = 4)
        val vm = lilith(viewModels, FakeCatalogRepository(store), source, opened)
        runCurrent()
        assertEquals(FileSelection(102, manifestB.input.file.md5), vm.uiState.value.selectedFile, "the requested file is selected without waiting for an effect")
        assertEquals(PreviewModel(entryB, manifestB), vm.uiState.value.stageModel)

        vm.show(opened)
        vm.pickVariant(FileSelection(101, manifestA.input.file.md5))
        vm.show(opened.copy(serial = 3))
        runCurrent()
        assertEquals(101L, vm.uiState.value.selectedFile?.fileId, "coming back to the page keeps what was picked on it since")

        vm.show(opened.copy(serial = 5))
        runCurrent()
        assertEquals(102L, vm.uiState.value.selectedFile?.fileId, "asking again for the same file still shows it")
        assertEquals(PreviewModel(entryB, manifestB), vm.uiState.value.stageModel)

        vm.show(PreviewRequest(102, "f".repeat(32), serial = 6))
        runCurrent()
        assertNull(vm.uiState.value.stageModel, "a file whose MD5 differs from the one asked for is never shown")

        vm.show(PreviewRequest(103, null, serial = 7))
        runCurrent()
        assertNull(vm.uiState.value.stageModel, "a file that is not current shows nothing rather than another file")
    }

    @Test
    fun aFileWithoutAPreviewKeepsItsFactsAndOnlyKnownMd5sMustAgree() {
        val md5 = "a".repeat(32)
        val sound = PreviewVariant(301, md5, "sound.zip", "Quiet", entry = null, sizeBytes = 2_000)
        val header = ModHeader(
            modId = "mod-9", title = "Sounds", category = ModCategory.Sounds, heroName = null, kind = null,
            has3dPreview = false, worksOnCurrentPatch = true, published = CalendarDate(2026, 1, 1),
            updated = CalendarDate(2026, 1, 1), downloads = 1, likes = 0, cover = CoverImage(),
            previewVariants = listOf(sound, PreviewVariant(302, null, "other.zip", null, null, sizeBytes = 5_000)),
        )
        val state = ModDetailUiState(header = header, selectedFile = sound.selection())
        assertEquals(FileSelection(301, md5), state.selectedFile, "a picked file keeps its MD5, so a re-upload under its id is not it")
        assertEquals(2_000L, state.selectedVersion?.sizeBytes, "a file needs no preview for its own facts")
        assertNull(header.previewFor(state.selectedFile))
        assertEquals(2_000L, state.copy(selectedFile = FileSelection(301)).selectedVersion?.sizeBytes, "an unknown MD5 does not refuse the exact id")
        assertNull(state.copy(selectedFile = FileSelection(301, "f".repeat(32))).selectedVersion, "a known MD5 that differs is refused")
        assertEquals(5_000L, state.copy(selectedFile = FileSelection(302, md5)).selectedVersion?.sizeBytes, "a catalogue file without an MD5 has nothing to disagree with")
        assertNotNull(header.nameOf(FileSelection(301, md5)))
        assertNull(header.nameOf(FileSelection(301, "f".repeat(32))), "a re-uploaded installed file keeps the manifest's label")
        assertNull(header.nameOf(FileSelection(399, md5)), "so does a delisted one")
    }

    @Test
    fun theExactSelectionResolvesOnlyThatFileIncludingASingleCurrentOne() {
        val manifest = PreviewFixtures.manifest(fileId = 201)
        val entry = PreviewFixtures.entry(manifest)
        val md5 = manifest.input.file.md5
        val header = ModHeader(
            modId = "mod-9", title = "Single", category = ModCategory.Skins, heroName = null, kind = null,
            has3dPreview = true, worksOnCurrentPatch = true, published = CalendarDate(2026, 1, 1),
            updated = CalendarDate(2026, 1, 1), downloads = 1, likes = 0, cover = CoverImage(), preview = entry,
        )
        assertEquals(entry, header.previewFor(null), "nothing picked is the single current file")
        assertEquals(entry, header.previewFor(FileSelection(201, md5)), "picking the single current file explicitly is that file")
        assertEquals(entry, header.previewFor(FileSelection(201)), "an unknown MD5 does not refuse the exact id")
        assertNull(header.previewFor(FileSelection(201, "0".repeat(32))), "a known MD5 that differs is refused")
        assertNull(header.previewFor(FileSelection(202, md5)), "another id is never this file")

        val listed = header.copy(preview = null, previewVariants = listOf(PreviewVariant(201, md5, "single.zip", null, entry)))
        assertEquals(entry, listed.previewFor(FileSelection(201, md5)))
        assertNull(listed.previewFor(FileSelection(201, "0".repeat(32))))
        assertNull(listed.copy(previewVariants = listOf(PreviewVariant(201, "0".repeat(32), "single.zip", null, entry))).previewFor(FileSelection(201, md5)), "the catalogue's MD5 disagreeing refuses too")
        assertNull(header.copy(previewVariants = listOf(PreviewVariant(202, null, "other.zip", null, null))).previewFor(FileSelection(201, md5)), "listed variants that no longer include the file refuse it")
    }

    @Test
    fun theSceneIsTheSameOnEveryModPageAndKeptAcrossRestarts() = withViewModels { viewModels ->
        val storage = MemoryStorage()
        val preferences = Preferences(storage)
        val lilith = sceneViewModel("lilith", preferences, viewModels)
        val faun = sceneViewModel("faun-celeste", preferences, viewModels)
        runCurrent()
        assertEquals(SceneSettings(), lilith.uiState.value.scene)

        val chosen = SceneSettings(Backdrop.Broadway, SceneLighting.Dramatic, turntable = false, bloom = true)
        lilith.setScene(chosen)
        runCurrent()
        assertEquals(chosen, faun.uiState.value.scene, "another mod's page has the same scene")
        assertEquals(chosen, sceneViewModel("lilith", Preferences(storage), viewModels).also { runCurrent() }.uiState.value.scene, "and so does the next run")

        faun.setScene(faun.uiState.value.scene.reset())
        runCurrent()
        assertEquals(SceneSettings(Backdrop.Studio, SceneLighting.Studio, turntable = true, bloom = false), lilith.uiState.value.scene)
    }

    @Test
    fun theToolbarTabsAndOwnBackdropMoveTheViewerAndSaveImagesNoticeGoesAway() = withViewModels { viewModels ->
        val vm = sceneViewModel("lilith", Preferences(MemoryStorage()), viewModels)
        runCurrent()
        fun viewer() = vm.uiState.value.viewer
        assertTrue(viewer().autoSpin)
        vm.useTool(ViewerTool.AutoSpin)
        vm.useTool(ViewerTool.Scene)
        vm.useTool(ViewerTool.SaveImage)
        vm.useTool(ViewerTool.Fullscreen)
        runCurrent()
        assertEquals(ViewerState(autoSpin = false, sceneOpen = true, fullscreen = true, pictureRequests = 1), viewer())
        vm.showTab(StageTab.Images)
        runCurrent()
        assertEquals(ViewerState(autoSpin = false, tab = StageTab.Images, pictureRequests = 1), viewer(), "the images close the panel and leave full screen")
        vm.showTab(StageTab.Preview)
        vm.useTool(ViewerTool.Scene)
        vm.closeScene()
        runCurrent()
        assertFalse(viewer().sceneOpen)

        vm.useBackdrop(null)
        runCurrent()
        assertEquals(Backdrop.Studio, vm.uiState.value.scene.backdrop, "cancelling the picker with no picture of one's own changes nothing")
        vm.useBackdrop("/data/backdrops/a.png")
        runCurrent()
        vm.setScene(vm.uiState.value.scene.copy(backdrop = Backdrop.Studio))
        vm.useBackdrop(null)
        runCurrent()
        assertEquals(SceneSettings(Backdrop.Custom, customBackdrop = "/data/backdrops/a.png"), vm.uiState.value.scene, "cancelling goes back to the earlier picture")
        assertNull(vm.uiState.value.customBackdrop, "a picture that cannot be read is not drawn")

        vm.showNotice(PictureNotice.Saved("/home/me/Pictures/Hideout/lilith.png"))
        runCurrent()
        assertEquals(PictureNotice.Saved("/home/me/Pictures/Hideout/lilith.png"), vm.uiState.value.picture)
        advanceTimeBy(6_001)
        runCurrent()
        assertNull(vm.uiState.value.picture)
        vm.showNotice(PictureNotice.Failed)
        vm.dismissPicture()
        runCurrent()
        assertNull(vm.uiState.value.picture)
    }

    @Test
    fun retryAsksForAManifestThatFailedAgainAndLoadsTheViewportAgain() = withViewModels { viewModels ->
        val manifest = PreviewFixtures.manifest()
        val entry = PreviewFixtures.entry(manifest)
        val source = object : PreviewSource {
            var requests = 0
            override suspend fun indexText(held: String?): String? = error("Not asked")
            override suspend fun manifestText(entry: IndexEntry): String {
                requests++
                if (requests == 1) error("Preview host unreachable")
                return PreviewFixtures.text(manifest)
            }
        }
        val vm = lilith(viewModels, FakeCatalogRepository(FakeStore(FakeConfig(previews = mapOf("lilith" to entry)))), source)
        runCurrent()
        assertEquals(Loadable.Failed(DataError.Unavailable), vm.uiState.value.preview)

        vm.retryPreview()
        runCurrent()
        assertEquals(2, source.requests)
        assertEquals(Loadable.Ready(PreviewModel(entry, manifest)), vm.uiState.value.preview)
        assertEquals(1, vm.uiState.value.viewer.loadAttempts)
    }

    @Test
    fun manifestFailureIsReportedOnceAndAManifestAloneIsNotRenderingSuccess() = withViewModels { viewModels ->
        val sent = mutableListOf<UsageEvent>()
        val analytics = UsageAnalytics(MemoryStorage(), UsageTransport { _, event -> sent += event }, backgroundScope)
        analytics.load(); analytics.choose(true)
        val manifest = PreviewFixtures.manifest()
        val entry = PreviewFixtures.entry(manifest)
        val source = object : PreviewSource {
            var requests = 0
            override suspend fun indexText(held: String?): String? = null
            override suspend fun manifestText(entry: IndexEntry): String {
                if (++requests == 1) error("private host path and mod name")
                return PreviewFixtures.text(manifest)
            }
        }
        val vm = lilith(viewModels, FakeCatalogRepository(FakeStore(FakeConfig(previews = mapOf("lilith" to entry)))), source, analytics = analytics)
        runCurrent()
        val result = sent.filterIsInstance<UsageEvent.Result>().single()
        assertEquals(UsageOutcome.Failed, result.outcome)
        assertEquals(UsageStage.Manifest, result.stage)
        assertFalse(sent.toString().contains("private host"))
        vm.retryPreview()
        runCurrent()
        assertNotNull(vm.uiState.value.stageModel)
        assertEquals(1, sent.filterIsInstance<UsageEvent.Result>().size)
    }

    @Test
    fun cancellingAManifestReportsCancellationButRevokedAttemptsStaySilent() = withViewModels { viewModels ->
        for (revoke in listOf(false, true)) {
            val sent = mutableListOf<UsageEvent>()
            val analytics = UsageAnalytics(MemoryStorage(), UsageTransport { _, event -> sent += event }, backgroundScope)
            analytics.load(); analytics.choose(true)
            val manifest = PreviewFixtures.manifest()
            val entry = PreviewFixtures.entry(manifest)
            val held = CompletableDeferred<String>()
            val source = object : PreviewSource {
                override suspend fun indexText(held: String?): String? = null
                override suspend fun manifestText(entry: IndexEntry): String = held.await()
            }
            lilith(viewModels, FakeCatalogRepository(FakeStore(FakeConfig(previews = mapOf("lilith" to entry)))), source, analytics = analytics)
            runCurrent()
            if (revoke) { analytics.choose(false); analytics.choose(true) }
            viewModels.store.clear()
            runCurrent()
            val results = sent.filterIsInstance<UsageEvent.Result>()
            if (revoke) assertTrue(results.isEmpty()) else {
                assertEquals(UsageOutcome.Cancelled, results.single().outcome)
                assertEquals(UsageStage.Manifest, results.single().stage)
            }
            analytics.close()
        }
    }

    @Test
    fun aSavedPictureIsNamedAfterTheModAndItsVariantInPlainCharacters() {
        assertEquals("lilith-the-hell-warden-werewolf-transform.png", pictureFileName("Lilith, the Hell Warden", "Werewolf Transform"))
        assertEquals("mina-sadistic-warden.png", pictureFileName("Mina - Sadistic Warden", "  "))
        assertEquals("ålesund-skin-1.png", pictureFileName("Ålesund Skin!", "#1"))
        assertEquals("hideout.png", pictureFileName("???", null))
        assertTrue(pictureFileName("x".repeat(300), "y").length <= 100)
    }

    private fun heldPage(): Pair<FakeCatalogRepository, FixturePreviewSource> {
        val manifest = PreviewFixtures.manifest()
        val store = FakeStore(FakeConfig())
        val entry = PreviewFixtures.entry(manifest)
        store.mods.update { mods -> mods.map { if (it.id == "lilith") it.copy(preview = entry, has3dPreview = true) else it } }
        return FakeCatalogRepository(store) to FixturePreviewSource("", mapOf(entry.bundleId to PreviewFixtures.text(manifest)))
    }

    private suspend fun preferencesWith3dPreviews(on: Boolean) = Preferences(MemoryStorage()).apply { setLoad3dPreviews(on) }

    @Test
    fun withLoadingOffNothingOfThePreviewIsFetchedUntilTheUserAsks() = withViewModels { viewModels ->
        val (catalog, source) = heldPage()
        val vm = lilith(viewModels, catalog, source, preferences = preferencesWith3dPreviews(false))
        runCurrent()

        assertEquals(PreviewLoad.Held, vm.uiState.value.previewLoad)
        assertEquals(0, source.manifestRequests)
        assertTrue(vm.uiState.value.canLoadPreview)
        assertEquals(StageKind.Model, stageKind(vm.uiState.value, assertNotNull(vm.uiState.value.header)), "the stage keeps to its picture")

        vm.loadPreview()
        runCurrent()

        assertEquals(1, source.manifestRequests)
        assertNotNull(vm.uiState.value.stageModel)
        assertFalse(vm.uiState.value.canLoadPreview)
    }

    @Test
    fun aRequestLoadsThePreviewWhateverTheSetting() = withViewModels { viewModels ->
        val (catalog, source) = heldPage()
        val opened = lilith(viewModels, catalog, source, PreviewRequest(101, null, serial = 1), preferences = preferencesWith3dPreviews(false))
        runCurrent()
        assertNotNull(opened.uiState.value.stageModel, "a page opened for a file")

        val (otherCatalog, otherSource) = heldPage()
        val asked = lilith(viewModels, otherCatalog, otherSource, preferences = preferencesWith3dPreviews(false))
        runCurrent()
        asked.show(PreviewRequest(null, null, serial = 2))
        runCurrent()
        assertNotNull(asked.uiState.value.stageModel, "a page asked for its preview, as the hero's action does")
        assertNull(asked.uiState.value.selectedFile, "with no file named, the page's selection stands")
    }

    @Test
    fun switchingTheSettingOffLeavesALoadedPreviewAloneAndOnLoadsAHeldOne() = withViewModels { viewModels ->
        val (catalog, source) = heldPage()
        val preferences = preferencesWith3dPreviews(true)
        val vm = lilith(viewModels, catalog, source, preferences = preferences)
        runCurrent()
        val shown = assertNotNull(vm.uiState.value.stageModel)

        preferences.setLoad3dPreviews(false)
        runCurrent()
        assertEquals(shown, vm.uiState.value.stageModel)
        assertEquals(1, source.manifestRequests, "nothing was torn down and fetched again")

        val (heldCatalog, heldSource) = heldPage()
        val held = lilith(viewModels, heldCatalog, heldSource, preferences = preferences)
        runCurrent()
        assertNull(held.uiState.value.stageModel)
        preferences.setLoad3dPreviews(true)
        runCurrent()
        assertNotNull(held.uiState.value.stageModel)
    }

    private fun TestScope.lilith(
        viewModels: ViewModels,
        catalog: FakeCatalogRepository,
        source: PreviewSource,
        opened: PreviewRequest? = null,
        mods: CatalogRepository = catalog,
        preferences: Preferences? = null,
        analytics: UsageAnalytics? = null,
    ): ModDetailViewModel {
        val previews = PreviewRepository(source, NoCacheStorage, backgroundScope)
        val vm = viewModels.keep(ModDetailViewModel("lilith", mods, AppContainer.fake().modLibrary, FakeSoundSamplePlayer(backgroundScope), previews, opened, preferences = preferences, analytics = analytics))
        backgroundScope.launch { vm.uiState.collect() }
        return vm
    }

    private fun TestScope.sceneViewModel(modId: String, preferences: Preferences, viewModels: ViewModels): ModDetailViewModel {
        val catalog = FakeCatalogRepository(FakeStore(FakeConfig()))
        val vm = viewModels.keep(ModDetailViewModel(modId, catalog, AppContainer.fake().modLibrary, FakeSoundSamplePlayer(backgroundScope), noPreviews(), preferences = preferences, files = ViewerFiles.Unavailable))
        backgroundScope.launch { vm.uiState.collect() }
        return vm
    }
}

private const val Settle = 10L

private class LaggingCollections(private val catalog: CatalogRepository) : CatalogRepository by catalog {
    private var collections = 0

    override val mods: Flow<List<Mod>> = flow {
        val lag = collections++.toLong()
        catalog.mods.collect {
            delay(lag)
            emit(it)
        }
    }
}
