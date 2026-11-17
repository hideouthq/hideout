package gg.hideouthq.customgames

import gg.hideouthq.core.customgames.Artifact
import gg.hideouthq.core.customgames.FetchProblem
import gg.hideouthq.core.customgames.Mode
import gg.hideouthq.core.customgames.ModeId
import gg.hideouthq.core.customgames.ModeOrigin
import gg.hideouthq.core.customgames.Transfer
import gg.hideouthq.core.data.fake.FakeCustomGamesFeed
import kotlin.time.Instant
import gg.hideouthq.core.customgames.Sha256
import gg.hideouthq.core.library.UnpackLimits
import gg.hideouthq.data.desktopEngine
import gg.hideouthq.library.DesktopInstall
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.request.header
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

internal fun zipOf(vararg files: Pair<String, String>): ByteArray {
    val out = ByteArrayOutputStream()
    ZipOutputStream(out).use { zip ->
        files.forEach { (name, text) ->
            zip.putNextEntry(ZipEntry(name))
            zip.write(text.encodeToByteArray())
            zip.closeEntry()
        }
    }
    return out.toByteArray()
}

class RuntimeStoreTest {
    private val root: Path = Files.createTempDirectory("hideout-runtime-store")
    private val folder: Path = root.resolve("store")
    private val served = mutableMapOf<String, ByteArray>()
    private val requests = CopyOnWriteArrayList<String>()
    private var stall: CompletableDeferred<Unit>? = null

    private val server = embeddedServer(Netty, port = 0, host = "127.0.0.1") {
        routing {
            get("/{name}") {
                val name = checkNotNull(call.parameters["name"])
                requests += name
                val bytes = served[name] ?: return@get call.respondBytes(ByteArray(0), status = HttpStatusCode.NotFound)
                val gate = stall
                if (gate != null) {
                    call.respondBytesWriter(ContentType.Application.OctetStream) {
                        writeFully(bytes.copyOfRange(0, bytes.size / 2))
                        flush()
                        gate.complete(Unit)
                        awaitCancellation()
                    }
                    return@get
                }
                val start = call.request.header(HttpHeaders.Range)?.removePrefix("bytes=")?.removeSuffix("-")?.toInt()
                if (start == null) return@get call.respondBytes(bytes, ContentType.Application.OctetStream)
                call.response.header(HttpHeaders.ContentRange, "bytes $start-${bytes.size - 1}/${bytes.size}")
                call.respondBytes(bytes.copyOfRange(start, bytes.size), ContentType.Application.OctetStream, HttpStatusCode.PartialContent)
            }
        }
    }.start(wait = false)

    private val base = "http://127.0.0.1:${runBlocking { server.engine.resolvedConnectors().single().port }}"
    private val client = DesktopInstall.downloadClient(desktopEngine(), "test")
    private val logs = CopyOnWriteArrayList<String>()

    private fun store(limits: UnpackLimits = UnpackLimits()) = RuntimeStore(folder, { client }, logs::add, limits)

    @AfterTest
    fun cleanUp() {
        client.close()
        server.stop(0, 0)
        root.toFile().deleteRecursively()
    }

    private fun sha(bytes: ByteArray) = checkNotNull(Sha256.parse(MessageDigest.getInstance("SHA-256").digest(bytes).toHexString()))

    private fun published(name: String, bytes: ByteArray, claimed: ByteArray = bytes): Artifact {
        served[name] = bytes
        return Artifact("$base/$name", claimed.size.toLong(), sha(claimed))
    }

    private fun provide(store: RuntimeStore, vararg wanted: RuntimePiece, keep: Set<Sha256> = emptySet(), record: Mode? = null, progress: (Transfer) -> Unit = {}) =
        runBlocking { withTimeout(30.seconds) { store.provide(wanted.asList(), keep, record = record, progress = progress) } }

    private fun mode(id: String, version: String, rules: Artifact, client: Artifact?) =
        FakeCustomGamesFeed.SampleMode.copy(id = ModeId(id), version = version, origin = ModeOrigin.Feed(rules, client))

    private fun packages(mode: Mode) = (mode.origin as ModeOrigin.Feed).let { listOfNotNull(it.rules, it.client) }.map { RuntimePiece.Package(it, runnable = false) }

    private fun install(store: RuntimeStore, mode: Mode, keep: Set<Sha256> = emptySet()) = assertNull(provide(store, *packages(mode).toTypedArray(), keep = keep, record = mode))

    private fun leftovers() = folder.listDirectoryEntries().filterNot { it.isDirectory() && it.name.length == 64 }

    @Test
    fun anOptionalPieceThatFailsCostsNothingElseAndComesAfterWhatIsRequired() {
        val required = published("required", zipOf("mod.json" to "{}"))
        val optional = published("optional", zipOf("renderer.js" to "r"))
        served.remove("optional")
        val store = store()
        val problem = runBlocking { withTimeout(30.seconds) { store.provide(listOf(RuntimePiece.Package(required, runnable = false)), emptySet(), listOf(RuntimePiece.Package(optional, runnable = false))) {} } }
        assertNull(problem)
        assertTrue(store.folderOf(required.sha256).isDirectory())
        assertFalse(store.folderOf(optional.sha256).isDirectory())
        assertEquals("required", requests.first())
    }

    @Test
    fun aPackageUnpacksIntoItsDigestsFolderAndAProgramIsExecutable() {
        assumeTrue(System.getProperty("os.name") == "Linux")
        val rules = published("rules.zip", zipOf("mod.json" to "{}", "maps/a/b.txt" to "deep"))
        val host = published("host.zip", zipOf("modlock-host.exe" to "host", "quickjs.wasm" to "wasm"))
        val cli = published("modlock", "binary".encodeToByteArray())
        val store = store()
        assertNull(provide(store, RuntimePiece.Package(rules, runnable = false), RuntimePiece.Package(host, runnable = true), RuntimePiece.Program(cli, "modlock")))

        assertEquals("deep", store.folderOf(rules.sha256).resolve("maps/a/b.txt").readText())
        assertFalse(Files.isExecutable(store.folderOf(rules.sha256).resolve("mod.json")))
        assertTrue(Files.isExecutable(store.folderOf(host.sha256).resolve("modlock-host.exe")))
        assertEquals("binary", store.folderOf(cli.sha256).resolve("modlock").readText())
        assertTrue(Files.isExecutable(store.folderOf(cli.sha256).resolve("modlock")))
        assertEquals(emptyList(), leftovers(), "no download or staging stays behind")
    }

    @Test
    fun whatIsHereIsNotAskedForAgain() {
        val rules = published("rules.zip", zipOf("mod.json" to "{}"))
        val store = store()
        assertNull(provide(store, RuntimePiece.Package(rules, runnable = false)))
        requests.clear()
        assertNull(provide(store, RuntimePiece.Package(rules, runnable = false)))
        assertEquals(emptyList(), requests.toList())
    }

    @Test
    fun theSameDigestNamedTwiceIsFetchedOnce() {
        val rules = published("rules.zip", zipOf("mod.json" to "{}"))
        assertNull(provide(store(), RuntimePiece.Package(rules, runnable = false), RuntimePiece.Package(rules, runnable = false)))
        assertEquals(listOf("rules.zip"), requests.toList())
    }

    @Test
    fun bytesThatAreNotWhatTheFeedPromisedLeaveNoFolder() {
        val promised = zipOf("mod.json" to "{}")
        val forged = zipOf("mod.json" to "[]")
        val rules = published("rules.zip", forged, claimed = promised)
        val store = store()
        assertEquals(FetchProblem.Mismatch, provide(store, RuntimePiece.Package(rules, runnable = false)))
        assertFalse(store.folderOf(rules.sha256).exists())
    }

    @Test
    fun aPathOutOfTheFolderIsRefusedAndNothingIsPublished() {
        for (name in listOf("../escaped.txt", "a/../../escaped.txt", "c:escaped.txt")) {
            val rules = published("rules.zip", zipOf("mod.json" to "{}", name to "x"))
            val store = store()
            assertEquals(FetchProblem.Unpack, provide(store, RuntimePiece.Package(rules, runnable = false)), name)
            assertFalse(store.folderOf(rules.sha256).exists(), name)
            assertFalse(folder.resolve("escaped.txt").exists(), name)
            assertFalse(root.resolve("escaped.txt").exists(), name)
        }
    }

    @Test
    fun aPackageOverTheLimitsIsRefused() {
        val big = published("big.zip", zipOf("a.txt" to "x".repeat(1000)))
        val many = published("many.zip", zipOf("a" to "", "b" to "", "c" to ""))
        val small = store(UnpackLimits(maxEntries = 2, maxTotalBytes = 100))
        assertEquals(FetchProblem.Unpack, provide(small, RuntimePiece.Package(big, runnable = false)))
        assertEquals(FetchProblem.Unpack, provide(small, RuntimePiece.Package(many, runnable = false)))
        assertFalse(small.folderOf(big.sha256).exists())
        assertFalse(small.folderOf(many.sha256).exists())
    }

    @Test
    fun onlyWhatTheFeedNamesIsKept() {
        val old = published("old.zip", zipOf("old" to "o"))
        val named = published("named.zip", zipOf("named" to "n"))
        val now = published("now.zip", zipOf("now" to "w"))
        val store = store()
        assertNull(provide(store, RuntimePiece.Package(old, runnable = false), RuntimePiece.Package(named, runnable = false)))
        val notOurs = folder.resolve("notes.txt").also { Files.writeString(it, "mine") }

        assertNull(provide(store, RuntimePiece.Package(now, runnable = false), keep = setOf(named.sha256)))
        assertFalse(store.folderOf(old.sha256).exists())
        assertTrue(store.folderOf(named.sha256).exists())
        assertTrue(store.folderOf(now.sha256).exists())
        assertEquals(listOf(notOurs), leftovers(), "only the store's own names are ever removed")
    }

    @Test
    fun progressCountsTheBytesOfThePiecesUpToTheWhole() {
        val a = published("a", Random(1).nextBytes(1 shl 20))
        val b = published("b.zip", zipOf("mod.json" to "{}"))
        val seen = CopyOnWriteArrayList<Transfer>()
        assertNull(provide(store(), RuntimePiece.Program(a, "a"), RuntimePiece.Package(b, runnable = false)) { seen += it })
        assertEquals(seen.map { it.done }.sorted(), seen.map { it.done })
        assertEquals(Transfer(a.bytes + b.bytes, a.bytes + b.bytes), seen.last())
    }

    @Test
    fun anInstalledModeIsRecordedWithItsListingSizeAndPackagesAndOutlivesTheStore() {
        val mode = mode("arena", "1.0.0", published("rules.zip", zipOf("mod.json" to "{}")), published("client.zip", zipOf("maps/a.vpk" to "map")))
        install(store(), mode)
        val record = runBlocking { store().installed() }.getValue(ModeId("arena"))
        assertEquals(mode, record.mode, "the verified listing it was installed from")
        assertEquals("{}".length + "map".length.toLong(), record.bytes, "what its packages take on disk")
        assertNull(record.lastPlayed)
        val at = Instant.fromEpochSeconds(1_800_000_000)
        runBlocking { store().played(ModeId("arena"), at) }
        assertEquals(at, runBlocking { store().installed() }.getValue(ModeId("arena")).lastPlayed)
    }

    @Test
    fun aRemovedModeStaysRemovedAndWhatAnotherNeedsStays() {
        val shared = published("shared.zip", zipOf("maps/a.vpk" to "map"))
        val runtime = published("runtime.zip", zipOf("platform.js" to "p"))
        val a = mode("a", "1", published("a.zip", zipOf("mod.json" to "{\"a\":1}")), shared)
        val b = mode("b", "1", published("b.zip", zipOf("mod.json" to "{\"b\":1}")), shared)
        val store = store()
        assertNull(provide(store, RuntimePiece.Package(runtime, runnable = false)))
        install(store, a, keep = setOf(runtime.sha256))
        install(store, b, keep = setOf(runtime.sha256))
        runBlocking { store.remove(ModeId("a"), setOf(runtime.sha256)) }
        assertFalse(store.folderOf((a.origin as ModeOrigin.Feed).rules.sha256).exists())
        assertTrue(store.folderOf(shared.sha256).exists(), "b still needs it")
        assertTrue(store.folderOf(runtime.sha256).exists())
        assertEquals(setOf(ModeId("b")), runBlocking { store.installed() }.keys)

        assertNull(provide(store, RuntimePiece.Package(runtime, runnable = false), keep = setOf(runtime.sha256)))
        assertFalse(store.folderOf((a.origin as ModeOrigin.Feed).rules.sha256).exists(), "nothing brings it back")
        assertTrue(store.folderOf((b.origin as ModeOrigin.Feed).rules.sha256).exists(), "a record keeps its packages")
    }

    @Test
    fun anUpdateReplacesTheOldVersionsPackagesAndKeepsWhenItWasPlayed() {
        val old = mode("arena", "1", published("old.zip", zipOf("mod.json" to "{\"v\":1}")), null)
        val new = mode("arena", "2", published("new.zip", zipOf("mod.json" to "{\"v\":2}")), null)
        val store = store()
        install(store, old)
        val at = Instant.fromEpochSeconds(1_800_000_000)
        runBlocking { store.played(ModeId("arena"), at) }
        install(store, new)
        val record = runBlocking { store.installed() }.getValue(ModeId("arena"))
        assertEquals("2", record.mode.version)
        assertEquals(at, record.lastPlayed)
        assertFalse(store.folderOf((old.origin as ModeOrigin.Feed).rules.sha256).exists())
    }

    @Test
    fun aModeWhosePackagesDidNotAllArriveIsNotRecorded() {
        val rules = published("rules.zip", zipOf("mod.json" to "{}"))
        val client = published("client.zip", zipOf("maps/a.vpk" to "map"))
        served.remove("client.zip")
        val store = store()
        runBlocking { withTimeout(30.seconds) { store.provide(listOf(RuntimePiece.Package(rules, runnable = false)), emptySet(), listOf(RuntimePiece.Package(client, runnable = false)), mode("arena", "1", rules, client)) {} } }
        assertEquals(emptyMap(), runBlocking { store.installed() })
    }

    @Test
    fun aRecordFileThatCannotBeReadCountsAsNoneInstalled() {
        Files.createDirectories(folder)
        Files.writeString(folder.resolve("installed.json"), "not json")
        assertEquals(emptyMap(), runBlocking { store().installed() })
    }

    @Test
    fun aCancelledDownloadLeavesNoFolderAndResumesFromItsPart() = runBlocking {
        val bytes = Random(2).nextBytes(1 shl 20)
        val cli = published("modlock", bytes)
        val store = store()
        val gate = CompletableDeferred<Unit>()
        stall = gate
        val running = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) { store.provide(listOf(RuntimePiece.Program(cli, "modlock")), emptySet()) {} }
        withTimeout(30.seconds) { gate.await() }
        withTimeout(30.seconds) { running.cancelAndJoin() }
        assertFalse(store.folderOf(cli.sha256).exists())

        stall = null
        assertNull(withTimeout(30.seconds) { store.provide(listOf(RuntimePiece.Program(cli, "modlock")), emptySet()) {} })
        assertTrue(store.folderOf(cli.sha256).resolve("modlock").exists())
        assertEquals(emptyList(), leftovers())
    }
}
