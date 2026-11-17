package gg.hideouthq.feature.shell

import gg.hideouthq.core.data.LibraryStore
import gg.hideouthq.core.data.ManifestSave
import gg.hideouthq.core.data.Preferences
import gg.hideouthq.core.data.fake.FakeDownloader
import gg.hideouthq.core.data.fake.FakeGameBanana
import gg.hideouthq.core.data.fake.FakeGameLauncher
import gg.hideouthq.core.data.fake.FakeGameLocator
import gg.hideouthq.core.data.fake.FakeProcesses
import gg.hideouthq.core.data.fake.InMemoryGameFiles
import gg.hideouthq.core.data.fake.InMemoryLibraryStore
import gg.hideouthq.core.data.fake.MemoryStorage
import gg.hideouthq.core.library.GameRoot
import gg.hideouthq.core.library.InstallPorts
import gg.hideouthq.core.library.LibraryManifest
import gg.hideouthq.core.library.Undoable
import gg.hideouthq.core.library.LibraryProblem
import gg.hideouthq.core.library.ModLibrary
import gg.hideouthq.core.library.MutationResult
import gg.hideouthq.core.library.NewProfile
import gg.hideouthq.core.library.ProfileColour
import gg.hideouthq.core.library.ProfileId
import gg.hideouthq.core.library.ProfileSeed
import gg.hideouthq.core.sharing.ProfileImports
import gg.hideouthq.feature.withViewModels
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileShellTest {
    private class HeldStore(private val store: InMemoryLibraryStore = InMemoryLibraryStore()) : LibraryStore by store {
        var held: CompletableDeferred<Unit>? = null
        var saves = 0
        override suspend fun save(manifest: LibraryManifest): ManifestSave {
            held?.await()
            saves++
            return store.save(manifest)
        }
    }

    private val disk = HeldStore()
    private val imports = ProfileImports()

    private fun shell(test: suspend TestScope.(ShellViewModel, ModLibrary) -> Unit) = withViewModels { viewModels ->
        val root = GameRoot("/games/Deadlock")
        val files = InMemoryGameFiles().apply {
            putBytes(root, InMemoryGameFiles.GameInfo, "GameInfo { FileSystem { SearchPaths { Game citadel/addons Game citadel } } }".encodeToByteArray())
        }
        val library = ModLibrary(FakeGameLocator(listOf(root.path), mapOf(root.path to null)), Preferences(MemoryStorage()), disk, files, InstallPorts(FakeGameBanana(), FakeDownloader(files), files, files, FakeProcesses()), backgroundScope).apply { start() }
        val vm = viewModels.keep(ShellViewModel(library, FakeGameLauncher(), imports = imports))
        backgroundScope.launch { vm.uiState.collect {} }
        runCurrent()
        test(vm, library)
    }

    @Test
    fun importingLeavesTheNewProfileDraftWithoutCreatingIt() = shell { vm, library ->
        val opened = mutableListOf<String?>()
        backgroundScope.launch { imports.incoming.collect { opened += it } }
        vm.newProfile()
        vm.setProfileName("Abandoned")
        vm.importProfile()
        runCurrent()
        assertNull(vm.uiState.value.profileEditor)
        assertEquals(listOf<String?>(null), opened)
        assertEquals(listOf("Main"), library.state.value.profiles!!.profiles.map { it.name })
        vm.newProfile()
        runCurrent()
        assertEquals("", assertIs<ProfileEditor.New>(vm.uiState.value.profileEditor).name)
    }

    @Test
    fun importingCannotReplaceAProfileWhileItIsBeingCreated() = shell { vm, library ->
        val opened = mutableListOf<String?>()
        backgroundScope.launch { imports.incoming.collect { opened += it } }
        disk.held = CompletableDeferred()
        vm.newProfile()
        vm.setProfileName("Practice")
        vm.confirmProfile()
        runCurrent()
        assertTrue(vm.uiState.value.profileSubmitting)
        vm.importProfile()
        runCurrent()
        assertTrue(opened.isEmpty())
        assertEquals("Practice", assertIs<ProfileEditor.New>(vm.uiState.value.profileEditor).name)
        disk.held!!.complete(Unit)
        runCurrent()
        assertEquals("Practice", library.state.value.profiles!!.activeProfile.name)
    }

    @Test
    fun createDraftDefaultsToCopyAndActivateAndSavesChosenColour() = shell { vm, library ->
        vm.newProfile()
        runCurrent()
        val draft = assertIs<ProfileEditor.New>(vm.uiState.value.profileEditor)
        assertEquals(library.state.value.profiles!!.active, draft.copyFrom)
        assertTrue(draft.activate)
        assertFalse(validProfileName(" ", vm.uiState.value))
        assertFalse(validProfileName(" main ", vm.uiState.value))
        vm.setProfileName("Tournament")
        vm.setProfileColour(ProfileColour.Sand)
        vm.setProfileSeed(null)
        vm.confirmProfile()
        runCurrent()
        val active = library.state.value.profiles!!.activeProfile
        assertEquals("Tournament", active.name)
        assertEquals(ProfileColour.Sand, active.colour)
        assertEquals(emptySet(), active.enabled)
        assertNull(vm.uiState.value.profileEditor)
    }

    @Test
    fun newProfilesAndDuplicatesStartInTheFirstColourNoProfileUses() = shell { vm, library ->
        vm.newProfile()
        runCurrent()
        assertEquals(ProfileColour.Blue, assertIs<ProfileEditor.New>(vm.uiState.value.profileEditor).colour, "Main is Sage")
        vm.setProfileName("Ranked")
        vm.confirmProfile()
        runCurrent()
        vm.duplicateProfile(library.state.value.profiles!!.active)
        runCurrent()
        assertEquals(listOf(ProfileColour.Sage, ProfileColour.Blue, ProfileColour.Clay), library.state.value.profiles!!.profiles.map { it.colour })
        for (colour in listOf(ProfileColour.Mauve, ProfileColour.Sand, ProfileColour.Rose)) {
            assertIs<MutationResult.Done<ProfileId>>(library.createProfile(NewProfile(colour.name, colour, ProfileSeed.Empty, activate = false)))
        }
        vm.newProfile()
        runCurrent()
        assertEquals(ProfileColour.Sage, assertIs<ProfileEditor.New>(vm.uiState.value.profileEditor).colour, "every colour is taken, so the first")
    }

    @Test
    fun aSwitchShowsNoNoticeAndDeleteAsksNothingAndOffersUndo() = shell { vm, library ->
        val main = library.state.value.profiles!!.active
        vm.newProfile()
        vm.setProfileName("Ranked")
        vm.setActivateProfile(false)
        vm.confirmProfile()
        runCurrent()
        val ranked = library.state.value.profiles!!.profiles.single { it.name == "Ranked" }
        vm.switchProfile(ranked.id)
        runCurrent()
        assertEquals(ranked.id, library.state.value.profiles!!.active)
        assertNull(vm.uiState.value.snackbar, "the sidebar shows the switch; the profile left is one click away")
        vm.switchProfile(main)
        runCurrent()
        assertEquals(main, library.state.value.profiles!!.active)
        vm.deleteProfile(ranked.id)
        runCurrent()
        assertNull(vm.uiState.value.profileEditor)
        assertEquals(SnackbarMessage.Edit(Undoable.Deleted("Ranked", switchedTo = null)), vm.uiState.value.snackbar?.message)
        assertEquals(1, library.state.value.profiles!!.profiles.size)
        vm.undo()
        runCurrent()
        assertEquals(listOf("Main", "Ranked"), library.state.value.profiles!!.profiles.map { it.name })
        vm.switchProfile(ranked.id)
        runCurrent()
        vm.dismissSnackbar()
        runCurrent()
        assertNull(vm.uiState.value.snackbar)
        assertEquals(ranked.id, library.state.value.profiles!!.active)

        vm.deleteProfile(ranked.id)
        runCurrent()
        assertEquals(SnackbarMessage.Edit(Undoable.Deleted("Ranked", switchedTo = "Main")), vm.uiState.value.snackbar?.message)
        assertEquals(main, library.state.value.profiles!!.active)
        vm.undo()
        runCurrent()
        assertEquals(ranked.id, library.state.value.profiles!!.active)
        assertEquals(listOf("Main", "Ranked"), library.state.value.profiles!!.profiles.map { it.name })
    }

    @Test
    fun renameValidationExcludesItselfAndTheSnackbarGoesByItself() = shell { vm, library ->
        vm.duplicateProfile(library.state.value.profiles!!.active)
        runCurrent()
        val copy = library.state.value.profiles!!.profiles.last()
        vm.renameProfile(copy.id)
        runCurrent()
        assertTrue(validProfileName(copy.name, vm.uiState.value, copy.id))
        assertFalse(validProfileName("MAIN", vm.uiState.value, copy.id))
        vm.setProfileName("Ranked-safe")
        vm.confirmProfile()
        runCurrent()
        assertEquals("Ranked-safe", library.state.value.profiles!!.profiles.last().name)
        vm.deleteProfile(copy.id)
        runCurrent()
        assertNotNull(vm.uiState.value.snackbar)
        advanceTimeBy(SnackbarMillis + 1)
        assertNull(vm.uiState.value.snackbar)
    }

    @Test
    fun aDuplicateOpensInRenameAndLeavingTheFieldSavesANameTheLibraryTakes() = shell { vm, library ->
        val main = library.state.value.profiles!!.active
        vm.duplicateProfile(main)
        runCurrent()
        val copy = library.state.value.profiles!!.profiles.last()
        assertEquals(ProfileEditor.Rename(copy.id, "Main copy"), vm.uiState.value.profileEditor)

        vm.setProfileName("Ranked")
        vm.leaveRename(main)
        runCurrent()
        assertEquals(ProfileEditor.Rename(copy.id, "Ranked"), vm.uiState.value.profileEditor, "leaving another profile's field changes nothing")
        vm.leaveRename(copy.id)
        runCurrent()
        assertNull(vm.uiState.value.profileEditor)
        assertEquals(listOf("Main", "Ranked"), library.state.value.profiles!!.profiles.map { it.name })

        vm.renameProfile(copy.id)
        vm.setProfileName("MAIN")
        vm.leaveRename(copy.id)
        runCurrent()
        assertNull(vm.uiState.value.profileEditor, "a taken name is dropped")
        vm.renameProfile(copy.id)
        vm.setProfileName(" ")
        vm.leaveRename(copy.id)
        runCurrent()
        assertNull(vm.uiState.value.profileEditor, "a blank name is dropped")
        assertEquals(listOf("Main", "Ranked"), library.state.value.profiles!!.profiles.map { it.name })
    }

    @Test
    fun keepingTheNameOrSwitchingToTheActiveProfileSavesNothingSoTheUndoStays() = shell { vm, library ->
        val ranked = assertIs<MutationResult.Done<ProfileId>>(library.createProfile(NewProfile("Ranked", ProfileColour.Blue, ProfileSeed.Empty, activate = false))).value
        val spare = assertIs<MutationResult.Done<ProfileId>>(library.createProfile(NewProfile("Spare", ProfileColour.Rose, ProfileSeed.Empty, activate = false))).value
        vm.switchProfile(ranked)
        vm.deleteProfile(spare)
        runCurrent()
        assertNotNull(vm.uiState.value.snackbar?.undo)
        val saves = disk.saves
        vm.renameProfile(ranked)
        vm.confirmProfile()
        runCurrent()
        assertNull(vm.uiState.value.profileEditor)
        vm.switchProfile(ranked)
        runCurrent()
        assertEquals(saves, disk.saves, "nothing to save")
        assertNotNull(vm.uiState.value.snackbar?.undo, "the delete can still be undone")
    }

    @Test
    fun anotherEditSavingLeavesThisDialogsConfirmUsableAndItsOwnAnswerClosesIt() = shell { vm, library ->
        val main = library.state.value.profiles!!.active
        val held = CompletableDeferred<Unit>().also { disk.held = it }
        vm.recolourProfile(main, ProfileColour.Rose)
        vm.newProfile()
        vm.setProfileName("Ranked")
        runCurrent()
        assertTrue(vm.uiState.value.saving)
        assertFalse(vm.uiState.value.profileSubmitting, "another edit's save is not this dialog's")
        vm.confirmProfile()
        runCurrent()
        assertTrue(vm.uiState.value.profileSubmitting)

        held.complete(Unit)
        runCurrent()
        assertNull(vm.uiState.value.profileEditor)
        assertFalse(vm.uiState.value.profileSubmitting)
        assertEquals(listOf("Main", "Ranked"), library.state.value.profiles!!.profiles.map { it.name })
        assertEquals(ProfileColour.Rose, library.state.value.profiles!!.profiles.first().colour)
    }

    @Test
    fun aRefusedRequestStaysInItsOwnOpenDialogWithItsProblemUntilTheNameChanges() = shell { vm, library ->
        vm.newProfile()
        vm.setProfileName("main")
        vm.confirmProfile()
        runCurrent()
        assertIs<ProfileEditor.New>(vm.uiState.value.profileEditor)
        assertEquals(LibraryProblem.ProfileNameTaken, vm.uiState.value.profileEditorProblem)
        assertFalse(vm.uiState.value.profileSubmitting)
        vm.dismissProfile()

        val copy = assertIs<MutationResult.Done<ProfileId>>(library.duplicateProfile(library.state.value.profiles!!.active)).value
        vm.renameProfile(copy)
        vm.setProfileName("main")
        vm.confirmProfile()
        runCurrent()
        assertEquals(copy, assertIs<ProfileEditor.Rename>(vm.uiState.value.profileEditor).id)
        assertEquals(LibraryProblem.ProfileNameTaken, vm.uiState.value.profileEditorProblem, "the sidebar's inline Rename shows this")
        assertFalse(vm.uiState.value.profileSubmitting)

        vm.setProfileName("Ranked")
        runCurrent()
        assertNull(vm.uiState.value.profileEditorProblem)
    }
}
