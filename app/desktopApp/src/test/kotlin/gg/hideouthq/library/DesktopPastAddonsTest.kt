package gg.hideouthq.library

import gg.hideouthq.core.data.Preferences
import gg.hideouthq.core.data.fake.FakeProcesses
import gg.hideouthq.core.data.fake.FakeVpk
import gg.hideouthq.core.data.gamebanana.GameBananaFiles
import gg.hideouthq.core.library.ChoiceSync
import gg.hideouthq.core.library.GameInfoEdit
import gg.hideouthq.core.library.GameInfoPatch
import gg.hideouthq.core.library.GameRoot
import gg.hideouthq.core.library.HideoutLines
import gg.hideouthq.core.library.InstallPorts
import gg.hideouthq.core.library.InstalledSet
import gg.hideouthq.core.library.InstalledSource
import gg.hideouthq.core.library.LibraryManifest
import gg.hideouthq.core.library.LibraryState
import gg.hideouthq.core.library.LibraryStatus
import gg.hideouthq.core.library.LoadingOwner
import gg.hideouthq.core.library.ManifestRecord
import gg.hideouthq.core.library.ModLibrary
import gg.hideouthq.core.library.MutationResult
import gg.hideouthq.core.library.Placement
import gg.hideouthq.core.library.Profile
import gg.hideouthq.core.library.ProfileBook
import gg.hideouthq.core.library.ProfileColour
import gg.hideouthq.core.library.ProfileId
import gg.hideouthq.core.library.SearchPathsCheck
import gg.hideouthq.core.library.SetFile
import gg.hideouthq.core.library.SetFolder
import gg.hideouthq.core.library.SetOrigin
import gg.hideouthq.core.library.Sha256
import gg.hideouthq.core.library.SubmissionIdentity
import gg.hideouthq.core.library.SubmissionKind
import gg.hideouthq.core.library.SwitchSummary
import gg.hideouthq.core.library.VpkPart
import gg.hideouthq.core.library.WatchTiming
import gg.hideouthq.core.library.inCitadel
import gg.hideouthq.core.library.relativePath
import gg.hideouthq.data.FileCacheStorage
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class DesktopPastAddonsTest {
    private val home: Path = Files.createTempDirectory(Files.createDirectories(Path.of("build", "flow-tests")), "home").toRealPath()
    private val game = home.resolve("Deadlock")
    private val root = GameRoot(game.toString())
    private val citadel = game.resolve("game/citadel").createDirectories()
    private val data = home.resolve("data").createDirectories()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> throw e })
    private val store = FileLibraryStore(data)

    private val valve = "\"GameInfo\"\r\n{\r\n\tFileSystem\r\n\t{\r\n\t\tSearchPaths\r\n\t\t{\r\n\t\t\tGame_Language\t\tcitadel_*LANGUAGE*\r\n\t\t\tGame\t\t\t\tcitadel\r\n\t\t\tGame\t\t\t\tcore\r\n\t\t}\r\n\t}\r\n}\r\n".encodeToByteArray()

    private fun bytes(index: Int) = FakeVpk.directory(seed = index)

    private val records = (1..150).map { index ->
        val image = Sha256.image(bytes(index))
        val source = InstalledSource.Remote(SubmissionIdentity(SubmissionKind.Mod, 1_000L + index, 2_000L + index, null))
        ManifestRecord("m$index", root, "Mod $index", Placement.Off, listOf(InstalledSet(source, null, null, SetOrigin.Imported(image), null, "pak01_dir.vpk", listOf(SetFile(VpkPart.Directory, image)))))
    }

    private val ids = records.map { it.installId }
    private val all = Profile(ProfileId("all"), "All", ProfileColour.Blue, ids.toSet(), mapOf(root to ids))
    private val fewer = Profile(ProfileId("fewer"), "Fewer", ProfileColour.Rose, ids.drop(51).toSet(), mapOf(root to ids))

    @AfterTest
    fun stop() = runBlocking {
        scope.coroutineContext.job.cancelAndJoin()
        store.close()
        deleteTree(home)
    }

    private fun open(): ModLibrary {
        val http = HttpClient(MockEngine { respondError(HttpStatusCode.NotFound) }) { expectSuccess = false }
        val ports = InstallPorts(
            GameBananaFiles(http), HttpDownloader(http, data.resolve("downloads"), hideoutAreas = listOf(data)), NioModInstaller(data.resolve("staging")), FileGameConfig(), FakeProcesses(),
            backups = FileBackupStore(data.resolve("originals")),
        )
        val watch = WatchTiming(debounce = 200.milliseconds, period = 2.seconds, processPoll = 200.milliseconds)
        return ModLibrary(SteamGameLocator(steamInstalls = { emptyList() }), Preferences(FileCacheStorage(data.toFile())), store, NioGameInventory, ports, scope, watch = watch).also {
            runBlocking {
                it.useGameFolder(game.toString())
                it.start()
                withTimeout(20_000) { it.state.first { state -> state.library.let { library -> library is LibraryStatus.Ready && library.interrupted == null } } }
            }
        }
    }

    private fun ModLibrary.switchAndSettle(profile: Profile): List<ManifestRecord> = runBlocking {
        switchProfile(profile.id).let { assertIs<MutationResult.Done<SwitchSummary>>(it, "$it") }
        val settled = withTimeout(60_000) {
            state.first { it.choiceSync == ChoiceSync.Settled && it.records().all { record -> record.enabled == record.installId in profile.enabled } }
        }
        settled.records()
    }

    private fun LibraryState.records() = (library as? LibraryStatus.Ready)?.records.orEmpty()

    private fun loadingFiles(): List<String> = SetFolder.Loading.map { citadel.resolve(it.inCitadel) }.filter { it.exists() }.flatMap { folder ->
        Files.list(folder).use { paths -> paths.map { "${folder.fileName}/${it.fileName}" }.toList().sorted() }
    }

    private fun pak(number: Int) = "pak${number.toString().padStart(2, '0')}_dir.vpk"

    @Test
    fun aHundredAndFiftySetsLoadInTheirOrderFromAddonsAndHideoutAddons2AndTurningFiftyOneOffBringsTheRestBackToAddons() {
        citadel.resolve("gameinfo.gi").writeBytes(valve)
        for ((index, record) in records.withIndex()) game.resolve(record.files.single().relativePath(record)).also { it.parent.createDirectories() }.writeBytes(bytes(index + 1))
        val main = ProfileBook.main(records)
        runBlocking { store.save(LibraryManifest(records = records, profiles = main.copy(profiles = main.profiles + all + fewer))) }
        val library = open()

        val placed = library.switchAndSettle(all)
        assertEquals(ids.withIndex().associate { (index, id) -> id to index + 49 }, placed.associate { it.installId to it.numbers.single().value })
        assertEquals((49..99).map { "addons/${pak(it)}" } + (1..99).map { "hideout_addons2/${pak(it)}" }, loadingFiles())
        for (record in placed) assertContentEquals(bytes(record.installId.drop(1).toInt()), game.resolve(record.files.single().relativePath(record)).readBytes(), record.installId)
        val info = citadel.resolve("gameinfo.gi").readBytes()
        assertEquals(SearchPathsCheck.Read(LoadingOwner.Hideout, customGames = false, overflow = 1), GameInfoPatch.check(info))
        assertContentEquals(valve, (GameInfoPatch.withBlock(info, HideoutLines(mods = false, customGames = false), 0) as GameInfoEdit.Patched).bytes, "taking the block out gives back Valve's file")

        val kept = library.switchAndSettle(fewer)
        assertEquals(ids.drop(51).withIndex().associate { (index, id) -> id to index + 1 }, kept.filter { it.enabled }.associate { it.installId to it.numbers.single().value }, "the 99 left on fit in addons again")
        assertEquals((1..99).map { "addons/${pak(it)}" }, loadingFiles())
        for (record in kept.filter { it.enabled }) assertContentEquals(bytes(record.installId.drop(1).toInt()), game.resolve(record.files.single().relativePath(record)).readBytes(), record.installId)
    }
}
