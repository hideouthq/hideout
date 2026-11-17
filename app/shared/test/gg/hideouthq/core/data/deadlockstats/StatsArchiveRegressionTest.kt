package gg.hideouthq.core.data.deadlockstats

import gg.hideouthq.core.data.CacheStorage
import gg.hideouthq.core.data.deadlock.proto.CMsgMatchMetaDataContents
import gg.hideouthq.core.data.fake.MemoryStorage
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*
import kotlin.time.Clock
import kotlinx.coroutines.test.TestScope
import kotlin.time.Instant

class StatsArchiveRegressionTest {
    private val account = SteamAccountId.ofAccountId(555)!!
    private class MutableClock(var seconds: Long = 1000) : Clock {
        override fun now() = Instant.fromEpochSeconds(seconds)
    }

    @Test fun oldestArchiveCanBeOpenedOfflineAndRemainsLoadedWithoutChangingFetchTime() = runTest {
        val storage = MemoryStorage()
        val clock = MutableClock()
        var offline = false
        val client = HttpClient(MockEngine { request ->
            check(!offline) { "Offline" }
            respond(metadata(request.url.encodedPath.substringAfterLast('/').substringBefore('_').toLong()))
        })
        opened(client, storage, clock = clock) { repo ->
            for (id in 1L..201L) {
                clock.seconds = 1000 + id
                repo.onCacheCandidate(reference(id))
            }
            assertEquals(200, repo.state.value.matchDetails.size)
            assertNull(repo.state.value.matchDetails[1])
            offline = true
            clock.seconds = 9000
            assertTrue(repo.fetchMatchDetail(1).isSuccess)
            assertEquals(1L, repo.state.value.matchDetails[1]?.matchId)
            assertEquals(1001L, repo.state.value.matchDetails[1]?.fetchedAt)
            assertNull(repo.state.value.matchDetails[2])

            assertTrue(repo.fetchMatchDetail(3).isSuccess)
            repo.refreshSocial()
            assertTrue(repo.fetchMatchDetail(2).isSuccess)
            assertEquals(MatchPlayerSummary(2, 555, 2, null, 0, null, 0, 0, 10, 0, 0), repo.state.value.matchSummaries[2])
            assertEquals(3L, repo.state.value.matchDetails[3]?.matchId, "An already loaded detail also becomes most recently accessed")
            assertNull(repo.state.value.matchDetails[4])
            assertEquals(201L, repo.state.value.matchDetails[201]?.matchId)
            repo.close()
            opened(client, storage, clock = clock) { reopened ->
            assertTrue(reopened.state.value.matchDetails.isEmpty(), "Archived details load when asked for")
            for (id in listOf(1L, 4L)) assertTrue(reopened.fetchMatchDetail(id).isSuccess, "$id is archived")
            assertEquals(1001L, reopened.state.value.matchDetails[1]?.fetchedAt)
            assertEquals(1004L, reopened.state.value.matchDetails[4]?.fetchedAt, "Evicted from memory, not from the archive")
            }
        }
    }

    @Test fun scalarTotalsAndTheirSampleCountsSurviveDetailEvictionAndRestart() = runTest {
        val storage = MemoryStorage()
        val clock = MutableClock()
        val client = HttpClient(MockEngine { request ->
            respond(metadata(request.url.encodedPath.substringAfterLast('/').substringBefore('_').toLong()))
        })
        opened(client, storage, clock = clock) { repo ->
            for (id in 1L..202L) {
                clock.seconds++
                repo.onCacheCandidate(reference(id))
            }
            assertNull(repo.state.value.matchDetails[1])
            assertNull(repo.state.value.matchDetails[2])
            assertHistoricalStats(repo.state.value)
            repo.close()
            opened(client, storage) { reopened ->
            assertHistoricalStats(reopened.state.value)
            assertTrue(reopened.state.value.matchDetails.isEmpty(), "Totals come from summaries, not reloaded details")
            assertNull(reopened.state.value.matchDetails[1])
            }
        }
    }

    @Test fun archiveRoundTripPreservesNullVersusKnownEmptyDeathDetails() = runTest {
        val storage = MemoryStorage()
        val archive = MatchDetailArchive(storage)
        val unknown = MatchParticipant(555)
        val knownEmpty = MatchParticipant(777)
        val withEvents = MatchParticipant(888, deathDetails = listOf(MatchDeathEvent(gameTimeS = 227, killerPlayerSlot = 5, deathDurationS = 8)))
        archive.save(account.value, MatchDetail(1, durationS = 100, winningTeam = 0, players = listOf(unknown, knownEmpty.copy(deathDetails = emptyList()), withEvents)))

        val loaded = archive.load(account.value, 1)!!
        val byAccount = loaded.players.associateBy { it.accountId }
        assertNull(byAccount.getValue(555).deathDetails, "omitted on save stays null on load")
        assertEquals(emptyList(), byAccount.getValue(777).deathDetails, "an explicit empty list round-trips as empty, not null")
        assertEquals(listOf(MatchDeathEvent(gameTimeS = 227, killerPlayerSlot = 5, deathDurationS = 8)), byAccount.getValue(888).deathDetails)
    }

    @Test fun archiveRetainsMoreThanTheMemoryWindowAndClearIsAccountScoped() = runTest {
        val storage = MemoryStorage()
        val archive = MatchDetailArchive(storage)
        repeat(230) { archive.save(555, MatchDetail(it + 1L, durationS = 100, winningTeam = 0, players = listOf(MatchParticipant(555)))) }
        archive.save(666, MatchDetail(1))
        val reopened = MatchDetailArchive(storage)
        assertEquals(1L, reopened.load(555, 1)?.matchId)
        assertEquals(230L, reopened.load(555, 230)?.matchId)
        reopened.clear(555)
        assertNull(reopened.load(555, 1)); assertNotNull(reopened.load(666, 1))
    }

    @Test fun requestedLegacyArchiveStatsRecoverOfflineWithoutLoadingTheDetail() = runTest {
        val storage = MemoryStorage()
        val archive = MatchDetailArchive(storage)
        val oldest = MatchDetail(1, durationS = 1200, winningTeam = 0, fetchedAt = 1,
            players = listOf(MatchParticipant(555, heroId = 2, statSnapshots = listOf(
                MatchPlayerStatSnapshot(timeStampS = 1200, playerDamage = 2400, playerHealing = 0),
            ))))
        archive.save(555, oldest)
        DeadlockStatsSnapshotStore(storage).save(DeadlockStatsSnapshot(accountId = 555,
            matchHistory = listOf(MatchHistoryEntry(1, matchDurationS = 1200, gameMode = 1, matchMode = 4))))
        var requests = 0
        val client = HttpClient(MockEngine { requests++; error("Offline") })
        opened(client, storage) { repo ->
            assertEquals(1, repo.refreshSummaries(listOf(1L)))
            assertEquals(StatTotal(2400, 1), repo.state.value.periodSummary(DateWindow()).totals.heroDamage)
            assertEquals(0, repo.state.value.matchSummaries[1]?.playerHealing)
            assertNull(repo.state.value.matchDetails[1])
            assertEquals(0, requests)
            repo.close()
            opened(client, storage) { reopened ->
            assertEquals(2400, reopened.state.value.matchSummaries[1]?.playerDamage)
            }
        }
    }

    @Test fun anInFlightApiSummaryCannotReplaceNewlyIngestedFinalStats() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val storage = MemoryStorage()
        val client = HttpClient(MockEngine { request ->
            if (request.url.host == "replay1.valve.net") respond(metadata(1)) else {
                entered.complete(Unit)
                release.await()
                respond("""[{"match_id":1,"players":[{"account_id":555,"final_stats":{"player_damage":5,"player_healing":99}}]}]""")
            }
        })
        opened(client, storage) { repo ->
            val summaries = async { repo.refreshSummaries(listOf(1L)) }
            entered.await()
            repo.onCacheCandidate(reference(1))
            release.complete(Unit)
            summaries.await()
            assertEquals(2400, repo.state.value.matchSummaries[1]?.playerDamage)
            assertEquals(0, repo.state.value.matchSummaries[1]?.playerHealing)
            repo.close()
            opened(client, storage) { reopened ->
            assertEquals(2400, reopened.state.value.matchSummaries[1]?.playerDamage)
            }
        }
    }

    @Test fun failedSnapshotDoesNotAcknowledgeCacheIngestionAndTheSameCandidateCanRecover() = runTest {
        val memory = MemoryStorage()
        var fail = true
        val storage = object : CacheStorage {
            override suspend fun read(name: String) = memory.read(name)
            override suspend fun write(name: String, text: String) {
                if (fail && name == "deadlock-stats-555.json") error("Disk full")
                memory.write(name, text)
            }
        }
        val client = HttpClient(MockEngine { respond(metadata(1)) })
        opened(client, storage) { repo ->
            repo.onCacheCandidate(reference(1))
            assertEquals("Disk full", repo.state.value.lastPersistenceError)
            assertNull(memory.read("deadlock-stats-555.json"))
            assertNull(repo.state.value.matchDetails[1], "Nothing unsaved is shown")
            fail = false
            repo.onCacheCandidate(reference(1))
            assertNull(repo.state.value.lastPersistenceError)
            repo.close()
            opened(client, storage) { reopened ->
            assertEquals(1L, reopened.state.value.matchHistory.single().matchId)
            assertEquals(2400, reopened.state.value.matchSummaries[1]?.playerDamage)
            assertTrue(reopened.fetchMatchDetail(1).isSuccess)
            assertEquals(1200, reopened.state.value.matchDetails[1]?.durationS)
            }
        }
    }

    @Test fun ingestionAndRefreshesSaveNoMatchDetailPayloadInTheAccountSnapshot() = runTest {
        val storage = RecordingStorage()
        val client = HttpClient(MockEngine { request ->
            if (request.url.host != "replay1.valve.net") respond("[]")
            else respond(metadata(request.url.encodedPath.substringAfterLast('/').substringBefore('_').toLong()))
        })
        opened(client, storage) { repo ->
            repo.onCacheCandidate(reference(1))
            repo.onCacheCandidate(reference(2))
            repo.refresh()
            repo.refreshSocial()
            assertEquals(setOf(1L, 2L), repo.state.value.matchDetails.keys)
            val snapshot = assertNotNull(storage.files["deadlock-stats-555.json"])
            assertFalse("matchDetails" in Json.parseToJsonElement(snapshot).jsonObject, "Details belong to the archive alone")
            assertFalse("\"players\"" in snapshot, "No detail payload is serialized into the snapshot")
            assertEquals(setOf(1L, 2L), DeadlockStatsSnapshotStore(storage).load(account)?.matchSummaries?.map { it.matchId }?.toSet())
            for (id in 1L..2L) assertNotNull(storage.files["steam-match-555-$id.json"])
        }
    }

    @Test fun openingAnAccountReadsNoArchivedDetailAndLoadsThemWhenAsked() = runTest {
        val storage = RecordingStorage()
        val details = (1L..3L).map(::current)
        val archive = MatchDetailArchive(storage)
        details.forEach { archive.save(account.value, it) }
        DeadlockStatsSnapshotStore(storage).save(DeadlockStatsSnapshot(account.value,
            matchHistory = details.map { MatchHistoryEntry(it.matchId, startTime = it.startTime, matchDurationS = it.durationS) }))
        storage.reads.clear(); storage.writes.clear()
        var requests = 0
        val client = HttpClient(MockEngine { requests++; error("Offline") })
        opened(client, storage) { repo ->
            assertEquals(setOf(1L, 2L, 3L), repo.state.value.matchHistory.map { it.matchId }.toSet())
            assertEquals(emptyList(), storage.reads.filter { it.startsWith("steam-match") }, "Opening reads no archived detail")
            assertEquals(emptyList(), storage.writes, "Opening writes nothing")
            assertTrue(repo.state.value.matchDetails.isEmpty())
            assertTrue(repo.fetchMatchDetail(2).isSuccess)
            assertEquals(mapOf(2L to details[1]), repo.state.value.matchDetails)
            assertEquals(0, requests)
        }
    }

    @Test fun switchingAccountsKeepsEachAccountsArchivedDetailsApart() = runTest {
        val storage = RecordingStorage()
        val other = SteamAccountId.ofAccountId(777)!!
        val archive = MatchDetailArchive(storage)
        archive.save(account.value, current(1)); archive.save(account.value, current(2))
        archive.save(other.value, current(1, player = other.value).copy(durationS = 1500))
        val client = HttpClient(MockEngine { error("Offline") })
        opened(client, storage) { repo ->
            assertTrue(repo.fetchMatchDetail(1).isSuccess)
            storage.reads.clear()
            repo.selectAccount(other)
            assertEquals(emptyList(), storage.reads.filter { it.startsWith("steam-match") })
            assertTrue(repo.state.value.matchDetails.isEmpty(), "The previous account's details do not carry over")
            assertTrue(repo.fetchMatchDetail(1).isSuccess)
            assertEquals(mapOf(1L to 1500), repo.state.value.matchDetails.mapValues { it.value.durationS })
            repo.selectAccount(account)
            assertTrue(repo.fetchMatchDetail(2).isSuccess)
            assertEquals(setOf(2L), repo.state.value.matchDetails.keys, "An account opens with no details loaded, whatever it showed before")
            assertTrue(repo.fetchMatchDetail(1).isSuccess)
            assertEquals(1200, repo.state.value.matchDetails.getValue(1).durationS)
            assertEquals(1500, DeadlockStatsSnapshotStore(storage).load(other)?.matchHistory?.single()?.matchDurationS)
        }
    }

    @Test fun anUnreadableArchiveFileIsAMissThatTheNextSaveReplaces() = runTest {
        for ((name, text) in listOf(
            "steam-match-555-1.json" to "not json",
            "steam-match-555-1.json" to Json.encodeToString(MatchDetail.serializer(), current(99)),
            "steam-match-index-555.json" to "not json",
        )) {
            val storage = RecordingStorage()
            MatchDetailArchive(storage).save(account.value, current(1))
            storage.files[name] = text
            val client = apiClient()
            opened(client, storage) { repo ->
                assertTrue(repo.fetchMatchDetail(1).isSuccess, "$name: $text")
                assertEquals(1800, repo.state.value.matchDetails[1]?.durationS, "the API's copy is shown")
                assertNull(repo.state.value.lastPersistenceError)
                assertEquals(1800, MatchDetailArchive(storage).load(account.value, 1)?.durationS, "and saved over $name")
            }
        }
    }

    @Test fun aFailedArchiveWriteIsReportedAndTheSameCandidateRecovers() = runTest {
        for (failing in listOf("steam-match-555-1.json", "steam-match-index-555.json")) {
            val storage = RecordingStorage()
            storage.writeFailure = { name -> if (name == failing) IllegalStateException("Disk full") else null }
            val client = HttpClient(MockEngine { respond(metadata(1)) })
            opened(client, storage) { repo ->
                repo.onCacheCandidate(reference(1))
                assertEquals("Disk full", repo.state.value.lastPersistenceError, failing)
                assertNull(repo.state.value.matchDetails[1], "Nothing unsaved is shown")
                assertTrue(repo.state.value.matchHistory.isEmpty())
                assertNull(storage.files["deadlock-stats-555.json"], "The derived snapshot waits for the archive")
                storage.writeFailure = { null }
                repo.onCacheCandidate(reference(1))
                assertNull(repo.state.value.lastPersistenceError)
                assertEquals(2400, DeadlockStatsSnapshotStore(storage).load(account)?.matchSummaries?.single()?.playerDamage)
                repo.close()
                opened(client, storage) { reopened ->
                assertTrue(reopened.fetchMatchDetail(1).isSuccess)
                assertEquals(1200, reopened.state.value.matchDetails[1]?.durationS)
                }
            }
        }
    }

    @Test fun cancellationWhileReadingOrWritingTheArchivePropagates() = runTest {
        val storage = RecordingStorage()
        storage.writeFailure = { name -> if (name.startsWith("steam-match-")) CancellationException("Closing") else null }
        val client = HttpClient(MockEngine { respond(metadata(1)) })
        opened(client, storage) { repo ->
            assertFailsWith<CancellationException> { repo.onCacheCandidate(reference(1)) }
            assertNull(repo.state.value.lastPersistenceError, "Cancellation is not a failed write")
            assertNull(storage.files["deadlock-stats-555.json"], "Nothing derived from an unsaved record is acknowledged")
            assertNull(repo.state.value.matchDetails[1])
            storage.writeFailure = { null }
            MatchDetailArchive(storage).save(account.value, current(1))
            storage.readFailure = { name -> if (name.startsWith("steam-match-")) CancellationException("Closing") else null }
            assertFailsWith<CancellationException> { repo.fetchMatchDetail(1) }
            assertFailsWith<CancellationException> { repo.refreshSummaries(listOf(1L)) }
        }
    }

    @Test fun openingAnUnchangedArchivedDetailRewritesNoArchiveFile() = runTest {
        val storage = RecordingStorage()
        MatchDetailArchive(storage).save(account.value, current(1))
        storage.writes.clear()
        val client = HttpClient(MockEngine { error("Offline") })
        opened(client, storage) { repo ->
            repeat(2) { assertTrue(repo.fetchMatchDetail(1).isSuccess) }
            assertEquals(emptyList(), storage.writes.filter { it.startsWith("steam-match") }, "An unchanged record is not written again")
            assertEquals(1L, DeadlockStatsSnapshotStore(storage).load(account)?.matchHistory?.single()?.matchId)
        }
    }

    private class RecordingStorage : CacheStorage {
        val files = ConcurrentHashMap<String, String>()
        val reads = CopyOnWriteArrayList<String>()
        val writes = CopyOnWriteArrayList<String>()
        @Volatile var readFailure: (String) -> Throwable? = { null }
        @Volatile var writeFailure: (String) -> Throwable? = { null }
        override suspend fun read(name: String): String? {
            reads += name
            readFailure(name)?.let { throw it }
            return files[name]
        }
        override suspend fun write(name: String, text: String) {
            writeFailure(name)?.let { throw it }
            writes += name
            files[name] = text
        }
    }

    private fun current(id: Long, player: Long = account.value) = MatchDetail(id, durationS = 1200, startTime = id.toInt(), winningTeam = 0,
        fetchedAt = id, players = listOf(MatchParticipant(player, heroId = 2, team = 0)))

    private fun apiClient() = HttpClient(MockEngine { request ->
        val match = request.url.encodedPath.removeSuffix("/metadata").substringAfterLast('/').toLongOrNull()
        if (match == null) respond("[]") else respond("""{"match_info":{"match_id":$match,
            |"duration_s":1800,"winning_team":0,"players":[{"account_id":555,"team":0}]}}""".trimMargin())
    })

    private fun assertHistoricalStats(state: DeadlockStatsState) {
        val totals = state.periodSummary(DateWindow()).totals
        assertEquals(202, totals.matches)
        assertEquals(StatTotal(2400, 2), totals.heroDamage)
        assertEquals(StatTotal(0, 1), totals.healing)
        assertEquals(StatTotal(600, 2), totals.bossDamage)
        assertEquals(StatTotal(100, 1), totals.damageMitigated)
        assertEquals(StatTotal(300, 1), totals.playerDamageTaken)
        assertEquals(StatTotal(0, 1), totals.selfHealing)
        assertEquals(StatRatio(60.0, 2), totals.heroDamagePerMin)
        assertEquals(StatRatio(15.0, 2), totals.objectiveDamagePerMin)
        assertEquals(StatRatio(0.6, 2), totals.accuracy)
        assertEquals(StatRatio(0.25, 2), totals.critRate)
        assertEquals(MatchPlayerSummary(1, 555, 2, 1200, 2400, 0, 600, 30, 10, 20, 5, 100, 300, 0), state.matchSummaries[1])
        assertEquals(MatchPlayerSummary(2, 555, 2, null, 0, null, 0, 0, 10, 0, 0), state.matchSummaries[2])
        assertEquals(setOf(1L, 2L), state.matchSummaries.keys)
    }

    private suspend fun TestScope.opened(client: HttpClient, storage: CacheStorage, clock: Clock = Clock.System, test: suspend (DeadlockStatsRepository) -> Unit) {
        val repo = DeadlockStatsRepository(client, storage, backgroundScope, clock = clock)
        try {
            repo.selectAccount(account)
            test(repo)
        } finally {
            repo.close()
        }
    }

    private fun reference(id: Long) = "http://replay1.valve.net/1422450/${id}_99.meta.bz2".toByteArray()

    private fun metadata(id: Long): ByteArray {
        val stats = when (id) {
            1L -> listOf(
                CMsgMatchMetaDataContents.PlayerStats(time_stamp_s = 1200, player_damage = 2400, player_healing = 0,
                    boss_damage = 600, shots_hit = 30, shots_missed = 10, hero_bullets_hit = 20, hero_bullets_hit_crit = 5,
                    damage_mitigated = 100, player_damage_taken = 300, self_healing = 0),
                CMsgMatchMetaDataContents.PlayerStats(time_stamp_s = 600, player_damage = 500, player_healing = 50),
                CMsgMatchMetaDataContents.PlayerStats(player_damage = 9999),
            )
            2L -> listOf(
                CMsgMatchMetaDataContents.PlayerStats(player_damage = 9999, player_healing = 99),
                CMsgMatchMetaDataContents.PlayerStats(player_damage = 0, boss_damage = 0, shots_hit = 0, shots_missed = 10,
                    hero_bullets_hit = 0, hero_bullets_hit_crit = 0),
            )
            else -> emptyList()
        }
        return metadataFile(CMsgMatchMetaDataContents.MatchInfo(
            match_id = id, duration_s = 1200, start_time = 100, winning_team = 0, game_mode = 1, match_mode = 4,
            players = listOf(
                CMsgMatchMetaDataContents.Players(account_id = 777, hero_id = 3, stats = listOf(CMsgMatchMetaDataContents.PlayerStats(player_damage = 99999))),
                CMsgMatchMetaDataContents.Players(account_id = 555, hero_id = 2, team = 0, stats = stats),
            ),
        ))
    }
}
