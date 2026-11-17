package gg.hideouthq.customgames

import gg.hideouthq.appupdate.ReleaseKey
import gg.hideouthq.core.customgames.InviteCode
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class CustomGamesFeedWiringTest {
    private val folder = Files.createTempDirectory("hideout-feed-wiring")
    private val userFile = folder.resolve("hideout.properties")

    @AfterTest
    fun cleanUp() = folder.toFile().deleteRecursively().let {}

    @Test
    fun theSiteHasOneDefaultAndIsOverriddenAsTheFeedIs() {
        assertEquals(CustomGamesSite.Default, CustomGamesSite.resolve(null, null, userFile)?.value)
        assertEquals("https://a", CustomGamesSite.resolve("https://a", "https://b", userFile)?.value)
        assertEquals("https://b", CustomGamesSite.resolve(" ", "https://b", userFile)?.value)
        Files.writeString(userFile, "${CustomGamesSite.Property}=https\\://c\n")
        assertEquals("https://c", CustomGamesSite.resolve(null, null, userFile)?.value)
    }

    @Test
    fun aSiteThatIsNoBareHttpsOriginCountsAsNone() {
        assertNull(CustomGamesSite.resolve("http://a", null, userFile))
        assertNull(CustomGamesSite.resolve("https://a/join", null, userFile))
    }

    @Test
    fun oneSiteNamesBothTheBridgePageAndTheInviteLink() {
        val site = assertNotNull(CustomGamesSite.resolve("https://a:8443", null, userFile))
        assertEquals("https://a:8443/bridge/", CustomGamesSite.bridgePage(site))
        assertEquals("https://a:8443/join#P95-MAN-26D", assertNotNull(InviteCode.parse("p95man26d")).webLink(site))
        assertEquals("${CustomGamesSite.Default}/bridge/", CustomGamesSite.bridgePage(CustomGamesSite.resolve(null, null, userFile)))
    }

    @Test
    fun aBrokenSiteSettingStillLeavesTheGameAPage() {
        assertEquals("${CustomGamesSite.Default}/bridge/", CustomGamesSite.bridgePage(CustomGamesSite.resolve("http://a", null, userFile)))
    }

    @Test
    fun theSitesFeedIsReadUnlessAnotherIsSet() {
        assertEquals("https://hideouthq.gg/customgames/customgames.json", CustomGamesFeedUrl.resolve(null, null, userFile))
        assertEquals(CustomGamesFeedUrl.Default, CustomGamesFeedUrl.resolve(" ", null, userFile))
        assertEquals("https://a", CustomGamesFeedUrl.resolve("https://a", "https://b", userFile))
        assertEquals("https://b", CustomGamesFeedUrl.resolve(" ", "https://b", userFile))
        Files.writeString(userFile, "${CustomGamesFeedUrl.Property}=https\\://c\n")
        assertEquals("https://c", CustomGamesFeedUrl.resolve(null, null, userFile))
    }

    @Test
    fun theKeyIsOverriddenItsOwnWayAndNeverTheReleaseKey() {
        assertEquals("hideout.customgames.feedKey", CustomGamesFeedKey.Property)
        assertEquals("HIDEOUT_CUSTOMGAMES_FEED_KEY", CustomGamesFeedKey.Environment)
        assertEquals("MCowBQYDK2VwAyEAhurl6iMAxAZhsvDzUl27iFpJphpy0RLVv0tHi3t8fyM=", CustomGamesFeedKey.resolve(null, null, userFile))
        assertEquals("key", CustomGamesFeedKey.resolve("key", null, userFile))
        assertEquals("key", CustomGamesFeedKey.resolve(null, "key", userFile))
        assertNotEquals(ReleaseKey, CustomGamesFeedKey.Default)
    }

    @Test
    fun theLogFilterHidesAnInviteTheLineLengthWouldCutInHalf() {
        val logged = safeToLog("x".repeat(1_990) + "tc" + "A".repeat(100))
        assertFalse("tcA" in logged, logged.takeLast(20))
        assertEquals(1_998, logged.length)
    }
}
