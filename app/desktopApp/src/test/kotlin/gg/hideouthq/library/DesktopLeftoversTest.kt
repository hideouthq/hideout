package gg.hideouthq.library

import gg.hideouthq.core.data.Preferences
import gg.hideouthq.core.data.fake.FakeProcesses
import gg.hideouthq.core.data.fake.FakeVpk
import gg.hideouthq.core.data.gamebanana.GameBananaFiles
import gg.hideouthq.core.library.CitadelPath
import gg.hideouthq.core.library.GameRoot
import gg.hideouthq.core.library.InstallPorts
import gg.hideouthq.core.library.Leftover
import gg.hideouthq.core.library.LeftoverOutcome
import gg.hideouthq.core.library.LibraryManifest
import gg.hideouthq.core.library.LibraryStatus
import gg.hideouthq.core.library.ModLibrary
import gg.hideouthq.core.library.Slot
import gg.hideouthq.core.library.WatchTiming
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
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class DesktopLeftoversTest {
    private val home: Path = Files.createTempDirectory(Files.createDirectories(Path.of("build", "flow-tests")), "home").toRealPath()
    private val game = home.resolve("Deadlock")
    private val root = GameRoot(game.toString())
    private val citadel = game.resolve("game/citadel").createDirectories()
    private val data = home.resolve("data").createDirectories()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> throw e })
    private val store = FileLibraryStore(data)

    private val valve = "\"GameInfo\"\r\n{\r\n\tFileSystem\r\n\t{\r\n\t\tSearchPaths\r\n\t\t{\r\n\t\t\tGame\t\t\t\tcitadel\r\n\t\t\tGame\t\t\t\tcore\r\n\t\t}\r\n\t}\r\n}\r\n".encodeToByteArray()

    private val files = mapOf(
        "addons/pak05_dir.vpk" to FakeVpk.directory(chunkReads = mapOf(0 to 48L), seed = 5),
        "addons/pak05_000.vpk" to ByteArray(48) { (it * 3).toByte() },
        "addons/pak06_dir.vpk" to FakeVpk.directory(seed = 6),
        "addons/631628_pak07_dir.vpk" to FakeVpk.directory(seed = 7),
        "hideout_addons2/pak03_dir.vpk" to FakeVpk.directory(seed = 3),
    )

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
            gameFiles = NioGameFiles(), backups = FileBackupStore(data.resolve("originals")),
        )
        val watch = WatchTiming(debounce = 200.milliseconds, period = 2.seconds, processPoll = 200.milliseconds)
        return ModLibrary(SteamGameLocator(steamInstalls = { emptyList() }), Preferences(FileCacheStorage(data.toFile())), store, NioGameInventory, ports, scope, watch = watch).also {
            runBlocking {
                it.useGameFolder(game.toString())
                it.start()
                withTimeout(20_000) { it.state.first { state -> state.library.let { library -> library is LibraryStatus.Ready && library.interrupted == null && library.loading != null } } }
            }
        }
    }

    private fun ModLibrary.ready() = state.value.library as LibraryStatus.Ready

    private fun key(path: Path): Any = checkNotNull(fileIdentity(path, Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes::class.java)))

    @Test
    fun aDeadlockFolderOnDiskListsItsLeftoversMovesSomeOutAsTheSameFilesAndKeepsOneInPlace() {
        citadel.resolve("gameinfo.gi").writeBytes(valve)
        for ((path, bytes) in files) citadel.resolve(path).also { it.parent.createDirectories() }.writeBytes(bytes)
        Files.createSymbolicLink(citadel.resolve("addons/pak09_dir.vpk"), citadel.resolve("addons/pak06_dir.vpk"))
        runBlocking { store.save(LibraryManifest()) }
        val keys = files.keys.associateWith { key(citadel.resolve(it)) }
        val library = open()

        val found = library.ready().leftovers
        assertEquals(listOf("addons/pak05_dir.vpk", "addons/pak05_000.vpk", "addons/pak06_dir.vpk", "hideout_addons2/pak03_dir.vpk"), found.flatMap { set -> set.paths.map { it.value } }, "a link and a name the game does not mount are not leftovers")

        val moving = found.filter { it.slot != Slot(6) }
        val moved = assertIs<LeftoverOutcome.Moved>(runBlocking { library.moveOut(moving) })
        val folder = citadel.resolve(moved.folder.value)
        assertTrue(moved.folder.value.startsWith("${CitadelPath.HideoutMoved.value}/"))
        for (path in moving.flatMap(Leftover::paths).map { it.value }) {
            assertFalse(citadel.resolve(path).exists(LinkOption.NOFOLLOW_LINKS), "$path left the game")
            assertContentEquals(files.getValue(path), folder.resolve(path).readBytes())
            assertEquals(keys.getValue(path), key(folder.resolve(path)), "$path is the very file it was")
        }
        assertEquals(listOf(Slot(6)), library.ready().leftovers.map { it.slot })

        assertEquals(LeftoverOutcome.Added, runBlocking { library.addLeftovers(library.ready().leftovers) })
        val record = library.ready().records.single()
        assertEquals("game/citadel/addons/pak06_dir.vpk", record.files.single().relativePath(record))
        assertEquals(keys.getValue("addons/pak06_dir.vpk"), key(citadel.resolve("addons/pak06_dir.vpk")), "claimed where it is, not copied")
        assertEquals(emptyList(), library.ready().leftovers)
        assertTrue(Files.isSymbolicLink(citadel.resolve("addons/pak09_dir.vpk")), "a link is never touched")
        assertContentEquals(files.getValue("addons/631628_pak07_dir.vpk"), citadel.resolve("addons/631628_pak07_dir.vpk").readBytes())
    }
}
