package gg.hideouthq.core.data.fake

import gg.hideouthq.core.crosshair.Crosshair
import gg.hideouthq.core.crosshair.CrosshairColor
import gg.hideouthq.core.crosshair.community.CommunityCrosshair
import gg.hideouthq.core.crosshair.community.CommunityCrosshairs
import gg.hideouthq.core.crosshair.crosshair
import gg.hideouthq.core.data.Loadable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.time.Instant

class FakeCommunityCrosshairs(state: Loadable<List<CommunityCrosshair>>) : CommunityCrosshairs {
    constructor(crosshairs: List<CommunityCrosshair> = SampleCrosshairs.all) : this(Loadable.Ready(crosshairs))

    override val crosshairs: StateFlow<Loadable<List<CommunityCrosshair>>> = MutableStateFlow(state).asStateFlow()

    override fun refresh() = Unit
}

object SampleCrosshairs {
    val all: List<CommunityCrosshair> = listOf(
        row("crosshair_sample01", "Mint cross", "kiri", "2026-10-25T21:04:00Z", likes = 1, crosshair(CrosshairColor(0, 255, 140), length = 4.0, thickness = 1.5, gap = -6.0, opacity = 1.0, lineBorder = 0.0, dotOpacity = 0.0, dotBorderOpacity = 0.0)),
        row("crosshair_sample02", "Pinpoint", "zed", "2026-10-24T09:12:00Z", likes = 1, crosshair(CrosshairColor(0, 255, 255), length = 0.0, thickness = 0.0, gap = 0.0, opacity = 0.0, lineBorder = 0.0, fixedGap = true, dotOpacity = 1.0, dotBorderOpacity = 0.6)),
        row("crosshair_sample03", "Classic white", "Nyx", "2026-10-23T18:40:00Z", likes = 0, crosshair(CrosshairColor.White, length = 6.0, thickness = 2.0, gap = -4.0, opacity = 1.0, dotOpacity = 0.0, dotBorderOpacity = 0.0)),
        row("crosshair_sample04", "Tiny green", "haze", "2026-10-22T13:00:00Z", likes = 0, crosshair(CrosshairColor(0, 255, 0), length = 3.0, thickness = 1.5, gap = -7.0, opacity = 1.0, lineBorder = 0.0, dotOpacity = 0.0, dotBorderOpacity = 0.0)),
        row("crosshair_sample05", "Wide yellow", "Ostrich", "2026-10-21T07:30:00Z", likes = 1, crosshair(CrosshairColor(255, 230, 0), length = 4.5, thickness = 1.5, gap = 3.0, opacity = 0.9, dotOpacity = 1.0, dotBorderOpacity = 0.6)),
        row("crosshair_sample06", "Dot and cross", "pocket", "2026-10-19T22:15:00Z", likes = 0, crosshair(CrosshairColor.White, length = 5.0, thickness = 2.0, gap = -2.0, opacity = 0.8, fixedGap = true, dotOpacity = 1.0, dotBorderOpacity = 1.0)),
        row("crosshair_sample07", "Pink haze", "seven", "2026-10-12T16:05:00Z", likes = 0, crosshair(CrosshairColor(255, 80, 200), length = 7.0, thickness = 1.0, gap = -5.0, opacity = 1.0, lineBorder = 0.0, dotOpacity = 0.0, dotBorderOpacity = 0.0)),
        row("crosshair_sample08", "Red dot", null, "2026-09-30T11:50:00Z", likes = 0, crosshair(CrosshairColor(255, 59, 59), length = 0.0, thickness = 0.0, gap = 0.0, opacity = 0.0, lineBorder = 0.0, dotOpacity = 1.0, dotBorderOpacity = 0.0)),
        row("crosshair_sample09", "Long lines", "Grey", "2026-09-02T08:00:00Z", likes = 0, crosshair(CrosshairColor.White, length = 16.0, thickness = 2.0, gap = 4.0, opacity = 0.5, dotOpacity = 0.7, dotBorderOpacity = 0.7)),
        row("crosshair_sample10", "Thick block", "sumo", "2026-07-14T19:20:00Z", likes = 0, crosshair(CrosshairColor(0, 255, 0), length = 100.0, thickness = 100.0, gap = -20.0, opacity = 0.3, lineBorder = 0.0, dotOpacity = 0.0, dotBorderOpacity = 0.0)),
        row("crosshair_sample11", "Static gap", "lumen", "2026-04-03T10:10:00Z", likes = 1, crosshair(CrosshairColor(0, 200, 255), length = 8.0, thickness = 2.0, gap = 2.0, opacity = 1.0, fixedGap = true, dotOpacity = 0.0, dotBorderOpacity = 0.0)),
        row("crosshair_sample12", "First light", "kiri", "2025-11-16T13:45:00Z", likes = 0, crosshair(CrosshairColor.White, length = 3.5, thickness = 1.0, gap = -5.0, opacity = 1.0, lineBorder = 0.0, dotOpacity = 0.5, dotBorderOpacity = 0.0)),
    )

    private fun row(id: String, name: String, creator: String?, createdAt: String, likes: Int, crosshair: Crosshair) = CommunityCrosshair(id, name, creator, Instant.parse(createdAt), likes, crosshair)
}
