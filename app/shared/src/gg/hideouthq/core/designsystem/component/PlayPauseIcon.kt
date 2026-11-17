package gg.hideouthq.core.designsystem.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.util.lerp
import gg.hideouthq.core.designsystem.icon.DefaultStrokeWidth
import gg.hideouthq.core.designsystem.theme.HideoutDimens
import gg.hideouthq.core.designsystem.theme.HideoutTheme

private const val Viewport = 24f
private const val GlyphStroke = DefaultStrokeWidth

private val PlayQuads = arrayOf(
    floatArrayOf(8f, 5.5f, 13.5f, 8.75f, 13.5f, 15.25f, 8f, 18.5f),
    floatArrayOf(13.5f, 8.75f, 19f, 12f, 19f, 12f, 13.5f, 15.25f),
)
private val PauseQuads = arrayOf(
    floatArrayOf(7f, 5f, 10f, 5f, 10f, 19f, 7f, 19f),
    floatArrayOf(14f, 5f, 17f, 5f, 17f, 19f, 14f, 19f),
)

@Composable
fun PlayPauseIcon(
    playing: Boolean,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    size: Dp = HideoutDimens.Icon.Size,
    tint: Color = LocalContentColor.current,
) {
    val progress by animateFloatAsState(
        targetValue = if (playing) 1f else 0f,
        animationSpec = HideoutTheme.motion.spatialFast(),
        label = "playPause",
    )
    Canvas(
        modifier
            .size(size)
            .semantics { if (contentDescription != null) this.contentDescription = contentDescription },
    ) {
        val scale = this.size.width / Viewport
        val t = progress.coerceIn(0f, 1f)
        val path = Path()
        for (quad in PlayQuads.indices) {
            val from = PlayQuads[quad]
            val to = PauseQuads[quad]
            for (corner in 0 until 4) {
                val point = Offset(lerp(from[2 * corner], to[2 * corner], t), lerp(from[2 * corner + 1], to[2 * corner + 1], t)) * scale
                if (corner == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
            }
            path.close()
        }
        drawPath(path, tint)
        drawPath(path, tint, style = Stroke(width = GlyphStroke * scale, join = StrokeJoin.Round))
    }
}
