package gg.hideouthq.appupdate

import gg.hideouthq.core.appupdate.FeedFetch
import gg.hideouthq.core.appupdate.SignedReleaseFeed
import gg.hideouthq.data.FileCacheStorage
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.security.KeyFactory
import java.security.KeyPairGenerator
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

class ReleaseFeedWiringTest {
    private val keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    private val check = signatureCheck(Base64.getEncoder().encodeToString(keys.public.encoded))
    private val folder = Files.createTempDirectory("hideout-feed")

    private fun sign(bytes: ByteArray): String = Signature.getInstance("Ed25519").run {
        initSign(keys.private)
        update(bytes)
        Base64.getEncoder().encodeToString(sign())
    }

    private fun feed(body: String, signature: String) = SignedReleaseFeed(
        HttpClient(MockEngine { request ->
            respond(if (request.url.encodedPath.endsWith(".sig")) signature else body, HttpStatusCode.OK)
        }),
        "https://example.test/releases.json",
        FileCacheStorage(folder.toFile()),
        Clock.System,
        check,
    )

    @Test
    fun theSignatureChecksTheExactBytes() {
        val bytes = "the feed".encodeToByteArray()
        assertTrue(check(bytes, sign(bytes)))
        assertEquals(false, check("the feed!".encodeToByteArray(), sign(bytes)))
    }

    @Test
    fun aFeedTheKeySignedIsBelievedAndKept() = runBlocking<Unit> {
        val source = feed(Feed, sign(Feed.encodeToByteArray()))
        assertIs<FeedFetch.Fresh>(source.fetch())
        assertNotNull(source.lastGood())
    }

    @Test
    fun aFeedAnotherKeySignedIsRefused() = runBlocking<Unit> {
        val other = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val signature = Signature.getInstance("Ed25519").run {
            initSign(other.private)
            update(Feed.encodeToByteArray())
            Base64.getEncoder().encodeToString(sign())
        }
        assertIs<FeedFetch.Refused>(feed(Feed, signature).fetch())
        assertNull(feed(Feed, signature).lastGood())
    }

    @Test
    fun aSignatureThatIsNotOneIsRefused() = runBlocking<Unit> {
        assertIs<FeedFetch.Refused>(feed(Feed, "not base64 !!").fetch())
        assertIs<FeedFetch.Refused>(feed(Feed, "c2hvcnQ=").fetch())
    }

    @Test
    fun theReleaseKeyIsAnEd25519PublicKey() {
        val key = KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(ReleaseKey)))
        assertEquals("EdDSA", key.algorithm)
    }

    private fun resolve(property: String? = null, environment: String? = null, file: String? = null, native: Boolean = true): String? {
        val userFile = folder.resolve("hideout.properties")
        if (file != null) Files.writeString(userFile, "${ReleaseFeedUrl.Property}=$file\n")
        return ReleaseFeedUrl.resolve(property, environment, userFile, native)
    }

    @Test
    fun aPackagedCopyUsesTheReleaseFeedAndARunFromSourceNeverChecks() {
        assertEquals("https://github.com/hideouthq/hideout/releases/latest/download/releases.json", resolve())
        assertNull(resolve(native = false))
    }

    @Test
    fun thePropertyBeatsTheEnvironmentWhichBeatsTheFile() {
        assertEquals("http://a.test/f.json", resolve("http://a.test/f.json", "http://b.test/f.json", "http://c.test/f.json"))
        assertEquals("http://b.test/f.json", resolve(null, "http://b.test/f.json", "http://c.test/f.json"))
        assertEquals("http://c.test/f.json", resolve(null, null, "http://c.test/f.json"))
    }

    @Test
    fun anOverrideMakesARunFromSourceCheck() = assertEquals("http://localhost:8000/f.json", resolve(environment = "http://localhost:8000/f.json", native = false))

    @Test
    fun anOverrideThatIsNotAUrlMeansNoCheck() = assertNull(resolve(environment = "ftp://a.test/f.json"))

    private companion object {
        val Feed = """
            {"schema": 1, "latest": "1.2.0", "notesUrl": "https://example.test/notes", "versions": [{"version": "1.2.0", "date": "2026-11-01", "kind": "fixes", "fixed": [{"area": "App", "text": "A fix"}]}]}
        """.trimIndent()
    }
}
