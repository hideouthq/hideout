package gg.hideouthq.appupdate

import gg.hideouthq.core.appupdate.AppVersion
import gg.hideouthq.core.appupdate.PackageDownload
import gg.hideouthq.core.appupdate.PackageFormat
import gg.hideouthq.core.appupdate.ReleasePackage
import gg.hideouthq.core.appupdate.UpdateFailure
import gg.hideouthq.data.desktopEngine
import gg.hideouthq.library.DesktopInstall
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.request.header
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class PackageCacheTest {
    private val bytes = Random(7).nextBytes(1 shl 20)
    private val half = bytes.size / 2
    private val folder: Path = Files.createTempDirectory("hideout-package-cache").resolve("app-update")
    private val requests = CopyOnWriteArrayList<String?>()
    private val partSent = CompletableDeferred<Unit>()

    private var answer: suspend ApplicationCall.() -> Unit = { serve(bytes) }

    private val server = embeddedServer(Netty, port = 0, host = "127.0.0.1") {
        routing {
            get("/package") {
                requests += call.request.header(HttpHeaders.Range)
                answer(call)
            }
            get("/moved") { call.respondRedirect("/package") }
        }
    }.start(wait = false)

    private val base = "http://127.0.0.1:${runBlocking { server.engine.resolvedConnectors().single().port }}"
    private val client = DesktopInstall.downloadClient(desktopEngine(), "test")
    private val cache = PackageCache(folder, client)

    @AfterTest
    fun cleanUp() {
        client.close()
        server.stop(0, 0)
        folder.parent.toFile().deleteRecursively()
    }

    private fun pkg(url: String = "$base/package", size: Long = bytes.size.toLong(), sha: String = sha256(bytes), format: PackageFormat = PackageFormat.AppImage) =
        ReleasePackage(AppVersion(1, 2, 0), format, url, size, sha)

    private fun part(pkg: ReleasePackage = pkg()): Path = folder.resolve("${pkg.sha256}.part")

    private fun seedPart(content: ByteArray, pkg: ReleasePackage = pkg()) {
        Files.createDirectories(folder)
        Files.write(part(pkg), content)
    }

    private suspend fun ApplicationCall.serve(file: ByteArray) {
        val start = request.header(HttpHeaders.Range)?.removePrefix("bytes=")?.removeSuffix("-")?.toInt()
        if (start == null) return respondBytes(file, ContentType.Application.OctetStream)
        if (start >= file.size) return respondBytes(ByteArray(0), status = HttpStatusCode.RequestedRangeNotSatisfiable)
        response.header(HttpHeaders.ContentRange, "bytes $start-${file.size - 1}/${file.size}")
        respondBytes(file.copyOfRange(start, file.size), ContentType.Application.OctetStream, HttpStatusCode.PartialContent)
    }

    private fun download(pkg: ReleasePackage = pkg(), progress: (Long) -> Unit = {}) = runBlocking { withTimeout(30.seconds) { cache.download(pkg, progress) } }

    private fun assertPublished(pkg: ReleasePackage = pkg()) {
        assertContentEquals(bytes, Files.readAllBytes(cache.file(pkg)))
        assertFalse(Files.exists(part(pkg)), "no part stays behind")
        assertTrue(runBlocking { cache.probe(pkg) })
    }

    private fun assertNothingKept(pkg: ReleasePackage = pkg()) {
        assertFalse(Files.exists(part(pkg)), "the part is deleted")
        assertFalse(Files.exists(cache.file(pkg)), "nothing is published")
    }

    @Test
    fun aDownloadIsPublishedUnderItsHashWithTheExtensionItsToolNeeds() {
        assertEquals(PackageDownload.Verified, download())
        assertPublished()
        assertEquals(folder.resolve("${sha256(bytes)}.AppImage"), cache.file(pkg()))
        assertEquals(listOf<String?>(null), requests.toList())
        assertEquals("msi", cache.file(pkg(format = PackageFormat.Msi)).toString().substringAfterLast('.'))
        assertEquals("deb", cache.file(pkg(format = PackageFormat.Deb)).toString().substringAfterLast('.'))
        assertEquals("rpm", cache.file(pkg(format = PackageFormat.Rpm)).toString().substringAfterLast('.'))
    }

    @Test
    fun progressCountsUpToTheWholeFile() {
        val seen = mutableListOf<Long>()
        download { seen += it }
        assertEquals(bytes.size.toLong(), seen.last())
        assertEquals(seen.sorted(), seen)
    }

    @Test
    fun aPartResumesWithARangeAndProgressStartsFromIt() {
        seedPart(bytes.copyOfRange(0, half))
        val seen = mutableListOf<Long>()

        assertEquals(PackageDownload.Verified, download { seen += it })

        assertEquals(listOf<String?>("bytes=$half-"), requests.toList())
        assertEquals(half.toLong(), seen.first())
        assertPublished()
    }

    @Test
    fun aServerThatIgnoresTheRangeStartsTheFileAgain() {
        seedPart(bytes.copyOfRange(0, half))
        answer = { respondBytes(bytes, ContentType.Application.OctetStream) }

        assertEquals(PackageDownload.Verified, download())

        assertEquals(listOf<String?>("bytes=$half-"), requests.toList())
        assertPublished()
    }

    @Test
    fun aRangeThatStartsElsewhereIsNotAppendedAndTheFileStartsAgain() {
        seedPart(bytes.copyOfRange(0, half))
        answer = {
            if (request.header(HttpHeaders.Range) == null) {
                serve(bytes)
            } else {
                response.header(HttpHeaders.ContentRange, "bytes 0-${bytes.size - 1}/${bytes.size}")
                respondBytes(bytes, ContentType.Application.OctetStream, HttpStatusCode.PartialContent)
            }
        }

        assertEquals(PackageDownload.Verified, download())

        assertEquals(listOf("bytes=$half-", null), requests.toList())
        assertPublished()
    }

    @Test
    fun aRangeTheServerCannotSatisfyStartsTheFileAgain() {
        seedPart(bytes.copyOfRange(0, half))
        answer = {
            if (request.header(HttpHeaders.Range) == null) serve(bytes) else respondBytes(ByteArray(0), status = HttpStatusCode.RequestedRangeNotSatisfiable)
        }

        assertEquals(PackageDownload.Verified, download())
        assertPublished()
    }

    @Test
    fun aFileShorterThanTheFeedSaysIsAMismatch() {
        answer = { respondBytes(bytes.copyOfRange(0, half), ContentType.Application.OctetStream) }

        assertEquals(PackageDownload.Failed(UpdateFailure.Mismatch), download())
        assertNothingKept()
    }

    @Test
    fun aFileLongerThanTheFeedSaysIsAMismatch() {
        answer = { respondBytes(bytes + 1, ContentType.Application.OctetStream) }

        assertEquals(PackageDownload.Failed(UpdateFailure.Mismatch), download())
        assertNothingKept()
    }

    @Test
    fun aFileWithTheWrongHashIsAMismatch() {
        val pkg = pkg(sha = "0".repeat(64))

        assertEquals(PackageDownload.Failed(UpdateFailure.Mismatch), download(pkg))
        assertNothingKept(pkg)
    }

    @Test
    fun aCorruptPartIsThrownAwayAndTheFileFetchedWhole() {
        seedPart(ByteArray(half) { 1 })

        assertEquals(PackageDownload.Verified, download())

        assertEquals(listOf("bytes=$half-", null), requests.toList())
        assertPublished()
    }

    @Test
    fun aCorruptPartOfTheFullSizeIsThrownAwayToo() {
        seedPart(ByteArray(bytes.size) { 1 })

        assertEquals(PackageDownload.Verified, download())

        assertEquals(listOf<String?>(null), requests.toList())
        assertPublished()
    }

    @Test
    fun aPartLongerThanThePackageIsThrownAway() {
        seedPart(ByteArray(bytes.size + 5) { 1 })

        assertEquals(PackageDownload.Verified, download())

        assertEquals(listOf<String?>(null), requests.toList())
        assertPublished()
    }

    @Test
    fun aWholePartThatIsGoodIsPublishedWithNoRequest() {
        seedPart(bytes)

        assertEquals(PackageDownload.Verified, download())

        assertEquals(emptyList(), requests.toList())
        assertPublished()
    }

    @Test
    fun aRedirectIsFollowedWithItsRange() {
        seedPart(bytes.copyOfRange(0, half))
        val pkg = pkg(url = "$base/moved")

        assertEquals(PackageDownload.Verified, download(pkg))

        assertEquals(listOf<String?>("bytes=$half-"), requests.toList())
        assertPublished(pkg)
    }

    @Test
    fun aServerErrorKeepsThePart() {
        seedPart(bytes.copyOfRange(0, half))
        answer = { respondBytes(ByteArray(0), status = HttpStatusCode.ServiceUnavailable) }

        assertEquals(PackageDownload.Failed(UpdateFailure.Server), download())
        assertEquals(half.toLong(), Files.size(part()))
    }

    @Test
    fun aMissingFileIsTheServersFailure() {
        answer = { respondBytes(ByteArray(0), status = HttpStatusCode.NotFound) }

        assertEquals(PackageDownload.Failed(UpdateFailure.Server), download())
    }

    @Test
    fun noServerIsAConnectionFailure() {
        val nobody = ServerSocket(0).use { it.localPort }

        assertEquals(PackageDownload.Failed(UpdateFailure.Connection), download(pkg(url = "http://127.0.0.1:$nobody/package")))
    }

    @Test
    fun aDroppedConnectionKeepsWhatArrivedAndTheNextTryResumes() {
        val dropping = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        thread {
            dropping.use { listener ->
                listener.accept().use { socket ->
                    val request = socket.getInputStream().bufferedReader()
                    while (request.readLine().orEmpty().isNotEmpty()) Unit
                    socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: ${bytes.size}\r\n\r\n".toByteArray() + bytes.copyOfRange(0, half))
                    socket.getOutputStream().flush()
                }
            }
        }

        assertEquals(PackageDownload.Failed(UpdateFailure.Connection), download(pkg(url = "http://127.0.0.1:${dropping.localPort}/package")))

        val kept = Files.size(part()).toInt()
        assertTrue(kept in 1..half, "$kept bytes")
        assertEquals(PackageDownload.Verified, download())
        assertEquals("bytes=$kept-", requests.last())
        assertPublished()
    }

    @Test
    fun aFolderThatCannotBeMadeIsADiskFailure() {
        val blocked = Files.createFile(folder.parent.resolve("a file"))
        val pkg = pkg()

        val result = runBlocking { PackageCache(blocked.resolve("app-update"), client).download(pkg) {} }

        assertEquals(PackageDownload.Failed(UpdateFailure.Disk), result)
    }

    @Test
    fun aUrlTheClientCannotReadIsAFailureNotAThrow() {
        assertEquals(PackageDownload.Failed(UpdateFailure.Server), download(pkg(url = "http://[not a host/")))
    }

    @Test
    fun cancellingKeepsThePartAndTheNextTryResumes() = runBlocking {
        answer = {
            respondBytesWriter(ContentType.Application.OctetStream, contentLength = bytes.size.toLong()) {
                writeFully(bytes, 0, half)
                flush()
                partSent.complete(Unit)
                awaitCancellation()
            }
        }
        val arrived = CompletableDeferred<Unit>()
        val running = async(Dispatchers.Default) { cache.download(pkg()) { if (it > 0) arrived.complete(Unit) } }
        withTimeout(30.seconds) {
            partSent.await()
            arrived.await()
        }

        running.cancelAndJoin()

        val kept = Files.size(part()).toInt()
        assertTrue(kept > 0, "the bytes that arrived stay")
        assertFalse(Files.exists(cache.file(pkg())))
        answer = { serve(bytes) }
        assertEquals(PackageDownload.Verified, cache.download(pkg()) {})
        assertEquals("bytes=$kept-", requests.last())
        assertPublished()
    }

    @Test
    fun aDownloadRunsOffTheCallersThread() {
        val caller = Thread.currentThread()
        val threads = mutableSetOf<Thread>()
        download { threads += Thread.currentThread() }
        assertFalse(caller in threads)
    }

    @Test
    fun probeNeedsBothTheSizeAndTheHash() = runBlocking {
        assertFalse(cache.probe(pkg()), "nothing there")
        Files.createDirectories(folder)
        Files.write(cache.file(pkg()), bytes.copyOfRange(0, half))
        assertFalse(cache.probe(pkg()), "wrong size")
        Files.write(cache.file(pkg()), ByteArray(bytes.size) { 1 })
        assertFalse(cache.probe(pkg()), "wrong hash")
        Files.write(cache.file(pkg()), bytes)
        assertTrue(cache.probe(pkg()))
    }

    @Test
    fun keepOnlyKeepsThatPackageAndItsPartAndDeletesOtherPackagesAndTheHelper() = runBlocking {
        val other = pkg(sha = "b".repeat(64))
        val thirdPart = folder.resolve("${"c".repeat(64)}.part")
        val helper = folder.resolve(FinishHelperName)
        val log = folder.resolve("finish-update.log")
        Files.createDirectories(folder)
        listOf(cache.file(pkg()), part(), cache.file(other), part(other), thirdPart, helper, log).forEach { Files.writeString(it, "x") }

        cache.keepOnly(pkg())

        assertTrue(Files.exists(cache.file(pkg())))
        assertTrue(Files.exists(part()), "resume needs it")
        assertTrue(Files.exists(log), "only a package or the helper is a leftover")
        assertFalse(Files.exists(cache.file(other)))
        assertFalse(Files.exists(part(other)))
        assertFalse(Files.exists(thirdPart))
        assertFalse(Files.exists(helper))
    }

    @Test
    fun keepOnlyNothingDeletesEveryPackage() = runBlocking {
        Files.createDirectories(folder)
        listOf(cache.file(pkg()), part()).forEach { Files.writeString(it, "x") }

        cache.keepOnly(null)

        assertNothingKept()
    }

    @Test
    fun keepOnlyWithNoFolderDoesNothing() = runBlocking {
        cache.keepOnly(null)
        assertFalse(Files.exists(folder))
    }

    private fun sha256(content: ByteArray) = MessageDigest.getInstance("SHA-256").digest(content).toHexString()
}
