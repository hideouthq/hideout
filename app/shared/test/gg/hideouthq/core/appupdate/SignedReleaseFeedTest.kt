package gg.hideouthq.core.appupdate

import gg.hideouthq.core.data.CacheStorage
import gg.hideouthq.core.data.fake.MemoryStorage
import gg.hideouthq.core.library.resourceBytes
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

class SignedReleaseFeedTest {
    private val url = "https://example.test/download/releases.json"
    private val keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    private val body = """{"schema":1,"latest":"1.4.0","notesUrl":"https://example.test/releases","versions":[{"version":"1.4.0","date":"2026-11-02","kind":"feature","highlights":[{"title":"T","text":"X","image":"a.webp"}]}]}"""
    private val fileName = "app-update-feed.json"
    private val now = Instant.fromEpochSeconds(1_800_000_000)
    private val clock = object : Clock { override fun now() = now }

    private fun sign(bytes: ByteArray, key: PrivateKey = keys.private): String =
        Base64.getEncoder().encodeToString(Signature.getInstance("Ed25519").run { initSign(key); update(bytes); sign() })

    private fun verifier(key: PublicKey = keys.public): (ByteArray, String) -> Boolean = { bytes, signature ->
        val public = KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(key.encoded))
        Signature.getInstance("Ed25519").run { initVerify(public); update(bytes); verify(Base64.getDecoder().decode(signature)) }
    }

    private fun source(
        storage: CacheStorage = MemoryStorage(),
        feed: () -> Pair<ByteArray, HttpStatusCode> = { body.encodeToByteArray() to HttpStatusCode.OK },
        signature: () -> Pair<String, HttpStatusCode> = { sign(body.encodeToByteArray()) to HttpStatusCode.OK },
    ) = SignedReleaseFeed(
        HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    if (request.url.toString() == url) {
                        val (bytes, status) = feed()
                        if (status == HttpStatusCode.OK) respond(bytes) else respondError(status)
                    } else {
                        assertEquals("$url.sig", request.url.toString())
                        val (text, status) = signature()
                        if (status == HttpStatusCode.OK) respond(text) else respondError(status)
                    }
                }
            }
        },
        url, storage, clock, verifier(),
    )

    @Test
    fun aGenuineFeedArrivesResolvedAgainstItsUrlAndStampedWithTheClock() = runTest {
        val fresh = assertIs<FeedFetch.Fresh>(source().fetch())
        assertEquals(AppVersion(1, 4, 0), fresh.known.feed.latest)
        assertEquals("https://example.test/download/a.webp", fresh.known.feed.versions.single().highlights.single().imageUrl)
        assertEquals(now, fresh.known.fetchedAt)
    }

    @Test
    fun aSignatureWithALineEndStillVerifies() = runTest {
        assertIs<FeedFetch.Fresh>(source(signature = { sign(body.encodeToByteArray()) + "\n" to HttpStatusCode.OK }).fetch())
    }

    @Test
    fun signaturesThatDoNotProveTheExactBytesAreRefusedAndNothingIsKept() = runTest {
        val stranger = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        for ((name, signature) in listOf(
            "another key's" to sign(body.encodeToByteArray(), stranger.private),
            "another file's" to sign("$body ".encodeToByteArray()),
            "not base64" to "%%%",
            "empty" to "",
        )) {
            val storage = MemoryStorage()
            val fetched = source(storage, signature = { signature to HttpStatusCode.OK }).fetch()
            assertIs<FeedFetch.Refused>(fetched, name)
            assertNull(storage.read(fileName), name)
        }
    }

    @Test
    fun aFeedChangedByABytePastItsSignatureIsRefused() = runTest {
        val changed = body.replace("1.4.0", "9.9.9").encodeToByteArray()
        assertIs<FeedFetch.Refused>(source(feed = { changed to HttpStatusCode.OK }).fetch())
    }

    @Test
    fun aGenuineFeedThisBuildCannotReadIsRefusedAndNothingIsKept() = runTest {
        val newer = """{"schema":2}""".encodeToByteArray()
        val storage = MemoryStorage()
        val fetched = source(storage, feed = { newer to HttpStatusCode.OK }, signature = { sign(newer) to HttpStatusCode.OK }).fetch()
        assertIs<FeedFetch.Refused>(fetched)
        assertNull(storage.read(fileName))
    }

    @Test
    fun aMissingFileOrServerErrorOrNoNetworkIsUnreachable() = runTest {
        assertIs<FeedFetch.Unreachable>(source(feed = { ByteArray(0) to HttpStatusCode.NotFound }).fetch(), "no release yet")
        assertIs<FeedFetch.Unreachable>(source(signature = { "" to HttpStatusCode.NotFound }).fetch(), "feed without a signature")
        assertIs<FeedFetch.Unreachable>(source(feed = { ByteArray(0) to HttpStatusCode.BadGateway }).fetch())
        assertIs<FeedFetch.Unreachable>(source(feed = { throw IOException("no route") }).fetch())
    }

    @Test
    fun theLastGoodFeedComesBackProvenAcrossRestarts() = runTest {
        val storage = MemoryStorage()
        assertNull(source(storage).lastGood(), "nothing kept yet")
        source(storage).fetch()
        val known = assertNotNull(source(storage, feed = { throw IOException("offline") }).lastGood())
        assertEquals(AppVersion(1, 4, 0), known.feed.latest)
        assertEquals(now, known.fetchedAt)
    }

    @Test
    fun aKeptFeedWithTypographicQuotesStillProvesItsBytes() = runTest {
        val storage = MemoryStorage()
        val sample = resourceBytes("appupdate/releases.json")
        assertIs<FeedFetch.Fresh>(source(storage, feed = { sample to HttpStatusCode.OK }, signature = { sign(sample) to HttpStatusCode.OK }).fetch())
        assertTrue(sample.decodeToString().any { it.code > 127 }, "the sample is what this test needs it to be")
        assertNotNull(source(storage).lastGood())
    }

    @Test
    fun aKeptFeedEditedOnDiskIsNoFeedAtAll() = runTest {
        val storage = MemoryStorage()
        source(storage).fetch()
        val kept = assertNotNull(storage.read(fileName))
        storage.write(fileName, kept.replace("1.4.0", "9.9.9"))
        assertNull(source(storage).lastGood())
        storage.write(fileName, "not json")
        assertNull(source(storage).lastGood())
    }

    @Test
    fun aRefusedFetchLeavesTheLastGoodFeedInPlace() = runTest {
        val storage = MemoryStorage()
        source(storage).fetch()
        assertIs<FeedFetch.Refused>(source(storage, signature = { "" to HttpStatusCode.OK }).fetch())
        assertEquals(AppVersion(1, 4, 0), source(storage).lastGood()?.feed?.latest)
    }

    @Test
    fun aFeedThatCannotBeKeptIsStillFresh() = runTest {
        val full = object : CacheStorage {
            override suspend fun read(name: String): String? = null

            override suspend fun write(name: String, text: String) = throw IOException("disk full")
        }
        assertIs<FeedFetch.Fresh>(source(full).fetch())
    }
}
