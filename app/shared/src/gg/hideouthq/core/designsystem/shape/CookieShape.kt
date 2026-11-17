package gg.hideouthq.core.designsystem.shape

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.graphics.shapes.CornerRounding
import androidx.graphics.shapes.circle
import androidx.graphics.shapes.rectangle
import androidx.graphics.shapes.Cubic
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import kotlin.math.cos
import kotlin.math.PI
import kotlin.math.sin

/**
 * The scalloped "cookie" outlines from the design: the polar curve r(θ) = 1 − a + a·cos(n·θ),
 * scaled to the box and starting at the top.
 *
 * Material 3 Expressive's MaterialShapes has no 8-lobe cookie and its cookies are much deeper, so
 * these are built from the formula as graphics-shapes [RoundedPolygon]s, which keeps [Morph]
 * available for the shape morphs in the motion spec.
 */
enum class Cookie(val lobes: Int, val depth: Float) {
    Eight(lobes = 8, depth = 0.055f),
    Six(lobes = 6, depth = 0.07f),
    Twelve(lobes = 12, depth = 0.04f),
    ;

    /** The outline in a unit box (0..1 on both axes). */
    val polygon: RoundedPolygon by lazy { cookiePolygon(lobes, depth) }

    val shape: Shape by lazy { PolygonShape(polygon) }
}

private const val OutlinePoints = 96
private const val Centre = 0.5f
private const val Radius = 0.5f

private fun cookiePolygon(lobes: Int, depth: Float): RoundedPolygon {
    val vertices = FloatArray(OutlinePoints * 2)
    for (i in 0 until OutlinePoints) {
        val theta = 2 * PI * i / OutlinePoints
        val r = 1 - depth + depth * cos(lobes * theta)
        vertices[2 * i] = Centre + Radius * (r * sin(theta)).toFloat()
        vertices[2 * i + 1] = Centre - Radius * (r * cos(theta)).toFloat()
    }
    return RoundedPolygon(vertices)
}

/** A circle in the unit box with the same number of points as the cookies, so morphs pair up evenly. */
val CirclePolygon: RoundedPolygon by lazy {
    RoundedPolygon.circle(numVertices = OutlinePoints, radius = Radius, centerX = Centre, centerY = Centre)
}

val StagePolygon: RoundedPolygon by lazy {
    RoundedPolygon.rectangle(width = 1f, height = 1f, rounding = CornerRounding(StageCornerFraction), centerX = Centre, centerY = Centre)
}

val CookieToStage: Morph by lazy { Morph(Cookie.Eight.polygon, StagePolygon) }

private const val StageCornerFraction = 0.024f

class PolygonShape(
    private val polygon: RoundedPolygon,
    private val rotationDegrees: Float = 0f,
) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Generic(polygon.cubics.toPath().fitTo(size, rotationDegrees))

    override fun equals(other: Any?) =
        other is PolygonShape && other.polygon == polygon && other.rotationDegrees == rotationDegrees

    override fun hashCode() = polygon.hashCode() * 31 + rotationDegrees.hashCode()
}

class MorphShape(private val morph: Morph, private val progress: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Generic(morph.asCubics(progress).toPath().fitTo(size))

    override fun equals(other: Any?) = other is MorphShape && other.morph === morph && other.progress == progress

    override fun hashCode() = morph.hashCode() * 31 + progress.hashCode()
}

fun List<Cubic>.toPath(path: Path = Path()): Path {
    path.rewind()
    forEachIndexed { index, cubic ->
        if (index == 0) path.moveTo(cubic.anchor0X, cubic.anchor0Y)
        path.cubicTo(
            cubic.control0X, cubic.control0Y,
            cubic.control1X, cubic.control1Y,
            cubic.anchor1X, cubic.anchor1Y,
        )
    }
    path.close()
    return path
}

fun Path.fitTo(size: Size, rotationDegrees: Float = 0f): Path {
    val matrix = Matrix()
    matrix.scale(size.width, size.height)
    if (rotationDegrees != 0f) {
        matrix.translate(Centre, Centre)
        matrix.rotateZ(rotationDegrees)
        matrix.translate(-Centre, -Centre)
    }
    transform(matrix)
    return this
}
