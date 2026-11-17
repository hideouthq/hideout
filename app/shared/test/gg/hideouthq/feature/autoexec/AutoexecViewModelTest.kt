package gg.hideouthq.feature.autoexec

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.text.input.insert
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.snapshots.Snapshot
import gg.hideouthq.AppContainer
import gg.hideouthq.core.autoexec.AutoexecDocument
import gg.hideouthq.core.autoexec.SectionMarkers
import gg.hideouthq.core.autoexec.CommandCategory
import gg.hideouthq.core.data.SavedNote
import gg.hideouthq.core.data.fake.FakeConfig
import gg.hideouthq.core.data.fake.FakeConfigFiles
import gg.hideouthq.core.data.fake.FaultPoint
import gg.hideouthq.core.data.fake.InMemoryGameFiles
import gg.hideouthq.core.data.fake.InMemoryLibraryStore
import gg.hideouthq.core.data.fake.UncataloguedSampleManifest
import gg.hideouthq.core.library.ConfigTarget
import gg.hideouthq.core.library.GameStatus
import gg.hideouthq.core.library.LibraryProblem
import gg.hideouthq.core.library.PendingChange
import gg.hideouthq.feature.withViewModels
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalFoundationApi::class)
class AutoexecViewModelTest {
    private val header = AutoexecDocument.Header
    private val commands = "$header\nfps_max 240\n// cl_showfps 1\nsensitivity 1.6\n"
    private val section = "${SectionMarkers.GrimoireCommands.start}\nvolume 1\n${SectionMarkers.GrimoireCommands.end}\n"
    private val fixture = "$commands\n$section"

    private inner class Page(config: FakeConfig) {
        val files = UncataloguedSampleManifest.files(config)
        val store = InMemoryLibraryStore(UncataloguedSampleManifest.manifest(config))
        val opened = FakeConfigFiles(files)
        val container = AppContainer.fake(config, libraryStore = store, files = files, configFiles = opened)
        lateinit var vm: AutoexecViewModel

        val ui: AutoexecUiState get() = vm.uiState.value
        val ready: AutoexecFile.Ready get() = assertIs(ui.file)
        val text: String get() = vm.editor.text.toString()
        val root get() = (container.modLibrary.state.value.game as GameStatus.Ready).root

        fun file(): String? = files.bytes(root, InMemoryGameFiles.Autoexec)?.decodeToString()

        fun writes(): Int = store.saved.count { (it.pending as? PendingChange.ConfigEdit)?.target is ConfigTarget.Autoexec }

        fun row(name: String) = ready.commands.single { it.row.ref.name == name }

        fun item(name: String) = ui.library.groups.flatMap { it.items }.single { it.command.name == name }
    }

    private fun page(autoexec: String? = fixture, test: suspend TestScope.(Page) -> Unit) = withViewModels { viewModels ->
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val page = Page(FakeConfig(autoexec = autoexec))
        page.vm = viewModels.keep(AutoexecViewModel(page.container.autoexec, page.container.modLibrary, page.opened))
        backgroundScope.launch { page.vm.uiState.collect {} }
        settle()
        test(page)
    }

    private fun TestScope.settle() {
        Snapshot.sendApplyNotifications()
        runCurrent()
    }

    private fun TestScope.type(page: Page, text: String) = text.forEach { key ->
        page.vm.editor.edit {
            val at = selection.start
            replace(at, selection.end, key.toString())
            placeCursorBeforeCharAt(at + 1)
        }
        settle()
    }

    private suspend fun eventually(done: () -> Boolean) = withContext(Dispatchers.Default) {
        withTimeout(5_000) { while (!done()) delay(20) }
    }

    @Test
    fun theHeaderWritesTheFilesPathWithTheSeparatorOfTheSystemTheGameIsOn() = page { page ->
        assertEquals("game\\citadel\\cfg\\autoexec.cfg", page.ui.path, "the fake game is in D:\\Games\\Deadlock")
        assertEquals("game/citadel/cfg/autoexec.cfg", autoexecUi(page.container.autoexec.state.value, AutoexecChoices(), '/').path)
    }

    @Test
    fun listEditsShowAtOnceAndReachTheFile() = page { page ->
        val vm = page.vm
        val (fpsMax, showFps, sensitivity) = listOf("fps_max", "cl_showfps", "sensitivity").map { page.row(it).row.ref }
        vm.setValue(fpsMax, "300")
        vm.setOn(showFps, true)
        vm.remove(sensitivity)
        vm.setAdding("volume 0.5")
        vm.add()
        settle()
        assertEquals(listOf("fps_max" to "300", "cl_showfps" to "1", "volume" to "0.5"), page.ready.commands.map { it.row.ref.name to it.row.value })
        assertEquals(3 to 0, page.ready.on to page.ready.off)
        assertEquals("", page.ui.adding, "the Add field empties once added")
        eventually { page.writes() == 1 }
        assertEquals("$header\nfps_max 300\ncl_showfps 1\nvolume 0.5\n\n$section", page.file())
    }

    @Test
    fun keysPressedBeforeTheListRedrawsAllReachTheirRow() = page { page ->
        val drawn = page.row("fps_max").row.ref
        page.vm.setValue(drawn, "2400")
        page.vm.setValue(drawn, "24000")
        page.vm.setOn(drawn, false)
        settle()
        assertEquals("24000" to false, page.row("fps_max").row.let { it.value to it.on }, "each key lands on the row as it is now")
        eventually { page.writes() == 1 }
        assertEquals("$header\n// fps_max 24000\n// cl_showfps 1\nsensitivity 1.6\n\n$section", page.file())
    }

    @Test
    fun theAddFieldKeepsTextThatIsNotACommandAndSplitsTheRestOnItsFirstSpace() = page { page ->
        page.vm.setAdding("  ;quit")
        page.vm.add()
        settle()
        assertEquals("  ;quit", page.ui.adding)
        page.vm.setAdding("bind F5 \"kill\"")
        page.vm.add()
        settle()
        assertEquals("F5 \"kill\"", page.row("bind").row.value)
    }

    @Test
    fun theLibrarysPlusAddsTheSuggestedValueAndItsCheckTakesEveryLineOfItOut() = page(autoexec = "$header\nfps_max 240\n// cl_showfps 1\nfps_max 120\n") { page ->
        assertEquals(true, page.item("cl_showfps").added, "a line turned off still counts as in the file")
        assertEquals(false, page.item("volume").added)
        page.vm.toggle(page.item("volume"))
        settle()
        assertEquals("0.5", page.row("volume").row.value)
        assertEquals(true, page.item("volume").added)

        page.vm.toggle(page.item("fps_max"))
        settle()
        assertEquals(listOf("cl_showfps", "volume"), page.ready.commands.map { it.row.ref.name }, "the check takes out both fps_max lines")
        assertEquals(false, page.item("fps_max").added)
    }

    @Test
    fun theLibrarySearchesNamesAndDescriptionsAndFiltersByCategory() = page { page ->
        page.vm.search("BACKGROUND")
        settle()
        assertEquals(listOf("engine_no_focus_sleep", "snd_mute_losefocus"), page.ui.library.groups.flatMap { it.items }.map { it.command.name })
        page.vm.search("")
        page.vm.filter(CommandCategory.Audio)
        settle()
        assertEquals(listOf(CommandCategory.Audio), page.ui.library.groups.map { it.category })
        assertEquals(page.ui.library.total, page.ui.library.categories.sumOf { it.second }, "every chip counts its whole category, whatever is shown")
    }

    @Test
    fun aTextEditShowsInTheListAndAListEditShowsInTheText() = page { page ->
        assertEquals(fixture.removeSuffix("\n"), page.text)
        page.vm.editor.setTextAndPlaceCursorAtEnd(page.text.replace("fps_max 240", "fps_max 300\nvolume 0.5"))
        settle()
        assertEquals(listOf("fps_max" to "300", "volume" to "0.5", "cl_showfps" to "1", "sensitivity" to "1.6"), page.ready.commands.map { it.row.ref.name to it.row.value }, "rows in file order")

        page.vm.setOn(page.row("fps_max").row.ref, false)
        settle()
        assertEquals("$header\n// fps_max 300\nvolume 0.5\n// cl_showfps 1", page.text.lines().take(4).joinToString("\n"))
        eventually { page.writes() == 1 }
        assertEquals("$header\n// fps_max 300\nvolume 0.5\n// cl_showfps 1\nsensitivity 1.6\n\n$section", page.file())
    }

    @Test
    fun typingThatTheFileDoesNotTakeGoesBackToTheFile() = page { page ->
        page.vm.editor.setTextAndPlaceCursorAtEnd("fps_max 999\n" + page.text)
        settle()
        assertEquals(fixture.removeSuffix("\n"), page.text, "nothing goes above the header")
        page.vm.editor.setTextAndPlaceCursorAtEnd(page.text.replace("volume 1", "volume 0"))
        settle()
        assertEquals(fixture.removeSuffix("\n"), page.text, "another manager's section is not Hideout's to change")
    }

    @Test
    fun theFirstSaveKeepsTheCursorAfterWhatWasTyped() = page(autoexec = null) { page ->
        page.vm.editor.setTextAndPlaceCursorAtEnd("fps_max 240")
        settle()
        eventually { page.file() != null }
        settle()
        assertEquals("$header\nfps_max 240", page.text, "the save puts the header first")
        assertEquals(page.text.length, page.vm.editor.selection.start, "the cursor stays after what was typed")
        page.vm.editor.edit { insert(selection.start, "0") }
        settle()
        assertEquals("$header\nfps_max 2400", page.text, "typing goes on where it was")
    }

    @Test
    fun keysTypedOneAfterAnotherInTheTextViewLandInOrder() = page(autoexec = "$header\nfps_max 240\n") { page ->
        page.vm.editor.edit { placeCursorBeforeCharAt(length) }
        type(page, "123")
        assertEquals("$header\nfps_max 240123", page.text, "each key lands after the one before")
        assertEquals(page.text.length, page.vm.editor.selection.start)
    }

    @Test
    fun undoTakesBackWhatWasTyped() = page { page ->
        val read = page.text
        page.vm.editor.edit { placeCursorBeforeCharAt(header.length + "\nfps_max 240".length) }
        type(page, "0")
        assertEquals(read.replace("fps_max 240", "fps_max 2400"), page.text)
        page.vm.editor.undoState.undo()
        settle()
        assertEquals(read, page.text, "undo takes the key back")
        page.vm.editor.undoState.undo()
        settle()
        assertEquals(read, page.text, "and nothing before it, the file as read")
    }

    @Test
    fun aChangeOnDiskShowsInBothViews() = page { page ->
        page.files.writeConfig(page.root, InMemoryGameFiles.Autoexec, "$header\nfps_max 120\n".encodeToByteArray())
        eventually { page.ready.commands.size == 1 }
        settle()
        assertEquals(listOf("fps_max" to "120"), page.ready.commands.map { it.row.ref.name to it.row.value })
        assertEquals("$header\nfps_max 120", page.text)
    }

    @Test
    fun anEditorOrAFolderTheDesktopCannotOpenIsSaidInTheNoteUntilTheNextEdit() = page { page ->
        page.opened.opens = false
        page.vm.openInEditor()
        eventually { page.ui.notOpened != null }
        assertEquals(AutoexecOpen.Editor, page.ui.notOpened)
        page.vm.showInFolder()
        eventually { page.ui.notOpened == AutoexecOpen.Folder }
        page.vm.setValue(page.row("fps_max").row.ref, "300")
        settle()
        assertNull(page.ui.notOpened, "an edit clears it, so the note says how the save went")
        page.vm.openInEditor()
        eventually { page.ui.notOpened == AutoexecOpen.Editor }
        page.vm.dismissNotOpened()
        settle()
        assertNull(page.ui.notOpened, "Dismiss puts it away")

        page.opened.opens = true
        page.vm.showInFolder()
        eventually { page.opened.shown.isNotEmpty() }
        assertNull(page.ui.notOpened)
    }

    @Test
    fun theSavedNoteSaysWhenItAppliesAndAFailedSaveCanBeTriedAgain() = page { page ->
        assertEquals(SavedNote.NextLaunch, page.ui.note)
        page.files.failing += FaultPoint.ReplaceConfig
        page.vm.setValue(page.row("fps_max").row.ref, "300")
        eventually { page.container.autoexec.state.value.note is SavedNote.Failed }
        settle()
        assertIs<LibraryProblem.AutoexecWriteFailed>(assertIs<SavedNote.Failed.File>(page.ui.note).problem)
        assertEquals("300", page.row("fps_max").row.value, "the edit stays shown")

        page.files.failing -= FaultPoint.ReplaceConfig
        page.vm.retry()
        val saved = "$header\nfps_max 300\n// cl_showfps 1\nsensitivity 1.6\n\n$section"
        eventually { page.file() == saved }
        settle()
        assertEquals(SavedNote.NextLaunch, page.ui.note)
    }

    @Test
    fun openInEditorSavesFirstAndAMissingFileBecomesTheHeader() = page(autoexec = null) { page ->
        page.vm.openInEditor()
        eventually { page.opened.edited.isNotEmpty() }
        assertEquals(listOf<String?>("$header\n"), page.opened.edited)

        page.vm.setAdding("fps_max 240")
        page.vm.add()
        page.vm.openInEditor()
        eventually { page.opened.edited.size == 2 }
        assertEquals("$header\nfps_max 240\n", page.opened.edited.last(), "the editor opens on the edit just made, not half a second later")
        page.vm.showInFolder()
        settle()
        assertEquals(listOf(ConfigTarget.Autoexec.path), page.opened.shown)
    }
}
