package gg.hideouthq.library

import gg.hideouthq.core.data.ConfigRead
import gg.hideouthq.core.data.ConfigRecovery
import gg.hideouthq.core.data.DownloadEntry
import gg.hideouthq.core.data.DownloadFolder
import gg.hideouthq.core.data.DownloadProblem
import gg.hideouthq.core.data.FolderChoice
import gg.hideouthq.core.data.FolderRefusal
import gg.hideouthq.core.data.DownloadProgress
import gg.hideouthq.core.data.DownloadResult
import gg.hideouthq.core.data.EntryState
import gg.hideouthq.core.data.LinkResult
import gg.hideouthq.core.data.ProcessScan
import gg.hideouthq.core.data.Removal
import gg.hideouthq.core.data.RemoteFile
import gg.hideouthq.core.data.ReplaceResult
import gg.hideouthq.core.library.ArchiveIdentity
import gg.hideouthq.core.library.ChangeToken
import gg.hideouthq.core.library.ConfigTarget
import gg.hideouthq.core.library.HideoutLines
import gg.hideouthq.core.library.PendingChange
import gg.hideouthq.core.library.GameRoot
import gg.hideouthq.core.library.Manager
import gg.hideouthq.core.library.Sha256
import gg.hideouthq.core.library.SubmissionIdentity
import gg.hideouthq.core.library.SubmissionKind
import gg.hideouthq.core.library.SubmissionRef
import gg.hideouthq.core.network.BusyHosts
import gg.hideouthq.core.network.tooManyWait
import gg.hideouthq.preview.sha256Hex
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.cancel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.DosFileAttributes
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DesktopPortsTest {
    private val home: Path = Files.createTempDirectory(Files.createDirectories(Path.of("build", "port-tests")), "home").toRealPath()
    private val root = GameRoot(home.resolve("Deadlock").toString())
    private val citadel = home.resolve("Deadlock/game/citadel").createDirectories()
    private val original = "GameInfo { FileSystem { SearchPaths {\n Game citadel\n } } }\n".encodeToByteArray()
    private val patched = "GameInfo { FileSystem { SearchPaths {\n // Hideout start\n Game citadel/addons\n // Hideout end\n Game citadel\n } } }\n".encodeToByteArray()

    private fun edit(token: String, from: ByteArray?, to: ByteArray, target: ConfigTarget = ConfigTarget.GameInfo(HideoutLines(mods = true, customGames = false))) =
        PendingChange.ConfigEdit(root, target, token, from?.let(Sha256::image), Sha256.image(to))

    private fun names(folder: Path = citadel) = Files.list(folder).use { paths -> paths.map { it.fileName.toString() }.toList().toSet() }

    @Test
    fun gameInfoIsReplacedWithoutLeftovers() = runBlocking<Unit> {
        val config = FileGameConfig()
        citadel.resolve("gameinfo.gi").writeBytes(original)
        val read = assertIs<ConfigRead.Present>(config.read(root))
        assertEquals(Sha256.image(original), read.image)

        assertEquals(ReplaceResult.Replaced, config.replace(edit("t1", original, patched), patched))
        assertContentEquals(patched, citadel.resolve("gameinfo.gi").readBytes())
        assertEquals(setOf("gameinfo.gi"), names())
        assertEquals(ReplaceResult.Changed, config.replace(edit("t2", original, original), original), "the file is no longer the expected one")
        assertContentEquals(patched, citadel.resolve("gameinfo.gi").readBytes())
    }

    @Test
    fun onWindowsAConfigMarkedReadOnlyIsMarkedAgainOnceReplaced() = runBlocking<Unit> {
        val gameInfo = citadel.resolve("gameinfo.gi").also { it.writeBytes(original) }
        Files.setAttribute(gameInfo, "dos:readonly", true)
        fun readOnly() = Files.readAttributes(gameInfo, DosFileAttributes::class.java).isReadOnly
        assumeTrue("this file system keeps DOS attributes", readOnly())

        assertEquals(ReplaceResult.Replaced, FileGameConfig(windows = true).replace(edit("t1", original, patched), patched))
        assertContentEquals(patched, gameInfo.readBytes())
        assertTrue(readOnly(), "the player's mark stays on the new file")
        assertEquals(setOf("gameinfo.gi"), names())

        Files.setAttribute(gameInfo, "dos:readonly", false)
        assertEquals(ReplaceResult.Replaced, FileGameConfig(windows = true).replace(edit("t2", patched, original), original))
        assertFalse(readOnly(), "a file that was not marked is not marked")
    }

    @Test
    fun aWriterThatGetsInDuringTheReplacementWinsAndTheEarlierFileIsKept() = runBlocking<Unit> {
        val steams = "// steam's new file\n".encodeToByteArray()
        val config = FileGameConfig(afterDisplace = { current -> current.writeBytes(steams) })
        citadel.resolve("gameinfo.gi").writeBytes(original)
        assertIs<ReplaceResult.Conflict>(config.replace(edit("t1", original, patched), patched))
        assertContentEquals(steams, citadel.resolve("gameinfo.gi").readBytes())
        assertContentEquals(original, citadel.resolve("gameinfo.gi.hideout-displaced-t1").readBytes())
        assertTrue(!citadel.resolve("gameinfo.gi.hideout-next-t1").exists())
    }

    @Test
    fun anInterruptedReplacementIsSettledFromWhatIsOnDisk() = runBlocking<Unit> {
        val config = FileGameConfig()
        citadel.resolve("gameinfo.gi.hideout-displaced-t1").writeBytes(original)
        citadel.resolve("gameinfo.gi.hideout-next-t1").writeBytes(patched)
        assertEquals(ConfigRecovery.Undone(), config.recover(edit("t1", original, patched)))
        assertContentEquals(original, citadel.resolve("gameinfo.gi").readBytes())
        assertEquals(setOf("gameinfo.gi"), names())

        citadel.resolve("gameinfo.gi").writeBytes(patched)
        citadel.resolve("gameinfo.gi.hideout-displaced-t2").writeBytes(original)
        assertEquals(ConfigRecovery.Applied(), config.recover(edit("t2", original, patched)))
        assertEquals(setOf("gameinfo.gi"), names())

        citadel.resolve("gameinfo.gi").writeBytes(byteArrayOf(1, 2, 3))
        assertIs<ConfigRecovery.Conflict>(config.recover(edit("t3", original, patched)))
        assertContentEquals(byteArrayOf(1, 2, 3), citadel.resolve("gameinfo.gi").readBytes())
    }

    @Test
    fun autoexecIsWrittenWhereNoneWasCfgIncludedAndReplacedLikeGameInfo() = runBlocking<Unit> {
        val config = FileGameConfig()
        val cfg = citadel.resolve("cfg")
        val first = "fps_max 240\n".encodeToByteArray()
        val second = "fps_max 300\n".encodeToByteArray()
        assertEquals(null, config.prepare(root, ConfigTarget.Autoexec), "a fresh install's cfg is made before anything is journaled")
        assertEquals(ReplaceResult.Replaced, config.replace(edit("a1", null, first, ConfigTarget.Autoexec), first))
        assertContentEquals(first, cfg.resolve("autoexec.cfg").readBytes())
        assertEquals(setOf("autoexec.cfg"), names(cfg))
        assertEquals(ReplaceResult.Changed, config.replace(edit("a2", null, second, ConfigTarget.Autoexec), second), "a file is there now")
        assertEquals(ReplaceResult.Replaced, config.replace(edit("a3", first, second, ConfigTarget.Autoexec), second))
        assertContentEquals(second, cfg.resolve("autoexec.cfg").readBytes())
        assertEquals(setOf("autoexec.cfg"), names(cfg))
        assertEquals(setOf("cfg"), names(), "nothing is written beside gameinfo.gi")
    }

    @Test
    fun aReplacementIsMadeOnceItIsPublishedThoughWhatIsLeftBesideItCannotBeTidiedAway() = runBlocking<Unit> {
        val cfg = citadel.resolve("cfg").createDirectories()
        val first = "fps_max 240\n".encodeToByteArray()
        val second = "fps_max 300\n".encodeToByteArray()
        cfg.resolve("autoexec.cfg").writeBytes(first)
        val config = FileGameConfig(createLink = { link, existing ->
            Files.createLink(link, existing)
            if (link.fileName.toString() == "autoexec.cfg") Files.setPosixFilePermissions(cfg, PosixFilePermissions.fromString("r-xr-xr-x"))
        })
        try {
            val replaced = config.replace(edit("a1", first, second, ConfigTarget.Autoexec), second)
            assumeTrue("a superuser can write anywhere", !Files.isWritable(cfg))
            assertEquals(ReplaceResult.Replaced, replaced)
            assertContentEquals(second, cfg.resolve("autoexec.cfg").readBytes())
        } finally {
            Files.setPosixFilePermissions(cfg, PosixFilePermissions.fromString("rwxr-xr-x"))
        }
    }

    @Test
    fun aCfgThatIsALinkOrCannotBeMadeIsRefusedBeforeAnythingIsWritten() = runBlocking<Unit> {
        val config = FileGameConfig()
        val elsewhere = home.resolve("elsewhere").createDirectories()
        Files.createSymbolicLink(citadel.resolve("cfg"), elsewhere)
        assertNotNull(config.prepare(root, ConfigTarget.Autoexec), "a linked cfg")
        Files.delete(citadel.resolve("cfg"))

        Files.setPosixFilePermissions(citadel, PosixFilePermissions.fromString("r-xr-xr-x"))
        try {
            assumeTrue("a superuser can write anywhere", !Files.isWritable(citadel))
            assertNotNull(config.prepare(root, ConfigTarget.Autoexec), "a cfg that cannot be made")
        } finally {
            Files.setPosixFilePermissions(citadel, PosixFilePermissions.fromString("rwxr-xr-x"))
        }
        assertEquals(emptyList(), Files.list(elsewhere).use { it.toList() })
    }

    @Test
    fun anInterruptedFirstAutoexecIsSettledFromWhatIsOnDisk() = runBlocking<Unit> {
        val config = FileGameConfig()
        val commands = "fps_max 240\n".encodeToByteArray()
        assertEquals(ConfigRecovery.Undone(), config.recover(edit("a0", null, commands, ConfigTarget.Autoexec)), "no file and no cfg, where none was")
        val cfg = citadel.resolve("cfg").createDirectories()
        cfg.resolve("autoexec.cfg.hideout-next-a1").writeBytes(commands)
        assertEquals(ConfigRecovery.Undone(), config.recover(edit("a1", null, commands, ConfigTarget.Autoexec)), "no file is where none was")
        assertEquals(emptySet(), names(cfg))

        cfg.resolve("autoexec.cfg").writeBytes(commands)
        assertEquals(ConfigRecovery.Applied(), config.recover(edit("a2", null, commands, ConfigTarget.Autoexec)))

        cfg.resolve("autoexec.cfg").writeBytes(byteArrayOf(1, 2, 3))
        assertIs<ConfigRecovery.Conflict>(config.recover(edit("a3", null, commands, ConfigTarget.Autoexec)))
        assertContentEquals(byteArrayOf(1, 2, 3), cfg.resolve("autoexec.cfg").readBytes())
    }

    @Test
    fun anEditOfAutoexecIsNoticedByItsWatcher() = runBlocking<Unit> {
        val cfg = citadel.resolve("cfg").createDirectories()
        val config = FileGameConfig()
        withTimeout(10_000) {
            val autoexec = async { config.changes(root, ConfigTarget.Autoexec.path).first() }
            delay(300)
            Files.write(cfg.resolve("autoexec.cfg"), "fps_max 240\n".encodeToByteArray())
            autoexec.await()
        }
    }

    @Test
    fun aWatchThatCannotBeRegisteredFailsSoItIsTriedAgain() = runBlocking<Unit> {
        val cfg = citadel.resolve("cfg").createDirectories()
        Files.setPosixFilePermissions(cfg, PosixFilePermissions.fromString("-wx------"))
        try {
            assumeTrue("a folder that cannot be read", !Files.isReadable(cfg))
            val failure = withTimeoutOrNull(5_000) { runCatching { FileGameConfig().changes(root, ConfigTarget.Autoexec.path).first() }.exceptionOrNull() }
            assertIs<IOException>(failure, "a watch that cannot start fails rather than staying silent")
        } finally {
            Files.setPosixFilePermissions(cfg, PosixFilePermissions.fromString("rwx------"))
        }
    }

    private val ivy = SubmissionRef(SubmissionKind.Mod, 628_739)
    private val body = ByteArray(300_000) { (it % 251).toByte() }
    private val md5 = MessageDigest.getInstance("MD5").digest(body).toHexString()
    private fun file(md5: String? = this.md5, size: Long = body.size.toLong()) =
        RemoteFile(1_691_864, "bg_ivy.zip", size, md5, null, 1, 1, "https://gamebanana.com/dl/1691864")

    private fun downloader(engine: MockEngine, folder: Path = home.resolve("downloads"), attempts: Int = 4, busy: BusyHosts = BusyHosts(), stallNotice: Duration = 200.milliseconds, progressEvery: Duration = ProgressEvery) =
        HttpDownloader(
            HttpClient(engine) { expectSuccess = false }, folder, hideoutAreas = listOf(home.resolve("data")), attempts = attempts, backoff = { Duration.ZERO },
            busy = busy, stallNotice = stallNotice, progressEvery = progressEvery,
            busyWait = { retryAfter, tries -> tooManyWait(retryAfter, tries)?.div(BusyScale) },
        )

    private suspend fun download(
        engine: MockEngine,
        file: RemoteFile,
        folder: Path = home.resolve("downloads"),
        downloader: HttpDownloader = downloader(engine, folder),
        onProgress: (DownloadProgress) -> Unit = {},
    ): DownloadResult = downloader.download(downloader.defaultFolder, ivy, file, onProgress = onProgress)

    @Test
    fun aDownloadIsCheckedByItsSizeAndMd5AndNamedForItsFile() = runBlocking<Unit> {
        val progress = mutableListOf<DownloadProgress>()
        val result = download(MockEngine { respond(body) }, file()) { progress += it }
        val archive = assertIs<DownloadResult.Done>(result).archive
        assertEquals(home.resolve("downloads/mod-628739/1691864/bg_ivy.zip").toString(), archive.path)
        assertEquals(body.size.toLong() to sha256Hex(body), archive.sizeBytes to archive.sha256)
        assertEquals(DownloadProgress(body.size.toLong(), body.size.toLong()), progress.last())
        assertEquals(listOf("bg_ivy.zip"), Files.list(home.resolve("downloads/mod-628739/1691864")).use { p -> p.map { it.fileName.toString() }.toList() }, "no part file is left")
    }

    @Test
    fun theArchiveIsAcknowledgedOnceItHasItsNameSoACallerCancelledBeforeTheResultStillLearnsOfIt() = runBlocking<Unit> {
        val downloader = downloader(MockEngine { respond(body) })
        var acknowledged: ArchiveIdentity? = null
        var returned: DownloadResult? = null
        val caller = launch(Dispatchers.Default) {
            val self = coroutineContext.job
            returned = downloader.download(downloader.defaultFolder, ivy, file(), onPublished = { archive ->
                acknowledged = archive
                self.cancel()
            }) {}
        }
        caller.join()

        assertTrue(caller.isCancelled)
        assertEquals(null, returned, "the cancelled caller never saw the result")
        val archive = assertNotNull(acknowledged)
        assertEquals(home.resolve("downloads/mod-628739/1691864/bg_ivy.zip").toString(), archive.path)
        assertEquals(body.size.toLong() to sha256Hex(body), archive.sizeBytes to archive.sha256)
        assertContentEquals(body, Path.of(archive.path).readBytes())
        assertEquals(listOf("bg_ivy.zip"), Files.list(home.resolve("downloads/mod-628739/1691864")).use { p -> p.map { it.fileName.toString() }.toList() }, "no part file is left")
    }

    @Test
    fun aStoppedTransferKeepsWhatItGotForTheNextTryUntilItIsDiscardedWithItsFolders() = runBlocking<Unit> {
        val sent = CompletableDeferred<Unit>()
        val downloader = downloader(MockEngine {
            val channel = ByteChannel()
            launch {
                channel.writeFully(body.copyOf(100_000))
                channel.flush()
                sent.complete(Unit)
            }
            respond(channel, HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, "${body.size}"))
        })
        var acknowledged: ArchiveIdentity? = null
        val caller = launch(Dispatchers.Default) { downloader.download(downloader.defaultFolder, ivy, file(), onPublished = { acknowledged = it }) {} }
        sent.await()
        val part = home.resolve("downloads/mod-628739/1691864/download.part")
        withTimeout(5_000) { while (!part.exists() || Files.size(part) < 100_000) delay(20) }
        caller.cancelAndJoin()

        assertEquals(null, acknowledged)
        assertEquals(100_000, Files.size(part), "what came stays for the next try, or for the next start")
        downloader.discardUnfinished(downloader.defaultFolder, ivy, 1_691_864)
        assertFalse(home.resolve("downloads/mod-628739").exists(), "the part and the folders it alone kept are gone")
    }

    @Test
    fun aStalledTransferSaysSoWithinSecondsAndItsRetryGoesOnFromTheBytesOnDisk() = runBlocking<Unit> {
        val ranges = mutableListOf<String?>()
        val engine = MockEngine { request ->
            ranges += request.headers[HttpHeaders.Range]
            if (ranges.size == 1) {
                val channel = ByteChannel()
                launch {
                    channel.writeFully(body.copyOf(100_000))
                    channel.flush()
                    delay(800)
                    channel.cancel(IOException("Read timed out"))
                }
                respond(channel, HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength to listOf("${body.size}"), HttpHeaders.AcceptRanges to listOf("bytes")))
            } else {
                respond(body.copyOfRange(100_000, body.size), HttpStatusCode.PartialContent, headersOf(HttpHeaders.ContentRange, "bytes 100000-299999/300000"))
            }
        }
        val progress = mutableListOf<DownloadProgress>()
        assertIs<DownloadResult.Done>(download(engine, file(), downloader = downloader(engine)) { synchronized(progress) { progress += it } })

        val stalled = progress.indexOfFirst { it.stalled }
        assertTrue(stalled >= 0, "it said it stalled: $progress")
        assertEquals(100_000, progress[stalled].doneBytes, "at the bytes it had")
        assertTrue(progress.drop(stalled + 1).any { !it.stalled && it.doneBytes > 100_000 }, "and moved on once bytes came again")
        assertEquals(listOf(null, "bytes=100000-"), ranges)
    }

    @Test
    fun aSlowTransferReportsEverySecondEvenBetweenProgressSteps() = runBlocking<Unit> {
        val progressEvery = ProgressEvery / 10
        val engine = MockEngine {
            val channel = ByteChannel()
            launch {
                for (start in listOf(0, 10_000)) {
                    channel.writeFully(body.copyOfRange(start, start + 10_000))
                    channel.flush()
                    delay(progressEvery * 1.1)
                }
                channel.writeFully(body.copyOfRange(20_000, body.size))
                channel.close()
            }
            respond(channel, HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength to listOf("${body.size}")))
        }
        val progress = mutableListOf<DownloadProgress>()
        assertIs<DownloadResult.Done>(download(engine, file(), downloader = downloader(engine, stallNotice = 10.seconds, progressEvery = progressEvery)) { synchronized(progress) { progress += it } })

        assertTrue(progress.any { it.doneBytes in 1..<256 * 1024 && !it.stalled }, "it moved before the first 256 KB: $progress")
    }

    @Test
    fun tryAgainAfterTheLastAttemptFailedAsksForTheRestFromWhatThePartHolds() = runBlocking<Unit> {
        val ranges = mutableListOf<String?>()
        val engine = MockEngine { request ->
            ranges += request.headers[HttpHeaders.Range]
            if (ranges.size == 1) {
                val channel = ByteChannel()
                launch {
                    channel.writeFully(body.copyOf(100_000))
                    channel.flush()
                    delay(300)
                    channel.cancel(IOException("Connection reset"))
                }
                respond(channel, HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength to listOf("${body.size}"), HttpHeaders.AcceptRanges to listOf("bytes")))
            } else respond(body.copyOfRange(100_000, body.size), HttpStatusCode.PartialContent, headersOf(HttpHeaders.ContentRange, "bytes 100000-299999/300000"))
        }
        val once = downloader(engine, attempts = 1)
        val failed = assertIs<DownloadResult.Failed>(once.download(once.defaultFolder, ivy, file()) {})
        assertEquals(true, assertIs<DownloadProblem.Network>(failed.problem).resumable, "the server serves ranges, so Try again goes on")
        assertEquals(100_000, Files.size(home.resolve("downloads/mod-628739/1691864/download.part")))

        assertIs<DownloadResult.Done>(once.download(once.defaultFolder, ivy, file()) {})
        assertEquals(listOf(null, "bytes=100000-"), ranges)
        assertEquals(listOf("bg_ivy.zip"), Files.list(home.resolve("downloads/mod-628739/1691864")).use { p -> p.map { it.fileName.toString() }.toList() })
    }

    @Test
    fun aHostThatAsksForTimeHoldsTheDownloadUntilThenAndSaysUntilWhen() = runBlocking<Unit> {
        var calls = 0
        val busy = BusyHosts()
        val engine = MockEngine {
            if (++calls == 1) respond("slow down", HttpStatusCode.TooManyRequests, headersOf(HttpHeaders.RetryAfter, "1")) else respond(body)
        }
        val progress = mutableListOf<DownloadProgress>()
        val held = downloader(engine, attempts = 1, busy = busy)
        assertIs<DownloadResult.Done>(held.download(held.defaultFolder, ivy, file()) { progress += it })
        assertEquals(2, calls, "the wait is not one of the attempts")
        assertNotNull(progress.firstNotNullOfOrNull { it.busyUntil }, "it said until when")
        assertEquals(emptyMap(), busy.hosts.value)
    }

    @Test
    fun aHostThatAsksForNoTimeIsAskedAgainAfterABackoffNotAtOnce() = runBlocking<Unit> {
        val sent = mutableListOf<Long>()
        val engine = MockEngine {
            sent += System.nanoTime()
            if (sent.size == 1) respond("slow down", HttpStatusCode.TooManyRequests, headersOf(HttpHeaders.RetryAfter, "0")) else respond(body)
        }
        val held = downloader(engine, attempts = 1)
        assertIs<DownloadResult.Done>(held.download(held.defaultFolder, ivy, file()) {})
        assertEquals(2, sent.size)
        assertTrue((sent[1] - sent[0]).nanoseconds >= 2.seconds / BusyScale, "it waited the backoff")
    }

    @Test
    fun aHostThatAsksForADayFailsTheDownloadAsBusyAndKeepsWhatItGot() = runBlocking<Unit> {
        var calls = 0
        val busy = BusyHosts()
        val engine = MockEngine { request ->
            calls++
            if (request.headers[HttpHeaders.Range] == null) {
                val channel = ByteChannel()
                launch {
                    channel.writeFully(body.copyOf(100_000))
                    channel.flush()
                    delay(300)
                    channel.cancel(IOException("Connection reset"))
                }
                respond(channel, HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength to listOf("${body.size}"), HttpHeaders.AcceptRanges to listOf("bytes")))
            } else respond("slow down", HttpStatusCode.TooManyRequests, headersOf(HttpHeaders.RetryAfter, "86400"))
        }
        val held = downloader(engine, attempts = 2, busy = busy)
        assertEquals(DownloadResult.Failed(DownloadProblem.Http(429)), held.download(held.defaultFolder, ivy, file()) {})
        assertEquals(2, calls, "asked once more, not held for a day")
        assertEquals(emptyMap(), busy.hosts.value)
        assertEquals(100_000, Files.size(home.resolve("downloads/mod-628739/1691864/download.part")), "Try again goes on from it")
    }

    @Test
    fun aFileWithoutAPublishedMd5IsCheckedBySizeAlone() = runBlocking<Unit> {
        assertIs<DownloadResult.Done>(download(MockEngine { respond(body) }, file(md5 = null)))
        assertEquals(
            DownloadResult.Failed(DownloadProblem.SizeMismatch(body.size + 1L, body.size.toLong())),
            download(MockEngine { respond(body) }, file(md5 = null, size = body.size + 1L), home.resolve("other")),
        )
    }

    @Test
    fun aWrongMd5IsFetchedOnceMoreThenRefused() = runBlocking<Unit> {
        var calls = 0
        val wrong = "0".repeat(32)
        assertEquals(DownloadResult.Failed(DownloadProblem.Md5Mismatch(wrong, md5)), download(MockEngine { calls++; respond(body) }, file(md5 = wrong)))
        assertEquals(2, calls)
        assertFalse(home.resolve("downloads/mod-628739").exists(), "nothing is kept of a file that can never be used, not even its folders")
    }

    @Test
    fun serverErrorsAreRetriedAndADroppedConnectionResumesFromWhereItStopped() = runBlocking<Unit> {
        var calls = 0
        val ranges = mutableListOf<String?>()
        val engine = MockEngine { request ->
            calls++
            ranges += request.headers[HttpHeaders.Range]
            when (calls) {
                1 -> respondError(HttpStatusCode.BadGateway)
                2 -> respond(body.copyOf(100_000))
                else -> respond(body.copyOfRange(100_000, body.size), HttpStatusCode.PartialContent, headersOf(HttpHeaders.ContentRange, "bytes 100000-299999/300000"))
            }
        }
        assertIs<DownloadResult.Done>(download(engine, file()))
        assertEquals(listOf(null, null, "bytes=100000-"), ranges)
    }

    private fun CoroutineScope.cutOff(whole: ByteArray, bytes: Int, vararg headers: Pair<String, String>): Pair<ByteChannel, Headers> {
        val channel = ByteChannel()
        launch {
            channel.writeFully(whole.copyOf(bytes))
            channel.flush()
            delay(300)
            channel.cancel(IOException("Connection reset"))
        }
        val named = listOf(HttpHeaders.ContentLength to listOf("${whole.size}"), HttpHeaders.AcceptRanges to listOf("bytes")) + headers.map { it.first to listOf(it.second) }
        return channel to headersOf(*named.toTypedArray())
    }

    @Test
    fun aResumeAsksOnlyWhileTheFileIsTheOneThePartBeganAndAReplacedFileComesWhole() = runBlocking<Unit> {
        val replaced = body.reversedArray()
        val asked = mutableListOf<Pair<String?, String?>>()
        val engine = MockEngine { request ->
            asked += request.headers[HttpHeaders.Range] to request.headers[HttpHeaders.IfRange]
            when {
                asked.size == 1 -> cutOff(body, 100_000, HttpHeaders.ETag to "\"v1\"").let { (channel, headers) -> respond(channel, HttpStatusCode.OK, headers) }
                request.headers[HttpHeaders.IfRange] == "\"v2\"" -> respond(replaced.copyOfRange(100_000, replaced.size), HttpStatusCode.PartialContent, headersOf(HttpHeaders.ContentRange, "bytes 100000-299999/300000"))
                else -> respond(replaced, HttpStatusCode.OK, headersOf(HttpHeaders.ETag, "\"v2\""))
            }
        }
        val done = assertIs<DownloadResult.Done>(download(engine, file(md5 = null)))
        assertEquals(listOf<Pair<String?, String?>>(null to null, "bytes=100000-" to "\"v1\""), asked)
        assertContentEquals(replaced, Files.readAllBytes(Path.of(done.archive.path)), "nothing of the old file is spliced in")
        assertEquals(listOf("bg_ivy.zip"), Files.list(home.resolve("downloads/mod-628739/1691864")).use { p -> p.map { it.fileName.toString() }.toList() })
    }

    @Test
    fun withNothingToNameTheFileByAndNoMd5APartStartsAgain() = runBlocking<Unit> {
        val ranges = mutableListOf<String?>()
        val engine = MockEngine { request ->
            ranges += request.headers[HttpHeaders.Range]
            if (ranges.size == 1) cutOff(body, 100_000).let { (channel, headers) -> respond(channel, HttpStatusCode.OK, headers) } else respond(body)
        }
        assertIs<DownloadResult.Done>(download(engine, file(md5 = null)))
        assertEquals(listOf<String?>(null, null), ranges)
    }

    @Test
    fun aPartialAnswerFromAnotherOffsetStartsTheFileAgain() = runBlocking<Unit> {
        val ranges = mutableListOf<String?>()
        val engine = MockEngine { request ->
            ranges += request.headers[HttpHeaders.Range]
            when (ranges.size) {
                1 -> cutOff(body, 100_000).let { (channel, headers) -> respond(channel, HttpStatusCode.OK, headers) }
                2 -> respond(body, HttpStatusCode.PartialContent, headersOf(HttpHeaders.ContentRange, "bytes 0-299999/300000"))
                else -> respond(body)
            }
        }
        val twice = downloader(engine, attempts = 2)
        assertIs<DownloadResult.Done>(twice.download(twice.defaultFolder, ivy, file()) {})
        assertEquals(listOf(null, "bytes=100000-", null), ranges)
    }

    @Test
    fun aMissingFileIsNotRetried() = runBlocking<Unit> {
        var calls = 0
        assertEquals(DownloadResult.Failed(DownloadProblem.Http(404)), download(MockEngine { calls++; respondError(HttpStatusCode.NotFound) }, file()))
        assertEquals(1, calls)
    }

    @Test
    fun everyOccupiedNameIsSkippedEvenWhenItHoldsTheSameBytes() = runBlocking<Unit> {
        val folder = home.resolve("downloads/mod-628739/1691864").createDirectories()
        folder.resolve("bg_ivy.zip").writeBytes("someone else's".encodeToByteArray())
        val first = assertIs<DownloadResult.Done>(download(MockEngine { respond(body) }, file())).archive
        assertEquals(folder.resolve("bg_ivy (2).zip").toString(), first.path)
        assertContentEquals("someone else's".encodeToByteArray(), folder.resolve("bg_ivy.zip").readBytes())
        val again = assertIs<DownloadResult.Done>(download(MockEngine { respond(body) }, file())).archive
        assertEquals(folder.resolve("bg_ivy (3).zip").toString(), again.path)
        assertContentEquals(body, folder.resolve("bg_ivy (2).zip").readBytes())
        assertContentEquals(body, folder.resolve("bg_ivy (3).zip").readBytes())
        assertEquals(false, Files.isSameFile(folder.resolve("bg_ivy (2).zip"), folder.resolve("bg_ivy (3).zip")))
    }

    @Test
    fun aLongJapaneseNameIsPublishedWithinWhatTheFileSystemAllows() = runBlocking<Unit> {
        val archive = assertIs<DownloadResult.Done>(download(MockEngine { respond(body) }, file().copy(fileName = "長".repeat(120) + ".zip"))).archive
        assertEquals(home.resolve("downloads/mod-628739/1691864/${"長".repeat(57)}.zip").toString(), archive.path)
        assertContentEquals(body, Path.of(archive.path).readBytes())
    }

    @Test
    fun aChosenFolderTakesNewDownloadsAndOnlyHideoutsOwnFolderIsEverMade() = runBlocking<Unit> {
        val downloader = downloader(MockEngine { respond(body) })
        val chosen = home.resolve("chosen").createDirectories()
        val archive = assertIs<DownloadResult.Done>(downloader.download(DownloadFolder(chosen.toString()), ivy, file()) {}).archive
        assertEquals(chosen.resolve("mod-628739/1691864/bg_ivy.zip").toString(), archive.path)
        assertFalse(home.resolve("downloads").exists(), "Hideout's own folder is made only when it is used")

        val gone = home.resolve("gone")
        assertIs<DownloadProblem.Disk>(assertIs<DownloadResult.Failed>(downloader.download(DownloadFolder(gone.toString()), ivy, file()) {}).problem)
        assertFalse(gone.exists(), "a chosen folder that is gone is not made again, and the download goes nowhere else")
        val linked = home.resolve("linked").also { Files.createSymbolicLink(it, chosen) }
        Files.delete(Path.of(archive.path))
        assertEquals(archive.path, assertIs<DownloadResult.Done>(downloader.download(DownloadFolder(linked.toString()), ivy, file()) {}).archive.path, "a linked folder takes downloads at its real path")

        assertIs<DownloadResult.Done>(downloader.download(downloader.defaultFolder, ivy, file()) {})
        assertTrue(home.resolve("downloads/mod-628739/1691864/bg_ivy.zip").exists())
    }

    @Test
    fun aFolderIsAcceptedOnlyWhenItIsAWritableFolderOutsideEveryGameAndHideoutsData() = runBlocking<Unit> {
        val downloader = downloader(MockEngine { respondError(HttpStatusCode.NotFound) })
        val game = home.resolve("Deadlock").createDirectories()
        val games = setOf(GameRoot(game.toString()))
        suspend fun check(path: Path) = downloader.checkFolder(path.toString(), games)

        val plain = home.resolve("Mods").createDirectories()
        assertEquals(FolderChoice.Accepted(DownloadFolder(plain.toString())), check(plain))
        home.resolve("downloads").createDirectories()
        assertEquals(FolderChoice.Accepted(downloader.defaultFolder), check(home.resolve("downloads")), "Hideout's own folder may be chosen again")
        val link = home.resolve("link").also { Files.createSymbolicLink(it, plain) }
        assertEquals(FolderChoice.Accepted(DownloadFolder(link.toString())), check(link), "a link to a folder, as a redirected Downloads is")

        val refusals = listOf(
            home.resolve("nowhere") to FolderRefusal.Missing,
            home.resolve("file.txt").also { it.writeBytes(body) } to FolderRefusal.NotAFolder,
            game to FolderRefusal.InsideGame,
            game.resolve("game/citadel").createDirectories() to FolderRefusal.InsideGame,
            home.resolve("into game").also { Files.createSymbolicLink(it, game) }.resolve("game") to FolderRefusal.InsideGame,
            home.resolve("data").createDirectories() to FolderRefusal.InsideHideout,
            home.resolve("data/staging").createDirectories() to FolderRefusal.InsideHideout,
            home.resolve("downloads/mod-628739").createDirectories() to FolderRefusal.InsideHideout,
        )
        refusals.forEach { (path, refusal) -> assertEquals(FolderChoice.Refused(refusal), check(path), "$path") }

        val readOnly = home.resolve("read-only").createDirectories()
        Files.setPosixFilePermissions(readOnly, PosixFilePermissions.fromString("r-xr-xr-x"))
        try {
            assumeTrue("a superuser can write anywhere", !Files.isWritable(readOnly))
            assertEquals(FolderChoice.Refused(FolderRefusal.NotWritable), check(readOnly))
        } finally {
            Files.setPosixFilePermissions(readOnly, PosixFilePermissions.fromString("rwxr-xr-x"))
        }
    }

    @Test
    fun aLinkedModOrFileFolderIsRefusedAndNothingIsWrittenThroughIt() = runBlocking<Unit> {
        val downloader = downloader(MockEngine { respond(body) })
        val base = home.resolve("downloads").createDirectories()
        val elsewhere = home.resolve("elsewhere").createDirectories()
        val sentinel = elsewhere.resolve("keep.txt").also { it.writeBytes("someone else's".encodeToByteArray()) }
        fun listed() = Files.list(elsewhere).use { paths -> paths.map { it.fileName.toString() }.sorted().toList() }

        Files.createSymbolicLink(base.resolve("mod-628739"), elsewhere)
        val throughMod = downloader.download(downloader.defaultFolder, ivy, file()) {}
        val afterMod = listed()
        deleteTree(elsewhere.resolve("1691864"))

        Files.delete(base.resolve("mod-628739"))
        base.resolve("mod-628739").createDirectories()
        Files.createSymbolicLink(base.resolve("mod-628739/1691864"), elsewhere)
        val throughFile = downloader.download(downloader.defaultFolder, ivy, file()) {}
        val afterFile = listed()

        assertEquals(listOf("keep.txt") to listOf("keep.txt"), afterMod to afterFile, "nothing is made through a linked mod folder ($throughMod) or file folder ($throughFile)")
        assertIs<DownloadProblem.Disk>(assertIs<DownloadResult.Failed>(throughMod).problem)
        assertIs<DownloadProblem.Disk>(assertIs<DownloadResult.Failed>(throughFile).problem)
        assertContentEquals("someone else's".encodeToByteArray(), sentinel.readBytes())
    }

    @Test
    fun aGameWhoseRealPlaceCannotBeReadRefusesTheFolderInsteadOfJudgingByItsName() = runBlocking<Unit> {
        val downloader = downloader(MockEngine { respondError(HttpStatusCode.NotFound) })
        val inside = home.resolve("real steam/Deadlock/game").createDirectories()
        val locked = home.resolve("locked").createDirectories()
        Files.createSymbolicLink(locked.resolve("steam"), home.resolve("real steam"))
        val game = GameRoot(locked.resolve("steam/Deadlock").toString())
        assertEquals(FolderChoice.Refused(FolderRefusal.InsideGame), downloader.checkFolder(inside.toString(), setOf(game)), "while the game's real place can be read")

        Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("---------"))
        try {
            assumeTrue("a superuser can look anywhere", !Files.isExecutable(locked))
            val choice = downloader.checkFolder(inside.toString(), setOf(game))
            assertIs<FolderChoice.Refused>(choice, "a folder that may be inside the game is never accepted on its name alone")
            assertEquals(FolderChoice.Refused(FolderRefusal.Unresolved), choice)
            assertEquals(FolderChoice.Refused(FolderRefusal.Unresolved), downloader.checkFolder(home.resolve("Mods").createDirectories().toString(), setOf(game)), "nor any other folder while it cannot be told")
            assertEquals(FolderChoice.Accepted(DownloadFolder(home.resolve("Mods").toString())), downloader.checkFolder(home.resolve("Mods").toString(), setOf(GameRoot(home.resolve("gone/Deadlock").toString()))), "a game that is not there holds no folder")
        } finally {
            Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwxr-xr-x"))
        }
    }

    @Test
    fun aDownloadBelowALinkedParentIsRecordedAtItsRealPathAndStillRemovedFromThere() = runBlocking<Unit> {
        val real = home.resolve("var home/player/Mods").createDirectories()
        Files.createSymbolicLink(home.resolve("home"), home.resolve("var home"))
        val chosen = home.resolve("home/player/Mods")
        val downloader = downloader(MockEngine { respond(body) })
        assertEquals(FolderChoice.Accepted(DownloadFolder(chosen.toString())), downloader.checkFolder(chosen.toString(), emptySet()), "a plain folder whose parent is a link, as /home is on some systems")

        val archive = assertIs<DownloadResult.Done>(downloader.download(DownloadFolder(chosen.toString()), ivy, file()) {}).archive
        assertEquals(real.resolve("mod-628739/1691864/bg_ivy.zip").toString(), archive.path, "recorded where it really is")
        val source = SubmissionIdentity(SubmissionKind.Mod, 628_739, 1_691_864, md5)
        val original = DownloadEntry(source, archive)
        val anchor = DownloadEntry(source, archive, ChangeToken("t1"))
        assertEquals(EntryState.Present(Sha256.image(body), twin = true), downloader.inspect(original, original))
        assertEquals(LinkResult.Linked, downloader.link(original, anchor))
        assertEquals(Removal.Removed, downloader.removeTwin(original, anchor, Sha256.image(body)))
        assertEquals(Removal.Removed, downloader.removeExact(anchor, Sha256.image(body)))
        assertEquals(emptyList(), Files.list(real).use { it.toList() }, "the emptied mod and file folders go from the real path, the chosen folder stays")
    }

    @Test
    fun removingADownloadNeverTakesThePartAnUnfinishedDownloadOfTheSameFileGoesOnFrom() = runBlocking<Unit> {
        val downloader = downloader(MockEngine { respond(body) })
        val archive = assertIs<DownloadResult.Done>(downloader.download(downloader.defaultFolder, ivy, file()) {}).archive
        val part = Path.of(archive.path).resolveSibling("download.part").apply { writeBytes(body.copyOf(100_000)) }
        val entry = DownloadEntry(SubmissionIdentity(SubmissionKind.Mod, 628_739, 1_691_864, md5), archive)
        assertEquals(Removal.Removed, downloader.removeExact(entry, Sha256.image(body)), "a reinstall's download of the same file is under way")
        assertEquals(100_000, Files.size(part), "its part stays, with the folders that hold it")
        downloader.discardUnfinished(downloader.defaultFolder, ivy, 1_691_864)
        assertFalse(home.resolve("downloads/mod-628739").exists(), "putting the unfinished install away is what takes it")
    }

    private val proc = home.resolve("proc")

    private fun process(pid: Int, argv: List<String>, name: String): Path {
        val folder = proc.resolve(pid.toString()).createDirectories()
        folder.resolve("cmdline").writeBytes(if (argv.isEmpty()) ByteArray(0) else argv.joinToString("\u0000", postfix = "\u0000").encodeToByteArray())
        folder.resolve("comm").writeBytes("$name\n".encodeToByteArray())
        proc.resolve("self").createDirectories()
        return folder
    }

    private fun unreadable(file: Path) {
        Files.setPosixFilePermissions(file, emptySet())
        assumeTrue("this user can read files without permission", !Files.isReadable(file))
    }

    private fun table(folder: Path = proc) = ProcessTable(ProcessSource.Proc(folder))

    @Test
    fun tidyingAGameRemovesHideoutsEmptyFoldersAndKeepsAnyThatHoldAFile() = runBlocking<Unit> {
        val game = home.resolve("tidy-game")
        val citadel = game.resolve("game").resolve("citadel")
        citadel.resolve("hideout_off").resolve("mod-1").createDirectories()
        citadel.resolve("hideout_backups").resolve("files").createDirectories()
        citadel.resolve("hideout_backups").resolve("library").createDirectories().resolve("b1.json").writeBytes(ByteArray(1))
        citadel.resolve("addons").createDirectories()
        NioModInstaller(home.resolve("staging")).tidy(GameRoot(game.toString()))
        assertFalse(Files.exists(citadel.resolve("hideout_off")))
        assertFalse(Files.exists(citadel.resolve("hideout_backups").resolve("files")))
        assertTrue(Files.exists(citadel.resolve("hideout_backups").resolve("library").resolve("b1.json")))
        assertTrue(Files.exists(citadel.resolve("addons")), "the game's own mods folder stays")
    }

    @Test
    fun onlyAProcessWhoseOwnExecutableIsTheGameCounts() = runBlocking<Unit> {
        process(10, listOf("""c:\windows\system32\steam.exe""", """S:\common\Deadlock\game\bin\win64\deadlock.exe""", "-insecure"), "steam.exe")
        process(11, listOf("/usr/bin/python3", "proton", "waitforexitandrun", "/games/Deadlock/game/bin/win64/deadlock.exe"), "python3")
        assertEquals(ProcessScan.Seen(), table().scan())
        process(12, listOf("""S:\common\Deadlock\game\bin\win64\deadlock.exe""", "-insecure", "-console"), "deadlock.exe")
        assertEquals(ProcessScan.Seen(game = true), table().scan())
        assertIs<ProcessScan.Unknown>(table(home.resolve("no-proc")).scan())
    }

    @Test
    fun theSameWalkSeesWhichOtherManagersAreOpen() = runBlocking<Unit> {
        process(20, listOf("/usr/bin/bwrap", "--args", "40", "--", "deadlock-mod-manager"), "bwrap")
        assertEquals(ProcessScan.Seen(), table().scan())
        process(21, listOf("/app/share/deadlock-mod-manager/deadlock-mod-manager", "--user-data-dir=/cef"), "deadlock-mod-ma")
        process(22, listOf("/opt/Grimoire/grimoire", "--type=renderer"), "grimoire")
        assertEquals(ProcessScan.Seen(managers = setOf(Manager.DeadlockModManager, Manager.Grimoire)), table().scan())
        process(23, listOf("""S:\common\Deadlock\game\bin\win64\deadlock.exe"""), "deadlock.exe")
        assertEquals(ProcessScan.Seen(game = true, managers = setOf(Manager.DeadlockModManager, Manager.Grimoire)), table().scan())
    }

    @Test
    fun kernelThreadsWithEmptyCommandLinesAndVanishedProcessesDoNotBlock() = runBlocking<Unit> {
        process(2, emptyList(), "kthreadd")
        process(30, listOf("/usr/bin/bash"), "bash")
        Files.createSymbolicLink(proc.resolve("31"), proc.resolve("exited"))
        assertEquals(ProcessScan.Seen(), table().scan())
    }

    @Test
    fun aProcessThatCannotBeReadIsSkippedRatherThanMakingTheScanUnknown() = runBlocking<Unit> {
        process(30, listOf("/usr/bin/bash"), "bash")
        unreadable(process(32, listOf("""S:\common\Deadlock\game\bin\win64\deadlock.exe"""), "wine64-preload").resolve("cmdline"))
        unreadable(process(33, listOf("/usr/bin/bash"), "bash").resolve("comm"))
        assertEquals(ProcessScan.Seen(), table().scan())
    }

    @Test
    fun aZombieGameIsNotARunningGame() = runBlocking<Unit> {
        val game = process(36, listOf("""S:\common\Deadlock\game\bin\win64\deadlock.exe"""), "deadlock.exe")
        game.resolve("stat").writeBytes("36 (deadlock.exe) Z 1 36 36 0 -1 4227084 0 0 0 0".encodeToByteArray())
        assertEquals(ProcessScan.Seen(), table().scan())
        game.resolve("stat").writeBytes("36 (deadlock (x).exe) S 1 36 36 0 -1 4227084 0 0 0 0".encodeToByteArray())
        assertEquals(ProcessScan.Seen(game = true), table().scan(), "the state follows the last parenthesis, whatever the name holds")
        val ended = mutableSetOf<Long>()
        game.resolve("stat").writeBytes("36 (deadlock.exe) Z 1".encodeToByteArray())
        ProcessTable(ProcessSource.Proc(proc)) { ended += it; true }.endGame()
        assertEquals(emptySet(), ended, "nothing is left to end")
    }

    @Test
    fun onWindowsAGameThatHasExitedThoughStillHeldIsNotRunning() = runBlocking<Unit> {
        var alive = false
        val table = ProcessTable(ProcessSource.Snapshot(alive = { pid -> pid != 8L || alive }) { listOf(ListedProcess(7, "steam.exe"), ListedProcess(8, "deadlock.exe")) })
        assertEquals(ProcessScan.Seen(), table.scan())
        alive = true
        assertEquals(ProcessScan.Seen(game = true), table.scan())
    }

    @Test
    fun theGameFoundBesideAnUnreadableProcessIsRunning() = runBlocking<Unit> {
        unreadable(process(34, listOf("/usr/bin/bash"), "bash").resolve("cmdline"))
        process(35, listOf("""S:\common\Deadlock\game\bin\win64\deadlock.exe"""), "deadlock.exe")
        assertEquals(ProcessScan.Seen(game = true), table().scan())
    }

    @Test
    fun onWindowsTheSnapshotsExecutableNamesSayWhatRuns() = runBlocking<Unit> {
        var running = listOf("[System Process]", "System", "steam.exe", "steamwebhelper.exe", "deadlock-launcher.exe")
        val table = ProcessTable(ProcessSource.Snapshot(alive = { true }) { running.map { ListedProcess(1, it) } })
        assertEquals(ProcessScan.Seen(), table.scan())
        running = running + "Deadlock Mod Manager.exe" + "Grimoire.exe"
        assertEquals(ProcessScan.Seen(managers = setOf(Manager.DeadlockModManager, Manager.Grimoire)), table.scan())
        running = running + "Deadlock.exe"
        assertEquals(ProcessScan.Seen(game = true, managers = setOf(Manager.DeadlockModManager, Manager.Grimoire)), table.scan())
    }

    @Test
    fun closingTheGameEndsOnlyTheGameAndEveryProcessOfIt() = runBlocking<Unit> {
        process(10, listOf("""c:\windows\system32\steam.exe""", """S:\common\Deadlock\game\bin\win64\deadlock.exe"""), "steam.exe")
        process(12, listOf("""S:\common\Deadlock\game\bin\win64\deadlock.exe""", "-console"), "deadlock.exe")
        process(35, listOf("""S:\common\Deadlock\game\bin\win64\deadlock.exe"""), "deadlock.exe")
        val ended = mutableSetOf<Long>()
        ProcessTable(ProcessSource.Proc(proc)) { ended += it; true }.endGame()
        assertEquals(setOf(12L, 35L), ended)
    }

    @Test
    fun onWindowsClosingTheGameUsesTheSnapshotsProcessIds() = runBlocking<Unit> {
        val listed = listOf(ListedProcess(4, "steam.exe"), ListedProcess(77, "Deadlock.exe"))
        val ended = mutableSetOf<Long>()
        ProcessTable(ProcessSource.Snapshot(alive = { true }) { listed }) { ended += it; true }.endGame()
        assertEquals(setOf(77L), ended)
    }

    @Test
    fun aGameThatRefusesToEndIsReportedAndNoGameIsNotAProblem() = runBlocking<Unit> {
        val running = ProcessSource.Snapshot(alive = { true }) { listOf(ListedProcess(77, "Deadlock.exe")) }
        assertFailsWith<IOException> { ProcessTable(running) { false }.endGame() }
        ProcessTable(ProcessSource.Snapshot(alive = { true }) { emptyList() }) { error("nothing to end") }.endGame()
        assertFailsWith<IOException> { ProcessTable(ProcessSource.Unsupported) { true }.endGame() }
    }

    @Test
    fun aSnapshotThatCannotBeReadAndAnyOtherSystemAreUnknown() = runBlocking<Unit> {
        assertIs<ProcessScan.Unknown>(ProcessTable(ProcessSource.Snapshot(alive = { true }) { throw IOException("the process list could not be read to its end") }).scan())
        assertIs<ProcessScan.Unknown>(ProcessTable(ProcessSource.Unsupported).scan())
    }

    @Test
    fun aRecoveryKeepsANextFileThatIsNotTheEditItExpected() = runBlocking<Unit> {
        val config = FileGameConfig()
        val sentinel = "not Hideout's edit".encodeToByteArray()
        citadel.resolve("gameinfo.gi.hideout-displaced-t1").writeBytes(original)
        citadel.resolve("gameinfo.gi.hideout-next-t1").writeBytes(sentinel)
        assertEquals(ConfigRecovery.Undone(kept = listOf("gameinfo.gi.hideout-next-t1")), config.recover(edit("t1", original, patched)))
        assertContentEquals(original, citadel.resolve("gameinfo.gi").readBytes())
        assertContentEquals(sentinel, citadel.resolve("gameinfo.gi.hideout-next-t1").readBytes())

        val torn = patched.copyOf(patched.size / 2)
        citadel.resolve("gameinfo.gi.hideout-next-t2").writeBytes(torn)
        assertEquals(ConfigRecovery.Undone(kept = listOf("gameinfo.gi.hideout-next-t2")), config.recover(edit("t2", original, patched)))
        assertContentEquals(torn, citadel.resolve("gameinfo.gi.hideout-next-t2").readBytes())

        citadel.resolve("gameinfo.gi.hideout-next-t3").writeBytes(patched)
        assertEquals(ConfigRecovery.Undone(), config.recover(edit("t3", original, patched)))
        assertEquals(setOf("gameinfo.gi", "gameinfo.gi.hideout-next-t1", "gameinfo.gi.hideout-next-t2"), names(), "an exact copy of the edit is removed")
    }

    @Test
    fun aLeftoverFileUnderThisAttemptsNamesIsNeverTouched() = runBlocking<Unit> {
        val config = FileGameConfig()
        val leftover = "an earlier file".encodeToByteArray()
        citadel.resolve("gameinfo.gi").writeBytes(original)
        citadel.resolve("gameinfo.gi.hideout-next-t1").writeBytes(leftover)
        assertIs<ReplaceResult.Failed>(config.replace(edit("t1", original, patched), patched))
        assertContentEquals(original, citadel.resolve("gameinfo.gi").readBytes())
        assertContentEquals(leftover, citadel.resolve("gameinfo.gi.hideout-next-t1").readBytes())

        citadel.resolve("gameinfo.gi.hideout-displaced-t2").writeBytes(leftover)
        assertIs<ReplaceResult.Failed>(config.replace(edit("t2", original, patched), patched))
        assertContentEquals(original, citadel.resolve("gameinfo.gi").readBytes())
        assertContentEquals(leftover, citadel.resolve("gameinfo.gi.hideout-displaced-t2").readBytes())
        assertEquals(setOf("gameinfo.gi", "gameinfo.gi.hideout-next-t1", "gameinfo.gi.hideout-displaced-t2"), names(), "only the attempt's own new file is gone")
    }

    @Test
    fun aForeignDisplacedNameIsNotConsumedWhenGameInfoDisappearsAfterARefusedEdit() = runBlocking<Unit> {
        val config = FileGameConfig()
        val current = citadel.resolve("gameinfo.gi")
        val foreign = citadel.resolve("gameinfo.gi.hideout-displaced-t1")
        val sentinel = "a preexisting foreign token file must keep its name and bytes".encodeToByteArray()
        current.writeBytes(original)
        foreign.writeBytes(sentinel)
        assertIs<ReplaceResult.Failed>(config.replace(edit("t1", original, patched), patched))
        assertContentEquals(original, current.readBytes())
        Files.delete(current)

        assertIs<ConfigRecovery.Failed>(config.recover(edit("t1", original, patched)))
        assertContentEquals(sentinel, foreign.readBytes())
        assertEquals(setOf("gameinfo.gi.hideout-displaced-t1"), names())
    }

    @Test
    fun aChangedFileMovedAsideByAnInterruptedReplacementIsKeptUnpublishedAndRecoveryFails() = runBlocking<Unit> {
        val config = FileGameConfig()
        val steams = "// steam wrote this just before Hideout moved it aside\n".encodeToByteArray()
        citadel.resolve("gameinfo.gi.hideout-displaced-t1").writeBytes(steams)
        citadel.resolve("gameinfo.gi.hideout-next-t1").writeBytes(patched)
        assertIs<ConfigRecovery.Failed>(config.recover(edit("t1", original, patched)))
        assertTrue(!citadel.resolve("gameinfo.gi").exists(), "unverified bytes are never published as gameinfo.gi")
        assertContentEquals(steams, citadel.resolve("gameinfo.gi.hideout-displaced-t1").readBytes(), "the displaced file keeps its name and bytes")
        assertEquals(setOf("gameinfo.gi.hideout-displaced-t1"), names(), "the staged copy, already identical to the edit, is cleaned up")

        val sentinel = "not Hideout's edit".encodeToByteArray()
        citadel.resolve("gameinfo.gi.hideout-displaced-t2").writeBytes(steams)
        citadel.resolve("gameinfo.gi.hideout-next-t2").writeBytes(sentinel)
        assertIs<ConfigRecovery.Failed>(config.recover(edit("t2", original, patched)))
        assertTrue(!citadel.resolve("gameinfo.gi").exists())
        assertContentEquals(steams, citadel.resolve("gameinfo.gi.hideout-displaced-t2").readBytes())
        assertContentEquals(sentinel, citadel.resolve("gameinfo.gi.hideout-next-t2").readBytes(), "a staged file that is not the edit's result is kept too")

        citadel.resolve("gameinfo.gi.hideout-displaced-t3").writeBytes(patched)
        assertEquals(ConfigRecovery.Applied(), config.recover(edit("t3", original, patched)), "a file moved aside that already was the edit is the edit")
        assertContentEquals(patched, citadel.resolve("gameinfo.gi").readBytes())
        assertEquals(
            setOf("gameinfo.gi", "gameinfo.gi.hideout-displaced-t1", "gameinfo.gi.hideout-displaced-t2", "gameinfo.gi.hideout-next-t2"),
            names(),
            "the unverified files from t1 and t2 are still sitting there, untouched",
        )
    }

    @Test
    fun anActiveConfigAlreadyPresentWinsOverAnUnrelatedDisplacedFile() = runBlocking<Unit> {
        val config = FileGameConfig()
        val currentUnrelated = byteArrayOf(9, 9, 9)
        val displacedUnrelated = "neither the edit's original nor its result".encodeToByteArray()
        citadel.resolve("gameinfo.gi").writeBytes(currentUnrelated)
        citadel.resolve("gameinfo.gi.hideout-displaced-t4").writeBytes(displacedUnrelated)
        val conflict = assertIs<ConfigRecovery.Conflict>(config.recover(edit("t4", original, patched)))
        assertEquals(listOf("gameinfo.gi.hideout-displaced-t4"), conflict.kept)
        assertContentEquals(currentUnrelated, citadel.resolve("gameinfo.gi").readBytes(), "the active config wins and is untouched")
        assertContentEquals(displacedUnrelated, citadel.resolve("gameinfo.gi.hideout-displaced-t4").readBytes(), "the unrelated token file stays untouched")
    }

    @Test
    fun onADriveWithoutHardLinksGameInfoIsReplacedByRenamesAndNothingIsLeftBeside() = runBlocking<Unit> {
        val config = FileGameConfig(windows = false, createLink = { link, _ -> throw FileSystemException(link.toString(), null, "Operation not permitted") })
        citadel.resolve("gameinfo.gi").writeBytes(original)
        assertEquals(ReplaceResult.Replaced, config.replace(edit("t1", original, patched), patched))
        assertContentEquals(patched, citadel.resolve("gameinfo.gi").readBytes())
        assertEquals(setOf("gameinfo.gi"), names(), "nothing was left beside it, the trial names included")
        assertEquals(ConfigRecovery.Applied(), config.recover(edit("t1", original, patched)))

        Files.delete(citadel.resolve("gameinfo.gi"))
        citadel.resolve("gameinfo.gi.hideout-next-t2").writeBytes(patched)
        citadel.resolve("gameinfo.gi.hideout-displaced-t2").writeBytes(original)
        assertEquals(ConfigRecovery.Undone(), config.recover(edit("t2", original, patched)), "a replacement cut short is put back by a rename too")
        assertContentEquals(original, citadel.resolve("gameinfo.gi").readBytes())
        assertEquals(setOf("gameinfo.gi"), names())
    }

    @Test
    fun aChangedFileMovedAsideIsNeverPutOverAGameInfoThatAppeared() = runBlocking<Unit> {
        val current = citadel.resolve("gameinfo.gi")
        val movedAside = original
        val appeared = "// steam wrote a new one meanwhile\n".encodeToByteArray()
        val config = FileGameConfig(windows = false, createLink = { link, existing ->
            if (link == current) current.writeBytes(appeared)
            Files.createLink(link, existing)
        })
        citadel.resolve("gameinfo.gi.hideout-displaced-t1").writeBytes(movedAside)
        val conflict = assertIs<ConfigRecovery.Conflict>(config.recover(edit("t1", original, patched)))
        assertEquals(listOf("gameinfo.gi.hideout-displaced-t1"), conflict.kept)
        assertContentEquals(appeared, current.readBytes())
        assertContentEquals(movedAside, citadel.resolve("gameinfo.gi.hideout-displaced-t1").readBytes())
    }

    @Test
    fun aChangedFileThatCannotBePutBackAtOnceIsLeftToRecoveryNotCalledAConflict() = runBlocking<Unit> {
        val current = citadel.resolve("gameinfo.gi")
        val displaced = citadel.resolve("gameinfo.gi.hideout-displaced-t1")
        val steams = "// steam wrote this just before the move\n".encodeToByteArray()
        var refuse = true
        val config = FileGameConfig(windows = false, afterDisplace = { displaced.writeBytes(steams) }, createLink = { link, existing ->
            if (refuse && link == current && existing == displaced) throw FileSystemException(link.toString(), null, "refused this once")
            Files.createLink(link, existing)
        })
        current.writeBytes(original)
        assertIs<ReplaceResult.Failed>(config.replace(edit("t1", original, patched), patched), "gameinfo.gi is missing, so the edit is not settled")
        refuse = false
        assertIs<ConfigRecovery.Failed>(config.recover(edit("t1", original, patched)), "steams matches neither from nor to, so it is kept, not published")
        assertTrue(!current.exists())
        assertContentEquals(steams, displaced.readBytes())
        assertEquals(setOf("gameinfo.gi.hideout-displaced-t1"), names())
    }
}

private const val BusyScale = 10
