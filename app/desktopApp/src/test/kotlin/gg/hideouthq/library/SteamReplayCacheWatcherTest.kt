package gg.hideouthq.library

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.concurrent.TimeUnit
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.io.path.div
import kotlin.io.path.writeBytes
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

@OptIn(ExperimentalPathApi::class)
class SteamReplayCacheWatcherTest {
    private val root = createTempDirectory("steam-replay-watcher")

    @AfterTest
    fun cleanUp() = root.deleteRecursively()

    @Test
    fun aFileCreatedAfterStartupIsFoundThroughLiveObservation() {
        val steamRoot = steamRootWithHttpcache("steam2")
        watching(steamRoot) { recorder ->
            awaitFirstPassSettled()

            (steamRoot.cache() / "100_1.meta.bz2").writeBytes(replayHeader(100))

            assertTrue(recorder.awaitContains("100_1"))
        }
    }

    @Test
    fun eachFileComesWithWhenSteamLastWroteIt() {
        val steamRoot = steamRootWithHttpcache("steam-written")
        val file = (steamRoot.cache() / "300_1.meta.bz2").also { it.writeBytes(replayHeader(300)) }
        Files.setLastModifiedTime(file, FileTime.from(1_700_000_000, TimeUnit.SECONDS))
        watching(steamRoot) { recorder ->
            assertTrue(recorder.awaitContains("300_1"))
            assertEquals(1_700_000_000L, recorder.writtenAt("300_1"))
        }
    }

    @Test
    fun aFileInANewlyCreatedNestedDirectoryIsFound() {
        val steamRoot = steamRootWithHttpcache("steam3")
        watching(steamRoot) { recorder ->
            awaitFirstPassSettled()

            val nested = (steamRoot.cache() / "nested" / "deeper").createDirectories()
            delay(200)
            (nested / "200_1.meta.bz2").writeBytes(replayHeader(200))

            assertTrue(recorder.awaitContains("200_1"))
        }
    }

    @Test
    fun aPartialWriteIsRetriedOnReconciliationRatherThanBeingSuppressed() {
        val steamRoot = steamRootWithHttpcache("steam4")
        val file = steamRoot.cache() / "300_1.meta.bz2"
        val fullHeader = replayHeader(300)
        file.writeBytes(fullHeader.copyOf(10))
        watching(steamRoot, reconcileInterval = 150.milliseconds) { recorder ->
            delay(100)
            assertFalse(recorder.contains("300_1"), "a 10-byte prefix should not yet contain the replay URL")
            file.writeBytes(fullHeader)

            assertTrue(recorder.awaitContains("300_1"), "reconciliation should retry the file once it is no longer partial")
        }
    }

    @Test
    fun laterPassesSkipAFileUntilItsSizeOrTimeChanges() {
        val steamRoot = steamRootWithHttpcache("steam7")
        val file = steamRoot.cache() / "400_1.meta.bz2"
        file.writeBytes(replayHeader(400))
        watching(steamRoot, reconcileInterval = 100.milliseconds) { recorder ->
            assertTrue(recorder.awaitContains("400_1"))
            delay(600)
            assertEquals(1, recorder.count("400_1"), "an unchanged file is read once across passes")

            file.writeBytes(replayHeader(400) + "more".toByteArray())
            assertTrue(recorder.awaitCount("400_1", 2), "a file that changed is read again")
        }
    }

    @Test
    fun closeStopsFurtherDiscoveryAfterTheJobSettles() = runBlocking {
        val steamRoot = steamRootWithHttpcache("steam6")
        val watcher = SteamReplayCacheWatcher(steamInstalls = installs(steamRoot))
        val recorder = Recorder()
        val job = launch(Dispatchers.IO) { watcher.start(recorder.onCandidate) }
        awaitFirstPassSettled()

        job.cancelAndClose(watcher)
        delay(300)
        (steamRoot.cache() / "500_1.meta.bz2").writeBytes(replayHeader(500))
        delay(300)

        assertFalse(recorder.contains("500_1"), "no candidate should be reported once the watcher is closed")
    }

    @Test
    fun aCacheThatAppearsAfterStartIsWatchedWithoutARestart() {
        val steamRoot = root / "steam-later"
        watching(steamRoot, reconcileInterval = 150.milliseconds) { recorder ->
            awaitFirstPassSettled()

            steamRoot.cache().createDirectories()
            awaitFirstPassSettled()
            (steamRoot.cache() / "900_1.meta.bz2").writeBytes(replayHeader(900))

            assertTrue(recorder.awaitContains("900_1"))
        }
    }

    @Test
    fun multipleConfiguredRootsAreAllScanned() {
        val steamRootA = steamRootWithHttpcache("steamA")
        val steamRootB = steamRootWithHttpcache("steamB")
        (steamRootA.cache() / "600_1.meta.bz2").writeBytes(replayHeader(600))
        (steamRootB.cache() / "601_1.meta.bz2").writeBytes(replayHeader(601))
        watching(steamRootA, steamRootB) { recorder ->
            assertTrue(recorder.awaitContains("600_1"))
            assertTrue(recorder.awaitContains("601_1"))
        }
    }

    @Test
    fun aRelocatedCacheIsWatchedButLinksInsideItAreNotFollowed() {
        val steamRoot = (root / "relocated-steam" / "appcache").createDirectories().parent
        val actualCache = (root / "cache-on-another-drive").createDirectories()
        (actualCache / "entry").writeBytes(replayHeader(800))
        Files.createSymbolicLink(steamRoot.cache(), actualCache)
        val outside = (root / "outside").createDirectories()
        (outside / "700_1.meta.bz2").writeBytes(replayHeader(700))
        Files.createSymbolicLink(actualCache / "escape", outside)
        Files.createSymbolicLink(actualCache / "linked.meta.bz2", outside / "700_1.meta.bz2")
        watching(steamRoot) { recorder ->
            assertTrue(recorder.awaitContains("800_1"))
            awaitFirstPassSettled()
            assertFalse(recorder.contains("700_1"), "nothing is read through a link inside the cache")
        }
    }

    private fun watching(vararg roots: Path, reconcileInterval: Duration = 5.minutes, check: suspend (Recorder) -> Unit) = runBlocking {
        val watcher = SteamReplayCacheWatcher(installs(*roots), reconcileInterval)
        val recorder = Recorder()
        val job = launch(Dispatchers.IO) { watcher.start(recorder.onCandidate) }
        check(recorder)
        job.cancelAndClose(watcher)
    }

    private fun installs(vararg roots: Path): () -> List<SteamInstall> = { roots.map { SteamInstall(it.toString(), SteamClient.Desktop) } }

    private fun Path.cache(): Path = this / "appcache" / "httpcache"

    private fun steamRootWithHttpcache(name: String): Path = (root / name / "appcache" / "httpcache").createDirectories().let { (root / name) }

    private fun replayHeader(matchId: Long): ByteArray =
        "\u0000\u0001cache-index-noise GET http://replay1.valve.net/1422450/${matchId}_1.meta.bz2 HTTP/1.1\r\n"
            .toByteArray(Charsets.ISO_8859_1)

    private suspend fun awaitFirstPassSettled() = delay(300)

    private suspend fun Job.cancelAndClose(watcher: SteamReplayCacheWatcher) {
        watcher.close()
        withTimeout(3_000) { join() }
    }

    private class Recorder {
        private val mutex = Mutex()
        private val seen = mutableListOf<Pair<String, Long>>()

        val onCandidate: suspend (ByteArray, Long) -> Unit = { bytes, writtenAt -> mutex.withLock { seen += bytes.toString(Charsets.ISO_8859_1) to writtenAt } }

        suspend fun contains(text: String): Boolean = count(text) > 0

        suspend fun count(text: String): Int = mutex.withLock { seen.count { it.first.contains(text) } }

        suspend fun writtenAt(text: String): Long? = mutex.withLock { seen.lastOrNull { it.first.contains(text) }?.second }

        suspend fun awaitCount(text: String, atLeast: Int): Boolean = withTimeoutOrNull(5_000) { while (count(text) < atLeast) delay(20) } != null

        suspend fun awaitContains(text: String, timeoutMillis: Long = 5_000): Boolean = try {
            withTimeout(timeoutMillis) {
                while (!contains(text)) delay(20)
                true
            }
        } catch (e: Exception) {
            false
        }
    }
}
