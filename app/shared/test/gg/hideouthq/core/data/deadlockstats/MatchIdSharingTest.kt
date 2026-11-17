package gg.hideouthq.core.data.deadlockstats

import gg.hideouthq.core.data.fake.MemoryStorage
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class MatchIdSharingTest {
    private val account = SteamAccountId.ofAccountId(555)!!

    private class SaltsEndpoint {
        val uploads = mutableListOf<String>()
        var answer: (Int) -> HttpStatusCode = { HttpStatusCode.OK }
        var hold: CompletableDeferred<Unit>? = null
    }

    @Test
    fun nothingIsSentWhileSharingIsOff() = runTest {
        val api = SaltsEndpoint()
        val repo = repository(api)
        repo.selectAccount(account)

        repo.onCacheCandidate(cacheFile(1), writtenAt = 1_000)
        settle()

        assertEquals(emptyList(), api.uploads)
    }

    @Test
    fun aSharedMatchSendsItsIdClusterAndSaltAndNothingAboutThePlayer() = runTest {
        val api = SaltsEndpoint()
        var request: io.ktor.client.request.HttpRequestData? = null
        val repo = repository(api) { request = it }
        repo.selectAccount(account)
        repo.configureSharing(since = 0)

        repo.onCacheCandidate(cacheFile(37_959_196, cluster = 404, salt = 937_530_290), writtenAt = 100)
        settle()

        assertEquals(listOf("""[{"match_id":37959196,"cluster_id":404,"metadata_salt":937530290}]"""), api.uploads)
        assertEquals(HttpMethod.Post, request?.method)
        assertEquals("https://api.deadlock-api.com/v1/matches/salts", request?.url?.toString())
        assertEquals(ContentType.Application.Json, request?.body?.contentType?.withoutParameters())
    }

    @Test
    fun aMatchSteamCachedBeforeSharingWasTurnedOnIsNeverSent() = runTest {
        val api = SaltsEndpoint()
        val repo = repository(api)
        repo.selectAccount(account)
        repo.configureSharing(since = 500)

        repo.onCacheCandidate(cacheFile(1), writtenAt = 499)
        settle()
        assertEquals(emptyList(), api.uploads, "cached before sharing was on")

        repo.onCacheCandidate(cacheFile(2), writtenAt = 500)
        settle()
        assertEquals(listOf(2L), api.uploads.flatMap(::matchIds))
    }

    @Test
    fun eachMatchIsSentOnceEvenAfterARestart() = runTest {
        val storage = MemoryStorage()
        val api = SaltsEndpoint()
        val repo = repository(api, storage)
        repo.selectAccount(account)
        repo.configureSharing(since = 0)
        repo.onCacheCandidate(cacheFile(1), writtenAt = 100)
        settle()
        repo.onCacheCandidate(cacheFile(1), writtenAt = 200)
        settle()
        repo.close()

        val reopened = repository(api, storage)
        reopened.selectAccount(account)
        reopened.configureSharing(since = 0)
        reopened.onCacheCandidate(cacheFile(1), writtenAt = 300)
        reopened.onCacheCandidate(cacheFile(2), writtenAt = 300)
        settle()

        assertEquals(listOf(1L, 2L), api.uploads.flatMap(::matchIds))
    }

    @Test
    fun throttlingAndServerErrorsAreTriedAgainAFewTimes() = runTest {
        val api = SaltsEndpoint().apply {
            answer = { n -> listOf(HttpStatusCode.TooManyRequests, HttpStatusCode.ServiceUnavailable).getOrElse(n - 1) { HttpStatusCode.OK } }
        }
        val repo = repository(api)
        repo.selectAccount(account)
        repo.configureSharing(since = 0)

        repo.onCacheCandidate(cacheFile(1), writtenAt = 100)
        settle()
        assertEquals(3, api.uploads.size, "accepted on the third try")
        repo.onCacheCandidate(cacheFile(1), writtenAt = 100)
        settle()
        assertEquals(3, api.uploads.size, "an accepted match is not sent again")

        api.answer = { HttpStatusCode.InternalServerError }
        repo.onCacheCandidate(cacheFile(2), writtenAt = 100)
        settle()
        assertEquals(7, api.uploads.size, "a failing server gets four tries")
        repo.onCacheCandidate(cacheFile(2), writtenAt = 100)
        settle()
        assertEquals(11, api.uploads.size, "a match that could not be sent is tried again when found again")
    }

    @Test
    fun aRefusedMatchIsNeitherRetriedNorSentAgain() = runTest {
        val api = SaltsEndpoint().apply { answer = { HttpStatusCode.BadRequest } }
        val repo = repository(api)
        repo.selectAccount(account)
        repo.configureSharing(since = 0)

        repo.onCacheCandidate(cacheFile(1), writtenAt = 100)
        settle()
        repo.onCacheCandidate(cacheFile(1), writtenAt = 100)
        settle()

        assertEquals(1, api.uploads.size)
    }

    @Test
    fun turningSharingOffStopsAnUploadUnderWayAndEveryOneAfter() = runTest {
        val api = SaltsEndpoint().apply { hold = CompletableDeferred() }
        val repo = repository(api)
        repo.selectAccount(account)
        repo.configureSharing(since = 0)
        repo.onCacheCandidate(cacheFile(1), writtenAt = 100)
        repo.onCacheCandidate(cacheFile(2), writtenAt = 100)
        runCurrent()
        assertEquals(1, api.uploads.size, "the first upload is under way")

        repo.configureSharing(since = null)
        api.hold?.complete(Unit)
        repo.onCacheCandidate(cacheFile(3), writtenAt = 100)
        settle()

        assertEquals(1, api.uploads.size)
        repo.configureSharing(since = 0)
        repo.onCacheCandidate(cacheFile(1), writtenAt = 100)
        settle()
        assertEquals(listOf(1L), matchIds(api.uploads.last()), "the stopped upload did not count as sent")
    }

    @Test
    fun manyMatchesGoInBatchesOfAtMostAThousand() = runTest {
        val api = SaltsEndpoint().apply { hold = CompletableDeferred() }
        val repo = repository(api)
        repo.selectAccount(account)
        repo.configureSharing(since = 0)
        repo.onCacheCandidate(cacheFile(1), writtenAt = 100)
        runCurrent()
        for (id in 2L..1501L) repo.onCacheCandidate(cacheFile(id), writtenAt = 100)
        api.hold?.complete(Unit)
        settle()

        assertEquals(listOf(1, 1000, 500), api.uploads.map { matchIds(it).size })
        assertTrue(api.uploads.flatMap(::matchIds).toSet() == (1L..1501L).toSet())
    }

    private fun TestScope.settle() {
        advanceTimeBy(60_000)
        runCurrent()
    }

    private fun TestScope.repository(
        api: SaltsEndpoint,
        storage: MemoryStorage = MemoryStorage(),
        seen: (io.ktor.client.request.HttpRequestData) -> Unit = {},
    ): DeadlockStatsRepository {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val client = HttpClient(MockEngine(MockEngineConfig().apply {
            this.dispatcher = dispatcher
            addHandler { request ->
                if (request.url.encodedPath != "/v1/matches/salts") return@addHandler respondError(HttpStatusCode.NotFound)
                seen(request)
                api.uploads += (request.body as TextContent).text
                val n = api.uploads.size
                api.hold?.await()
                respond("", api.answer(n))
            }
        }))
        return DeadlockStatsRepository(client, storage, backgroundScope, clock = clock(), requestGate = StatsRequestGate(clock()), ioDispatcher = dispatcher)
    }

    private fun cacheFile(matchId: Long, cluster: Long = 1, salt: Long = 99): ByteArray =
        "\u0000\u0001GET http://replay$cluster.valve.net/1422450/${matchId}_$salt.meta.bz2 HTTP/1.1\r\n".toByteArray(Charsets.ISO_8859_1)

    private fun matchIds(upload: String): List<Long> = Regex("\"match_id\":([0-9]+)").findAll(upload).map { it.groupValues[1].toLong() }.toList()
}
