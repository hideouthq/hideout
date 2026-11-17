package gg.hideouthq.feature.collections

import androidx.lifecycle.ViewModelStore
import gg.hideouthq.AppContainer
import gg.hideouthq.core.data.CatalogRepository
import gg.hideouthq.core.data.FileListing
import gg.hideouthq.core.data.LibraryStore
import gg.hideouthq.core.data.ManifestSave
import gg.hideouthq.core.data.RemoteFile
import gg.hideouthq.core.data.collections.CollectionsRepository
import gg.hideouthq.core.data.fake.FakeConfig
import gg.hideouthq.core.data.fake.FakeDownloader
import gg.hideouthq.core.data.fake.FakeGameBanana
import gg.hideouthq.core.data.fake.FakeVpk
import gg.hideouthq.core.data.fake.InMemoryLibraryStore
import gg.hideouthq.core.data.fake.LibrarySeed
import gg.hideouthq.core.library.InstallInput
import gg.hideouthq.core.library.InstallRequest
import gg.hideouthq.core.library.LibraryManifest
import gg.hideouthq.core.library.LibraryProblem
import gg.hideouthq.core.library.LibraryStatus
import gg.hideouthq.core.library.MutationResult
import gg.hideouthq.core.library.NewProfile
import gg.hideouthq.core.library.ProfileColour
import gg.hideouthq.core.library.ProfileId
import gg.hideouthq.core.library.ProfileSeed
import gg.hideouthq.core.library.SubmissionKind
import gg.hideouthq.core.library.SubmissionRef
import gg.hideouthq.core.model.CalendarDate
import gg.hideouthq.core.model.CollectionLook
import gg.hideouthq.core.model.Mod
import gg.hideouthq.core.model.ModCategory
import gg.hideouthq.core.model.Rgb
import gg.hideouthq.core.model.SkinCollection
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
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
class MakeProfileTest {
    private val silverBest = SubmissionRef(SubmissionKind.Mod, 551_301)
    private val silverOther = SubmissionRef(SubmissionKind.Mod, 551_302)
    private val mina = SubmissionRef(SubmissionKind.Mod, 551_303)
    private val look = CollectionLook(Rgb(0x000000), Rgb(0x000000), Rgb(0xFFFFFF), emptyList(), arch = false, rim = listOf(Rgb(0xFFFFFF)))
    private val files = listOf(silverBest, silverOther, mina).associateWith { ref ->
        RemoteFile(ref.id - 551_000, "skin${ref.id}.zip", 1000, null, "Default", 20, 1, "https://gamebanana.com/dl/${ref.id}")
    }

    private class HeldStore(private val store: InMemoryLibraryStore) : LibraryStore by store {
        var held: CompletableDeferred<Unit>? = null

        override suspend fun save(manifest: LibraryManifest): ManifestSave {
            held?.await()
            return store.save(manifest)
        }
    }

    private inner class World {
        val store = HeldStore(InMemoryLibraryStore(LibraryManifest(records = emptyList())))
        val app = AppContainer.fake(
            FakeConfig(seed = LibrarySeed.Empty),
            libraryStore = store,
            gameBanana = FakeGameBanana(files.mapValues { (ref, file) -> FileListing.Available("Skin ${ref.id}", listOf(file)) }),
            downloader = { gameFiles ->
                FakeDownloader(gameFiles).also { downloader -> files.values.forEach { downloader.serve(it.id, listOf("skin.vpk" to FakeVpk.directory(seed = it.id.toInt()))) } }
            },
        )
        val library = app.modLibrary
        val catalog = object : CatalogRepository by app.catalog {
            override val mods = flowOf(listOf(skin(silverBest, "silver", 900), skin(silverOther, "silver", 100), skin(mina, "mina", 50)))
        }
        val collections = object : CollectionsRepository {
            override val collections: StateFlow<List<SkinCollection>> =
                MutableStateFlow(listOf(SkinCollection("halloween", "Halloween", null, look, emptyList(), listOf(silverOther.modId, mina.modId, silverBest.modId))))
        }
        val vm = CollectionViewModel("halloween", catalog, collections, library)

        fun sheet(): ProfileSheet = assertNotNull(vm.uiState.value.sheet)
        fun records() = (library.state.value.library as LibraryStatus.Ready).records
        fun book() = checkNotNull(library.state.value.profiles)
    }

    private fun check(block: suspend TestScope.(World) -> Unit) = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val world = World()
            store.put("collection", world.vm)
            backgroundScope.launch { world.vm.uiState.collect() }
            runCurrent()
            assertTrue(world.library.state.value.canModify)
            block(world)
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun theNewProfileGetsEachHerosMostPopularSkinWithoutBecomingActive() = check { world ->
        val main = world.book().active
        world.library.install(InstallRequest(InstallInput.Remote(mina), setOf(main)))
        runCurrent()
        val installedMina = world.records().single()

        world.vm.openProfileSheet()
        runCurrent()
        val sheet = world.sheet()
        assertEquals("Halloween", sheet.name)
        assertEquals(listOf("Mina" to mina.modId, "Silver" to silverBest.modId), sheet.rows.map { it.hero.name to it.picked }, "one row per hero, by name, the most downloaded picked")
        assertEquals(listOf(silverBest.modId, silverOther.modId), sheet.rows[1].options.map { it.modId })
        assertTrue(sheet.rows[0].options.single { it.modId == mina.modId }.installed, "Mina's pick is installed already")

        world.vm.confirmProfile()
        runCurrent()
        assertNull(world.vm.uiState.value.sheet, "the sheet closes once the profile exists")
        val book = world.book()
        assertEquals(main, book.active, "the active profile does not change")
        val made = book.profiles.single { it.name == "Halloween" }
        val installedSilver = world.records().single { it.modId == silverBest.modId }
        assertEquals(setOf(installedMina.installId, installedSilver.installId), made.enabled)
        assertEquals(setOf(installedMina.installId), book.activeProfile.enabled, "the new skin is installed into the new profile only")
        assertTrue(world.records().none { it.modId == silverOther.modId }, "only the picks install")
    }

    @Test
    fun aTakenNameIsRefusedAndTheNextFreeOneIsOffered() = check { world ->
        assertIs<MutationResult.Done<ProfileId>>(world.library.createProfile(NewProfile("halloween", ProfileColour.Blue, ProfileSeed.Empty, activate = false)))
        runCurrent()
        world.vm.openProfileSheet()
        runCurrent()
        assertEquals("Halloween 2", world.sheet().name)

        world.vm.setProfileName("HALLOWEEN ")
        runCurrent()
        assertEquals(LibraryProblem.ProfileNameTaken, world.sheet().nameProblem)
        assertFalse(world.sheet().canConfirm)
        val profiles = world.book().profiles.size
        world.vm.confirmProfile()
        runCurrent()
        assertEquals(profiles, world.book().profiles.size)
        assertNotNull(world.vm.uiState.value.sheet, "the sheet stays open")
    }

    @Test
    fun whileItsRequestIsOutTheSheetDoesNotCallItsOwnNameTaken() = check { world ->
        world.vm.openProfileSheet()
        runCurrent()
        val save = CompletableDeferred<Unit>().also { world.store.held = it }
        world.vm.confirmProfile()
        runCurrent()
        assertTrue(world.library.state.value.shownProfiles!!.profiles.any { it.name == "Halloween" }, "the shown book already holds the new profile")
        assertTrue(world.sheet().submitting)
        assertNull(world.sheet().nameProblem, "the sheet keeps showing, as it closes, what it shows now")

        save.complete(Unit)
        runCurrent()
        assertNull(world.vm.uiState.value.sheet)
    }

    private fun skin(ref: SubmissionRef, hero: String, downloads: Int) = Mod(
        id = ref.modId, title = "Skin ${ref.id}", category = ModCategory.Skins, heroId = hero, downloads = downloads, likes = 0, sizeBytes = null,
        publishedAt = 0, updated = CalendarDate(2026, 10, 1), trendingRank = 1,
    )
}
