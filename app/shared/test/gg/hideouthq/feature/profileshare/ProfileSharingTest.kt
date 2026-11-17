package gg.hideouthq.feature.profileshare

import gg.hideouthq.AppContainer
import gg.hideouthq.core.data.FileListing
import gg.hideouthq.core.data.RemoteFile
import gg.hideouthq.core.data.fake.FakeConfig
import gg.hideouthq.core.data.fake.FakeDownloader
import gg.hideouthq.core.data.fake.FakeGameBanana
import gg.hideouthq.core.data.fake.FakeGameLauncher
import gg.hideouthq.core.data.fake.FakeProfileShares
import gg.hideouthq.core.data.fake.FakeVpk
import gg.hideouthq.core.data.fake.InMemoryLibraryStore
import gg.hideouthq.core.data.fake.LibrarySeed
import gg.hideouthq.core.library.InstallInput
import gg.hideouthq.core.library.InstallRequest
import gg.hideouthq.core.library.LibraryManifest
import gg.hideouthq.core.library.LibraryStatus
import gg.hideouthq.core.library.ManifestRecord
import gg.hideouthq.core.library.ProfileBook
import gg.hideouthq.core.library.SubmissionKind
import gg.hideouthq.core.library.SubmissionRef
import gg.hideouthq.core.sharing.ProfileLink
import gg.hideouthq.core.sharing.SharedMod
import gg.hideouthq.core.sharing.SharedProfile
import gg.hideouthq.core.sharing.longLink
import gg.hideouthq.feature.ViewModels
import gg.hideouthq.feature.shell.ShareLink
import gg.hideouthq.feature.shell.ShellViewModel
import gg.hideouthq.feature.shell.SnackbarMessage
import gg.hideouthq.feature.withViewModels
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileSharingTest {
    private val x = SubmissionRef(SubmissionKind.Mod, 700_001)
    private val y = SubmissionRef(SubmissionKind.Mod, 700_002)
    private val z = SubmissionRef(SubmissionKind.Mod, 700_003)
    private val files = listOf(x, y, z).associateWith { ref -> RemoteFile(ref.id + 1_000_000, "skin${ref.id}.zip", 1000, null, "Default", 20, 1, "https://gamebanana.com/dl/${ref.id}") }
    private val shares = FakeProfileShares()

    /** One person's Hideout, with GameBanana offering [x], [y] and [z]. */
    private inner class Hideout {
        lateinit var downloader: FakeDownloader
        val app = AppContainer.fake(
            FakeConfig(seed = LibrarySeed.Empty),
            libraryStore = InMemoryLibraryStore(LibraryManifest(records = emptyList())),
            gameBanana = FakeGameBanana(files.mapValues { (ref, file) -> FileListing.Available("Skin ${ref.id}", listOf(file)) }),
            downloader = { gameFiles ->
                FakeDownloader(gameFiles).also { downloader -> files.values.forEach { downloader.serve(it.id, listOf("skin.vpk" to FakeVpk.directory(seed = it.id.toInt()))) }; this.downloader = downloader }
            },
            profileShares = shares,
        )
        val library = app.modLibrary

        fun book(): ProfileBook = checkNotNull(library.state.value.shownProfiles)
        fun records(): List<ManifestRecord> = (library.state.value.library as LibraryStatus.Ready).records
        fun installId(ref: SubmissionRef): String = records().single { it.modId == ref.modId }.installId

        fun install(ref: SubmissionRef) = library.install(InstallRequest(InstallInput.Remote(ref), setOf(book().active)))
    }

    private fun TestScope.shell(viewModels: ViewModels, hideout: Hideout): ShellViewModel =
        viewModels.keep(ShellViewModel(hideout.library, FakeGameLauncher(), shares, hideout.app.profileImports)).also { vm ->
            backgroundScope.launch { vm.uiState.collect {} }
            runCurrent()
        }

    private fun TestScope.importer(viewModels: ViewModels, hideout: Hideout): ProfileImportViewModel =
        viewModels.keep(ProfileImportViewModel(hideout.library, hideout.app.catalog, shares, hideout.app.profileImports)).also { vm ->
            backgroundScope.launch { vm.uiState.collect {} }
            runCurrent()
        }

    private fun ProfileImportViewModel.sheet(): ImportSheet? = uiState.value.sheet

    @Test
    fun aFriendGetsTheSameModsInANewProfileInTheSameOrderWithoutSwitching() = withViewModels { viewModels ->
        val me = Hideout()
        runCurrent()
        listOf(x, y, z).forEach { me.install(it); runCurrent() }
        assertEquals(listOf(z, y, x).map(me::installId), me.book().activeProfile.orders.values.single(), "each install goes first")
        val myShell = shell(viewModels, me)
        myShell.shareProfile(me.book().active)
        runCurrent()
        val link = assertNotNull(myShell.uiState.value.shareLink)
        assertTrue(link.short && link.text.startsWith("https://hideouthq.gg/p/"), link.text)
        assertEquals(0, link.leftOut)
        myShell.linkCopied(link, copied = true)
        runCurrent()
        assertEquals(SnackbarMessage.Shared(link), myShell.uiState.value.snackbar?.message)
        assertNull(myShell.uiState.value.shareLink)

        val friend = Hideout()
        runCurrent()
        friend.install(x)
        runCurrent()
        val main = friend.book().active
        val held = CompletableDeferred<Unit>().also { friend.downloader.gates[files.getValue(y).id] = it }
        val importer = importer(viewModels, friend)
        friend.app.profileImports.open(link.text)
        runCurrent()
        val sheet = assertIs<ImportSheet.Reviewing>(importer.sheet())
        assertEquals("Main 2", sheet.name, "the friend already has a Main")
        assertEquals(listOf(z, y, x).map { it.modId }, sheet.mods.map { it.modId })
        assertEquals(listOf(false, false, true), sheet.mods.map { it.installed })

        importer.confirmImport()
        runCurrent()
        assertNull(importer.sheet(), "the sheet closes once the profile exists")
        val made = friend.book().profiles.single { it.name == "Main 2" }
        assertEquals(main, friend.book().active, "the friend stays on their own profile")
        assertEquals(setOf(friend.installId(x), friend.installId(z)), made.enabled, "y is still downloading")

        held.complete(Unit)
        runCurrent()
        val book = friend.book()
        val imported = book.profiles.single { it.name == "Main 2" }
        val order = listOf(z, y, x).map(friend::installId)
        assertEquals(order.toSet(), imported.enabled)
        assertEquals(order, imported.orders.values.single(), "it loads in the shared order, whichever install finished first")
        assertEquals(setOf(friend.installId(x)), book.activeProfile.enabled, "the new mods install into the new profile only")
    }

    @Test
    fun withoutTheSiteTheLinkCarriesTheWholeProfileAndStillImports() = withViewModels { viewModels ->
        val me = Hideout()
        runCurrent()
        me.install(x)
        runCurrent()
        shares.reachable = false
        val myShell = shell(viewModels, me)
        myShell.shareProfile(me.book().active)
        runCurrent()
        val link = assertNotNull(myShell.uiState.value.shareLink)
        val expected = SharedProfile("Main", listOf(SharedMod(x.id, setOf(files.getValue(x).id))))
        assertEquals(ShareLink(expected.longLink(shares.site), "Main", leftOut = 0, short = false), link, "the fake catalogue doesn't know x, so its file is pinned")
        assertEquals(ProfileLink.Whole(expected), ProfileLink.parse(link.text))

        val friend = Hideout()
        runCurrent()
        val importer = importer(viewModels, friend)
        friend.app.profileImports.open(link.text)
        runCurrent()
        assertIs<ImportSheet.Reviewing>(importer.sheet())
        importer.setImportName("From a friend")
        importer.confirmImport()
        runCurrent()
        assertEquals(setOf(friend.installId(x)), friend.book().profiles.single { it.name == "From a friend" }.enabled)
    }

    @Test
    fun aCodeTheSiteDoesntKnowOrCantAnswerKeepsTheFieldAndSaysWhy() = withViewModels { viewModels ->
        val friend = Hideout()
        runCurrent()
        val importer = importer(viewModels, friend)
        friend.app.profileImports.open()
        runCurrent()
        assertEquals(ImportSheet.Asking("", null, "hideouthq.gg"), importer.sheet())

        importer.setImportText("K7Q2-M9XD")
        importer.readImport()
        runCurrent()
        assertEquals(ImportSheet.Asking("K7Q2-M9XD", ImportProblem.Missing, "hideouthq.gg"), importer.sheet())

        shares.reachable = false
        importer.readImport()
        runCurrent()
        assertEquals(ImportProblem.Unreachable, (importer.sheet() as ImportSheet.Asking).problem)

        importer.setImportText("not a profile!")
        importer.readImport()
        runCurrent()
        assertEquals(ImportSheet.Asking("not a profile!", ImportProblem.Unreadable, "hideouthq.gg"), importer.sheet())

        importer.dismissImport()
        runCurrent()
        assertNull(importer.sheet())
        assertEquals(1, friend.book().profiles.size, "nothing was made")
    }

    @Test
    fun aProfileWithNothingFromGameBananaHasNothingToShare() = withViewModels { viewModels ->
        val me = Hideout()
        runCurrent()
        val myShell = shell(viewModels, me)
        myShell.shareProfile(me.book().active)
        runCurrent()
        assertNull(myShell.uiState.value.shareLink)
        assertEquals(SnackbarMessage.NothingToShare("Main"), myShell.uiState.value.snackbar?.message)
    }
}
