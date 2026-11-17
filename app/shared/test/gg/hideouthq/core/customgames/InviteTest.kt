package gg.hideouthq.core.customgames

import gg.hideouthq.core.data.fake.FakeCustomGamesFeed
import java.net.URI
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InviteTest {
    private val code = assertNotNull(InviteCode.parse("P95MAN26D"))
    private fun pick(key: String, value: String) = assertNotNull(SettingKey.parse(key)) to assertNotNull(SettingValue.parse(value))
    private val choices = assertNotNull(Choices.of(mapOf(pick("teams", "2v2"), pick("score", "5"))))
    private val offer = InviteOffer(DisplayName.parse("nori"), ModeId("rich-mode.2"), OfferContent.Feed(assertNotNull(Sha256.parse("a".repeat(64)))), choices, 4)
    private val pack = SharedPack(assertNotNull(Sha256.parse("b".repeat(64))), 26_575_674)

    private fun encoded(text: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(text.encodeToByteArray())

    @Test
    fun anOfferCrossesTheLinkAsOneTokenAndComesBackWhole() {
        val folder = InviteOffer(null, ModeId("arena"), OfferContent.Host(pack), Choices.None, 8)
        for (sent in listOf(offer, folder)) {
            assertTrue(Regex("[A-Za-z0-9_-]{1,1024}").matches(sent.encoded), "the helper's --offer takes it as it is")
            assertEquals(sent, InviteOffer.decode(sent.encoded))
        }
    }

    @Test
    fun anOfferIsReadOnlyInItsOneShape() {
        assertEquals(offer, InviteOffer.decode(encoded("rich-mode.2\n${"a".repeat(64)}\nteams=2v2,score=5\n4\nnori")))
        assertEquals(OfferContent.Host(pack), InviteOffer.decode(encoded("arena\nhost:${"b".repeat(64)}:26575674\n\n8\n"))?.content)
        val broken = listOf(
            "\n${"a".repeat(64)}\n\n4\nnori" to "a package with no mode",
            "\n\n\n4\nnori" to "no mode at all",
            "arena\nhost:${"b".repeat(64)}\n\n4\nnori" to "a host's pack with no size",
            "arena\nhost:${"b".repeat(64)}:0\n\n4\nnori" to "an empty pack",
            "arena\nhost:${"b".repeat(64)}:08\n\n4\nnori" to "a size as Hideout would never write it",
            "arena\nhost:${"b".repeat(64)}:8589934593\n\n4\nnori" to "a pack over the feed's limit",
            "arena\nhost:b\n\n4\nnori" to "a pack digest that isn't one",
            "Arena\n\n\n4\nnori" to "a mode id that isn't one",
            "arena\n\n\n0\nnori" to "no room",
            "arena\n\n\n65\nnori" to "more room than any mode",
            "arena\n\n\nfour\nnori" to "a size that isn't a number",
            "arena\n\na=1,b=2,c=3,d=4,e=5\n4\nnori" to "more picks than a listing may declare",
            "arena\n\na=1,a=2\n4\nnori" to "one setting picked twice",
            "arena\n\nA=1\n4\nnori" to "a pick that isn't safe on a command line",
            "arena\n\na\n4\nnori" to "a pick with no value",
            "arena\n\n\n4\n nori" to "a name as Hideout would never write it",
            "arena\n\n\n4" to "a line missing",
            "arena\n\n\n4\nnori\nextra" to "a line too many",
        )
        for ((text, why) in broken) assertNull(InviteOffer.decode(encoded(text)), why)
        for (garbage in listOf("", "not base64!", "a")) assertNull(InviteOffer.decode(garbage), garbage)
    }

    @Test
    fun anOfferIsMadeFromTheModeTheChoicesAndWhoHosts() {
        val me = LocalPlayer("7656", DisplayName.parse("nori"))
        val client = Artifact("https://example.test/c.zip", 5, assertNotNull(Sha256.parse("c".repeat(64))))
        val listing = FakeCustomGamesFeed.SampleListing.copy(
            players = 2..6,
            settings = listOf(ModeSetting(pick("teams", "1v1").first, "Teams", null, listOf(SettingOption(pick("t", "1v1").second, "1v1", 2), SettingOption(pick("t", "2v2").second, "2v2", 4)), pick("t", "1v1").second)),
        )
        val feedMode = Mode(ModeId("arena"), "1.0.0", listing, ModeOrigin.Feed(client, client))
        val twos = assertNotNull(Choices.of(mapOf(pick("teams", "2v2"))))
        assertEquals(InviteOffer(me.name, ModeId("arena"), OfferContent.Feed(client.sha256), twos, 4), InviteOffer.of(feedMode, twos, me))
        val folder = feedMode.copy(origin = ModeOrigin.Folder("/built/arena"))
        val unnamed = LocalPlayer("7656", null)
        assertEquals(InviteOffer(null, ModeId("arena"), OfferContent.Host(pack), Choices.defaultsOf(listing), 2), InviteOffer.of(folder, Choices.defaultsOf(listing), unnamed, pack), "a folder's content comes from the host")
    }

    @Test
    fun aNameIsShownAsItsOwnerSetItAndNoMore() {
        assertEquals("nori", DisplayName.parse("  nori ")?.value)
        assertEquals("😀".repeat(32), DisplayName.parse("😀".repeat(32))?.value)
        for (bad in listOf(null, "", "   ", "n".repeat(33), "no\nri", "no\u0000ri")) assertNull(DisplayName.parse(bad), "$bad")
        assertFalse("nori" in "${DisplayName.parse("nori")}${LocalPlayer("7656", DisplayName.parse("nori"))}")
    }

    @Test
    fun aCodeIsReadForgivingCaseDashesAndSpaces() {
        for (typed in listOf("P95-MAN-26D", "p95-man-26d", "P95MAN26D", "p95 man 26d", "  P95-MAN-26D\n", "P9 5M-AN2 6D", "\u200bP95-MAN\u2060-26D\ufeff")) {
            assertEquals(code, InviteCode.parse(typed), typed)
        }
        assertEquals("P95MAN26D", code.value, "the helper takes the bare code")
        assertEquals("P95-MAN-26D", code.shown, "a friend reads it in threes")
    }

    @Test
    fun onlyNineCharactersOfTheAlphabetAreACode() {
        for (bad in listOf("", " ", "P95-MAN-26", "P95-MAN-26DD", "P95-MAN-260", "P95-MAN-261", "P95-MAN-26O", "P95-MAN-26I", "P95-MAN-26L", "P95-MAN-26U", "P95-MAN-26é", "P95_MAN_26D")) {
            assertNull(InviteCode.parse(bad), bad)
        }
    }

    @Test
    fun theInputIsBoundedBeforeAnythingElse() {
        assertNull(InviteCode.parse(" ".repeat(4097 - 9) + "P95MAN26D"))
        assertEquals(code, InviteCode.parse(" ".repeat(4096 - 9) + "P95MAN26D"))
    }

    @Test
    fun everyLinkACodeTravelsInReadsAsThatCode() {
        val site = assertNotNull(SiteOrigin.parse("https://example.test"))
        val web = code.webLink(site)
        assertEquals("https://example.test/join#P95-MAN-26D", web, "the code stays in the fragment, which a browser never sends")
        assertEquals("P95-MAN-26D", URI(web).rawFragment)
        for (link in listOf(
            "hideout://join/P95-MAN-26D",
            "HIDEOUT://JOIN/p95man26d",
            "hideout://join/P95-MAN-26D/",
            web,
            "https://example.test/join/#P95MAN26D",
            "  https://other.test/join#p95-man-26d\n",
        )) {
            assertEquals(code, InviteCode.parse(link), link)
        }
    }

    @Test
    fun aLinkWithAnythingElseInItReadsAsNoCode() {
        for (link in listOf(
            "hideout://join/",
            "hideout://join",
            "hideout://P95-MAN-26D",
            "hideout://other/P95-MAN-26D",
            "hideout://join/P95-MAN-26D?auto=1",
            "hideout://join/P95-MAN-26D#x",
            "hideout://join/P95-MAN-2",
            "http://example.test/join#P95-MAN-26D",
            "https://example.test/other#P95-MAN-26D",
            "https://example.test/join",
            "https://example.test/join#",
            "https://example.test/join?code=P95-MAN-26D",
        )) {
            assertNull(InviteCode.parse(link), link)
        }
    }

    @Test
    fun onlyTheRegisteredSchemeCountsAsALaunchLink() {
        assertTrue(InviteCode.isAppLink("hideout://join/anything"))
        assertTrue(InviteCode.isAppLink("HIDEOUT:whatever"))
        assertFalse(InviteCode.isAppLink("--finish-update"))
        assertFalse(InviteCode.isAppLink(code.shown))
    }

    @Test
    fun aCodeSaysNothingOfItselfWhenPrinted() {
        val invite = Invite(code, offer)
        assertEquals("InviteCode(redacted)", "$code")
        for (printed in listOf("$code", "$invite", "${Session.Confirming(invite)}", "${Session.Finding(code, DeadlineToken(1))}", "${SessionEvent.InviteOpened(code)}")) {
            assertNull(Regex("P95.?MAN.?26D").find(printed), printed)
        }
    }

    @Test
    fun logsLoseAnythingShapedLikeACodeOrATailcatAddress() {
        val address = "tcAbC-d_0123456789abcdefghijklmnopqrstuvwxyz"
        assertEquals(
            "join [invite] or [invite] at https://x.test/join#[invite], via [invite].",
            redactInvites("join P95-MAN-26D or P95MAN26D at https://x.test/join#P95-MAN-26D, via $address."),
        )
        for (kept in listOf("tcp 127.0.0.1:27067", "listening on tcp", "a character stretched", "P95-MAN-26DX", "2026-11-11")) assertEquals(kept, redactInvites(kept))
    }
}
