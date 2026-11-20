package gg.hideouthq.core.library

import kotlin.test.Test
import kotlin.test.assertEquals

class LibraryEventsTest {
    private val root = GameRoot("/games/Deadlock")
    private val lash = record("lash", "Lash Gold", 101, number = 1)
    private val haze = record("haze", "Haze Hood", 102, number = null)
    private val main = Profile(ProfileId("main"), "Main", ProfileColour.Sage, setOf("lash"), mapOf(root to listOf("lash", "haze")))
    private val ranked = Profile(ProfileId("ranked"), "Ranked", ProfileColour.Blue, setOf("lash", "haze"), mapOf(root to listOf("haze", "lash")))
    private val book = ProfileBook(main.id, listOf(main, ranked))
    private val ready = LibraryState(
        game = GameStatus.Ready(root, FoundVia.Steam),
        library = LibraryStatus.Ready(listOf(lash)),
        loading = ModLoading.Loads(LoadingOwner.Hideout),
        deadlock = GameActivity.Closed,
        profiles = book.copy(profiles = listOf(main.copy(orders = mapOf(root to listOf("lash"))), ranked.copy(enabled = setOf("lash"), orders = mapOf(root to listOf("lash"))))),
    )

    @Test
    fun startingUpLogsTheGameDeadlockModLoadingAndTheInstallList() {
        assertEquals(
            listOf(
                "Found Deadlock at /games/Deadlock, found through Steam",
                "Deadlock: closed",
                "Mod loading: on, through Hideout's lines in gameinfo.gi",
                "Read the install list: 1 mod, 1 on",
            ),
            libraryEvents(LibraryState(), ready),
        )
    }

    @Test
    fun anInstallLogsTheGameBananaFileItCameFromAndAnUninstallLogsTheMod() {
        val installed = ready.copy(library = LibraryStatus.Ready(listOf(haze, lash)), profiles = book)

        assertEquals(listOf("Installed Haze Hood (mod-102): GameBanana file 502 (version 1 of 2)"), libraryEvents(ready, installed))
        assertEquals(listOf("Uninstalled Haze Hood (mod-102)"), libraryEvents(installed, ready))
    }

    @Test
    fun switchingProfileLogsTheSwitchAndTheModsItTurnedOnOnceTheirFilesMoved() {
        val before = ready.copy(library = LibraryStatus.Ready(listOf(haze, lash)), profiles = book)
        val switched = before.copy(profiles = book.copy(active = ranked.id))
        val moved = switched.copy(library = LibraryStatus.Ready(listOf(haze.copy(placement = Placement.On(Slot(1))), lash.copy(placement = Placement.On(Slot(2))))))

        assertEquals(listOf("Switched to profile Ranked"), libraryEvents(before, switched))
        assertEquals(listOf("Turned on: Haze Hood (mod-102)"), libraryEvents(switched, moved))
    }

    @Test
    fun turningModsOffAndOnAndReorderingThemEachLogOneLine() {
        val both = ready.copy(library = LibraryStatus.Ready(listOf(lash, haze.copy(placement = Placement.On(Slot(2))))))
        val lashOff = both.copy(library = LibraryStatus.Ready(listOf(lash.copy(placement = Placement.Off), haze.copy(placement = Placement.On(Slot(2))))))
        val swapped = both.copy(library = LibraryStatus.Ready(listOf(lash.copy(placement = Placement.On(Slot(2))), haze.copy(placement = Placement.On(Slot(1))))))

        assertEquals(listOf("Turned off: Lash Gold (mod-101)"), libraryEvents(both, lashOff))
        assertEquals(listOf("Turned on: Lash Gold (mod-101)"), libraryEvents(lashOff, both))
        assertEquals(listOf("Reordered: Haze Hood (mod-102), Lash Gold (mod-101)"), libraryEvents(both, swapped))
    }

    @Test
    fun aProblemIsLoggedWhenItIsFirstShownAndNotAgainWhileItStays() {
        val failed = ready.copy(operations = mapOf("mod-7" to Operation("mod-7", OperationStage.Failed(LibraryProblem.NoFiles), "Seven")))

        assertEquals(listOf("Installing mod-7 failed: NoFiles"), libraryLog(ready, failed))
        assertEquals(emptyList(), libraryLog(failed, failed.copy(deadlock = GameActivity.Closed)))
    }

    @Test
    fun losingTheGameAndDeadlockStartingAreLogged() {
        assertEquals(listOf("Deadlock: running"), libraryEvents(ready, ready.copy(deadlock = GameActivity.Running)))
        assertEquals(listOf("Lost Deadlock at /games/Deadlock"), libraryEvents(ready, ready.copy(game = GameStatus.Missing())))
    }

    private fun record(id: String, title: String, submission: Long, number: Int?) = oneSetRecord(
        id, root, InstalledSource.Remote(SubmissionIdentity(SubmissionKind.Mod, submission, submission + 400, null)), title, null,
        ArchiveIdentity("/downloads/$id.zip", 10, "a".repeat(64)), number, listOf(directory(FileImage(2048, "b".repeat(64)))),
        version = VersionPosition(0, 2), originalName = "$id.zip",
    )
}
