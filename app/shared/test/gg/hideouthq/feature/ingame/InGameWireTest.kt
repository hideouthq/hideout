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
import gg.hideouthq.core.data.fake.FakeCustomGamesFeed
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

/**
 * The wire is pinned by golden files in hideout/ingame/fixtures, which the Panorama side's tests read too. A change to a
 * frame or a command shows here first: rerun with HIDEOUT_WRITE_FIXTURES=1 to rewrite them, and read the diff.
 */
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

    private suspend fun frames(live: LiveSession, restarts: RestartReason? = null, transfer: Transfer? = null) =
        inGameFrames(PresentInputs(live, restarts, transfer, modes, site = site))

    @Test
    fun framesAreTheGoldenOnes() = runTest {
        val idle = frames(LiveSession())
        golden("hello.json", idle.getValue(Topic.Hello))
        golden("catalog.json", idle.getValue(Topic.Catalog))
        golden("game-idle.json", idle.getValue(Topic.Game))
        golden("alive.json", InGameWire.encode(Frame.Alive))

        val starting = frames(LiveSession(Session.HostPreparing(DeadlineToken(1)), hostRecord), RestartReason.NotSetUp, Transfer(7_700_000, 12_048_000))
        golden("game-starting.json", starting.getValue(Topic.Game))

        val hosting = Session.Hosting(code, "street", mapOf(0 to Player("nori", me.steamId)), OwnGame.In)
        golden("game-ready.json", frames(LiveSession(hosting, hostRecord.copy(startedAt = Instant.fromEpochSeconds(1_800_000_000)))).getValue(Topic.Game))

        golden("game-failed.json", frames(LiveSession(Session.Ended(EndReason.HostFailed("the map did not load")), hostRecord)).getValue(Topic.Game))

        val offer = InviteOffer.of(bebop, threes, LocalPlayer("76561198000000002", DisplayName.parse("Mugi")))
        val review = LiveSession(Session.Confirming(Invite(code, offer)), GameRecord(Role.Guest(code, offer, bebop.listing), me))
        golden("game-review.json", frames(review, RestartReason.GameUpdated).getValue(Topic.Game))
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
            PanelCommand.JoinInvite("9f2c"),
            PanelCommand.JoinServer("3c41"),
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
