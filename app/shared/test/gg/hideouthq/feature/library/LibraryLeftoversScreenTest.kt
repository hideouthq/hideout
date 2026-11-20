package gg.hideouthq.feature.library

import gg.hideouthq.core.data.FolderOpener
import gg.hideouthq.core.data.Preferences
import gg.hideouthq.core.data.ProcessScan
import gg.hideouthq.core.data.fake.FakeDownloader
import gg.hideouthq.core.data.fake.FakeGameBanana
import gg.hideouthq.core.data.fake.FakeGameLocator
import gg.hideouthq.core.data.fake.FakeProcesses
import gg.hideouthq.core.data.fake.FakeVpk
import gg.hideouthq.core.data.fake.InMemoryGameFiles
import gg.hideouthq.core.data.fake.InMemoryLibraryStore
import gg.hideouthq.core.data.fake.MemoryStorage
import gg.hideouthq.core.library.CitadelPath
import gg.hideouthq.core.library.GameInfoEdit
import gg.hideouthq.core.library.GameInfoPatch
import gg.hideouthq.core.library.GameRoot
import gg.hideouthq.core.library.HideoutLines
import gg.hideouthq.core.library.InstallPorts
import gg.hideouthq.core.library.LibraryManifest
import gg.hideouthq.core.library.LibraryProblem
import gg.hideouthq.core.library.ModLibrary
import gg.hideouthq.feature.withViewModels
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryLeftoversScreenTest {
    private val root = GameRoot("/games/Deadlock")
    private val gameInfo = (GameInfoPatch.withBlock("\"GameInfo\"\n{\n\tFileSystem\n\t{\n\t\tSearchPaths\n\t\t{\n\t\t\tGame citadel\n\t\t}\n\t}\n}\n".encodeToByteArray(), HideoutLines(mods = true, customGames = false), 0) as GameInfoEdit.Patched).bytes

    private inner class Screen(game: Boolean) {
        val files = InMemoryGameFiles().apply {
            putBytes(root, InMemoryGameFiles.GameInfo, gameInfo)
            putBytes(root, "${InMemoryGameFiles.Addons}/pak05_dir.vpk", FakeVpk.directory(seed = 5))
            putBytes(root, "${InMemoryGameFiles.Addons}/pak06_dir.vpk", FakeVpk.directory(seed = 6))
            putBytes(root, "${InMemoryGameFiles.Addons}/631628_pak07_dir.vpk", FakeVpk.directory(seed = 7))
        }
        val processes = FakeProcesses(ProcessScan.Seen(game = game))
        val opened = mutableListOf<CitadelPath>()
        lateinit var library: ModLibrary
        lateinit var screen: LibraryViewModel

        fun TestScope.open(viewModels: androidx.lifecycle.ViewModelStore) {
            library = ModLibrary(
                FakeGameLocator(listOf(root.path), mapOf(root.path to null)), Preferences(MemoryStorage()), InMemoryLibraryStore(LibraryManifest()), files,
                InstallPorts(FakeGameBanana(), FakeDownloader(files), files, files, processes, gameFiles = files.gameFiles), backgroundScope,
            ).also { it.start() }
            screen = LibraryViewModel(library, FolderOpener { _, folder -> opened += folder; true }).also { viewModels.put("library", it) }
            backgroundScope.launch { screen.uiState.collect {} }
            runCurrent()
        }

        val ui: LibraryUiState get() = screen.uiState.value
    }

    private fun screen(game: Boolean = false, test: suspend TestScope.(Screen) -> Unit) = withViewModels { viewModels ->
        val screen = Screen(game)
        with(screen) { open(viewModels.store) }
        test(screen)
    }

    @Test
    fun theNoteCountsWhatLoadsAndGoesOnceEveryLeftoverIsMovedOut() = screen { screen ->
        assertEquals(LibraryNote.Leftovers(2, "addons"), screen.ui.note)
        screen.screen.reviewLeftovers()
        runCurrent()
        assertTrue(screen.ui.leftovers.open && screen.ui.leftovers.canChange)

        screen.screen.moveLeftoversOut(screen.ui.leftovers.sets.take(1).map { it.slot })
        runCurrent()
        assertEquals(LibraryNote.Leftovers(1, "addons"), screen.ui.note)
        val moved = (screen.ui.leftovers.last as LeftoverResult.Moved).folder
        assertTrue(moved.startsWith("hideout_backups\\moved\\"), "a folder below citadel, as this system writes paths: $moved")

        screen.screen.openMovedFolder()
        runCurrent()
        assertEquals(listOf(CitadelPath(moved.replace('\\', '/'))), screen.opened)

        screen.screen.moveLeftoversOut(screen.ui.leftovers.sets.map { it.slot })
        runCurrent()
        assertNull(screen.ui.note)
        assertTrue(screen.ui.leftovers.open && screen.ui.leftovers.sets.isEmpty(), "the dialog stays to say where they went")
        screen.screen.closeLeftovers()
        runCurrent()
        assertFalse(screen.ui.leftovers.open)
    }

    @Test
    fun addedLeftoversBecomeRowsAndLeaveTheNote() = screen { screen ->
        screen.screen.addLeftovers(screen.ui.leftovers.sets.map { it.slot })
        runCurrent()

        assertNull(screen.ui.note)
        assertEquals(LeftoverResult.Added, screen.ui.leftovers.last)
        assertEquals(listOf("pak05_dir", "pak06_dir"), screen.ui.rows.map { it.title })
        assertTrue(screen.ui.rows.all { it.enabled })
    }

    @Test
    fun whileDeadlockRunsTheReviewWaitsAndNothingChanges() = screen(game = true) { screen ->
        assertFalse(screen.ui.leftovers.canChange)
        screen.screen.moveLeftoversOut(screen.ui.leftovers.sets.map { it.slot })
        runCurrent()

        assertEquals(LeftoverResult.Failed(LibraryProblem.GameRunning), screen.ui.leftovers.last)
        assertEquals(LibraryNote.Leftovers(2, "addons"), screen.ui.note)
    }
}
