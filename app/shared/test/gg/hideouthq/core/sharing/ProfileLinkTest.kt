package gg.hideouthq.core.sharing

import gg.hideouthq.AppContainer
import gg.hideouthq.core.customgames.LinkProblem
import gg.hideouthq.core.customgames.SiteOrigin
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProfileLinkTest {
    private val site = checkNotNull(SiteOrigin.parse("https://hideouthq.gg"))
    private val shared = SharedProfile("Ranked", listOf(SharedMod(700_001, setOf(7)), SharedMod(731_748)))

    @Test
    fun aCodeReadsTheWayPeopleTypeIt() {
        assertEquals("K7Q2M9XD", ShareCode.parse("K7Q2M9XD")?.value)
        assertEquals("K7Q2M9XD", ShareCode.parse(" k7q2-m9xd ")?.value)
        assertEquals("10K7Q2M9", ShareCode.parse("IOk7Q2M9")?.value, "I reads as 1 and O as 0")
        assertEquals("1K7Q2M9X", ShareCode.parse("lk7q2m9x")?.value, "L reads as 1")
        assertEquals("K7Q2M9XDK7Q2", ShareCode.parse("K7Q2M9XDK7Q2")?.value, "up to 12 characters")
        listOf("K7Q2M9X", "K7Q2M9XDK7Q2M", "K7Q2M9XU", "K7Q2M9X!").forEach { assertNull(ShareCode.parse(it), it) }
        assertEquals("https://hideouthq.gg/p/K7Q2M9XD", ShareCode.parse("K7Q2M9XD")?.webLink(site))
    }

    @Test
    fun everyFormOfALinkReadsToItsCodeOrItsProfile() {
        val code = ProfileLink.Code(checkNotNull(ShareCode.parse("K7Q2M9XD")))
        listOf("K7Q2M9XD", "k7q2-m9xd", "hideout://p/K7Q2M9XD", "HIDEOUT://p/K7Q2M9XD/", "https://hideouthq.gg/p/K7Q2M9XD", "https://localhost:8787/p/k7q2m9xd?utm=x", " https://hideouthq.gg/p/K7Q2M9XD/\n")
            .forEach { assertEquals(code, ProfileLink.parse(it), it) }
        val whole = ProfileLink.Whole(shared)
        listOf(shared.encoded, shared.longLink(null), shared.longLink(site), "https://other.example/profile#${shared.encoded}").forEach { assertEquals(whole, ProfileLink.parse(it), it) }
        assertEquals("hideout://profile/${shared.encoded}", shared.longLink(null))
        assertEquals("https://hideouthq.gg/profile#${shared.encoded}", shared.longLink(site))
        listOf("", "hello", "hideout://join/P95-MAN-26D", "https://hideouthq.gg/join#P95MAN26D", "https://hideouthq.gg/p/", "hideout://profile/AQ", "https://hideouthq.gg/q/K7Q2M9XD", "x".repeat(20_000))
            .forEach { assertNull(ProfileLink.parse(it), it.take(40)) }
    }

    @Test
    fun profileLinksGoToTheImportAndEveryOtherAppLinkToCustomGames() = runTest {
        val container = AppContainer.fake()
        val games = checkNotNull(container.customGames)
        container.openAppLink("hideout://p/K7Q2M9XD")
        assertEquals("hideout://p/K7Q2M9XD", container.profileImports.incoming.first())
        container.openAppLink("hideout://profile/garbage")
        assertEquals("hideout://profile/garbage", container.profileImports.incoming.first(), "an unreadable profile link still goes to the import, which says so")
        container.openAppLink("hideout://join/P95-MAN-26D")
        container.openAppLink("hideout://whatever")
        assertNull(withTimeoutOrNull(100) { container.profileImports.incoming.first() }, "join and unknown links never reach the import")
        assertEquals(LinkProblem.Unreadable, games.linkProblem.first { it != null }, "custom games still answers an unknown link as it did")
        assertTrue(ProfileLink.isAppLink("hideout://P/K7Q2M9XD"))
        assertFalse(ProfileLink.isAppLink("hideout://join/P95-MAN-26D"))
        assertFalse(ProfileLink.isAppLink("https://hideouthq.gg/p/K7Q2M9XD"))
    }

    @Test
    fun theSiteKeepsTheProfilesBytesAndHandsBackItsCode() = runBlocking<Unit> {
        val sent = mutableListOf<HttpRequestData>()
        val shares = SiteProfileShares(client { request -> sent += request; respond("""{"code":"K7Q2M9XD"}""", headers = json) }, site)
        assertEquals("K7Q2M9XD", shares.keep(shared)?.value)
        val request = sent.single()
        assertEquals(HttpMethod.Post, request.method)
        assertEquals("https://hideouthq.gg/api/profiles", request.url.toString())
        assertEquals(ContentType.Application.OctetStream, request.body.contentType)
        assertContentEquals(shared.bytes(), (request.body as OutgoingContent.ByteArrayContent).bytes())
    }

    @Test
    fun whenTheSiteCantKeepItTheresNoCode() = runBlocking<Unit> {
        assertNull(SiteProfileShares(client { respondError(HttpStatusCode.TooManyRequests) }, site).keep(shared))
        assertNull(SiteProfileShares(client { respondError(HttpStatusCode.PayloadTooLarge) }, site).keep(shared))
        assertNull(SiteProfileShares(client { throw IOException("offline") }, site).keep(shared))
        assertNull(SiteProfileShares(client { respond("not json") }, site).keep(shared))
        assertNull(ProfileShares.None.keep(shared))
    }

    @Test
    fun aCodeTheSiteDoesntKnowIsMissingAndAnUnansweredOneIsUnreachable() = runBlocking<Unit> {
        val code = checkNotNull(ShareCode.parse("K7Q2M9XD"))
        val asked = mutableListOf<String>()
        val found = SiteProfileShares(client { request -> asked += request.url.toString(); respond(shared.bytes()) }, site).find(code)
        assertEquals(SharedLookup.Found(shared), found)
        assertEquals(listOf("https://hideouthq.gg/api/profiles/K7Q2M9XD"), asked)
        assertEquals(SharedLookup.Missing, SiteProfileShares(client { respondError(HttpStatusCode.NotFound) }, site).find(code))
        assertEquals(SharedLookup.Missing, SiteProfileShares(client { respond(byteArrayOf(9, 9)) }, site).find(code), "bytes that aren't a profile")
        assertEquals(SharedLookup.Unreachable, SiteProfileShares(client { throw IOException("offline") }, site).find(code))
        assertEquals(SharedLookup.Unreachable, SiteProfileShares(client { respondError(HttpStatusCode.BadGateway) }, site).find(code))
        assertEquals(SharedLookup.Unreachable, ProfileShares.None.find(code))
    }

    private fun client(answer: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) = HttpClient(MockEngine(answer)) { expectSuccess = false }

    private val json = headersOf("Content-Type", "application/json")
}
