package gg.hideouthq.core.crosshair

import kotlin.math.floor
import kotlin.math.roundToInt

/** A rectangle in device pixels, around the crosshair's centre at (0, 0). */
data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float)

/** An outline: [outer] with [inner] cut out, its border drawn inward as the game's panels draw theirs. */
data class Frame(val outer: Box, val inner: Box)

/**
 * One crosshair as the game draws it, layer by layer in drawing order: line borders, lines, dot border, dot. Each layer
 * is one path at its own opacity, so where its shapes overlap the colour is not laid on twice. The dot and its border
 * are the ovals their boxes hold. A layer the game does not draw is empty or null.
 */
data class CrosshairShapes(
    val lineOutlines: List<Frame>,
    val lines: List<Box>,
    val dotOutline: Frame?,
    val dot: Box?,
    val color: CrosshairColor,
    val borderColor: CrosshairColor,
    val lineOpacity: Float,
    val lineOutlineOpacity: Float,
    val dotOpacity: Float,
    val dotOutlineOpacity: Float,
)

object CrosshairGeometry {
    /** The one offset the game's stylesheet adds to every line's distance from the centre. */
    private const val CentreOffset = 4

    /** [crosshair] at [pxPerUnit] device pixels per layout unit: the density for Actual size, the zoom times it otherwise. */
    fun shapes(crosshair: Crosshair, pxPerUnit: Float): CrosshairShapes {
        val lines = crosshair.lines
        val dot = crosshair.dot
        val width = lines.thickness.whole
        val height = lines.length.whole
        val distance = CentreOffset + lines.gap.whole
        val pips = listOf(Pip(0, -distance, width, height), Pip(0, distance, width, height), Pip(-distance, 0, height, width), Pip(distance, 0, height, width))
        fun box(pip: Pip, grow: Int = 0) = box(pip.x, pip.y, pip.width + grow, pip.height + grow, pxPerUnit)
        val lineBorder = lines.border.size.whole
        val dotSize = dot.size.whole
        val dotBorder = dot.border.size.whole
        val dotOutline = dotSize + dot.border.gap.whole + 2 * dotBorder
        return CrosshairShapes(
            lineOutlines = if (lines.border.shows) pips.map { frame(box(it, lines.border.gap.whole + 2 * lineBorder), lineBorder, pxPerUnit) } else emptyList(),
            lines = if (width > 0 && height > 0 && lines.opacity > ConvarNumber.Zero) pips.map { box(it) } else emptyList(),
            dotOutline = if (dot.border.shows) frame(box(0, 0, dotOutline, dotOutline, pxPerUnit), dotBorder, pxPerUnit) else null,
            dot = if (crosshair.hasDot) box(0, 0, dotSize, dotSize, pxPerUnit) else null,
            color = crosshair.color,
            borderColor = crosshair.borderColor,
            lineOpacity = lines.opacity.value.toFloat(),
            lineOutlineOpacity = lines.border.opacity.value.toFloat(),
            dotOpacity = dot.opacity.value.toFloat(),
            dotOutlineOpacity = dot.border.opacity.value.toFloat(),
        )
    }

    private class Pip(val x: Int, val y: Int, val width: Int, val height: Int)

    private fun box(x: Int, y: Int, width: Int, height: Int, pxPerUnit: Float): Box {
        val across = (width * pxPerUnit).roundToInt()
        val down = (height * pxPerUnit).roundToInt()
        val left = floor(x * pxPerUnit - across / 2f + 0.5f)
        val top = floor(y * pxPerUnit - down / 2f + 0.5f)
        return Box(left, top, left + across, top + down)
    }

    private fun frame(outer: Box, border: Int, pxPerUnit: Float): Frame {
        val inset = (border * pxPerUnit).roundToInt()
        return Frame(outer, Box(outer.left + inset, outer.top + inset, outer.right - inset, outer.bottom - inset))
    }
}
