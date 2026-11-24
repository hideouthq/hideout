package gg.hideouthq.tools

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import gg.hideouthq.core.designsystem.image.RemoteImage
import gg.hideouthq.core.designsystem.image.RemoteImages
import gg.hideouthq.core.model.CalendarDate
import gg.hideouthq.core.model.CoverImage
import gg.hideouthq.core.model.Mod
import gg.hideouthq.core.model.ModCategory

/** Covers for the other managers' sample mods, painted here so a render shows them without the network. */
internal object ForeignCovers : RemoteImages {
    private val Published = CalendarDate(2026, 10, 1)

    private val art = mapOf(
        "mod-700001" to (Color(0xFF7B2FF7) to Color(0xFF00E0FF)),
        "mod-700002" to (Color(0xFF3A4A3F) to Color(0xFFB8C46A)),
        "mod-700003" to (Color(0xFFE2483D) to Color(0xFFFFC857)),
        "mod-700010" to (Color(0xFF14213D) to Color(0xFFFCA311)),
        "mod-724744" to (Color(0xFF1B4332) to Color(0xFFD8F3DC)),
        "mod-735850" to (Color(0xFF023E8A) to Color(0xFF90E0EF)),
    )

    val mods: List<Mod> = art.keys.mapIndexed { index, id ->
        Mod(
            id = id, title = id, category = ModCategory.Other, downloads = 0, likes = 0, sizeBytes = null,
            publishedAt = Published.epochSeconds, updated = Published, trendingRank = 1_000 + index,
            cover = CoverImage(url = "https://images.example/$id.webp"),
        )
    }

    private val loaded = mutableStateOf(true)

    @Composable
    override fun rememberImage(url: String, contentScale: ContentScale): RemoteImage? = remember(url) {
        art[url.substringAfterLast('/').removeSuffix(".webp")]?.let { (from, to) -> RemoteImage(Cover(from, to), loaded) }
    }

    private class Cover(private val from: Color, private val to: Color) : Painter() {
        override val intrinsicSize: Size = Size.Unspecified

        override fun DrawScope.onDraw() {
            drawRect(Brush.linearGradient(listOf(from, to), Offset.Zero, Offset(size.width, size.height)))
            drawCircle(to.copy(alpha = 0.55f), radius = size.minDimension * 0.42f, center = Offset(size.width * 0.72f, size.height * 0.38f))
        }
    }
}
