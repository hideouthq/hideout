package gg.hideouthq.core.library

import gg.hideouthq.core.data.CacheStorage
import gg.hideouthq.core.data.FileObservation
import gg.hideouthq.core.data.GameInventory
import gg.hideouthq.core.data.GameLocator
import gg.hideouthq.core.data.InstallStatus
import gg.hideouthq.core.data.LibraryStore
import gg.hideouthq.core.data.ManifestLoad
import gg.hideouthq.core.data.ManifestSave
import gg.hideouthq.core.data.Preferences
import gg.hideouthq.core.data.fake.InMemoryGameFiles
import gg.hideouthq.core.data.fake.InMemoryLibraryStore
import gg.hideouthq.core.data.fake.ports
import gg.hideouthq.core.data.fake.MemoryStorage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class ModLibraryTest {
    private class Disk(var steam: List<String> = emptyList(), val folders: MutableMap<String, FolderProblem?> = mutableMapOf()) : GameLocator {
        override val separator = '/'

        override suspend fun detect(): List<GameRoot> = steam.map(::GameRoot)

        override suspend fun check(path: String): FolderCheck = when {
            !folders.containsKey(path) -> FolderCheck.Invalid(path, FolderProblem.Unavailable)
            else -> folders[path]?.let { FolderCheck.Invalid(path, it) } ?: FolderCheck.Valid(GameRoot(path))
        }
    }

    private val steamRoot = "/steam/steamapps/common/Deadlock"
    private val copy = "/games/Deadlock"

    private fun disk(steam: Boolean = true) = Disk(
        steam = if (steam) listOf(steamRoot) else emptyList(),
        folders = mutableMapOf(steamRoot to null, copy to null, "$copy/game" to FolderProblem.NotDeadlock),
    )

    private fun TestScope.library(
        disk: Disk,
        storage: CacheStorage = MemoryStorage(),
        store: LibraryStore = InMemoryLibraryStore(),
        inventory: GameInventory = InMemoryGameFiles(),
    ): Pair<ModLibrary, Preferences> {
        val preferences = Preferences(storage)
        return ModLibrary(disk, preferences, store, inventory, ((inventory as? InMemoryGameFiles) ?: InMemoryGameFiles()).ports(), backgroundScope) to preferences
    }

    private fun TestScope.started(library: ModLibrary): GameStatus {
        library.start()
        runCurrent()
        return library.state.value.game
    }

    @Test
    fun searchingUntilStartedThenSteamsFolder() = runTest {
        val (library, _) = library(disk())
        assertEquals(GameStatus.Searching, library.state.value.game)
        assertEquals(GameStatus.Ready(GameRoot(steamRoot), FoundVia.Steam), started(library))
    }

    @Test
    fun aRememberedChoiceWinsAtStartupAndCountsAsSteamsWhenSteamKnowsIt() = runTest {
        val (chosen, preferences) = library(disk())
        preferences.setGameFolder(copy)
        assertEquals(GameStatus.Ready(GameRoot(copy), FoundVia.Chosen), started(chosen))

        val (steams, remembered) = library(disk())
        remembered.setGameFolder(steamRoot)
        assertEquals(GameStatus.Ready(GameRoot(steamRoot), FoundVia.Steam), started(steams))
    }

    @Test
    fun aRememberedChoiceThatStoppedWorkingFallsBackToSteamOrIsReported() = runTest {
        val withSteam = library(disk())
        withSteam.second.setGameFolder("$copy/game")
        assertEquals(GameStatus.Ready(GameRoot(steamRoot), FoundVia.Steam), started(withSteam.first))

        val withoutSteam = library(disk(steam = false))
        withoutSteam.second.setGameFolder("$copy/game")
        assertEquals(GameStatus.Missing(FolderCheck.Invalid("$copy/game", FolderProblem.NotDeadlock)), started(withoutSteam.first))
    }

    @Test
    fun nothingFoundAnywhereIsMissing() = runTest {
        val (library, _) = library(disk(steam = false))
        assertEquals(GameStatus.Missing(), started(library))
    }

    @Test
    fun choosingADeadlockFolderUsesAndRemembersIt() = runTest {
        val (library, preferences) = library(disk(steam = false))
        started(library)
        assertEquals(FolderCheck.Valid(GameRoot(copy)), library.useGameFolder(copy))
        assertEquals(GameStatus.Ready(GameRoot(copy), FoundVia.Chosen), library.state.value.game)
        assertEquals(copy, preferences.gameFolder())
    }

    @Test
    fun choosingTheWrongFolderChangesNothing() = runTest {
        val (library, preferences) = library(disk())
        val before = started(library)
        assertEquals(FolderCheck.Invalid("$copy/game", FolderProblem.NotDeadlock), library.useGameFolder("$copy/game"))
        assertEquals(before, library.state.value.game)
        assertNull(preferences.gameFolder())
    }

    @Test
    fun choosingAFolderSteamKnowsRemembersItAsSteamsThroughRestartAndMissingSteamMetadata() = runTest {
        val disk = disk().apply { steam = listOf(steamRoot, copy) }
        val storage = MemoryStorage()
        val (first, preferences) = library(disk, storage)
        preferences.setGameFolder(steamRoot)
        started(first)
        first.useGameFolder(copy)
        assertEquals(GameStatus.Ready(GameRoot(copy), FoundVia.Steam), first.state.value.game)
        assertEquals(copy, preferences.gameFolder())
        assertEquals(GameStatus.Ready(GameRoot(copy), FoundVia.Steam), started(library(disk, storage).first))

        disk.steam = emptyList()
        assertEquals(GameStatus.Ready(GameRoot(copy), FoundVia.Chosen), started(library(disk, storage).first))
    }

    @Test
    fun detectingAgainPrefersSteamButKeepsAWorkingChoiceWhenSteamHasNone() = runTest {
        val disk = disk(steam = false)
        val (library, preferences) = library(disk)
        preferences.setGameFolder(copy)
        started(library)
        assertEquals(GameStatus.Ready(GameRoot(copy), FoundVia.Chosen), library.detectGame())
        assertEquals(copy, preferences.gameFolder())

        disk.steam = listOf(steamRoot)
        assertEquals(GameStatus.Ready(GameRoot(steamRoot), FoundVia.Steam), library.detectGame())
        assertNull(preferences.gameFolder())
    }

    @Test
    fun launchingChecksTheFolderAgain() = runTest {
        val disk = disk(steam = false)
        val files = InMemoryGameFiles().apply { putBytes(GameRoot(copy), InMemoryGameFiles.GameInfo, LoadsAddonsGameInfo) }
        val (library, _) = library(disk, inventory = files)
        started(library)
        library.useGameFolder(copy)
        var launched: GameRoot? = null
        assertNull(library.launch(modded = true) { launched = it })
        assertEquals(GameRoot(copy), launched)

        disk.folders.remove(copy)
        assertEquals(LibraryProblem.GameNotFound, library.launch(modded = true) {})
        assertEquals(GameStatus.Missing(FolderCheck.Invalid(copy, FolderProblem.Unavailable)), library.state.value.game)
    }

    private val root = GameRoot(copy)

    private fun record(installId: String = "install-1", number: Int? = 5, files: List<SetFile> = listOf(file())) = oneSetRecord(
        installId = installId,
        gameRoot = root,
        source = InstalledSource.Remote(SubmissionIdentity(SubmissionKind.Mod, 721_908, 1_829_854, "0332f45e5015186cb47219853f499a25")),
        title = "Nicole over Pocket",
        variant = "Default",
        archive = ArchiveIdentity("/downloads/nicole.zip", 1_000L, "d".repeat(64)),
        number = number,
        files = files,
    )

    private fun file(sizeBytes: Long = 1_000L, sha256: String = "e".repeat(64)) = directory(FileImage(sizeBytes, sha256))

    private fun TestScope.libraryStatus(library: ModLibrary): LibraryStatus {
        library.start()
        runCurrent()
        return library.state.value.library.unlisted()
    }

    private fun LibraryStatus.unlisted(): LibraryStatus = (this as? LibraryStatus.Ready)?.copy(loading = null) ?: this

    @Test
    fun aFreshManifestReconcilesToAnEmptyReadyLibrary() = runTest {
        val (library, _) = library(disk())
        assertEquals(LibraryStatus.Loading, library.state.value.library)
        assertEquals(LibraryStatus.Ready(emptyList()), libraryStatus(library))
    }

    private fun TestScope.statusOver(owned: ManifestRecord, files: InMemoryGameFiles.() -> Unit = {}): LibraryStatus =
        libraryStatus(library(disk(), store = InMemoryLibraryStore(LibraryManifest(records = listOf(owned))), inventory = InMemoryGameFiles().apply(files)).first)

    private fun InMemoryGameFiles.intact(owned: ManifestRecord, index: Int = 0) = putFile(root, owned.files[index].relativePath(owned), owned.files[index].sizeBytes, owned.files[index].sha256)

    @Test
    fun ownedFilesThatMatchOnDiskNeedNoRepair() = runTest {
        val owned = record()
        assertEquals(LibraryStatus.Ready(listOf(owned)), statusOver(owned) { intact(owned) })
    }

    @Test
    fun aMissingOwnedFileIsMissing() = runTest {
        val owned = record()
        assertEquals(LibraryStatus.Ready(listOf(owned), damaged = mapOf(owned.installId to FilesDamage.Missing)), statusOver(owned))
    }

    @Test
    fun anOwnedFileWithTheWrongHashHasChangedEvenWithTheRightSize() = runTest {
        val owned = record()
        val status = statusOver(owned) { putFile(root, owned.files[0].relativePath(owned), owned.files[0].sizeBytes, "f".repeat(64)) }
        assertEquals(mapOf(owned.installId to FilesDamage.Changed), (status as LibraryStatus.Ready).damaged)
    }

    @Test
    fun aChunkMissingBesideAChangedDirectoryIsPartOfTheEditAsAVpkRebuiltWithFewerChunks() = runTest {
        val owned = record(files = listOf(file(), SetFile(VpkPart.Chunk(0), FileImage(1_000L, "e".repeat(64)))))
        val status = statusOver(owned) { putFile(root, owned.files[0].relativePath(owned), owned.files[0].sizeBytes, "f".repeat(64)) }
        assertEquals(mapOf(owned.installId to FilesDamage.Changed), (status as LibraryStatus.Ready).damaged)
    }

    @Test
    fun anOffRecordsFilesAreCheckedUnderHideoutOffNotAddons() = runTest {
        val owned = record(number = null, files = listOf(file()))
        assertEquals(LibraryStatus.Ready(listOf(owned)), statusOver(owned) { intact(owned) })
    }

    @Test
    fun anUnreadableManifestIsBlockedNotAnEmptyWritableLibrary() = runTest {
        val store = InMemoryLibraryStore(forceUnreadable = "schema version 7 is not supported")
        val (library, _) = library(disk(), store = store)
        assertEquals(LibraryStatus.Blocked("schema version 7 is not supported"), libraryStatus(library))
    }

    private class ThrowingInventory : GameInventory by InMemoryGameFiles() {
        override suspend fun file(root: GameRoot, relativePath: String): FileObservation = throw IllegalStateException("file exploded")
    }

    private class ThrowingStore : LibraryStore {
        override suspend fun load(): ManifestLoad = throw IllegalStateException("store exploded")
        override suspend fun save(manifest: LibraryManifest): ManifestSave = ManifestSave.Refused("not used")
    }

    @Test
    fun anInventoryThatThrowsBlocksTheLibraryInsteadOfLeavingItLoading() = runTest {
        val store = InMemoryLibraryStore(LibraryManifest(records = listOf(record())))
        val (library, _) = library(disk(), store = store, inventory = ThrowingInventory())
        assertEquals(LibraryStatus.Blocked("checking the game's files failed: file exploded"), libraryStatus(library))
        assertEquals(GameStatus.Ready(GameRoot(steamRoot), FoundVia.Steam), library.state.value.game)
    }

    @Test
    fun startingAndChoosingAnotherGameReadEachOwnedFileOnce() = runTest {
        val owned = record()
        val files = InMemoryGameFiles().apply { putFile(root, owned.files[0].relativePath(owned), owned.files[0].sizeBytes, owned.files[0].sha256) }
        val reads = mutableListOf<String>()
        val inventory = object : GameInventory by files {
            override suspend fun file(root: GameRoot, relativePath: String): FileObservation = files.file(root, relativePath).also { reads += relativePath }
        }
        val (library, _) = library(disk(), store = InMemoryLibraryStore(LibraryManifest(records = listOf(owned))), inventory = inventory)
        assertEquals(LibraryStatus.Ready(listOf(owned)), libraryStatus(library))
        library.useGameFolder(copy)
        assertEquals(GameStatus.Ready(GameRoot(copy), FoundVia.Chosen), library.state.value.game)
        assertEquals(listOf(owned.files[0].relativePath(owned)), reads)
    }

    @Test
    fun choosingTheGameFolderOnceItsDriveIsBackChecksItsModsAgain() = runTest {
        val unreadable = record()
        val missing = record(installId = "install-2", number = 6)
            .withOnly { copy(source = InstalledSource.Remote(SubmissionIdentity(SubmissionKind.Mod, 40_112, 1, "1".repeat(32)))) }
        val files = InMemoryGameFiles().apply { listOf(unreadable, missing).forEach { putFile(root, it.files[0].relativePath(it), it.files[0].sizeBytes, it.files[0].sha256) } }
        var mounted = false
        val inventory = object : GameInventory by files {
            override suspend fun file(root: GameRoot, relativePath: String): FileObservation = when {
                mounted -> files.file(root, relativePath)
                relativePath.endsWith("pak05_dir.vpk") -> FileObservation.Unreadable("the drive is not mounted")
                else -> FileObservation.Absent
            }
        }
        val disk = disk(steam = false).apply { folders.remove(copy) }
        val (library, _) = library(disk, store = InMemoryLibraryStore(LibraryManifest(records = listOf(unreadable, missing))), inventory = inventory)
        assertEquals(LibraryStatus.Ready(listOf(unreadable, missing), damaged = mapOf(missing.installId to FilesDamage.Missing), unchecked = setOf(unreadable.installId)), libraryStatus(library))

        mounted = true
        disk.folders[copy] = null
        library.useGameFolder(copy)
        assertEquals(LibraryStatus.Ready(listOf(unreadable, missing)), library.state.value.library.unlisted())
    }

    @Test
    fun aProblemsReasonIsASentenceNotAClassName() {
        for (problem in listOf(LibraryProblem.NoRoomInOrder(4), LibraryProblem.LibraryUnavailable(null))) {
            assertFalse(problem::class.simpleName!! in problem.reason(), problem.reason())
        }
    }

    @Test
    fun aStoreThatThrowsBlocksTheLibraryInsteadOfLeavingItLoading() = runTest {
        val (library, _) = library(disk(), store = ThrowingStore())
        assertEquals(LibraryStatus.Blocked("reading the install list failed: store exploded"), libraryStatus(library))
    }

    @Test
    fun anOwnedFileThatCannotBeReadIsUncheckedNotIntactOrMissing() = runTest {
        val owned = record(files = listOf(file(), SetFile(VpkPart.Chunk(0), FileImage(1_000L, "e".repeat(64)))))
        val status = statusOver(owned) {
            intact(owned)
            failFile(root, owned.files[1].relativePath(owned), "Permission denied")
        }
        assertEquals(LibraryStatus.Ready(listOf(owned), unchecked = setOf(owned.installId)), status)
    }

    @Test
    fun aMissingFileOutweighsAnUnreadableOneInTheSameSet() = runTest {
        val owned = record(files = listOf(file(), SetFile(VpkPart.Chunk(0), FileImage(1_000L, "e".repeat(64)))))
        val status = statusOver(owned) { failFile(root, owned.files[1].relativePath(owned), "Permission denied") }
        assertEquals(LibraryStatus.Ready(listOf(owned), damaged = mapOf(owned.installId to FilesDamage.Missing)), status)
    }

    @Test
    fun installStatusComesFromTheManifestByExactSubmission() = runTest {
        val on = record()
        val off = record(installId = "off", number = null, files = listOf(file()))
            .withOnly { copy(source = InstalledSource.Remote(SubmissionIdentity(SubmissionKind.Mod, 40_112, 1, "1".repeat(32)))) }
        val sound = record(installId = "sound", number = 7, files = listOf(file()))
            .withOnly { copy(source = InstalledSource.Remote(SubmissionIdentity(SubmissionKind.Sound, 721_908, 2, "2".repeat(32)))) }
        val store = InMemoryLibraryStore(LibraryManifest(records = listOf(on, off, sound)))
        val (library, _) = library(disk(), store = store)
        libraryStatus(library)

        assertEquals(InstallStatus.Installed(enabled = true), library.status("mod-721908").first())
        assertEquals(InstallStatus.Installed(enabled = false), library.status("mod-40112").first())
        assertEquals(InstallStatus.Installed(enabled = true), library.status("sound-721908").first())
        assertEquals(InstallStatus.NotInstalled, library.status("sound-40112").first())
        assertEquals(InstallStatus.NotInstalled, library.status("lilith").first())
    }

    @Test
    fun installStatusIsUnknownUntilTheManifestIsReadAndWhileItIsBlocked() = runTest {
        val (loading, _) = library(disk(), store = InMemoryLibraryStore(LibraryManifest(records = listOf(record()))))
        assertEquals(InstallStatus.Unknown, loading.status("mod-721908").first())

        val (blocked, _) = library(disk(), store = InMemoryLibraryStore(forceUnreadable = "schema version 7 is not supported"))
        libraryStatus(blocked)
        assertEquals(InstallStatus.Unknown, blocked.status("mod-721908").first())
    }
}
