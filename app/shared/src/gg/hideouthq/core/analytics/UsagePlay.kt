package gg.hideouthq.core.analytics

import gg.hideouthq.core.customgames.Mode
import gg.hideouthq.core.customgames.ModeOrigin
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlin.time.Duration
import kotlin.time.TimeMark
import kotlin.time.TimeSource

class PublicUsageMode private constructor(val id: String, val name: String) {
    companion object {
        fun from(mode: Mode): PublicUsageMode? =
            if (mode.origin is ModeOrigin.Feed) PublicUsageMode(mode.id.value, mode.listing.name) else null
    }
}

enum class UsagePlayRole(val wire: String) { Host("host"), Guest("guest") }

@JvmInline
value class PlayedMinutes private constructor(val value: Int) {
    companion object {
        val Five = PlayedMinutes(5)
        fun remainder(duration: Duration): PlayedMinutes? =
            duration.inWholeMinutes.coerceIn(0, 5).toInt().takeIf { it > 0 }?.let(::PlayedMinutes)
    }
}

internal sealed interface PlayClock {
    fun elapsed(): Duration
    class Playing(private val completed: Duration, private val since: TimeMark) : PlayClock {
        override fun elapsed(): Duration = completed + since.elapsedNow()
    }
    class Paused(private val completed: Duration) : PlayClock {
        override fun elapsed(): Duration = completed
    }
    class Closed(private val completed: Duration) : PlayClock {
        override fun elapsed(): Duration = completed
    }
}

class UsagePlay internal constructor(private val activity: MutableStateFlow<PlayClock>, private val time: TimeSource, private val job: Job?) : AutoCloseable {
    fun setPlaying(active: Boolean) {
        activity.update { before ->
            when {
                before is PlayClock.Closed || (before is PlayClock.Playing) == active -> before
                active -> PlayClock.Playing(before.elapsed(), time.markNow())
                else -> PlayClock.Paused(before.elapsed())
            }
        }
    }

    override fun close() {
        activity.update { if (it is PlayClock.Closed) it else PlayClock.Closed(it.elapsed()) }
        job?.cancel()
    }
}
