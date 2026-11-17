package gg.hideouthq.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import gg.hideouthq.core.data.DownloadProblem
import gg.hideouthq.core.library.LibraryProblem
import gg.hideouthq.resources.Res
import gg.hideouthq.resources.busy_when_minutes
import gg.hideouthq.resources.busy_when_soon
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

@Composable
fun busyWhen(until: Instant): String {
    val now by produceState(Clock.System.now(), until) {
        while (true) {
            value = Clock.System.now()
            delay(Tick)
        }
    }
    return when (val minutes = busyMinutes(until - now)) {
        0 -> stringResource(Res.string.busy_when_soon)
        else -> pluralStringResource(Res.plurals.busy_when_minutes, minutes, minutes)
    }
}

/** [left] in whole minutes to say, rounded; 0 when under [Soon]. */
fun busyMinutes(left: Duration): Int = if (left < Soon) 0 else (left.inWholeSeconds / 60.0).roundToInt().coerceAtLeast(1)

private val Tick = 15.seconds
private val Soon = 45.seconds

/**
 * Whether trying an install again can end differently: not when GameBanana no longer offers the mod or the file, the
 * file it serves has failed its checksum twice, or the fault lies in the file itself ([LibraryLabels.fileAtFault]).
 */
val LibraryProblem.retryHelps: Boolean
    get() = when (this) {
        is LibraryProblem.Withdrawn, LibraryProblem.NoFiles, LibraryProblem.FileNotOffered, LibraryProblem.NotAGameBananaMod, LibraryProblem.AlreadyInstalled -> false
        is LibraryProblem.Download -> when (val download = problem) {
            is DownloadProblem.Http -> download.status !in NeverServed
            is DownloadProblem.Md5Mismatch -> false
            is DownloadProblem.Network, is DownloadProblem.SizeMismatch, is DownloadProblem.Disk -> true
        }
        else -> !LibraryLabels.fileAtFault(this)
    }

private val NeverServed = setOf(401, 403, 404, 410)
