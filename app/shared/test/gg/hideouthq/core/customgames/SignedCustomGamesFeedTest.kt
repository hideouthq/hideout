package gg.hideouthq.core.customgames

import gg.hideouthq.core.data.CacheStorage
import gg.hideouthq.core.data.fake.MemoryStorage
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds

class SignedCustomGamesFeedTest {
    private val url = "https://example.test/custom-games.json"
    private val fileName = "custom-games-feed.json"
    private val storage = MemoryStorage()

    private fun feedText(sequence: Long, name: String = "Arena"): String {
        val artifact = """{"url":"https://example.test/a.zip","bytes":5,"sha256":"${"a".repeat(64)}"}"""
        val listing = """{"name":"$name","tagline":"t","description":"d","players":{"min":2,"max":8},"creators":["c"],"released":"2026-11-01"}"""
        val mode = """{"id":"arena","version":"1.0.0","listing":$listing,"rules":$artifact}"""
        return """{"schema":2,"sequence":$sequence,"runtime":{"modlock":"v1","cli":{"linux":$artifact},"host":$artifact,"platform":$artifact,"renderer":$artifact},"modes":[$mode]}"""
    }

    private fun sign(text: String) = "signed-${text.encodeToByteArray().contentHashCode()}"

    private val genuine: (ByteArray, String) -> Boolean = { bytes, signature -> signature == sign(bytes.decodeToString()) }

    private sealed interface Served {
        data class Feed(val text: String, val signature: String) : Served

        data object Down : Served

        data object Offline : Served
    }

    private fun served(text: String, signature: String = sign(text)) = Served.Feed(text, signature)

    private var answer: Served = Served.Down

    private val source = SignedCustomGamesFeed(
        HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    when (val now = answer) {
                        is Served.Feed -> respond(if (request.url.toString() == url) now.text else now.signature)
                        Served.Down -> respondError(HttpStatusCode.ServiceUnavailable)
                        Served.Offline -> throw IOException("no route")
                    }
                }
            }
        },
        url, storage, genuine,
    )

    private suspend fun fetched(sequence: Long) {
        answer = served(feedText(sequence))
        assertEquals(sequence, assertIs<CustomGamesFetch.Fresh>(source.fetch()).feed.sequence)
    }

    private inner class Overlapping(val older: Long, val newer: Long) : CacheStorage {
        private val bothRead = CompletableDeferred<Unit>()
        private val newerWritten = CompletableDeferred<Unit>()
        private var reads = 0

        override suspend fun read(name: String): String? {
            val kept = storage.read(name)
            synchronized(this) { if (++reads == 2) bothRead.complete(Unit) }
            withTimeoutOrNull(500.milliseconds) { bothRead.await() }
            return kept
        }

        override suspend fun write(name: String, text: String) {
            if ("\"sequence\":$older" in text) withTimeoutOrNull(500.milliseconds) { newerWritten.await() }
            storage.write(name, text)
            if ("\"sequence\":$newer" in text) newerWritten.complete(Unit)
        }
    }

    @Test
    fun twoFetchesAtOnceKeepTheHighestSequence() = runBlocking(Dispatchers.Default) {
        fetched(7)
        val texts = ArrayDeque(listOf(feedText(9), feedText(8)))
        val overlapping = SignedCustomGamesFeed(
            HttpClient(MockEngine) {
                engine {
                    addHandler { request ->
                        respond(if (request.url.toString() == url) synchronized(texts) { texts.removeFirst() } else "signature")
                    }
                }
            },
            url, Overlapping(older = 8, newer = 9), { _, _ -> true },
        )
        val answers = listOf(async { overlapping.fetch() }, async { overlapping.fetch() }).awaitAll()
        assertEquals(9, overlapping.lastGood()?.sequence, "answers: $answers")
    }

    @Test
    fun aGenuineFeedArrivesParsedAndIsKept() = runTest {
        answer = served(feedText(7))
        val fresh = assertIs<CustomGamesFetch.Fresh>(source.fetch())
        assertEquals("Arena", fresh.feed.modes.single().listing.name)
        assertEquals(fresh.feed, source.lastGood())
    }

    @Test
    fun aKeptFeedSurvivesARestart() = runTest {
        fetched(7)
        val restarted = SignedCustomGamesFeed(HttpClient(MockEngine) { engine { addHandler { respondError(HttpStatusCode.NotFound) } } }, url, storage, genuine)
        assertEquals(7, restarted.lastGood()?.sequence)
    }

    @Test
    fun aFeedWhoseSignatureDoesNotMatchIsRefusedAndTheLastGoodStays() = runTest {
        fetched(7)
        answer = served(feedText(8), "signed-by-someone-else")
        assertEquals(CustomGamesFetch.Refused(FeedRefusal.BadSignature), source.fetch())
        assertEquals(7, source.lastGood()?.sequence)
    }

    @Test
    fun aSignedFeedThisVersionCannotReadIsRefusedAndTheLastGoodStays() = runTest {
        fetched(7)
        answer = served("""{"schema":99}""")
        assertEquals(CustomGamesFetch.Refused(FeedRefusal.Unreadable), source.fetch())
        assertEquals(7, source.lastGood()?.sequence)
    }

    @Test
    fun anOlderGenuineFeedIsARollbackAndTheLastGoodStays() = runTest {
        fetched(7)
        answer = served(feedText(6))
        assertEquals(CustomGamesFetch.Refused(FeedRefusal.Rollback), source.fetch())
        assertEquals(7, source.lastGood()?.sequence)
    }

    @Test
    fun theHighestSequenceHoldsAcrossARestart() = runTest {
        fetched(9)
        val older = feedText(8)
        val restarted = SignedCustomGamesFeed(
            HttpClient(MockEngine) { engine { addHandler { request -> respond(if (request.url.toString() == url) older else sign(older)) } } },
            url, storage, genuine,
        )
        assertEquals(CustomGamesFetch.Refused(FeedRefusal.Rollback), restarted.fetch())
    }

    @Test
    fun theSameSequenceAndAHigherOneAreAccepted() = runTest {
        fetched(7)
        answer = served(feedText(7, "Arena again"))
        assertEquals("Arena again", assertIs<CustomGamesFetch.Fresh>(source.fetch()).feed.modes.single().listing.name)
        fetched(8)
        assertEquals(8, source.lastGood()?.sequence)
    }

    @Test
    fun beingOfflineOrDownLeavesTheLastGoodFeed() = runTest {
        fetched(7)
        for (trouble in listOf(Served.Offline, Served.Down)) {
            answer = trouble
            assertIs<CustomGamesFetch.Unreachable>(source.fetch(), trouble.toString())
            assertEquals(7, source.lastGood()?.sequence, trouble.toString())
        }
    }

    @Test
    fun aKeptFeedEditedOnDiskIsNoFeedAtAll() = runTest {
        fetched(7)
        val kept = storage.read(fileName)!!
        storage.write(fileName, kept.replace("Arena", "Forged"))
        assertNull(source.lastGood())
    }

    @Test
    fun aKeptFileThatCannotBeReadIsNoFeedAtAll() = runTest {
        storage.write(fileName, "not json")
        assertNull(source.lastGood())
    }
}
