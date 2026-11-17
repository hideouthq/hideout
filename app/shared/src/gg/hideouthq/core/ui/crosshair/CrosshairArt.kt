package gg.hideouthq.core.ui.crosshair

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.RadialGradientShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import gg.hideouthq.core.crosshair.Box as UnitBox
import gg.hideouthq.core.crosshair.Crosshair
import gg.hideouthq.core.crosshair.CrosshairGeometry
import gg.hideouthq.core.crosshair.Frame
import kotlin.math.floor

enum class CrosshairBackdrop {
    Sky,
    Dark,
    Grey,
    Card;

    companion object {
        val Choices = listOf(Sky, Dark, Grey)
    }
}

@Composable
fun CrosshairTile(
    crosshair: Crosshair,
    backdrop: CrosshairBackdrop,
    zoom: Float,
    shape: Shape,
    modifier: Modifier = Modifier,
) {
    Box(modifier.clip(shape)) {
        CrosshairBackground(backdrop, Modifier.fillMaxSize())
        CrosshairImage(crosshair, zoom, Modifier.fillMaxSize())
    }
}

@Composable
private fun CrosshairBackground(backdrop: CrosshairBackdrop, modifier: Modifier = Modifier) {
    when (backdrop) {
        CrosshairBackdrop.Sky -> Box(modifier.background(SkyBrush))
        CrosshairBackdrop.Dark -> Box(modifier.background(DarkBrush))
        CrosshairBackdrop.Grey -> Box(modifier.background(Color(0xFF7D7D7D)))
        CrosshairBackdrop.Card -> Box(modifier.background(CardBrush))
    }
}

/** [crosshair] at the centre of its bounds, [zoom] times its in-game size, one dp per layout unit at 1x. */
@Composable
fun CrosshairImage(crosshair: Crosshair, zoom: Float, modifier: Modifier = Modifier) {
    Canvas(modifier) { drawCrosshair(crosshair, zoom * density) }
}

fun DrawScope.drawCrosshair(crosshair: Crosshair, pxPerUnit: Float) {
    val shapes = CrosshairGeometry.shapes(crosshair, pxPerUnit)
    val colour = Color(shapes.color.r, shapes.color.g, shapes.color.b)
    val border = Color(shapes.borderColor.r, shapes.borderColor.g, shapes.borderColor.b)
    translate(floor(size.width / 2), floor(size.height / 2)) {
        union(shapes.lineOutlines.map { ring(it, oval = false) })?.let { drawPath(it, border, alpha = shapes.lineOutlineOpacity) }
        union(shapes.lines.map { Path().apply { addRect(it.rect) } })?.let { drawPath(it, colour, alpha = shapes.lineOpacity) }
        shapes.dotOutline?.let { drawPath(ring(it, oval = true), border, alpha = shapes.dotOutlineOpacity) }
        shapes.dot?.let { drawOval(colour, topLeft = Offset(it.left, it.top), size = it.rect.size, alpha = shapes.dotOpacity) }
    }
}

private val UnitBox.rect: Rect get() = Rect(left, top, right, bottom)

private fun ring(frame: Frame, oval: Boolean): Path {
    fun shape(box: UnitBox) = Path().apply { if (oval) addOval(box.rect) else addRect(box.rect) }
    return Path().apply { op(shape(frame.outer), shape(frame.inner), PathOperation.Difference) }
}

private fun union(paths: List<Path>): Path? = paths.reduceOrNull { all, next -> Path().apply { op(all, next, PathOperation.Union) } }

private val SkyBrush = Brush.verticalGradient(0f to Color(0xFF8FB6D9), 0.55f to Color(0xFFC9DCEA), 0.56f to Color(0xFF9AA48C), 1f to Color(0xFF6F7A62))
private val DarkBrush = glow(Color(0xFF2A2C33), Color(0xFF0E0F12), reach = 0.7f)
private val CardBrush = glow(Color(0xFF3A3D44), Color(0xFF17181B), reach = 0.85f)

private fun glow(centre: Color, edge: Color, reach: Float) = object : ShaderBrush() {
    override fun createShader(size: Size) = RadialGradientShader(Offset(size.width / 2, size.height * 0.4f), size.maxDimension * reach, listOf(centre, edge))
}
