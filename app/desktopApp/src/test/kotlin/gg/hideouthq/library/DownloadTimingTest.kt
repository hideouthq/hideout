package gg.hideouthq.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class DownloadTimingTest {
    @Test
    fun remainingTimeUsesOnlyBytesTransferredDuringThisAttempt() {
        assertEquals(9L, remainingDownloadSeconds(receivedBytes = 1_000_000, remainingBytes = 9_000_000, elapsedNanos = 1_000_000_000))
        assertEquals(18L, remainingDownloadSeconds(receivedBytes = 1_000_000, remainingBytes = 9_000_000, elapsedNanos = 2_000_000_000))
        assertEquals(1L, remainingDownloadSeconds(receivedBytes = 9_000_000, remainingBytes = 1, elapsedNanos = 2_000_000_000))
    }

    @Test
    fun startupNoProgressAndCompletedTransfersDoNotInventAnEstimate() {
        assertNull(remainingDownloadSeconds(10, 90, 999_999_999))
        assertNull(remainingDownloadSeconds(0, 90, 2_000_000_000))
        assertNull(remainingDownloadSeconds(10, 0, 2_000_000_000))
        assertNull(remainingDownloadSeconds(10, -1, 2_000_000_000))
    }

    @Test
    fun anOutageIsTriedAgainAfterLongerWaitsOfAtMostHalfAMinuteUntilTwoMinutesHaveGone() {
        val retry = RetryWindow()
        val waits = mutableListOf<Duration>()
        var lasted = Duration.ZERO
        while (true) {
            val wait = retry.wait(waits.size + 1, lasted) ?: break
            waits += wait
            lasted += wait
        }
        assertEquals(listOf(1, 2, 4, 8, 16, 30, 30, 29).map { it.seconds }, waits)
        assertNull(retry.wait(1, 2.minutes), "a first try counts the outage from where it began")
    }
}
