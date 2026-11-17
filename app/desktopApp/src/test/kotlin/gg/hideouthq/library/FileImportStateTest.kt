package gg.hideouthq.library

import gg.hideouthq.AppContainer
import gg.hideouthq.HideoutAppState
import gg.hideouthq.core.data.FilePicker
import gg.hideouthq.core.data.LocalFile
import gg.hideouthq.core.data.ProcessScan
import gg.hideouthq.core.data.fake.FakeProcesses
import gg.hideouthq.core.ui.FileImportPresentation
import gg.hideouthq.core.ui.FileImportProblem
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FileImportStateTest {
    @Test fun cancelledPickerDoesNotStartAnInstallOrKeepTheModalOpen() = runBlocking {
        var calls = 0
        val state = HideoutAppState(AppContainer.fake(filePicker = FilePicker { calls++; null }))
        assertTrue(state.container.modLibrary.state.value.canModify)
        state.fileImport.pick()
        assertEquals(FileImportPresentation.Picking, state.fileImport.presentation)
        state.fileImport.chooseFile("Add from file")
        assertEquals(1, calls)
        assertEquals(FileImportPresentation.Idle, state.fileImport.presentation)
        assertTrue(state.container.modLibrary.state.value.operations.isEmpty())
        assertFalse(state.modal.isOpen)
    }

    @Test fun anUnsupportedFileNeverReachesTheInstaller() {
        val state = HideoutAppState(AppContainer.fake())
        state.fileImport.drop(listOf(LocalFile("/input.exe", "input.exe", 100)))
        assertEquals(FileImportProblem.Unsupported, assertIs<FileImportPresentation.Feedback>(state.fileImport.presentation).problem)
        assertTrue(state.container.modLibrary.state.value.operations.isEmpty())
    }

    @Test fun eachSupportedFileOfADropInstallsOnItsOwnWhileADialogIsOpen() {
        val state = HideoutAppState(AppContainer.fake())
        state.modal.hold(this)
        state.fileImport.drop(listOf(LocalFile("/a.zip", "a.zip", 100), LocalFile("/b.zip", "b.zip", 100), LocalFile("/notes.txt", "notes.txt", 10)))
        assertEquals(FileImportPresentation.Idle, state.fileImport.presentation)
        assertEquals(2, state.container.modLibrary.state.value.operations.size)
    }

    @Test fun runningGameAllowsPickerAndDropWithoutAllowingLiveFileChanges() = runBlocking {
        var calls = 0
        val state = HideoutAppState(AppContainer.fake(
            processes = FakeProcesses(ProcessScan.Seen(game = true)),
            filePicker = FilePicker { calls++; null },
        ))
        assertFalse(state.container.modLibrary.state.value.canModify)
        assertTrue(state.container.modLibrary.state.value.canInstall)
        state.fileImport.pick()
        assertEquals(FileImportPresentation.Picking, state.fileImport.presentation)
        state.fileImport.chooseFile("Add from file")
        assertEquals(1, calls)
        assertEquals(FileImportPresentation.Idle, state.fileImport.presentation)
        state.fileImport.drop(listOf(LocalFile("/a.zip", "a.zip", 100)))
        assertEquals(FileImportPresentation.Idle, state.fileImport.presentation)
        val operation = state.container.modLibrary.state.value.operations.values.single()
        val failure = assertIs<gg.hideouthq.core.library.OperationStage.Failed>(operation.stage)
        assertIs<gg.hideouthq.core.library.LibraryProblem.LocalInputRefused>(failure.problem)
        assertFalse(state.container.modLibrary.state.value.canModify)
    }
}
