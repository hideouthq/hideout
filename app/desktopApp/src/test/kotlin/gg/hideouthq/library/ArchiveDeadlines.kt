package gg.hideouthq.library

import gg.hideouthq.core.data.UnpackResult
import gg.hideouthq.core.library.ArchiveIdentity
import gg.hideouthq.core.library.RejectionReason
import gg.hideouthq.core.library.UnpackLimits
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.nanoseconds

private fun UnpackResult.outcome(): Any = when (this) {
    is UnpackResult.Unpacked -> vpks.map { it.path to it.sha256 }
    is UnpackResult.Rejected -> rejection
    is UnpackResult.Failed -> reason
}

internal fun assertEveryDeadlineIsHonoured(staging: Path, archive: ArchiveIdentity, limits: UnpackLimits = UnpackLimits()): Any {
    fun unpack(ticks: Long): Pair<UnpackResult, Long> {
        var now = 0L
        val result = runBlocking { NioModInstaller(staging, timeLimit = ticks.nanoseconds, clock = { now++ }).unpack(archive, limits) }
        return result to now
    }
    val (unhurried, used) = unpack(Long.MAX_VALUE / 4)
    assertTrue(used > 2, "reading ${archive.fileName} checks the deadline more than once, not $used times")
    for (ticks in 1 until used) {
        val (result, asked) = unpack(ticks)
        if (asked > ticks) assertEquals(RejectionReason.TimedOut, (result as? UnpackResult.Rejected)?.rejection?.reason, "deadline at check $ticks of ${archive.fileName}")
        else assertEquals(unhurried.outcome(), result.outcome(), "no deadline fired at check $ticks of ${archive.fileName}")
    }
    return unhurried.outcome()
}
