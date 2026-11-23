package gg.hideouthq.social

import com.sun.net.httpserver.HttpServer
import gg.hideouthq.core.customgames.Choices
import gg.hideouthq.core.customgames.InviteCode
import gg.hideouthq.core.customgames.InviteOffer
import gg.hideouthq.core.customgames.JoinTicket
import gg.hideouthq.core.customgames.LocalPlayer
import gg.hideouthq.core.customgames.RelayRegion
import gg.hideouthq.core.customgames.SteamId
import gg.hideouthq.core.customgames.social.Delivery
import gg.hideouthq.core.customgames.social.GameStatus
import gg.hideouthq.core.customgames.social.ListingId
import gg.hideouthq.core.customgames.social.ListingToken
import gg.hideouthq.core.customgames.social.ListingWanted
import gg.hideouthq.core.customgames.social.Resolved
import gg.hideouthq.core.data.fake.FakeCustomGamesFeed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.SecureRandom
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

private val Code = checkNotNull(InviteCode.parse("P95-MAN-26D"))
private val Offer = InviteOffer.of(FakeCustomGamesFeed.BebopWars, Choices.None, LocalPlayer("76561198000000002", null))
private val Me = checkNotNull(SteamId.parse("76561198000000001"))
private val Friend = checkNotNull(SteamId.parse("76561198000000002"))

private class FakeSite {
    class Seen(val method: String, val path: String, val body: String, val time: String?, val build: String?, val device: String?)

    val seen = CopyOnWriteArrayList<Seen>()

    @Volatile
    var answer: (Seen) -> Pair<Int, String> = { 204 to "" }
    private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
        createContext("/") { exchange ->
            val request = Seen(
                exchange.requestMethod, exchange.requestURI.rawPath, exchange.requestBody.readBytes().decodeToString(),
                exchange.requestHeaders.getFirst(SiteSigner.TimeHeader), exchange.requestHeaders.getFirst(SiteSigner.BuildHeader), exchange.requestHeaders.getFirst(SiteSigner.DeviceHeader),
            )
            seen += request
            val (status, body) = answer(request)
            val bytes = body.encodeToByteArray()
            exchange.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            if (bytes.isNotEmpty()) exchange.responseBody.write(bytes)
            exchange.close()
        }
        start()
    }
    val url = "http://127.0.0.1:${server.address.port}"

    fun close() = server.stop(0)
}

class SiteSocialTest {
    private val site = FakeSite()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val device = DeviceKey.generate(SecureRandom()).first
    private val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec("k".repeat(64).encodeToByteArray(), "HmacSHA256")) }
    private var clock = System.currentTimeMillis() / 1000
    private val routes = SiteRoutes(OkHttpClient(), SiteSigner(mac, "0.3.0", device) { clock }, site.url)

    @AfterTest
    fun end() {
        scope.cancel()
        site.close()
    }

    private fun until(what: String, holds: () -> Boolean) = runBlocking {
        try {
            withTimeout(10.seconds) { while (!holds()) delay(10.milliseconds) }
        } catch (e: Exception) {
            throw AssertionError("never saw $what", e)
        }
    }

    @Test
    fun aKeptListingIsPutRefreshedAndDeletedWhenNoLongerWanted() {
        val directory = SiteDirectory(routes, scope, refresh = 200.milliseconds)
        val token = ListingToken("0123456789abcdef0123456789abcdef")
        val wanted = ListingWanted(token, JoinTicket(Code, Offer), RelayRegion(4, "fra"), 2, GameStatus.Lobby)
        directory.keep(wanted)
        until("two refreshes") { site.seen.count { it.method == "PUT" } >= 2 }
        val put = site.seen.first()
        assertEquals("/api/games/${token.value}", put.path)
        assertEquals("0.3.0", put.build)
        assertEquals(device.header, put.device)
        val body = Json.parseToJsonElement(put.body).jsonObject
        assertEquals(setOf("code", "offer", "region", "players", "status", "host"), body.keys, "exactly what the site's parseListing takes")
        assertEquals(Code.value, body["code"]!!.jsonPrimitive.content)
        assertEquals("""{"id":4,"code":"fra"}""", body["region"].toString())
        assertEquals("lobby", body["status"]!!.jsonPrimitive.content)

        directory.keep(null)
        until("the delete") { site.seen.any { it.method == "DELETE" } }
        val deleted = site.seen.count()
        runBlocking { delay(500.milliseconds) }
        assertEquals(deleted, site.seen.size, "no refresh after the delete")
        assertEquals("/api/games/${token.value}", site.seen.last().path)
    }

    @Test
    fun aNewTokenTakesTheOldListingDownFirst() {
        val directory = SiteDirectory(routes, scope, refresh = 10.seconds)
        val first = ListingWanted(ListingToken("1".repeat(32)), JoinTicket(Code, Offer), RelayRegion(4, "fra"), 1, GameStatus.Lobby)
        directory.keep(first)
        until("the first put") { site.seen.any { it.method == "PUT" } }
        directory.keep(first.copy(token = ListingToken("2".repeat(32))))
        until("the second put") { site.seen.count { it.method == "PUT" } == 2 }
        assertEquals(listOf("PUT /api/games/${"1".repeat(32)}", "DELETE /api/games/${"1".repeat(32)}", "PUT /api/games/${"2".repeat(32)}"), site.seen.map { "${it.method} ${it.path}" })
    }

    @Test
    fun aListedGameResolvesToItsCodeOnlyWhileItIsOpen() = runBlocking {
        val directory = SiteDirectory(routes, scope)
        val id = checkNotNull(ListingId.parse("3c41a9e07d5b2f18"))
        site.answer = { 200 to """{"id":"${id.value}","code":"${Code.value}","offer":"${Offer.encoded}"}""" }
        assertEquals(Resolved.Open(JoinTicket(Code, Offer)), directory.resolve(id))
        assertEquals("/api/games/${id.value}", site.seen.last().path)
        site.answer = { 410 to """{"error":"closed"}""" }
        assertEquals(Resolved.Gone, directory.resolve(id))
        site.answer = { 409 to """{"error":"full"}""" }
        assertEquals(Resolved.Gone, directory.resolve(id))
        site.close()
        assertEquals(Resolved.Gone, directory.resolve(id), "unreachable")
    }

    @Test
    fun aSlowSiteGivesUpOnAListedGameBeforeTheGamesPanelDoes() = runBlocking {
        val release = CountDownLatch(1)
        site.answer = {
            release.await(30, TimeUnit.SECONDS)
            410 to ""
        }
        try {
            val asked = TimeSource.Monotonic.markNow()
            assertEquals(Resolved.Gone, SiteDirectory(routes, scope).resolve(checkNotNull(ListingId.parse("3c41a9e07d5b2f18"))))
            val took = asked.elapsedNow()
            assertTrue(took < 6.seconds, "the panel waits 8 s for an answer, and this took $took")
        } finally {
            release.countDown()
        }
    }

    @Test
    fun theListReadsRowsAndDropsAnyItCannotRead() = runBlocking {
        val directory = SiteDirectory(routes, scope)
        site.answer = {
            200 to """{"games":[
                {"id":"3c41a9e07d5b2f18","offer":"${Offer.encoded}","region":{"id":4,"code":"fra"},"players":3,"status":"lobby","host":{"kind":"player"}},
                {"id":"nope","offer":"${Offer.encoded}","region":{"id":4,"code":"fra"},"players":3,"status":"lobby","host":{"kind":"player"}},
                {"id":"a17b0c9d2e3f4051","offer":"${Offer.encoded}","region":{"id":4,"code":"fra"},"players":3,"status":"lobby","host":{"kind":"hideout"}}
            ]}"""
        }
        val games = checkNotNull(directory.list())
        assertEquals(listOf("3c41a9e07d5b2f18"), games.map { it.id.value })
        assertEquals(Offer, games.single().offer)
        site.answer = { 503 to "" }
        assertNull(directory.list())
    }

    @Test
    fun anInviteIsDeliveredOnlyWhenTheSiteSaysSo() = runBlocking {
        val mailbox = SiteMailbox(routes, scope, {})
        site.answer = { 200 to """{"delivered":true}""" }
        assertEquals(Delivery.Delivered, mailbox.deliver(Friend, Me, JoinTicket(Code, Offer), 2))
        val sent = site.seen.last()
        assertEquals("/api/mailbox/${Friend.value}/invites", sent.path)
        assertEquals("""{"from":"${Me.value}","code":"${Code.value}","offer":"${Offer.encoded}","players":2}""", sent.body)
        site.answer = { 200 to """{"delivered":false}""" }
        assertEquals(Delivery.NotReached, mailbox.deliver(Friend, Me, JoinTicket(Code, Offer), 2))
        site.answer = { 429 to """{"error":"rate_limited"}""" }
        assertEquals(Delivery.NotReached, mailbox.deliver(Friend, Me, JoinTicket(Code, Offer), 2))
    }

    @Test
    fun aClockOffBeyondTheWindowRetriesOnceAtTheSitesTime() = runBlocking {
        val real = System.currentTimeMillis() / 1000
        clock = real - 3_600
        site.answer = { request -> if (abs(request.time!!.toLong() - System.currentTimeMillis() / 1000) <= 5) 200 to """{"delivered":true}""" else 404 to """{"error":"not_found"}""" }
        assertEquals(Delivery.Delivered, SiteMailbox(routes, scope, {}).deliver(Friend, Me, JoinTicket(Code, Offer), 2), "retried at the time the site's Date said")
        assertEquals(2, site.seen.size)
        assertEquals(Delivery.Delivered, SiteMailbox(routes, scope, {}).deliver(Friend, Me, JoinTicket(Code, Offer), 2))
        assertEquals(3, site.seen.size, "the next request signs at the site's time from the start")
    }

    @Test
    fun aMailboxMessageIsAnInviteOnlyWhenEveryFieldReads() {
        val good = """{"type":"invite","invite":{"id":"${"9f".repeat(16)}","from":"${Friend.value}","code":"${Code.value}","offer":"${Offer.encoded}","players":2,"expires":1763900060000}}"""
        val invite = checkNotNull(parseInvite(good))
        assertEquals(Friend, invite.from)
        assertEquals(JoinTicket(Code, Offer), invite.ticket)
        assertEquals(1_763_900_060_000, invite.expires.toEpochMilliseconds())
        for (bad in listOf(good.replace("invite\",", "other\","), good.replace(Friend.value, "12345"), good.replace("\"players\":2", "\"players\":\"2\""), good.replace("9f".repeat(16), "9F"), "pong", "")) {
            assertNull(parseInvite(bad), bad)
        }
    }

    @Test
    fun theRegionsLineGivesEachMeasuredRegionById() {
        assertEquals(mapOf(4 to 31, 9 to 180), regionsIn("""{"type":"regions","regions":[{"id":4,"code":"fra","ms":31},{"id":7,"code":"nyc"},{"id":9,"code":"sea","ms":180}]}"""))
        assertNull(regionsIn("""{"type":"stopped"}"""))
        assertNull(regionsIn("not json"))
    }
}
