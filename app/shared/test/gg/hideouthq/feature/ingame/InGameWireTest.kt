package gg.hideouthq.feature.ingame

import gg.hideouthq.core.customgames.Choices
import gg.hideouthq.core.customgames.DeadlineToken
import gg.hideouthq.core.customgames.DisplayName
import gg.hideouthq.core.customgames.EndReason
import gg.hideouthq.core.customgames.GameRecord
import gg.hideouthq.core.customgames.Invite
import gg.hideouthq.core.customgames.InviteCode
import gg.hideouthq.core.customgames.InviteOffer
import gg.hideouthq.core.customgames.Listing
import gg.hideouthq.core.customgames.LiveSession
import gg.hideouthq.core.customgames.LocalPlayer
import gg.hideouthq.core.customgames.ModeId
import gg.hideouthq.core.customgames.ModeList
import gg.hideouthq.core.customgames.Outreach
import gg.hideouthq.core.customgames.OwnGame
import gg.hideouthq.core.customgames.Player
import gg.hideouthq.core.customgames.RestartReason
import gg.hideouthq.core.customgames.Role
import gg.hideouthq.core.customgames.Session
import gg.hideouthq.core.customgames.SettingKey
import gg.hideouthq.core.customgames.SettingValue
import gg.hideouthq.core.customgames.SiteOrigin
import gg.hideouthq.core.customgames.SteamId
import gg.hideouthq.core.customgames.Transfer
import gg.hideouthq.core.customgames.social.InvitationId
import gg.hideouthq.core.customgames.social.ListingId
import gg.hideouthq.core.data.fake.FakeCustomGamesFeed
import gg.hideouthq.core.customgames.JoinTicket
import gg.hideouthq.core.customgames.OfferContent
import gg.hideouthq.core.customgames.RelayRegion
import gg.hideouthq.core.customgames.social.Delivery
import gg.hideouthq.core.customgames.social.GameStatus
import gg.hideouthq.core.customgames.social.Hosted
import gg.hideouthq.core.customgames.social.Invitation
import gg.hideouthq.core.customgames.social.ListingToken
import gg.hideouthq.core.customgames.social.PublicGame
import gg.hideouthq.core.customgames.social.Servers
import gg.hideouthq.core.customgames.social.ServersRead
import gg.hideouthq.core.customgames.social.SocialState
import gg.hideouthq.core.customgames.social.SocialStatus
import gg.hideouthq.core.data.fake.FakeCustomGamesPlatform
import gg.hideouthq.feature.customgames.PresentInputs
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

/** The Panorama side's tests read the same golden files. HIDEOUT_WRITE_FIXTURES=1 rewrites them; read the diff. */
class InGameWireTest {
    private val fixtures: Path = Path.of(System.getProperty("user.dir")).resolve("../../ingame/fixtures").normalize()
    private val writing = System.getenv("HIDEOUT_WRITE_FIXTURES") == "1"

    private fun golden(name: String, actual: String) {
        val file = fixtures.resolve(name)
        if (writing) {
            Files.createDirectories(fixtures)
            Files.writeString(file, actual)
        }
        assertEquals(Files.readString(file), actual, "$name is not what the app sends; rerun with HIDEOUT_WRITE_FIXTURES=1 if the change is meant")
    }

    private val code = checkNotNull(InviteCode.parse("P95-MAN-26D"))
    private val me = LocalPlayer("76561198000000001", null)
    private val kestrel = checkNotNull(SteamId.parse("76561198000000007"))
    private val bebop = FakeCustomGamesFeed.BebopWars
    private val threes = checkNotNull(Choices.defaultsOf(bebop.listing).with(bebop.listing.settings.single(), checkNotNull(SettingValue.parse("3v3"))))
    private val modes = ModeList(feed = FakeCustomGamesFeed.SampleModes.copy(modes = listOf(FakeCustomGamesFeed.SampleMode, bebop)))
    private val hostRecord = GameRecord(Role.Host(bebop, threes, Outreach(Listing.Public, setOf(kestrel))), me)
    private val site = SiteOrigin.parse("https://hideouthq.gg")

    private suspend fun frames(live: LiveSession, social: SocialView = SocialView(), restarts: RestartReason? = null, transfer: Transfer? = null) =
        inGameFrames(PresentInputs(live, restarts, transfer, modes, site = site), social)

    private fun invitation(id: String) = checkNotNull(InvitationId.parse(id))

    private fun listing(id: String) = checkNotNull(ListingId.parse(id))

    @Test
    fun framesAreTheGoldenOnes() = runTest {
        val idle = frames(LiveSession())
        golden("hello.json", idle.getValue(Topic.Hello))
        golden("catalog.json", idle.getValue(Topic.Catalog))
        golden("game-idle.json", idle.getValue(Topic.Game))
        golden("alive.json", InGameWire.encode(Frame.Alive))

        val starting = frames(LiveSession(Session.HostPreparing(DeadlineToken(1)), hostRecord), restarts = RestartReason.NotSetUp, transfer = Transfer(7_700_000, 12_048_000))
        golden("game-starting.json", starting.getValue(Topic.Game))

        val hosting = Session.Hosting(code, "street", mapOf(0 to Player("nori", me.steamId)), OwnGame.In)
        golden("game-ready.json", frames(LiveSession(hosting, hostRecord.copy(startedAt = Instant.fromEpochSeconds(1_800_000_000)))).getValue(Topic.Game))

        golden("game-failed.json", frames(LiveSession(Session.Ended(EndReason.HostFailed("the map did not load")), hostRecord)).getValue(Topic.Game))

        val offer = InviteOffer.of(bebop, threes, LocalPlayer("76561198000000002", DisplayName.parse("Mugi")))
        val review = LiveSession(Session.Confirming(Invite(code, offer)), GameRecord(Role.Guest(code, offer, bebop.listing), me))
        golden("game-review.json", frames(review, restarts = RestartReason.GameUpdated).getValue(Topic.Game))
    }

    @Test
    fun socialFramesAreTheGoldenOnes() = runTest {
        val nori = checkNotNull(SteamId.parse("76561198000000008"))
        val wren = checkNotNull(SteamId.parse("76561198000000009"))
        val mugi = LocalPlayer("76561198000000002", DisplayName.parse("Mugi"))
        val offer = InviteOffer.of(bebop, threes, mugi)
        val fra = RelayRegion(4, "fra")
        val hosting = Session.Hosting(code, "street", mapOf(0 to Player("nori", me.steamId)), OwnGame.In, region = fra)
        val record = GameRecord(Role.Host(bebop, threes, Outreach(Listing.Public, setOf(kestrel, nori, wren)), offer), me, startedAt = Instant.fromEpochSeconds(1_800_000_000))
        val folder = InviteOffer(null, ModeId("arena"), OfferContent.Host(FakeCustomGamesPlatform.SampleShared), Choices.None, 4)
        val state = SocialState(
            inbox = listOf(
                Invitation(invitation("9f2c41d07e6b4a58b3c1d2e3f4a5b6c7"), checkNotNull(SteamId.parse("76561198000000002")), JoinTicket(code, offer), 2, Instant.fromEpochMilliseconds(1_763_900_060_000)),
                Invitation(invitation("0b7e5d21c3a94f68a1b2c3d4e5f60718"), checkNotNull(SteamId.parse("76561198000000003")), JoinTicket(code, folder), 1, Instant.fromEpochMilliseconds(1_763_900_045_000)),
            ),
            hosted = Hosted(code, ListingToken("0123456789abcdef0123456789abcdef"), mapOf(kestrel to Delivery.Delivered, nori to Delivery.NotReached, wren to Delivery.Sending)),
            servers = Servers(
                ServersRead.Ready,
                listOf(
                    PublicGame(listing("3c41a9e07d5b2f18"), offer, fra, 3, GameStatus.Lobby),
                    PublicGame(listing("a17b0c9d2e3f4051"), offer.copy(host = null, capacity = 6), RelayRegion(9, "sea"), 6, GameStatus.Full),
                    PublicGame(listing("77aa0c9d2e3f4051"), folder, fra, 1, GameStatus.Lobby),
                ),
                ping = mapOf(4 to 31),
            ),
        )
        val on = SocialView(SocialStatus.On, available = true, state)
        val social = inGameFrames(PresentInputs(LiveSession(hosting, record), modes = modes, site = site), on)
        golden("hello-on.json", social.getValue(Topic.Hello))
        golden("game-invites.json", social.getValue(Topic.Game))
        golden("inbox.json", social.getValue(Topic.Inbox))
        golden("servers.json", social.getValue(Topic.Servers))
        golden("hello-other-account.json", frames(LiveSession(), SocialView(SocialStatus.OtherAccount, available = true)).getValue(Topic.Hello))

        val idle = frames(LiveSession())
        golden("inbox-empty.json", idle.getValue(Topic.Inbox))
        golden("servers-unavailable.json", idle.getValue(Topic.Servers))
        for ((read, name) in listOf(ServersRead.Idle to "idle", ServersRead.Loading to "loading", ServersRead.Unreachable to "unreachable")) {
            golden("servers-$name.json", frames(LiveSession(), on.copy(state = SocialState(servers = Servers(read)))).getValue(Topic.Servers))
        }
    }

    @Test
    fun aFrameIsAsciiSoTheGameReadsItWithoutDecoding() = runTest {
        val failed = frames(LiveSession(Session.Ended(EndReason.HostFailed(null)), hostRecord)).getValue(Topic.Game)
        assertEquals("", failed.filter { it.code >= 0x80 })
        val title = Json.parseToJsonElement(failed).jsonObject["host"]!!.jsonObject["words"]!!.jsonObject["title"]!!.jsonPrimitive.content
        assertEquals("Bebop Wars couldn’t load its arena", title, "escaped, and read back whole")
    }

    @Test
    fun aLineIsPackedAsModlocksRelayPacksIt() {
        val frame = """{"t":"x","s":"a b\\"}"""
        golden("lines.json", Json.encodeToString(mapOf("frame" to frame, "line" to InGameWire.line(7, frame).decodeToString())) + "\n")
        assertEquals("~7:{\"t\":\"x\",\"s\":\"aĠbŜŜ\"}\n", InGameWire.line(7, frame).decodeToString(), "a space and a backslash are U+0100 plus the byte")
    }

    @Test
    fun resultWidthsAreTheGoldenOnes() {
        golden("results.json", Json.encodeToString(CommandResult.entries.associate { it.name.lowercase() to it.width }) + "\n")
    }

    @Test
    fun goldenCommandsParseToThePressesTheyName() {
        val invitation = checkNotNull(InvitationId.parse("9f2c41d07e6b4a58b3c1d2e3f4a5b6c7"))
        val expected = listOf(
            PanelCommand.Host(
                bebop.id, mapOf(checkNotNull(SettingKey.parse("teams")) to checkNotNull(SettingValue.parse("3v3"))),
                Outreach(Listing.Public, setOf(kestrel)), RestartReason.NotSetUp,
            ),
            PanelCommand.Host(bebop.id, emptyMap(), Outreach.Private, null),
            PanelCommand.Act(Action.Stop),
            PanelCommand.Act(Action.Restart),
            PanelCommand.Act(Action.Play),
            PanelCommand.Act(Action.Dismiss),
            PanelCommand.Act(Action.Again),
            PanelCommand.Confirm(RestartReason.GameUpdated),
            PanelCommand.Confirm(null),
            PanelCommand.Invite(setOf(kestrel)),
            PanelCommand.Relist(Listing.InviteOnly),
            PanelCommand.JoinInvite(invitation),
            PanelCommand.JoinServer(checkNotNull(ListingId.parse("3c41a9e07d5b2f18"))),
            PanelCommand.Forget(invitation),
            PanelCommand.Servers,
            PanelCommand.Hello(checkNotNull(SteamId.parse("76561198000000001"))),
            PanelCommand.Hello(null),
        )
        val commands = Json.parseToJsonElement(Files.readString(fixtures.resolve("commands.json"))) as JsonArray
        assertEquals(expected, commands.map { InGameWire.parseCommand(it.toString()) })
    }

    @Test
    fun malformedCommandsParseToNothing() {
        val refused = Json.parseToJsonElement(Files.readString(fixtures.resolve("commands-invalid.json"))) as JsonArray
        refused.forEach { assertNull(InGameWire.parseCommand(it.toString()), "$it") }
        assertNull(InGameWire.parseCommand("not json"))
        val crowd = (0 until 64).joinToString(",") { "\"765611980000001%02d\"".format(it) }
        assertNull(InGameWire.parseCommand("""{"k":"invite","to":[$crowd]}"""), "more friends than a match can hold")
        assertNull(InGameWire.parseCommand("""{"k":"act","action":"stop","pad":"${"x".repeat(5000)}"}"""), "too long")
        assertEquals(ModeId("arena"), (InGameWire.parseCommand("""{"k":"host","mode":"arena","picks":{},"listing":"public","invite":[],"restart":null}""") as PanelCommand.Host).mode)
    }
}
