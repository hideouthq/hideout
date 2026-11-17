package gg.hideouthq.library

import gg.hideouthq.core.data.deadlock.proto.CMsgMatchMetaData
import gg.hideouthq.core.data.deadlock.proto.CMsgMatchMetaDataContents
import gg.hideouthq.core.data.deadlockstats.DeadlockStatsRepository
import gg.hideouthq.core.data.deadlockstats.SteamAccountId
import gg.hideouthq.core.data.deadlockstats.profileSummary
import gg.hideouthq.data.FileCacheStorage
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import java.io.ByteArrayOutputStream
import kotlin.io.path.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalPathApi::class)
class DeadlockStatsIntegrationTest {
    @Test fun cacheToDecodedStateToDiskAndApiReconciliationWithoutUploads() = runBlocking {
        val root = createTempDirectory("hideout-stats-integration")
        val steam = (root / "Steam").createDirectories()
        val cache = (steam / "appcache" / "httpcache" / "nested").createDirectories()
        val storage = FileCacheStorage((root / "saved").toFile())
        var uploads = 0
        var downloads = 0
        val content = CMsgMatchMetaDataContents(match_info = CMsgMatchMetaDataContents.MatchInfo(
            match_id = 42, duration_s = 1200, start_time = 100, winning_team = 0, game_mode = 1, match_mode = 4,
            players = listOf(CMsgMatchMetaDataContents.Players(account_id = 555, hero_id = 2, team = 0, kills = 5)),
        ))
        val raw = CMsgMatchMetaData(match_id = 42, match_details = content.encodeByteString()).encode()
        val payload = ByteArrayOutputStream().also { out -> BZip2CompressorOutputStream(out).use { it.write(raw) } }.toByteArray()
        val client = HttpClient(MockEngine { request ->
            when {
                request.url.host == "replay1.valve.net" -> { downloads++; respond(payload) }
                request.url.encodedPath.endsWith("/salts") -> { uploads++; respond("") }
                request.url.encodedPath.endsWith("/match-history") -> respond("""[{"match_id":42,"hero_id":2,"start_time":100,"game_mode":1,"match_mode":4,"player_team":0,"player_kills":5,"match_result":0}]""")
                request.url.encodedPath.endsWith("/rank") -> respond("""{"badge":0,"rank":0,"subrank":0,"last_match":null}""")
                else -> respond("[]")
            }
        })
        val watcher = SteamReplayCacheWatcher({ listOf(SteamInstall(steam.toString(), SteamClient.Desktop)) }, 100.milliseconds)
        val repo = DeadlockStatsRepository(client, storage, this, watcher)
        val reopened = DeadlockStatsRepository(client, storage, this)
        try {
            repo.selectAccount(SteamAccountId.ofAccountId(555)!!)
            repo.configure(watcherEnabled = true)
            (cache / "entry").writeBytes("replay1.valve.net\u0000/1422450/42_99.meta.bz2\u0000".toByteArray())
            withTimeout(5_000) { repo.state.first { it.matchHistory.any { h -> h.matchId == 42L } } }
            assertEquals(1, repo.state.value.profileSummary()!!.wins)
            repo.refresh()
            assertEquals(1, repo.state.value.matchHistory.size, "API and local history reconcile by match id")
            assertEquals(1200, repo.state.value.matchHistory.single().matchDurationS, "Partial API rows retain local fields")
            assertEquals(1, downloads)
            assertEquals(0, uploads)
            assertNull(repo.state.value.lastPersistenceError)
            repo.close()
            reopened.selectAccount(SteamAccountId.ofAccountId(555)!!)
            assertEquals(42L, reopened.state.value.matchHistory.single().matchId)
            assertTrue(reopened.state.value.matchDetails.isEmpty(), "Archived details load when asked for")
            assertTrue(reopened.fetchMatchDetail(42).isSuccess)
            assertTrue(reopened.state.value.matchDetails.getValue(42).isComplete)
            assertEquals(1, downloads, "Reopening does not initiate any network requests")
        } finally {
            repo.close(); reopened.close(); client.close(); root.deleteRecursively()
        }
    }
}
