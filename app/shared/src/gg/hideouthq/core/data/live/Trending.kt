package gg.hideouthq.core.data.live

import kotlin.math.pow

internal object Trending {
    private const val LikeWeight = 25.0
    private const val AgeOffsetDays = 7.0
    private const val Gravity = 1.5

    fun score(downloads: Int, likes: Int, ageDays: Long): Double =
        (downloads + LikeWeight * likes) / (ageDays.coerceAtLeast(0) + AgeOffsetDays).pow(Gravity)
}
