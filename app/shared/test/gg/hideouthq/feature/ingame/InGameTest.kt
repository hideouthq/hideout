package gg.hideouthq.feature.ingame

import gg.hideouthq.core.customgames.CustomGames
import gg.hideouthq.core.customgames.CustomGamesFetch
import gg.hideouthq.core.customgames.LinkEvent
import gg.hideouthq.core.customgames.LinkStop
import gg.hideouthq.core.customgames.Listing
import gg.hideouthq.core.customgames.ModeId
import gg.hideouthq.core.customgames.Outreach
import gg.hideouthq.core.customgames.Role
import gg.hideouthq.core.customgames.Session
import gg.hideouthq.core.customgames.SettingKey
import gg.hideouthq.core.customgames.SettingValue
import gg.hideouthq.core.customgames.SiteOrigin
import gg.hideouthq.core.customgames.SteamId
import gg.hideouthq.core.customgames.social.ListingId
import gg.hideouthq.core.data.fake.FakeCustomGamesFeed
import gg.hideouthq.core.data.fake.FakeCustomGamesPlatform
import gg.hideouthq.core.data.fake.FakeSessionFiles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

private val Kestrel = checkNotNull(SteamId.parse("76561198000000007"))
private val Bebop = FakeCustomGamesFeed.BebopWars

class InGameTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val platform = FakeCustomGamesPlatform(chatty = false)
    private val feed = FakeCustomGamesFeed(answer = CustomGamesFetch.Fresh(FakeCustomGamesFeed.SampleModes.copy(modes = listOf(Bebop))))
    private val games = CustomGames(platform, FakeSessionFiles(), feed, scope, hostFolders = flowOf(false)).apply { start() }
    private val inGame = InGame(games, SiteOrigin.parse("https://hideouthq.gg"), scope)

    @AfterTest
    fun end() = scope.cancel()

    private fun <T> Flow<T>.awaited(what: String, wanted: (T) -> Boolean): T = runBlocking {
        try {
            withTimeout(60.seconds) { first(wanted) }
        } catch (e: Exception) {
            throw AssertionError("never saw $what", e)
        }
    }

    private fun listed() {
        inGame.opened()
        games.modes.awaited("the mode list") { it.feed != null }
    }

    private fun hostBebop(outreach: Outreach = Outreach(Listing.Public, setOf(Kestrel)), picks: Map<SettingKey, SettingValue> = mapOf(key("teams") to value("3v3"))) =
        inGame.run(PanelCommand.Host(Bebop.id, picks, outreach, restart = null))

    private fun game(wanted: (JsonObject) -> Boolean): JsonObject =
        inGame.topics.map { topics -> topics[Topic.Game]?.let { Json.parseToJsonElement(it).jsonObject } }.awaited("that game frame") { it != null && wanted(it) }!!

    private val JsonObject.host get() = this["host"] as? JsonObject

    @Test
    fun aHostPressStartsTheGameWithWhoItInvitesAndWhetherItIsListed() {
        listed()
        assertEquals(CommandResult.Accepted, hostBebop())
        val record = games.live.awaited("the host's record") { it.game != null }.game
        val role = assertIs<Role.Host>(record?.role)
        assertEquals(Outreach(Listing.Public, setOf(Kestrel)), role.outreach)
        assertEquals(value("3v3"), role.choices[key("teams")])
        val card = game { it.host?.get("phase")?.jsonPrimitive?.content == "starting" }.host!!
        assertEquals("public", card["listing"]?.jsonPrimitive?.content)
        assertEquals(listOf(Kestrel.value), card["invitees"]?.jsonArray?.map { it.jsonPrimitive.content })
    }

    @Test
    fun aHostPressWhileAGameRunsIsBusyAndTheRunningGameKeepsWhatItWasFor() {
        listed()
        hostBebop()
        games.live.awaited("the server starting") { it.session is Session.HostStarting }
        assertEquals(CommandResult.Busy, hostBebop(Outreach.Private))
        val role = assertIs<Role.Host>(games.live.value.game?.role)
        assertEquals(Outreach(Listing.Public, setOf(Kestrel)), role.outreach)
        assertEquals(1, platform.hostRequests.size, "one game was prepared")
    }

    @Test
    fun aModeNoLongerListedIsGoneAndAPickTheModeDoesNotOfferIsInvalid() {
        listed()
        assertEquals(CommandResult.Gone, inGame.run(PanelCommand.Host(ModeId("gone"), emptyMap(), Outreach.Private, null)))
        assertEquals(CommandResult.Invalid, hostBebop(picks = mapOf(key("teams") to value("9v9"))))
        assertEquals(CommandResult.Invalid, hostBebop(picks = mapOf(key("score") to value("5"))))
        assertEquals(Session.Idle, games.live.value.session)
    }

    @Test
    fun invitesListingsAndJoinsAreUnavailableUntilThisBuildCanSendThem() {
        assertEquals(CommandResult.Unavailable, inGame.run(PanelCommand.Invite(setOf(Kestrel))))
        assertEquals(CommandResult.Unavailable, inGame.run(PanelCommand.Relist(Listing.Public)))
        assertEquals(CommandResult.Unavailable, inGame.run(PanelCommand.JoinServer(checkNotNull(ListingId.parse("3c41a9e07d5b2f18")))))
    }

    @Test
    fun theGameTopicFollowsTheSessionAndPlayAgainKeepsTheOutreach() {
        listed()
        assertEquals(JsonNull, game { true }["host"], "no game, no card")

        hostBebop()
        val starting = game { it.host?.get("phase")?.jsonPrimitive?.content == "starting" }.host!!
        assertEquals(listOf("stop"), starting["actions"]?.jsonArray?.map { it.jsonPrimitive.content })
        assertEquals("Getting Bebop Wars ready", starting["steps"]?.jsonArray?.first()?.jsonObject?.get("label")?.jsonPrimitive?.content)

        games.live.awaited("the server starting") { it.session is Session.HostStarting }
        platform.links.last().say(LinkEvent.Stopped(LinkStop.ServerExited, "the map did not load"))
        val failed = game { it.host?.get("phase")?.jsonPrimitive?.content == "failed" }.host!!
        assertEquals("Bebop Wars couldn’t load its arena", failed["words"]?.jsonObject?.get("title")?.jsonPrimitive?.content, "the game screen's own words")
        assertEquals(listOf("again", "dismiss"), failed["actions"]?.jsonArray?.map { it.jsonPrimitive.content })

        assertEquals(CommandResult.Accepted, inGame.run(PanelCommand.Act(Action.Again)))
        game { it.host?.get("phase")?.jsonPrimitive?.content == "starting" }
        assertEquals(Outreach(Listing.Public, setOf(Kestrel)), assertIs<Role.Host>(games.live.value.game?.role).outreach, "Play again invites the same friends")

        assertEquals(CommandResult.Accepted, inGame.run(PanelCommand.Act(Action.Stop)))
        games.live.awaited("the game stopped") { it.session is Session.Ended }
    }
}

private fun key(text: String) = checkNotNull(SettingKey.parse(text))

private fun value(text: String) = checkNotNull(SettingValue.parse(text))
