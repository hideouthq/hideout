package gg.hideouthq.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
}
