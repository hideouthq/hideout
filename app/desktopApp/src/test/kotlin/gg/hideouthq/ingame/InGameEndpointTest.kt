package gg.hideouthq.ingame

import gg.hideouthq.core.customgames.Choices
import gg.hideouthq.core.customgames.CustomGames
import gg.hideouthq.core.customgames.CustomGamesFetch
import gg.hideouthq.core.customgames.InviteCode
import gg.hideouthq.core.customgames.InviteOffer
import gg.hideouthq.core.customgames.JoinTicket
import gg.hideouthq.core.customgames.LocalPlayer
import gg.hideouthq.core.customgames.MenusPack
import gg.hideouthq.core.customgames.PackToken
import gg.hideouthq.core.customgames.RelayRegion
import gg.hideouthq.core.customgames.Session
import gg.hideouthq.core.customgames.SiteOrigin
import gg.hideouthq.core.customgames.SteamId
import gg.hideouthq.core.customgames.social.InvitationId
import gg.hideouthq.core.customgames.social.Delivery
import gg.hideouthq.core.customgames.social.Directory
import gg.hideouthq.core.customgames.social.GameStatus
import gg.hideouthq.core.customgames.social.Invitation
import gg.hideouthq.core.customgames.social.ListingId
import gg.hideouthq.core.customgames.social.ListingWanted
import gg.hideouthq.core.customgames.social.Mailbox
import gg.hideouthq.core.customgames.social.PublicGame
import gg.hideouthq.core.customgames.social.Resolved
import gg.hideouthq.core.customgames.social.Social
import gg.hideouthq.core.customgames.social.SocialNetwork
import gg.hideouthq.core.data.fake.FakeCustomGamesFeed
import gg.hideouthq.core.data.fake.FakeCustomGamesPlatform
import gg.hideouthq.core.data.fake.FakeSessionFiles
import gg.hideouthq.feature.ingame.CommandResult
import gg.hideouthq.feature.ingame.InGame
import gg.hideouthq.feature.ingame.Topic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Socket
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

private const val Token = "0123456789abcdef0123456789abcdef"
private const val Site = "https://hideouthq.gg"
private const val GameAgent = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Valve Source2 HTML/1.0"
private val Ticket = JoinTicket(checkNotNull(InviteCode.parse("P95-MAN-26D")), InviteOffer.of(FakeCustomGamesFeed.BebopWars, Choices.None, LocalPlayer("76561198000000007", null)))
private const val Host = """{"k":"host","mode":"bebop-wars","picks":{},"listing":"invite-only","invite":[],"restart":null}"""

class InGameEndpointTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val platform = FakeCustomGamesPlatform(chatty = false)
    private val feed = FakeCustomGamesFeed(answer = CustomGamesFetch.Fresh(FakeCustomGamesFeed.SampleModes.copy(modes = listOf(FakeCustomGamesFeed.BebopWars))))
    private val games = CustomGames(platform, FakeSessionFiles(), feed, scope, hostFolders = flowOf(false)).apply { start() }
    private val inGame = InGame(games, Social(games, null, scope).apply { start() }, SiteOrigin.parse(Site), scope)
    private val pack = MenusPack(FakeCustomGamesPlatform.SampleStamp, checkNotNull(PackToken.parse(Token)))
    private val endpoint = InGameEndpoint(inGame, { pack }, {}, keepalive = 300.milliseconds).also { assertTrue(it.start(0)) }
    private val port = checkNotNull(endpoint.port)
    private val sockets = mutableListOf<Socket>()

    @AfterTest
    fun end() {
        sockets.forEach(Socket::close)
        endpoint.close()
        scope.cancel()
    }

    private class Answer(val status: Int, val headers: Map<String, String>, val body: InputStream)

    private fun ask(path: String, method: String = "GET", host: String = "127.0.0.1:$port", origin: String? = Site, agent: String = GameAgent): Answer {
        val socket = Socket("127.0.0.1", port).also { sockets += it }
        socket.soTimeout = 90_000
        val headers = listOfNotNull("Host: $host", origin?.let { "Origin: $it" }, "User-Agent: $agent", "Connection: close")
        socket.getOutputStream().write("$method $path HTTP/1.1\r\n${headers.joinToString("") { "$it\r\n" }}\r\n".encodeToByteArray())
        val input = socket.getInputStream()
        val status = input.line().split(' ')[1].toInt()
        val answered = generateSequence { input.line().takeIf { it.isNotEmpty() } }.associate { it.substringBefore(':').lowercase() to it.substringAfter(':').trim() }
        val body = if (answered["transfer-encoding"] == "chunked") Dechunked(input) else input
        return Answer(status, answered, body)
    }

    private fun command(query: String): Int {
        val answer = ask("/c?$query")
        assertEquals(200, answer.status)
        assertEquals("image/png", answer.headers["content-type"])
        val png = answer.body.readAllBytes()
        assertEquals(1, ByteBuffer.wrap(png, 20, 4).int, "one pixel high")
        return ByteBuffer.wrap(png, 16, 4).int
    }

    private fun press(n: Int, c: String, t: String = Token, p: String = "1", e: String = inGame.epoch) = command("p=$p&t=$t&e=$e&b=4242&n=$n&c=${URLEncoder.encode(c, Charsets.UTF_8)}")

    private fun listed() = runBlocking { withTimeout(60.seconds) { inGame.opened(); games.modes.first { it.feed != null } } }

    @Test
    fun theStreamIsForTheGamesBridgePageAlone() {
        assertEquals(403, ask("/panorama/stream", origin = null).status, "an <img> or a local program sends no Origin")
        assertEquals(403, ask("/panorama/stream", origin = "https://evil.example").status, "another site")
        assertEquals(403, ask("/panorama/stream", agent = "Mozilla/5.0 Firefox/140.0").status, "the bridge page in a desktop browser")
        assertEquals(403, ask("/panorama/stream", host = "rebound.example:$port").status, "a rebound DNS name")
        assertEquals(403, ask("/c?p=1&t=$Token&b=1&n=1&c=%7B%7D", host = "rebound.example:$port").status)

        val preflight = ask("/panorama/stream", method = "OPTIONS")
        assertEquals(204, preflight.status)
        assertEquals(Site, preflight.headers["access-control-allow-origin"])
        assertEquals("true", preflight.headers["access-control-allow-private-network"])

        val stream = ask("/panorama/stream")
        assertEquals(200, stream.status)
        assertEquals(Site, stream.headers["access-control-allow-origin"])
        val first = List(3) { stream.body.frame() }
        assertEquals(listOf("hello", "catalog", "game"), first.map { it["t"]?.jsonPrimitive?.content }, "every topic at once, in the bridge page's packing")
        val hello = runBlocking { inGame.topics.first { it.isNotEmpty() } }.getValue(Topic.Hello)
        assertEquals(Json.parseToJsonElement(hello), first.first())
        generateSequence { stream.body.frame() }.first { it["t"]?.jsonPrimitive?.content == "alive" }
    }

    @Test
    fun aPressReachesTheSessionAndTheOpenStreamFollowsIt() {
        listed()
        val stream = ask("/panorama/stream").body
        generateSequence { stream.frame() }.first { it["t"]?.jsonPrimitive?.content == "game" }

        assertEquals(1, press(1, Host), "accepted")
        val starting = generateSequence { stream.frame() }.first { it["t"]?.jsonPrimitive?.content == "game" && it["host"] is JsonObject }
        assertEquals("starting", starting["host"]!!.jsonObject["phase"]?.jsonPrimitive?.content)
    }

    @Test
    fun aPressNeedsThePacksTokenAndARepeatRunsNothing() {
        listed()
        assertEquals(404, ask("/c?p=1&t=${"f".repeat(32)}&b=1&n=1&c=${URLEncoder.encode(Host, Charsets.UTF_8)}").status, "a web page can't know the token")
        assertEquals(404, ask("/c?p=1&b=1&n=1&c=${URLEncoder.encode(Host, Charsets.UTF_8)}").status)
        assertEquals(Session.Idle, games.live.value.session)

        assertEquals(1, press(1, Host))
        runBlocking { withTimeout(60.seconds) { games.live.first { it.session is Session.HostStarting } } }
        assertEquals(1, press(1, Host), "a retried image gets its first answer")
        assertEquals(1, platform.hostRequests.size, "and hosts nothing twice")
        assertEquals(2, press(2, Host), "a new press while the game runs is busy")
        assertEquals(4, press(1, Host), "an older press than the newest answered is refused")

        assertEquals(6, press(3, Host, p = "0"), "a pack from an older Hideout")
        assertEquals(4, press(4, "{not json"))
        assertEquals(5, press(5, """{"k":"invite","to":["76561198000000007"]}"""))
        assertEquals(404, ask("/elsewhere").status)
    }

    @Test
    fun aPressFromBeforeHideoutRestartedNeverRuns() {
        listed()
        val hello = Json.parseToJsonElement(runBlocking { inGame.topics.first { it.isNotEmpty() } }.getValue(Topic.Hello)).jsonObject
        val before = "p=1&t=$Token&e=${hello["epoch"]?.jsonPrimitive?.content}&b=4242&n=1&c=${URLEncoder.encode(Host, Charsets.UTF_8)}"
        assertEquals(1, command(before))
        runBlocking { withTimeout(60.seconds) { games.live.first { it.session is Session.HostStarting } } }
        games.stop()
        runBlocking { withTimeout(60.seconds) { games.live.first { it.session is Session.Ended } } }

        val restarted = InGame(games, Social(games, null, scope).apply { start() }, SiteOrigin.parse(Site), scope)
        InGameEndpoint(restarted, { pack }, {}).use { other ->
            assertTrue(other.start(0))
            assertEquals(7, runBlocking { withTimeout(10.seconds) { queryFrom(before, other).await() } }, "the image Deadlock retried after the restart is stale")
            assertEquals(5, runBlocking { withTimeout(10.seconds) { pressFrom("4242", """{"k":"servers"}""", other, restarted.epoch).await() } }, "while a press with the new start's epoch is heard")
        }
        assertEquals(1, platform.hostRequests.size, "and hosts nothing twice")
    }

    @Test
    fun aBootIsRememberedHoweverManyOthersPressUntilItHasBeenQuietTenMinutes() {
        val time = TestTimeSource()
        val seen = Seen(time = time)
        var runs = 0
        val press = { runs++; CommandResult.Accepted }
        seen.answer("first", 1, press)
        for (other in 1..40) seen.answer("other$other", 1) { CommandResult.Accepted }
        time += 9.minutes
        seen.answer("first", 1, press)
        assertEquals(1, runs, "a retried image gets its first answer")
        time += 2.minutes
        seen.answer("late", 1) { CommandResult.Accepted }
        seen.answer("first", 1, press)
        assertEquals(2, runs, "a boot quiet that long is forgotten")
    }

    @Test
    fun aServerJoinIsAnsweredBeforeTheDirectoryIs() {
        listed()
        val asked = CompletableDeferred<Unit>()
        val answer = CompletableDeferred<Resolved>()
        val resolves = AtomicInteger()
        val id = checkNotNull(ListingId.parse("3c41a9e07d5b2f18"))
        val directory = object : Directory {
            override suspend fun list(): List<PublicGame> = listOf(PublicGame(id, Ticket.offer, RelayRegion(4, "fra"), 1, GameStatus.Lobby))
            override suspend fun resolve(id: ListingId): Resolved {
                resolves.incrementAndGet()
                asked.complete(Unit)
                return answer.await()
            }
            override fun keep(wanted: ListingWanted?) = Unit
        }
        val mailbox = object : Mailbox {
            override fun hold(owner: SteamId?) = Unit
            override val invitations: Flow<Invitation> = emptyFlow()
            override val refused = MutableStateFlow<SteamId?>(null)
            override suspend fun deliver(id: InvitationId, to: SteamId, from: SteamId, ticket: JoinTicket, players: Int) = Delivery.Delivered
        }
        val social = Social(games, SocialNetwork(mailbox, directory) { null }, scope).apply { start() }
        social.wantServers()
        runBlocking { withTimeout(10.seconds) { social.state.first { it.servers.games.isNotEmpty() } } }
        val served = InGame(games, social, SiteOrigin.parse(Site), scope)
        InGameEndpoint(served, { pack }, {}).use { other ->
            assertTrue(other.start(0))
            val join = """{"k":"join","server":"${id.value}"}"""
            assertEquals(1, runBlocking { withTimeout(2.seconds) { pressFrom("games", join, other, served.epoch).await() } }, "accepted while the directory has not answered")
            runBlocking { withTimeout(10.seconds) { asked.await() } }
            assertEquals(1, runBlocking { withTimeout(2.seconds) { pressFrom("games", join, other, served.epoch).await() } }, "a retried image gets the same answer")
            answer.complete(Resolved.Open(Ticket))
            runBlocking { withTimeout(10.seconds) { games.live.first { it.session is Session.Finding } } }
            assertEquals(1, resolves.get(), "and asks the directory once")
        }
    }

    private fun pressFrom(boot: String, c: String, to: InGameEndpoint, epoch: String) = queryFrom("p=1&t=$Token&e=$epoch&b=$boot&n=1&c=${URLEncoder.encode(c, Charsets.UTF_8)}", to)

    private fun queryFrom(query: String, to: InGameEndpoint) = scope.async(Dispatchers.IO) {
        val port = checkNotNull(to.port)
        val socket = Socket("127.0.0.1", port).also { sockets += it }
        socket.getOutputStream().write("GET /c?$query HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nConnection: close\r\n\r\n".encodeToByteArray())
        val input = socket.getInputStream()
        generateSequence { input.line().takeIf { it.isNotEmpty() } }.count()
        ByteBuffer.wrap(input.readAllBytes(), 16, 4).int
    }

    @Test
    fun aPortAnotherProgramHoldsLeavesTheEndpointOff() {
        assertFalse(InGameEndpoint(inGame, { pack }, {}).start(port))
    }
}

private fun InputStream.frame(): JsonObject {
    val line = String(utf8Line(), Charsets.UTF_8)
    check(line.startsWith("~")) { "not a packed line: $line" }
    val bytes = line.substringAfter(':').map { if (it.code >= 0x100) (it.code - 0x100).toByte() else it.code.toByte() }.toByteArray()
    return Json.parseToJsonElement(bytes.decodeToString()).jsonObject
}

private fun InputStream.utf8Line(): ByteArray {
    val out = ByteArrayOutputStream()
    while (true) {
        val b = read()
        check(b >= 0) { "the stream ended" }
        if (b == '\n'.code) return out.toByteArray()
        out.write(b)
    }
}

private fun InputStream.line(): String = String(utf8Line(), Charsets.ISO_8859_1).removeSuffix("\r")

private class Dechunked(private val input: InputStream) : InputStream() {
    private var left = 0

    override fun read(): Int {
        if (left == 0) {
            left = input.line().substringBefore(';').trim().toInt(16)
            if (left == 0) return -1
        }
        val b = input.read()
        if (--left == 0) input.line()
        return b
    }
}
