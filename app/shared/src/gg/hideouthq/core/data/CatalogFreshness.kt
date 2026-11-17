package gg.hideouthq.core.data

import kotlin.time.Instant

/**
 * How current the catalogue is. [checkedAt] is when a check against its source last succeeded, in Unix seconds, and
 * null before the first. [error] is why the latest check failed; it stays until one succeeds, and meanwhile the
 * catalogue keeps serving what it held. [busyUntil] is set while the check under way waits for GameBanana, which asked
 * to wait until then; the check goes on by itself.
 */
data class CatalogFreshness(
    val checkedAt: Long? = null,
    val running: CheckProgress? = null,
    val error: DataError? = null,
    val busyUntil: Instant? = null,
) {
    val checking: Boolean get() = running != null
}

/** How far a running check is: 0..1, or null until the amount of work is known. */
data class CheckProgress(val fraction: Float? = null)
