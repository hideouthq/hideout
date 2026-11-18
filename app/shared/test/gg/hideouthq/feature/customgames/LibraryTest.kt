package gg.hideouthq.feature.customgames

import gg.hideouthq.core.customgames.Artifact
import gg.hideouthq.core.customgames.Choices
import gg.hideouthq.core.customgames.CustomGamesFetch
import gg.hideouthq.core.customgames.DeadlineToken
import gg.hideouthq.core.customgames.EndReason
import gg.hideouthq.core.customgames.FetchProblem
import gg.hideouthq.core.customgames.FolderRead
import gg.hideouthq.core.customgames.Install
import gg.hideouthq.core.customgames.InstalledMode
import gg.hideouthq.core.customgames.LibraryEntry
import gg.hideouthq.core.customgames.ModeId
import gg.hideouthq.core.customgames.ModeKind
import gg.hideouthq.core.customgames.ModeList
import gg.hideouthq.core.customgames.ModeOrigin
import gg.hideouthq.core.customgames.Session
import gg.hideouthq.core.customgames.SettingValue
import gg.hideouthq.core.customgames.Sha256
import gg.hideouthq.core.customgames.Transfer
import gg.hideouthq.core.data.fake.FakeCustomGamesFeed
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class LibraryTest {
    private val bebop = FakeCustomGamesFeed.BebopWars
    private val arena = FakeCustomGamesFeed.SampleMode
    private val released = FakeCustomGamesFeed.Placeholder
    private val feed = released.copy(modes = listOf(bebop, arena))
    private val today = LocalDate.of(2026, 11, 6)

    @Test
    fun oneModeHidesTheFiltersSortAndSearch() {
        val view = libraryView(ModeList(feed = released), ModeFilter(), today)
        assertFalse(view.filtersShown)
        assertEquals(listOf("mode:bebop-wars"), view.shown.map { it.key })
    }

    @Test
    fun aTeamModeForAFixedEvenCountReadsAsTwoSides() {
        val teams = bebop.listing.copy(players = 10..10, settings = emptyList())
        assertEquals(PlayerCount.Sides(5), playerCount(teams))
        assertEquals(PlayerCount.Between(2..12), playerCount(teams.copy(players = 2..12)))
        assertEquals(PlayerCount.Between(2..6), playerCount(bebop.listing))
        assertEquals(PlayerCount.Exactly(10), playerCount(teams.copy(kind = ModeKind.FreeForAll)))
        assertEquals(PlayerCount.Exactly(9), playerCount(teams.copy(players = 9..9)))
    }

    @Test
    fun kindsComeFromTheModesWithCountsAndFilter() {
        val view = libraryView(ModeList(feed = feed), ModeFilter(kind = ModeKind.Teams), today)
        assertTrue(view.filtersShown)
        assertEquals(listOf(KindCount(null, 2), KindCount(ModeKind.Teams, 1)), view.kinds, "a mode with no kind lists under All only")
        assertEquals(listOf(bebop.id), view.shown.map { it.id })
    }

    @Test
    fun searchMatchesNameTaglineAndCreatorsIgnoringCase() {
        assertEquals(listOf(arena.id), libraryView(ModeList(feed = feed), ModeFilter(query = "last ONE"), today).shown.map { it.id })
        assertEquals(2, libraryView(ModeList(feed = feed), ModeFilter(query = "hideout team"), today).shown.size)
        assertEquals(0, libraryView(ModeList(feed = feed), ModeFilter(query = "zzz"), today).shown.size)
    }

    @Test
    fun newestSortsByTheVersionsDate() {
        val older = arena.copy(listing = arena.listing.copy(released = LocalDate.of(2026, 1, 1)))
        val newer = bebop.copy(listing = bebop.listing.copy(released = LocalDate.of(2026, 11, 2)))
        val view = libraryView(ModeList(feed = feed.copy(modes = listOf(older, newer))), ModeFilter(sort = ModeSort.Newest), today)
        assertEquals(listOf(bebop.id, arena.id), view.shown.map { it.id })
        assertTrue(view.shown.first().new)
        assertFalse(view.shown.last().new)
    }

    @Test
    fun aReadableFolderIsAModeAndOneThatIsntShowsOnlyInInstalled() {
        val folder = arena.copy(origin = ModeOrigin.Folder("/dev/arena"))
        val modes = ModeList(feed = released, folders = listOf(LibraryEntry.Folder("/dev/arena", FolderRead.Read(folder)), LibraryEntry.Folder("/dev/broken", FolderRead.BadListing)))
        val view = libraryView(modes, ModeFilter(), today)
        assertEquals(listOf("mode:bebop-wars", "folder:/dev/arena"), view.shown.map { it.key })
        assertTrue(view.shown.last().folder)
        assertEquals(2, view.installed.count)
    }

    @Test
    fun installedRowsKeepRetiredModesAndCountUpdatesAndBytes() {
        val newer = bebop.copy(version = "1.1.0", origin = ModeOrigin.Feed((bebop.origin as ModeOrigin.Feed).rules.copy(sha256 = arena.let { (it.origin as ModeOrigin.Feed).rules.sha256 }), null))
        val modes = ModeList(
            feed = FakeCustomGamesFeed.Placeholder.copy(modes = listOf(newer)),
            installed = mapOf(bebop.id to InstalledMode(bebop, 12_000_000, null), ModeId("gone") to InstalledMode(arena.copy(id = ModeId("gone")), 5_000_000, null)),
            failed = mapOf(bebop.id to FetchProblem.Connection),
        )
        val installed = libraryView(modes, ModeFilter(), today).installed
        assertEquals(listOf(true, false), installed.rows.map { it.inFeed })
        assertEquals(17_000_000, installed.bytes)
        assertEquals(1, installed.updates)
        assertEquals(FetchProblem.Connection, installed.rows.first().failed)
        assertEquals("1.1.0", assertIs<Install.Installed>(installed.rows.first().install).update?.version)
    }

    @Test
    fun theShelfTakesFourInFeedOrderAndCountsAll() {
        val many = (1..6).map { arena.copy(id = ModeId("m$it")) }
        val (cards, total) = shelf(ModeList(feed = feed.copy(modes = many)), today)
        assertEquals(listOf("m1", "m2", "m3", "m4"), cards.map { it.id.value })
        assertEquals(6, total)
    }

    @Test
    fun theHostDialogsCapacityFollowsThePickAndTheDownloadHidesOnceInstalled() {
        val teams = bebop.listing.settings.first { it.key.value == "teams" }
        val picked = Choices.defaultsOf(bebop.listing).with(teams, checkNotNull(SettingValue.parse("3v3")))
        val view = hostDialog(bebop, ModeList().downloadToHost(bebop), picked)
        assertEquals(6, view.capacity)
        assertEquals(12_048_000, view.download)
        assertEquals(listOf("3v3"), view.settings.map { it.picked?.value })
        val installed = hostDialog(bebop, ModeList(installed = mapOf(bebop.id to InstalledMode(bebop, 1, null))).downloadToHost(bebop), picked)
        assertNull(installed.download)
        assertEquals(4, hostDialog(bebop, null, Choices.defaultsOf(bebop.listing)).capacity)
    }

    @Test
    fun everyHostButtonHostsTheListedVersionAndCountsItsDownload() {
        val older = bebop.copy(version = "0.9.0", origin = ModeOrigin.Feed(Artifact("https://example.test/old-rules.zip", 40_000, digest("d")), Artifact("https://example.test/old-client.zip", 11_000_000, digest("e"))))
        val modes = ModeList(feed = feed, installed = mapOf(bebop.id to InstalledMode(older, 11_040_000, null)))
        val page = assertNotNull(modeCard(modes, "mode:bebop-wars", today)).mode
        assertEquals(bebop, page, "the version a friend's list names")
        assertEquals(page, libraryView(modes, ModeFilter(), today).installed.rows.single().mode, "the Installed tab hosts the same one")
        val dialog = hostDialog(page, modes.downloadToHost(page), Choices.defaultsOf(page.listing))
        assertEquals(12_048_000, dialog.download, "its packages aren't here yet")

        val offline = ModeList(installed = modes.installed)
        val kept = libraryView(offline, ModeFilter(), today).installed.rows.single().mode
        assertEquals(older, kept, "with no list read, Host hosts what is installed")
        assertEquals(kept, assertNotNull(modeCard(offline, "mode:bebop-wars", today)).mode)
        assertNull(offline.downloadToHost(kept))
    }

    private fun digest(char: String) = checkNotNull(Sha256.parse(char.repeat(64)))

    @Test
    fun hostIsBlockedOnlyWhileCleaningUpOrWhileAGameRuns() {
        assertEquals(HostBlock.CleaningUp, hostBlock(Session.Recovering))
        assertEquals(HostBlock.GameRunning, hostBlock(Session.HostPreparing(DeadlineToken(1))))
        assertNull(hostBlock(Session.Idle))
        assertNull(hostBlock(Session.Ended(EndReason.PlayerStopped)))
    }

    @Test
    fun upkeepWaitsForTheSessionAndForARunningUpdateButNotForDeadlock() {
        val idle = ModeList(feed = feed)
        assertEquals(UpkeepBlock.CleaningUp, upkeepBlock(HostBlock.CleaningUp, idle))
        assertEquals(UpkeepBlock.GameRunning, upkeepBlock(HostBlock.GameRunning, idle.copy(updating = bebop.id to null)))
        assertEquals(UpkeepBlock.Updating, upkeepBlock(null, idle.copy(updating = bebop.id to null)))
        assertNull(upkeepBlock(null, idle))
    }

    @Test
    fun aCardCarriesOneBadgeTheMostPressing() {
        val record = InstalledMode(bebop, 1, null)
        assertEquals(ModeBadge.Update, ModeCard("k", bebop, Install.Installed(record, arena), new = true, folder = false).badge)
        assertEquals(ModeBadge.Installed, ModeCard("k", bebop, Install.Updating(null), new = true, folder = false).badge, "a mode that is here is no longer news")
        assertEquals(ModeBadge.New, ModeCard("k", bebop, Install.NotInstalled, new = true, folder = false).badge)
        assertNull(ModeCard("k", bebop, Install.NotInstalled, new = false, folder = false).badge)
        assertEquals(ModeBadge.Folder, ModeCard("k", bebop, Install.NotInstalled, new = false, folder = true).badge)
    }

    @Test
    fun anInstalledRowsStatusPutsARunningUpdateFirstThenAFailedOneThenADroppedListing() {
        val record = InstalledMode(bebop, 1, null)
        fun row(install: Install, inFeed: Boolean = true, failed: FetchProblem? = null) = InstalledRow("k", bebop, record, install, inFeed, failed)
        assertEquals(InstalledStatus.UpToDate, row(Install.Installed(record, null)).status)
        assertEquals(InstalledStatus.UpdateTo("1.1.0"), row(Install.Installed(record, bebop.copy(version = "1.1.0"))).status)
        assertEquals(InstalledStatus.Updating(Transfer(1, 2)), row(Install.Updating(Transfer(1, 2)), failed = FetchProblem.Server).status)
        assertEquals(InstalledStatus.UpdateFailed, row(Install.Installed(record, bebop), failed = FetchProblem.Server).status)
        assertEquals(InstalledStatus.NotListed, row(Install.Installed(record, null), inFeed = false).status)
    }

    @Test
    fun daysSincePlayedCountCalendarDaysInTheZone() {
        val zone = ZoneId.of("Europe/London")
        val lateYesterday = Instant.parse("2026-11-05T23:30:00Z")
        assertEquals(1, playedDaysAgo(lateYesterday, today, zone))
        assertEquals(0, playedDaysAgo(lateYesterday, today, ZoneId.of("Asia/Tokyo")), "already the 6th in Tokyo")
        assertNull(playedDaysAgo(null, today, zone))
    }

    @Test
    fun browseWaitsForTheFirstAnswerThenSaysWhyNothingShows() {
        fun body(modes: ModeList, filter: ModeFilter = ModeFilter()) = browseBody(libraryView(modes, filter, today), modes)
        assertEquals(BrowseBody.Loading, body(ModeList()), "not asked yet")
        assertEquals(BrowseBody.Loading, body(ModeList(refreshing = true)))
        assertEquals(BrowseBody.NoModes, body(ModeList(problem = CustomGamesFetch.Unreachable("offline"))))
        assertEquals(BrowseBody.NoModes, body(ModeList(noFeed = true)))
        assertEquals(BrowseBody.NoMatch, body(ModeList(feed = feed), ModeFilter(query = "zzz")))
        assertEquals(BrowseBody.Cards, body(ModeList(feed = feed, refreshing = true)))
    }
}
