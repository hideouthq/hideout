package gg.hideouthq.core.data.deadlockstats

import gg.hideouthq.core.data.CacheStorage
import gg.hideouthq.core.data.deadlock.proto.CMsgMatchMetaDataContents
import gg.hideouthq.core.data.fake.MemoryStorage
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.*
import kotlin.time.Clock
import kotlin.time.Instant

class StatsLifecycleTest {
    private val account = SteamAccountId.ofAccountId(555)!!
    private val header = "replay1.valve.net\u0000/1422450/42_99.meta.bz2\u0000".toByteArray()

    @Test fun partialMetadataIsPublishedThenRetriedUntilComplete() = runTest {
        var downloads = 0
        var seconds = 1_000L
        val clock = object : Clock { override fun now() = Instant.fromEpochSeconds(seconds) }
        val client = HttpClient(MockEngine {
            downloads++
            respond(metadata(if (downloads == 1) null else 1200))
        })
        val repo = DeadlockStatsRepository(client, MemoryStorage(), backgroundScope, clock = clock)
        try {
            repo.selectAccount(account)
            repo.onCacheCandidate(header)
            assertFalse(repo.state.value.matchDetails.getValue(42).isComplete)
            seconds += 60
            repo.onCacheCandidate(header)
            assertEquals(2, downloads)
            assertEquals(1200, repo.state.value.matchDetails.getValue(42).durationS)
            repo.onCacheCandidate(header)
            assertEquals(2, downloads)
        } finally { repo.close(); client.close() }
    }

    @Test fun repeatedTemporaryFailuresRecoverAfterCooldownWithoutRestarting() = runTest {
        var downloads = 0
        var seconds = 1_000L
        val clock = object : Clock { override fun now() = Instant.fromEpochSeconds(seconds) }
        val client = HttpClient(MockEngine {
            downloads++
            respond(metadata(if (downloads <= 5) null else 1200))
        })
        val repo = DeadlockStatsRepository(client, MemoryStorage(), backgroundScope, clock = clock)
        try {
            repo.selectAccount(account)
            repeat(5) { repo.onCacheCandidate(header); seconds += 300 }
            repo.onCacheCandidate(header)
            assertEquals(5, downloads, "Repeated scans respect the cooldown")
            seconds += 3600
            repo.onCacheCandidate(header)
            assertEquals(6, downloads)
            assertTrue(repo.state.value.matchDetails.getValue(42).isComplete)
        } finally { repo.close(); client.close() }
    }

    @Test fun switchingPublishesTheNewAccountBeforeLoadingItsSnapshot() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val storage = object : CacheStorage {
            override suspend fun read(name: String): String? {
                if (name == "deadlock-stats-777.json") { entered.complete(Unit); release.await() }
                return null
            }
            override suspend fun write(name: String, text: String) = Unit
        }
        var requests = 0
        val client = HttpClient(MockEngine { requests++; respond("[]") })
        val repo = DeadlockStatsRepository(client, storage, backgroundScope)
        try {
            repo.selectAccount(account)
            val switch = async { repo.selectAccount(SteamAccountId.ofAccountId(777)!!) }
            entered.await()
            assertEquals(777L, repo.state.value.accountId)
            assertTrue(repo.state.value.matchHistory.isEmpty())
            release.complete(Unit)
            switch.await()
            assertEquals(0, requests, "Selecting an account must not initiate network traffic")
        } finally { release.complete(Unit); repo.close(); client.close() }
    }

    @Test fun aDownloadFromThePreviousAccountCannotPopulateTheNextAccount() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val client = HttpClient(MockEngine { entered.complete(Unit); release.await(); respond(metadata(1200)) })
        val repo = DeadlockStatsRepository(client, MemoryStorage(), backgroundScope)
        try {
            repo.selectAccount(account)
            val download = async { repo.onCacheCandidate(header) }
            entered.await()
            repo.selectAccount(SteamAccountId.ofAccountId(777)!!)
            release.complete(Unit)
            download.await()
            assertTrue(repo.state.value.matchDetails.isEmpty())
            repo.selectAccount(account)
            repo.onCacheCandidate(header)
            assertEquals(42L, repo.state.value.matchHistory.single().matchId)
        } finally { release.complete(Unit); repo.close(); client.close() }
    }

    private fun metadata(duration: Int?) = metadataFile(CMsgMatchMetaDataContents.MatchInfo(
        match_id = 42, duration_s = duration, start_time = 100, winning_team = 0, game_mode = 1, match_mode = 4,
        players = listOf(CMsgMatchMetaDataContents.Players(account_id = 555, hero_id = 2, team = 0, kills = 5)),
    ))
}
