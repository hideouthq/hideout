package gg.hideouthq.core.customgames

import gg.hideouthq.core.data.fake.FakeCustomGamesFeed
import kotlin.test.Test
import kotlin.test.assertEquals

class CustomGamesLibraryTest {
    private val arena = FakeCustomGamesFeed.SampleMode
    private val feed = FakeCustomGamesFeed.SampleModes
    private val retired = InstalledMode(arena.copy(id = ModeId("old-mode")), 7, null)
    private val folder = FakeCustomGamesFeed.SampleFolder

    @Test
    fun theFeedsModesComeFirstInItsOrderThenWhatOnlyThisComputerHasThenFolders() {
        val modes = ModeList(
            feed = feed,
            installed = mapOf(retired.mode.id to retired),
            folders = listOf(LibraryEntry.Folder("/built/arena", FolderRead.Read(folder)), LibraryEntry.Folder("/built/broken", FolderRead.BadListing)),
        )
        assertEquals(
            listOf(
                LibraryEntry.Listed(arena, Install.NotInstalled, inFeed = true),
                LibraryEntry.Listed(retired.mode, Install.Installed(retired, null), inFeed = false),
                LibraryEntry.Folder("/built/arena", FolderRead.Read(folder)),
                LibraryEntry.Folder("/built/broken", FolderRead.BadListing),
            ),
            library(modes),
        )
    }

    @Test
    fun anInstalledModeIsListedAndHostableWithNoFeedAtAll() {
        val installed = InstalledMode(arena, 5, null)
        assertEquals(listOf(LibraryEntry.Listed(arena, Install.Installed(installed, null), inFeed = false)), library(ModeList(installed = mapOf(arena.id to installed))))
    }

    @Test
    fun aModeInBothTheFeedAndTheRecordIsOneEntryOfferingTheUpdate() {
        val older = InstalledMode(arena.copy(version = "0.9.0", origin = ModeOrigin.Feed(Artifact("https://x/old.zip", 3, Sha256.parse("0".repeat(64))!!), null)), 3, null)
        val modes = ModeList(feed = feed, installed = mapOf(arena.id to older))
        assertEquals(listOf(LibraryEntry.Listed(arena, Install.Installed(older, arena), inFeed = true)), library(modes))
    }

    @Test
    fun aFolderWithTheSameIdAsAFeedModeIsItsOwnEntry() {
        val modes = ModeList(feed = feed, folders = listOf(LibraryEntry.Folder("/built/arena", FolderRead.Read(folder))))
        assertEquals(listOf("mode:arena", "folder:/built/arena"), library(modes).map { it.key })
    }
}
