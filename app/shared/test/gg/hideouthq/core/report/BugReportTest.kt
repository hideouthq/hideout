package gg.hideouthq.core.report

import gg.hideouthq.core.customgames.Choices
import gg.hideouthq.core.customgames.CustomGamesFetch
import gg.hideouthq.core.customgames.GameRecord
import gg.hideouthq.core.customgames.GuestPaths
import gg.hideouthq.core.customgames.InstalledMode
import gg.hideouthq.core.customgames.InviteCode
import gg.hideouthq.core.customgames.LiveSession
import gg.hideouthq.core.customgames.ModeList
import gg.hideouthq.core.customgames.OwnGame
import gg.hideouthq.core.customgames.Player
import gg.hideouthq.core.customgames.Role
import gg.hideouthq.core.customgames.Session
import gg.hideouthq.core.data.FileRead
import gg.hideouthq.core.data.FileStamp
import gg.hideouthq.core.data.FolderEntry
import gg.hideouthq.core.data.FolderListing
import gg.hideouthq.core.data.InstallDefaults
import gg.hideouthq.core.data.SettingsSnapshot
import gg.hideouthq.core.data.fake.FakeCustomGamesFeed
import gg.hideouthq.core.library.ArchiveIdentity
import gg.hideouthq.core.library.EntryKind
import gg.hideouthq.core.library.FileImage
import gg.hideouthq.core.library.FoundVia
import gg.hideouthq.core.library.GameActivity
import gg.hideouthq.core.library.GameRoot
import gg.hideouthq.core.library.GameStatus
import gg.hideouthq.core.library.InstalledSource
import gg.hideouthq.core.library.LibraryState
import gg.hideouthq.core.library.LibraryStatus
import gg.hideouthq.core.library.LoadingOwner
import gg.hideouthq.core.library.ModLoading
import gg.hideouthq.core.library.Placement
import gg.hideouthq.core.library.Profile
import gg.hideouthq.core.library.ProfileBook
import gg.hideouthq.core.library.ProfileColour
import gg.hideouthq.core.library.ProfileId
import gg.hideouthq.core.library.Slot
import gg.hideouthq.core.library.SubmissionIdentity
import gg.hideouthq.core.library.SubmissionKind
import gg.hideouthq.core.library.VersionPosition
import gg.hideouthq.core.library.directory
import gg.hideouthq.core.library.oneSetRecord
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Instant

class BugReportTest {
    private val root = GameRoot("/games/Deadlock")
    private val lash = record("lash", "Lash Gold", 101, number = 1)
    private val haze = record("haze", "Haze Hood", 102, number = null)
    private val main = Profile(ProfileId("main"), "Main", ProfileColour.Sage, setOf("lash"), mapOf(root to listOf("lash", "haze")))
    private val ranked = Profile(ProfileId("ranked"), "Ranked", ProfileColour.Blue, setOf("lash", "haze"), mapOf(root to listOf("haze", "lash")))
    private val book = ProfileBook(main.id, listOf(main, ranked))
    private val state = LibraryState(
        game = GameStatus.Ready(root, FoundVia.Steam),
        library = LibraryStatus.Ready(listOf(haze, lash)),
        loading = ModLoading.Loads(LoadingOwner.Hideout),
        deadlock = GameActivity.Closed,
        profiles = book,
        shownProfiles = book,
    )

    @Test
    fun theLibraryAndItsProfilesAreListedInTheActiveLoadOrderWithWhereEachModCameFrom() {
        val text = report(state, FolderListing.Listed(listOf(file("pak01_dir.vpk", 2048))))

        assertSection(
            text,
            """
            ## Game
            Folder: /games/Deadlock, found through Steam
            Deadlock: closed
            Mod loading: on, through Hideout's lines in gameinfo.gi
            Other mod managers open: unknown
            Free space: game folder 1.5 GB, Hideout's data unknown
            """,
        )
        assertSection(
            text,
            """
            ## Library
            Status: ready, 2 mods, 1 on
            Downloads: 0 kept, 0 Clear may delete
            - 1. Lash Gold (mod-101) [lash]: on, pak01
                GameBanana file 501 (version 1 of 2), part pak01_dir.vpk, 1 file, 2.0 KB
            - 2. Haze Hood (mod-102) [haze]: off
                GameBanana file 502 (version 1 of 2), part pak01_dir.vpk, 1 file, 2.0 KB
            """,
        )
        assertSection(
            text,
            """
            ## Profiles
            - Main (active): 1 of 2 on
                order in /games/Deadlock: Lash Gold (on), Haze Hood
            - Ranked: 2 of 2 on
                order in /games/Deadlock: Haze Hood (on), Lash Gold (on)
            """,
        )
        assertSection(
            text,
            """
            ## Settings
            Game folder: none chosen, Steam says where
            Ask which files to install: on
            Keep downloaded files: on
            Download folder: Hideout's own
            Repair skins automatically: on
            Download updates automatically: on
            Load 3D previews by default: on
            Hide mature mods: off
            Custom games: on
            Host mode folders: off
            Stats: on, Steam tracking off, sharing match IDs off
            Unfinished downloads: none
            """,
        )
        assertTrue(text.endsWith("## Log\n2026-11-16T10:00:00Z Hideout 1.2.3 started\n"), text)
    }

    @Test
    fun theGameSectionListsTheSearchPathsInTheOrderDeadlockReadsThemWithHideoutsMarked() {
        val searchPaths = listOf(
            "Game_LowViolence\tcitadel_lv", "// MODLOCK DEV BEGIN", "Game\t\t\tcitadel/modlock_dev/31bb65da30bd1ed1", "Mod\t\t\t\"citadel\"", "Write\t\t\t\"citadel\"",
            "// MODLOCK DEV END", "// Hideout start", "Game\t\t\t\tcitadel/hideout_games/menus", "Game\t\t\t\tcitadel/hideout_games/mode", "Game\t\t\t\tcitadel/addons",
            "Mod\t\t\t\tcitadel", "Write\t\t\t\tcitadel", "// Hideout end", "Game\t\t\t\tcitadel", "Game\t\t\t\tcore",
        )
        val info = "\"GameInfo\"\r\n{\r\n\tFileSystem\r\n\t{\r\n\t\tSearchPaths\r\n\t\t{\r\n${searchPaths.joinToString("") { "\t\t\t$it\r\n" }}\t\t}\r\n\t}\r\n}\r\n"

        assertSection(
            report(state, FolderListing.Listed(emptyList()), gameInfo = FileRead.Present(info.encodeToByteArray())),
            """
            ## Game
            Folder: /games/Deadlock, found through Steam
            Deadlock: closed
            Mod loading: on, through Hideout's lines in gameinfo.gi
            Search paths: citadel/modlock_dev/31bb65da30bd1ed1, citadel/hideout_games/menus (Hideout's), citadel/hideout_games/mode (Hideout's), citadel/addons (Hideout's), citadel
            Other mod managers open: unknown
            Free space: game folder 1.5 GB, Hideout's data unknown
            """,
        )
        assertTrue("Search paths: gameinfo.gi is missing\n" in report(state, FolderListing.Listed(emptyList()), gameInfo = FileRead.Absent))
        assertTrue("Search paths: gameinfo.gi could not be used: NoSearchPaths\n" in report(state, FolderListing.Listed(emptyList()), gameInfo = FileRead.Present("GameInfo\n{\n}\n".encodeToByteArray())))
    }

    @Test
    fun filesInAddonsHideoutDidNotPutThereAreCalledOutAndAModsMissingFileIsListed() {
        val both = state.copy(library = LibraryStatus.Ready(listOf(lash, haze.copy(placement = Placement.On(Slot(2))))))
        val listing = FolderListing.Listed(
            listOf(file("readme.txt", 10), file("pak05_dir.vpk", 5000), file("pak01_dir.vpk", 4096), file("pak01_dir.vpk.hideout-ab12", 2048)),
        )

        assertSection(
            report(both, listing),
            """
            ## Files in game/citadel/addons
            Summary: 4 entries, 2 Hideout's, 2 not, 1 missing
            - pak01_dir.vpk, 4.0 KB: Hideout's, Lash Gold (mod-101), but Hideout wrote 2.0 KB
            - pak01_dir.vpk.hideout-ab12, 2.0 KB: a temporary of a change Hideout is making
            - pak05_dir.vpk, 4.9 KB: NOT Hideout's, it loads all the same
            - readme.txt, 10 B: not Hideout's
            - pak02_dir.vpk: missing, of Haze Hood (mod-102)
            """,
        )
    }

    @Test
    fun customGamesShowTheSessionTheListAndTheInstalledModesWithoutTheInviteCode() {
        val code = InviteCode.parse("P95-MAN-26D")!!
        val mode = FakeCustomGamesFeed.BebopWars
        val hosting = Session.Hosting(code, "street", mapOf(0 to Player("nori", "7656"), 1 to Player("Ann", "7657")), OwnGame.In, GuestPaths(direct = 1))
        val record = GameRecord(Role.Host(mode, Choices.None), null, startedAt = Instant.parse("2026-11-16T10:01:00Z"))
        val modes = ModeList(
            feed = FakeCustomGamesFeed.Placeholder,
            problem = CustomGamesFetch.Unreachable("timed out"),
            installed = mapOf(mode.id to InstalledMode(mode.copy(version = "0.0.9", origin = FakeCustomGamesFeed.SampleMode.origin), 12_582_912, null)),
        )

        val text = report(state, FolderListing.Listed(emptyList()), CustomGamesFacts(LiveSession(hosting, record), modes))

        val aaaa = "a".repeat(12)
        assertSection(
            text,
            """
            ## Custom games
            Available: yes, turned on
            Session: hosting on street, 2 players, guests 1 direct and 0 through the relay; this computer's game is in the match
            Game: hosting Bebop Wars (bebop-wars 0.1.0, from the list), started 2026-11-16T10:01:00Z
            List: sequence 1, 1 mode, runtime Modlock linux $aaaa, windows $aaaa, host $aaaa, platform $aaaa, renderer $aaaa; the last read failed: unreachable, timed out
            - Installed: Bebop Wars (bebop-wars 0.0.9, from the list), 12.0 MB, last played never, version 0.1.0 available
            """,
        )
        assertTrue(listOf(code.value, code.shown).none { it in text }, text)
    }

    @Test
    fun aBuildWithoutCustomGamesSaysSoInOneLine() {
        assertSection(
            report(state, FolderListing.Listed(emptyList())),
            """
            ## Custom games
            Available: no, this build has no custom games helper
            """,
        )
    }

    private fun report(state: LibraryState, addons: FolderListing, customGames: CustomGamesFacts? = null, gameInfo: FileRead? = null) = bugReport(
        ReportFacts(
            savedAt = Instant.parse("2026-11-16T10:05:00Z"),
            machine = Machine("1.2.3", "appimage package, updates itself", "Linux 6.8 amd64 (Ubuntu 24.04)", "KDE, wayland", "en-GB", "Java 25, Substrate VM"),
            library = state,
            settings = SettingsSnapshot(null, InstallDefaults(), true, true, true, false, true, false, true, false, false, emptyList()),
            customGames = customGames,
            addons = addons,
            gameInfo = gameInfo,
            gameFreeBytes = 1_610_612_736,
            dataFreeBytes = null,
            log = "2026-11-16T10:00:00Z Hideout 1.2.3 started\n",
        ),
    )

    private fun assertSection(text: String, expected: String) {
        val section = expected.trimIndent() + "\n"
        assertTrue("\n$section\n" in text, "expected\n$section\nin\n$text")
    }

    private fun file(name: String, size: Long) = FolderEntry(name, EntryKind.File, FileStamp(size, 0))

    private fun record(id: String, title: String, submission: Long, number: Int?) = oneSetRecord(
        id, root, InstalledSource.Remote(SubmissionIdentity(SubmissionKind.Mod, submission, submission + 400, null)), title, null,
        ArchiveIdentity("/downloads/$id.zip", 10, "a".repeat(64)), number, listOf(directory(FileImage(2048, "b".repeat(64)))),
        version = VersionPosition(0, 2), originalName = "$id.zip",
    )
}
