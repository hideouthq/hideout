package gg.hideouthq.core.customgames

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class CustomGamesFeedTest {
    private val hash = "a".repeat(64)

    private fun artifact(url: String = "https://example.test/a.zip", bytes: String = "5", sha: String = hash) =
        """{"url":"$url","bytes":$bytes,"sha256":"$sha"}"""

    private fun mode(
        id: String = "arena",
        name: String = "Arena",
        version: String = "1.0.0",
        players: String = """{"min":2,"max":8}""",
        art: String = "{}",
        rules: String = artifact(),
        client: String = "",
    ) = """{"id":"$id","version":"$version","listing":${listing(name, players, art)},"rules":$rules$client}"""

    private fun listing(name: String, players: String, art: String) =
        """{"name":"$name","tagline":"Last one standing.","description":"Fight.","players":$players,"creators":["Hideout team"],"released":"2026-11-01","art":$art}"""

    private fun feed(
        schema: String = "2",
        sequence: String = "7",
        modlock: String = "v0.1.9",
        cli: String = """{"linux":${artifact()},"windows":${artifact()}}""",
        host: String = artifact(),
        platform: String = artifact(),
        renderer: String = artifact(),
        modes: String = mode(),
        upcoming: String? = null,
    ) = """{"schema":$schema,"sequence":$sequence,"runtime":{"modlock":"$modlock","cli":$cli,"host":$host,"platform":$platform,"renderer":$renderer},"modes":[$modes]""" +
        (upcoming?.let { ""","upcoming":[$it]""" } ?: "") + "}"

    private fun upcoming(id: String = "dodgeball", players: String = """{"min":2,"max":12}""") =
        """{"id":"$id","listing":{"name":"Dodgeball","tagline":"One ball.","description":"One ball.","players":$players,"creators":["Hideout team"],"kind":"free-for-all"}}"""

    @Test
    fun anUpcomingModeIsAListingThatNothingCanHostInstallOrJoin() {
        val parsed = assertNotNull(parseCustomGamesFeed(feed(upcoming = upcoming())))
        val dodgeball = parsed.upcoming.single()
        assertEquals(ModeId("dodgeball"), dodgeball.id)
        assertEquals("Dodgeball", dodgeball.listing.name)
        assertNull(dodgeball.listing.released)
        assertNull(parsed.mode(ModeId("dodgeball")), "no version and no packages, so it is no mode")
        assertEquals(listOf(ModeId("arena")), parsed.modes.map { it.id })
        val modes = ModeList(feed = parsed)
        assertNull(modes.hostable(ModeId("dodgeball")))
        assertNull(modes.shownFor(InviteOffer(null, ModeId("dodgeball"), OfferContent.Feed(Sha256.parse(hash)), Choices.None, 4)), "an invite naming it finds nothing to join")
    }

    @Test
    fun aFeedWithoutUpcomingModesHasNone() {
        assertEquals(emptyList(), assertNotNull(parseCustomGamesFeed(feed())).upcoming)
    }

    @Test
    fun anUpcomingModeBreakingAListingRuleRefusesTheFeed() {
        assertNull(parseCustomGamesFeed(feed(upcoming = upcoming(players = """{"min":2,"max":65}"""))))
        assertNull(parseCustomGamesFeed(feed(upcoming = upcoming(id = "Not An Id"))))
    }

    @Test
    fun anUpcomingModeCannotShareAnIdWithAModeOrAnother() {
        assertNull(parseCustomGamesFeed(feed(upcoming = upcoming(id = "arena"))))
        assertNull(parseCustomGamesFeed(feed(upcoming = upcoming() + "," + upcoming())))
    }

    @Test
    fun aWholeFeedReads() {
        val parsed = assertNotNull(
            parseCustomGamesFeed(
                feed(
                    host = artifact(url = "https://example.test/host.zip", bytes = "30000000"),
                    modes = mode(client = ""","client":${artifact(url = "https://example.test/c.vpk")}"""),
                ),
            ),
        )
        assertEquals(7, parsed.sequence)
        assertEquals(setOf(DesktopOs.Linux, DesktopOs.Windows), parsed.runtime.cli.keys)
        assertEquals(Artifact("https://example.test/host.zip", 30000000, Sha256.parse(hash)!!), parsed.runtime.host)
        val only = parsed.modes.single()
        assertEquals(ModeId("arena"), only.id)
        assertEquals("Arena", only.listing.name)
        assertEquals(2..8, only.listing.players)
        val origin = assertIs<ModeOrigin.Feed>(only.origin)
        assertEquals("https://example.test/c.vpk", origin.client?.url)
        assertEquals(10L, only.download, "rules and client")
        assertEquals(only, parsed.mode(ModeId("arena")))
    }

    @Test
    fun aModeWithNoClientUsesStockAssets() {
        assertNull(assertIs<ModeOrigin.Feed>(assertNotNull(parseCustomGamesFeed(feed())).modes.single().origin).client)
    }

    @Test
    fun unknownFieldsAndOperatingSystemsAreIgnored() {
        val text = feed(
            cli = """{"linux":${artifact()},"macos":{"url":"not read"},"future":1}""",
            modes = mode(client = ""","artwork":{"a":1}"""),
        ).replaceFirst("{", """{"relay":{"x":1},""")
        assertEquals(setOf(DesktopOs.Linux), assertNotNull(parseCustomGamesFeed(text)).runtime.cli.keys)
    }

    @Test
    fun aMissingOsIsAllowedButAnEmptyCliMapIsNot() {
        assertEquals(setOf(DesktopOs.Windows), parseCustomGamesFeed(feed(cli = """{"windows":${artifact()}}"""))?.runtime?.cli?.keys)
        assertNull(parseCustomGamesFeed(feed(cli = "{}")))
        assertNull(parseCustomGamesFeed(feed(cli = """{"macos":${artifact()}}""")), "no OS this build knows")
    }

    @Test
    fun anEmptyModeListIsAFeed() {
        assertEquals(emptyList(), parseCustomGamesFeed(feed(modes = ""))?.modes)
    }

    @Test
    fun anUnknownSchemaOrBrokenTopLevelIsRefused() {
        for (schema in listOf("0", "1", "3", "\"new\"")) assertNull(parseCustomGamesFeed(feed(schema = schema)), schema)
        for (sequence in listOf("0", "-1", "\"new\"")) assertNull(parseCustomGamesFeed(feed(sequence = sequence)), sequence)
        for (text in listOf("", "not json", "[]", "{}", """{"schema":1}""")) assertNull(parseCustomGamesFeed(text), text)
    }

    @Test
    fun aBadDigestAnywhereRefusesTheFeed() {
        for (sha in listOf("", "a".repeat(63), "a".repeat(65), "A".repeat(64), "g".repeat(64))) {
            val bad = artifact(sha = sha)
            assertNull(parseCustomGamesFeed(feed(host = bad)), "host $sha")
            assertNull(parseCustomGamesFeed(feed(platform = bad)), "platform $sha")
            assertNull(parseCustomGamesFeed(feed(renderer = bad)), "renderer $sha")
            assertNull(parseCustomGamesFeed(feed(cli = """{"linux":$bad}""")), "cli $sha")
            assertNull(parseCustomGamesFeed(feed(modes = mode(rules = bad))), "rules $sha")
            assertNull(parseCustomGamesFeed(feed(modes = mode(client = ""","client":$bad"""))), "client $sha")
        }
    }

    @Test
    fun nonPositiveOrAbsurdByteCountsAreRefused() {
        for (bytes in listOf("0", "-1", (8L * 1024 * 1024 * 1024 + 1).toString(), Long.MAX_VALUE.toString(), "99999999999999999999")) {
            assertNull(parseCustomGamesFeed(feed(host = artifact(bytes = bytes))), bytes)
        }
        assertNotNull(parseCustomGamesFeed(feed(host = artifact(bytes = (8L * 1024 * 1024 * 1024).toString()))))
    }

    @Test
    fun urlsFollowTheReleaseFeedsRule() {
        assertNotNull(parseCustomGamesFeed(feed(host = artifact(url = "http://example.test/a.zip"))), "the digest protects a plain download")
        for (url in listOf("", "a.zip", "/a.zip", "ftp://example.test/a.zip", "file:///a.zip", "javascript:x")) {
            assertNull(parseCustomGamesFeed(feed(host = artifact(url = url))), url)
        }
    }

    @Test
    fun duplicateModeIdsAreRefused() {
        assertNull(parseCustomGamesFeed(feed(modes = mode() + "," + mode(name = "Other"))))
        assertEquals(2, parseCustomGamesFeed(feed(modes = mode() + "," + mode(id = "duel")))?.modes?.size)
    }

    @Test
    fun playerRangesMustReadForwardAndStayInBounds() {
        for ((min, max) in listOf(8 to 2, 0 to 4, -1 to 4, 1 to 65)) {
            assertNull(parseCustomGamesFeed(feed(modes = mode(players = """{"min":$min,"max":$max}"""))), "$min..$max")
        }
        assertEquals(1..1, parseCustomGamesFeed(feed(modes = mode(players = """{"min":1,"max":1}""")))?.modes?.single()?.listing?.players)
    }

    @Test
    fun aModeWithNoListingRefusesTheFeed() {
        assertNull(parseCustomGamesFeed(feed(modes = """{"id":"arena","version":"1.0.0","rules":${artifact()}}""")))
    }

    @Test
    fun aFeedShowsOnlyArtItServesOverHttps() {
        val art = """{"card":"https://example.test/card.png","tile":"http://example.test/tile.png","backdrop":"art/backdrop.png","screenshots":["https://example.test/s.png","file:///s.png"]}"""
        val listed = assertNotNull(parseCustomGamesFeed(feed(modes = mode(art = art)))).modes.single().listing.art
        assertEquals(ModeArt(card = "https://example.test/card.png", screenshots = listOf("https://example.test/s.png")), listed)
    }

    @Test
    fun anInvitesClientIsFoundOnlyByItsModeAndExactPackage() {
        val other = "b".repeat(64)
        val parsed = ModeList(feed = assertNotNull(parseCustomGamesFeed(feed(modes = mode(client = ""","client":${artifact(sha = other)}""")))))
        val client = Sha256.parse(other)
        assertEquals(other, parsed.forGuest(InviteOffer(null, ModeId("arena"), OfferContent.Feed(client), Choices.None, 8))?.clientDigest?.hex)
        assertNull(parsed.forGuest(InviteOffer(null, ModeId("duel"), OfferContent.Feed(client), Choices.None, 8)))
        assertNull(parsed.forGuest(InviteOffer(null, ModeId("arena"), OfferContent.Feed(Sha256.parse(hash)), Choices.None, 8)))
    }

    @Test
    fun aModeIdCannotNameAnotherFolder() {
        for (id in listOf("", "..", "../x", "a/b", "a\\\\b", "Arena", ".hidden")) {
            assertNull(parseCustomGamesFeed(feed(modes = mode(id = id))), id)
        }
    }

    @Test
    fun aModeNeedsANameAndVersion() {
        assertNull(parseCustomGamesFeed(feed(modes = mode(name = " "))))
        assertNull(parseCustomGamesFeed(feed(modes = mode(version = ""))))
    }

    @Test
    fun aDigestParsesOnlyInItsCanonicalForm() {
        assertEquals(hash, Sha256.parse(hash)?.hex)
        assertNull(Sha256.parse(hash.uppercase()))
        assertNull(Sha256.parse(hash.drop(1)))
    }
}
