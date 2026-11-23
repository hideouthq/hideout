package gg.hideouthq.core.customgames

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class LinkEventsTest {
    private fun server(event: String) = parseLinkEvent("""{"type":"server","event":$event}""")

    @Test
    fun theHelpersOwnLinesRead() {
        val code = InviteCode.parse("P95MAN26D")!!
        val offer = InviteOffer(null, ModeId("arena"), OfferContent.Feed(null), Choices.None, 4)
        val lines = listOf(
            """{"type":"invite","code":"P95MAN26D"}""" to LinkEvent.InviteReady(code),
            """{"type":"invite","code":"P95MAN26D","region":{"id":4,"code":"fra"}}""" to LinkEvent.InviteReady(code, RelayRegion(4, "fra")),
            """{"type":"invite","code":"P95MAN26D","region":{"id":0,"code":"FRA"}}""" to LinkEvent.InviteReady(code),
            """{"type":"offer","offer":"${offer.encoded}"}""" to LinkEvent.Offer(offer),
            """{"type":"offer","offer":"not an offer"}""" to LinkEvent.BadOffer,
            """{"type":"offer"}""" to LinkEvent.BadOffer,
            """{"type":"linked","game":"127.0.0.1:5","screen":"127.0.0.1:6","ms":9}""" to LinkEvent.Linked,
            """{"type":"linked"}""" to LinkEvent.Linked,
            """{"type":"entered"}""" to LinkEvent.Entered,
            """{"type":"enter-expired"}""" to LinkEvent.NotEntered(siteUnreachable = false),
            """{"type":"enter-expired","page":"unreachable"}""" to LinkEvent.NotEntered(siteUnreachable = true),
            """{"type":"staged","held":[]}""" to null,
            """{"type":"path","kind":"none"}""" to LinkEvent.Path(null),
            """{"type":"game","state":"flowing"}""" to LinkEvent.Game(GameTraffic.Flowing),
            """{"type":"game","state":"lost"}""" to LinkEvent.Game(GameTraffic.Lost),
            """{"type":"game","state":"quiet"}""" to LinkEvent.Game(GameTraffic.Quiet),
            """{"type":"game","state":"sideways"}""" to null,
            """{"type":"guests","direct":2,"relay":1}""" to LinkEvent.Guests(GuestPaths(direct = 2, relay = 1)),
            """{"type":"guests","direct":0,"relay":0}""" to LinkEvent.Guests(GuestPaths()),
            """{"type":"path","kind":"direct","latencyMs":4}""" to LinkEvent.Path(LinkPath.Direct),
            """{"type":"path","kind":"relay","latencyMs":40}""" to LinkEvent.Path(LinkPath.Relay),
            """{"type":"stopped","reason":"stdin-closed"}""" to LinkEvent.Stopped(LinkStop.StdinClosed),
            """{"type":"stopped","reason":"server-exited"}""" to LinkEvent.Stopped(LinkStop.ServerExited),
            """{"type":"stopped","reason":"listen-failed","message":"in use"}""" to LinkEvent.Stopped(LinkStop.ListenFailed, "in use"),
            """{"type":"fetch","done":4194304,"total":64169336}""" to LinkEvent.Fetch(Transfer(4_194_304, 64_169_336)),
            """{"type":"fetch","done":1}""" to null,
            """{"type":"fetched"}""" to LinkEvent.Fetched,
        )
        for ((line, expected) in lines) assertEquals(expected, parseLinkEvent(line), line)
    }

    @Test
    fun aModesReportedScoreReadsAsItsSections() {
        val line = """{"type":"state","state":{"scoreboard":{"sides":[{"score":3,"slots":[0]},{"score":2,"slots":[1]}],"notes":["First to 5","Round 6"]}}}"""
        val board = SectionState.Scoreboard(listOf(ScoreSide(3, listOf(0)), ScoreSide(2, listOf(1))), listOf("First to 5", "Round 6"))
        assertEquals(LinkEvent.Reported(listOf(board)), parseLinkEvent(line))
        assertNull(parseLinkEvent("""{"type":"state","state":{"teampicker":{"x":1}}}"""), "nothing this Hideout knows")
        assertNull(parseLinkEvent("""{"type":"state","state":[1]}"""))
    }

    @Test
    fun aSectionThatBreaksALimitIsSkippedAndTheRestRead() {
        fun board(body: String) = parseSectionState(Json.parseToJsonElement("""{"scoreboard":$body,"teampicker":{}}""").jsonObject)
        assertEquals(listOf(SectionState.Scoreboard(listOf(ScoreSide(0, emptyList())), emptyList())), board("""{"sides":[{"score":0}]}"""))
        val broken = listOf(
            """{"sides":[]}""" to "no sides",
            """{"sides":[${(1..17).joinToString(",") { """{"score":$it}""" }}]}""" to "more sides than a board shows",
            """{"sides":[{"score":"3"}]}""" to "a score that isn't a number",
            """{"sides":[{"score":1.5}]}""" to "a score that isn't whole",
            """{"sides":[{"score":1,"slots":[-1]}]}""" to "a slot no player has",
            """{"sides":[{"score":1}],"notes":["a","b","c"]}""" to "more than two notes",
            """{"sides":[{"score":1}],"notes":["${"n".repeat(49)}"]}""" to "a note too long for its line",
            """{"sides":[{"score":1}],"notes":["   "]}""" to "a blank note",
            """[1]""" to "not an object",
        )
        for ((body, why) in broken) assertEquals(emptyList(), board(body), why)
    }

    @Test
    fun everyStopReasonReads() {
        val reasons = mapOf(
            "stdin-closed" to LinkStop.StdinClosed,
            "signal" to LinkStop.Signal,
            "server-exited" to LinkStop.ServerExited,
            "server-start-failed" to LinkStop.ServerStartFailed,
            "server-dead" to LinkStop.ServerDead,
            "link-timeout" to LinkStop.LinkTimeout,
            "not-found" to LinkStop.NotFound,
            "link-lost" to LinkStop.LinkLost,
            "host-ended" to LinkStop.HostEnded,
            "host-failed" to LinkStop.HostFailed,
            "bad-invite" to LinkStop.BadInvite,
            "relay-unavailable" to LinkStop.RelayUnavailable,
            "listen-failed" to LinkStop.ListenFailed,
            "fetch-failed" to LinkStop.FetchFailed,
            "fetch-mismatch" to LinkStop.FetchMismatch,
        )
        assertEquals(LinkStop.entries.toSet() - LinkStop.Unknown, reasons.values.toSet())
        for ((wire, stop) in reasons) {
            assertEquals(LinkEvent.Stopped(stop), parseLinkEvent("""{"type":"stopped","reason":"$wire"}"""))
        }
    }

    @Test
    fun anUnknownOrMissingStopReasonIsUnknown() {
        val unknown = LinkEvent.Stopped(LinkStop.Unknown)
        assertEquals(unknown, parseLinkEvent("""{"type":"stopped","reason":"solar-flare"}"""))
        assertEquals(unknown, parseLinkEvent("""{"type":"stopped"}"""))
        assertEquals(unknown, parseLinkEvent("""{"type":"stopped","reason":null}"""))
        assertEquals(unknown, parseLinkEvent("""{"type":"stopped","reason":{"a":1}}"""))
    }

    @Test
    fun modlocksNestedEventsRead() {
        val events = listOf(
            """{"host":{"ready":{"map":"street_test"}}}""" to ServerEvent.Ready("street_test"),
            """{"host":{"ready":{}}}""" to ServerEvent.Ready(""),
            """{"host":{"failed":{"mod":"arena","error":"boom"}}}""" to ServerEvent.Failed("boom"),
            """{"host":{"failed":{"error":"interface broke"}}}""" to ServerEvent.Failed("interface broke"),
            """{"host":{"joined":{"slot":3,"name":"Ann","steamId":"76561198000000001"}}}""" to ServerEvent.Joined(3, Player("Ann", "76561198000000001")),
            """{"host":{"joined":{"name":"First"}}}""" to ServerEvent.Joined(0, null),
            """{"host":{"joined":{"slot":4,"name":"Bot","steamId":"0"}}}""" to ServerEvent.Joined(4, null),
            """{"host":{"left":{"slot":3}}}""" to ServerEvent.Left(3),
            """{"host":{"left":{}}}""" to ServerEvent.Left(0),
        )
        for ((event, expected) in events) assertEquals(LinkEvent.Server(expected), server(event), event)
    }

    @Test
    fun aSandboxFallbackCountsAsAFailedServer() {
        assertEquals(LinkEvent.Server(ServerEvent.Failed("no game here")), server("""{"sandbox":{"reason":"no game here"}}"""))
    }

    @Test
    fun aSteamIdReadsAsANumberToo() {
        assertEquals(LinkEvent.Server(ServerEvent.Joined(1, Player("", "76561198000000001"))), server("""{"host":{"joined":{"slot":1,"steamId":76561198000000001}}}"""))
    }

    @Test
    fun unreadableLinesAreNull() {
        val lines = listOf(
            "", " ", "not json", "[]", "5", "null", "{", "{}", """{"type":5}""", """{"type":"future"}""",
            """{"type":"invite"}""", """{"type":"invite","code":"not an address"}""", """{"type":"invite","code":{}}""",
            """{"type":"server"}""", """{"type":"server","event":5}""", """{"type":"server","event":{}}""",
            """{"type":"path","kind":"carrier-pigeon","latencyMs":1}""", """{"type":"path"}""",
            """{"type":"stopped"} trailing""",
        )
        for (line in lines) assertNull(parseLinkEvent(line), line)
    }

    @Test
    fun linesAndServerEventsTheSessionDoesNotActOnAreNull() {
        val lines = listOf(
            """{"type":"flow-open","flow":1}""",
            """{"type":"flow-close","flow":1,"upBytes":2,"downBytes":3}""",
            """{"type":"server-log","line":"not json <x>"}""",
        )
        for (line in lines) assertNull(parseLinkEvent(line), line)
        val events = listOf(
            """{"host":{"ui":{"mod":"arena","slot":2,"change":{"reset":true,"removed":["a"],"generation":"2"}}}}""",
            """{"host":{"slowFrame":{"mod":"arena","report":{"tookMicros":"900"}}}}""",
            """{"host":{"started":{"mod":"arena","reloaded":true}}}""",
            """{"note":"opening the game"}""",
            """{"built":{"dir":"/m","ok":true}}""",
            """{"hosting":{"joinCode":"x"}}""",
            """{"host":{"ready":"<a&b>"}}""",
            """{"host":5}""",
            """{"host":{}}""",
            """{"future":{"a":1}}""",
        )
        for (event in events) assertNull(server(event), event)
    }

    @Test
    fun itNeverThrows() {
        val samples = listOf(
            """{"type":"server","event":{"host":{"joined":{"slot":3,"name":"Ann","steamId":"7"}}}}""",
            """{"type":"stopped","reason":"server-exited","message":"x"}""",
            """{"type":"linked","game":"127.0.0.1:5","screen":"127.0.0.1:6","ms":9}""",
        )
        val random = Random(42)
        repeat(20_000) {
            val chars = samples.random(random).toCharArray()
            repeat(random.nextInt(1, 4)) { chars[random.nextInt(chars.size)] = random.nextInt(0, 0x250).toChar() }
            parseLinkEvent(chars.concatToString())
        }
        parseLinkEvent("[".repeat(200_000))
        parseLinkEvent("""{"a":""".repeat(200_000))
        parseLinkEvent("""{"type":"server","event":{"host":{"joined":{"slot":99999999999999999999}}}}""")
    }

    @Test
    fun aHelperMessageThatQuotesAnInviteReachesNoScreenWithIt() {
        val address = "tcFriendSecretAddressThatLetsAnyoneIn"
        val stopped = assertIs<LinkEvent.Stopped>(parseLinkEvent("""{"type":"stopped","reason":"server-exited","message":"could not join $address"}"""))
        assertEquals("could not join [invite]", stopped.message)
        val failed = assertIs<LinkEvent.Server>(parseLinkEvent("""{"type":"server","event":{"host":{"failed":{"error":"bad $address"}}}}""")).event
        assertEquals(ServerEvent.Failed("bad [invite]"), failed)
    }
}
