package gg.hideouthq.library

import gg.hideouthq.core.data.LocalFile
import gg.hideouthq.core.data.Preferences
import gg.hideouthq.core.data.fake.FakeVpk
import gg.hideouthq.core.library.ChoiceSync
import gg.hideouthq.core.library.InstallInput
import gg.hideouthq.core.library.InstallRequest
import gg.hideouthq.core.library.LibraryState
import gg.hideouthq.core.library.LibraryStatus
import gg.hideouthq.core.library.ModLibrary
import gg.hideouthq.core.library.ProfileId
import gg.hideouthq.core.library.ResourceIndexCache
import gg.hideouthq.core.library.SubmissionKind
import gg.hideouthq.core.library.SubmissionRef
import gg.hideouthq.core.network.BusyHosts
import gg.hideouthq.data.FileCacheStorage
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** How often installing a mod hashes the whole of its VPK, through the classes production wires, until it is on and checked. */
class InstallReadsTest {
    private val home: Path = Files.createTempDirectory(Files.createDirectories(Path.of("build", "flow-tests")), "reads").toRealPath()
    private val game = home.resolve("Deadlock")
    private val citadel = game.resolve("game/citadel").createDirectories()
    private val data = home.resolve("data").createDirectories()
    private val cache = home.resolve("cache").createDirectories()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val store = FileLibraryStore(data)
    private val vpk = FakeVpk.directory(embeddedBytes = 3_000_000, seed = 7)
    private val archive = zip("Mod/mod_dir.vpk" to vpk)

    init {
        citadel.resolve("gameinfo.gi").writeBytes(Valve)
        citadel.resolve("addons").createDirectories()
        citadel.resolve("pak01_dir.vpk").writeBytes(FakeVpk.directory(seed = 40))
    }

    @AfterTest
    fun stop() = runBlocking {
        scope.coroutineContext.job.cancelAndJoin()
        store.close()
        deleteTree(home)
    }

    @Test
    fun addingAVpkFromAFileHashesItAsItIsCopiedInAndUnpackedAndOnceItsCopyInTheGameIsReadBack() {
        val file = home.resolve("mod_dir.vpk").also { it.writeBytes(vpk) }
        val hashes = HashedFiles.during {
            val library = library()
            library.install(InstallRequest(InstallInput.Local(LocalFile(file.toString(), file.fileName.toString(), vpk.size.toLong())), setOf(ProfileId("main"))))
            settled(library)
        }
        assertEquals(3, hashes.of(vpk.size.toLong()), hashes.report())
    }

    @Test
    fun aCatalogueInstallHashesTheArchiveOnceAndItsVpkAsItIsExtractedAndReadBackInTheGame() {
        val hashes = HashedFiles.during {
            val library = library()
            library.install(InstallRequest(InstallInput.Remote(SubmissionRef(SubmissionKind.Mod, 1)), setOf(ProfileId("main"))))
            settled(library)
        }
        assertEquals(1, hashes.of(archive.size.toLong()), hashes.report())
        assertEquals(2, hashes.of(vpk.size.toLong()), hashes.report())
    }

    private val listing = """{"_idRow":1,"_sName":"Reads","_bIsTrashed":false,"_bIsWithheld":false,"_bIsPrivate":false,"_aFiles":[""" +
        """{"_idRow":11,"_sFile":"mod.zip","_nFilesize":${archive.size},"_nDownloadCount":1,"_tsDateAdded":1,"_sDownloadUrl":"https:\/\/gamebanana.com\/dl\/11","_sMd5Checksum":"${md5(archive)}"}]}"""

    private val http = HttpClient(MockEngine { request ->
        if (request.url.encodedPath.startsWith("/dl/")) respond(archive)
        else respond(listing, headers = headersOf(HttpHeaders.ContentType, "application/json"))
    }) { expectSuccess = false }

    private fun library(): ModLibrary {
        val ports = DesktopInstall.ports(data, cache, http, http, BusyHosts()) {}
        val library = ModLibrary(
            SteamGameLocator(steamInstalls = { emptyList() }), Preferences(FileCacheStorage(data.toFile())), store, NioGameInventory, ports, scope,
            compatibility = DesktopInstall.compatibility(data), resourceIndexes = ResourceIndexCache(FileCacheStorage(cache.toFile()), NioResourceIndexSource()),
        )
        runBlocking {
            library.useGameFolder(game.toString())
            library.start()
            withTimeout(20_000) { library.state.first { state -> state.library.let { it is LibraryStatus.Ready && it.interrupted == null } && state.profiles != null } }
        }
        return library
    }

    /** Until the mod is on and nothing runs, then a second later again, so a check that follows turning it on is counted. */
    private fun settled(library: ModLibrary) = runBlocking {
        withTimeout(20_000) { library.state.first { it.installedAndQuiet() } }
        delay(1_000)
        withTimeout(20_000) { library.state.first { it.installedAndQuiet() } }
    }

    private fun LibraryState.installedAndQuiet(): Boolean {
        val records = (library as? LibraryStatus.Ready)?.records.orEmpty()
        val on = profiles?.activeProfile?.enabled.orEmpty()
        return operations.isEmpty() && changes.isEmpty() && choiceSync == ChoiceSync.Settled && !saving && records.isNotEmpty() &&
            records.all { it.installId in on && it.enabled } && !compatibility.checking && compatibility.installs.values.none { it.pending != null }
    }

    private fun md5(bytes: ByteArray) = MessageDigest.getInstance("MD5").digest(bytes).toHexString()

    private companion object {
        val Valve = "\"GameInfo\"\r\n{\r\n\tFileSystem\r\n\t{\r\n\t\tSearchPaths\r\n\t\t{\r\n\t\t\tGame\t\t\t\tcitadel\r\n\t\t\tGame\t\t\t\tcore\r\n\t\t}\r\n\t}\r\n}\r\n".encodeToByteArray()
    }
}
