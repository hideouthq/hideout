package gg.hideouthq.deadlockstats

import gg.hideouthq.core.data.deadlock.proto.CMsgMatchMetaData
import gg.hideouthq.core.data.deadlock.proto.CMsgMatchMetaDataContents
import gg.hideouthq.core.data.deadlockstats.*
import gg.hideouthq.data.FileCacheStorage
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class SteamBridgeRepositoryTest {
    @Test fun nativeCollectorDownloadsArchivesAndRestoresMatchWithoutUploading(): Unit = runBlocking {
        val fixture = System.getProperty("hideout.steam.fixture")
        assumeTrue("Run native/steam-bridge/tools/test.sh for the native integration test", fixture != null)
        val executable = Path.of(fixture!!).toRealPath()
        assertTrue(Files.isExecutable(executable))
        val root = Files.createTempDirectory("steam-repository-test-")
        val directory = root.resolve("bridge")
        val storage = FileCacheStorage(root.resolve("saved").toFile())
        val account = SteamAccountId.ofAccountId(555)!!
        val info = CMsgMatchMetaDataContents.MatchInfo(match_id = 123, duration_s = 1200, winning_team = 0,
            players = listOf(CMsgMatchMetaDataContents.Players(account_id = 555, hero_id = 17, team = 0, kills = 5)))
        val raw = CMsgMatchMetaData(match_id = 123,
            match_details = CMsgMatchMetaDataContents(match_info = info).encodeByteString()).encode()
        val payload = ByteArrayOutputStream().also { out -> BZip2CompressorOutputStream(out).use { it.write(raw) } }.toByteArray()
        val downloads = AtomicInteger()
        val client = HttpClient(MockEngine { request ->
            assertEquals("GET", request.method.value)
            if (request.url.host == "replay1.valve.net") {
                assertEquals("/1422450/123_42.meta.bz2", request.url.encodedPath)
                downloads.incrementAndGet()
                respond(payload)
            } else {
                assertTrue(request.url.encodedPath.endsWith("/items") || request.url.encodedPath.endsWith("/ranks"), request.url.toString())
                respond("[]")
            }
        })
        val port = SteamBridgeServer({ directory }, this, { SteamTrackingInstallation(true, true) }, { hello ->
                SteamProcessResolver().resolve(hello.pid, executable, requireNotNull(SteamArchitecture.read(executable)))
        })
        val repository = DeadlockStatsRepository(client, storage, this, steamTrackingFactory = { port })
        val reopened = DeadlockStatsRepository(client, storage, this)
        var process: Process? = null
        try {
            repository.selectAccount(account)
            repository.configureSteamTracking(true)
            withTimeout(5_000) {
                while (!Files.exists(directory.resolve("endpoint"))) kotlinx.coroutines.delay(10)
            }
            process = ProcessBuilder(executable.toString(), directory.resolve("endpoint").toString()).start()
            val collected = withTimeout(30_000) {
                repository.state.first { it.matchDetails[123]?.isComplete == true }
            }
            assertEquals(123L, collected.matchHistory.single().matchId)
            assertEquals(5, collected.matchDetails.getValue(123).players.single().kills)
            assertNull(collected.lastPersistenceError)
            repository.close()
            process.destroy()
            process.waitFor()
            reopened.selectAccount(account)
            assertEquals(123L, reopened.state.value.matchHistory.single().matchId)
            assertTrue(reopened.fetchMatchDetail(123).isSuccess)
            assertEquals(5, reopened.state.value.matchDetails.getValue(123).players.single().kills)
            assertEquals(1, downloads.get())
        } finally {
            repository.close()
            reopened.close()
            process?.destroy()
            process?.waitFor()
            client.close()
            root.toFile().deleteRecursively()
        }
    }
}
