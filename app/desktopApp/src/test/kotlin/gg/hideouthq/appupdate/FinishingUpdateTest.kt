package gg.hideouthq.appupdate

import gg.hideouthq.core.customgames.ProcessIdentity
import gg.hideouthq.customgames.identityOf
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FinishingUpdateTest {
    private val folder = Files.createTempDirectory("hideout-finishing")
    private val helper = ProcessIdentity(4242, 1_800_000_000_000)
    private val soon = helper.startedAtMillis + 30_000
    private val looked = mutableListOf<ProcessIdentity>()

    @AfterTest
    fun cleanUp() = folder.toFile().deleteRecursively().let { }

    private fun finishing() = FinishingUpdate(folder)

    private fun running(vararg alive: ProcessIdentity): (ProcessIdentity) -> Boolean = { looked += it; it in alive }

    @Test
    fun withNoHelperAnnouncedHideoutOpens() {
        assertFalse(finishing().handOff("hideout://join/ABCD", running(helper), soon))
        assertEquals(emptyList(), looked)
        assertNull(finishing().close())
    }

    @Test
    fun aRunningHelperIsAskedToReopenHideoutWithTheLinkAndThisCopyLeaves() {
        finishing().announce(helper)
        assertTrue(finishing().handOff("hideout://join/ABCD", running(helper), soon))
        assertEquals(listOf(helper), looked)
        assertEquals("hideout://join/ABCD", finishing().close())
        assertNull(finishing().close(), "a request is read once")
    }

    @Test
    fun aRequestWithoutALinkStillReopens() {
        finishing().announce(helper)
        assertTrue(finishing().handOff(null, running(helper), soon))
        assertEquals("", finishing().close())
    }

    @Test
    fun onlyTheExactProcessCountsAndOneGoneIsForgotten() {
        finishing().announce(helper)
        assertFalse(finishing().handOff(null, running(ProcessIdentity(helper.pid, helper.startedAtMillis + 1)), soon), "the pid reused by another process")
        assertFalse(finishing().handOff(null, running(helper), soon), "forgotten")
        assertEquals(listOf(helper), looked)
        assertNull(finishing().close())
    }

    @Test
    fun aHelperRunningPastTheLimitIsForgottenAndHideoutOpens() {
        finishing().announce(helper)
        assertTrue(finishing().handOff(null, running(helper), helper.startedAtMillis + FinishingUpdate.HandOffLimitMillis))
        finishing().close()

        finishing().announce(helper)
        assertFalse(finishing().handOff(null, running(helper), helper.startedAtMillis + FinishingUpdate.HandOffLimitMillis + 1))
        assertFalse(finishing().handOff(null, running(helper), soon), "forgotten")
        assertNull(finishing().close())
    }

    @Test
    fun aHelperThatEndsWhileTheRequestIsWrittenLeavesThisCopyToOpen() {
        finishing().announce(helper)
        val endsMeanwhile: (ProcessIdentity) -> Boolean = { finishing().close(); true }
        assertFalse(finishing().handOff("hideout://join/ABCD", endsMeanwhile, soon))
        assertNull(finishing().close(), "no request is left for the next helper")
    }

    @Test
    fun aNewAnnouncementDropsARequestLeftByAHelperThatCrashed() {
        finishing().announce(helper)
        assertTrue(finishing().handOff("hideout://join/ABCD", running(helper), soon))
        finishing().announce(ProcessIdentity(5151, soon))
        assertEquals(null, finishing().close())
    }

    @Test
    fun aMangledAnnouncementIsNoHelper() {
        for (text in listOf("", "4242", "4242 soon", "a b", "1 2 3")) {
            Files.writeString(folder.resolve("finish-update.helper"), text)
            assertFalse(finishing().handOff(null, running(helper), soon), text)
        }
        assertEquals(emptyList(), looked)
    }

    @Test
    fun theLiveLookupKnowsThisProcessAndNotAStartTimeOffByOne() {
        val self = checkNotNull(identityOf(ProcessHandle.current()))
        assertTrue(isRunning(self))
        assertFalse(isRunning(self.copy(startedAtMillis = self.startedAtMillis - 1)))
        val gone = ProcessBuilder("true").start().also { it.waitFor() }
        assertFalse(isRunning(ProcessIdentity(gone.pid(), self.startedAtMillis)))
    }
}
