package gg.hideouthq.viewer

import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.exp
import kotlin.math.ln

data class Camera(
    val azimuthDegrees: Float,
    val pitchDegrees: Float = StartPitchDegrees,
    val zoom: Float = 1f,
    val panX: Float = 0f,
    val panY: Float = 0f,
) {
    fun orbit(right: Float, down: Float): Camera = copy(
        azimuthDegrees = azimuthDegrees - right * DegreesPerDp,
        pitchDegrees = (pitchDegrees + down * DegreesPerDp).coerceIn(MinPitchDegrees, MaxPitchDegrees),
    )

    fun zoomed(steps: Float): Camera = copy(zoom = (zoom * exp(-steps * LnZoomPerStep)).coerceIn(MinZoom, MaxZoom))

    companion object {
        val StartPitchDegrees = Math.toDegrees(atan(0.0875)).toFloat()
        const val MinPitchDegrees = -80f
        const val MaxPitchDegrees = 80f
        const val DegreesPerDp = 0.4f
        const val MinZoom = 1f
        const val MaxZoom = 2.5f

        private val LnZoomPerStep = ln(1.1f)
    }
}

internal class ViewCamera(start: Camera) {
    val framing = CameraFraming.Reference(start.azimuthDegrees, start.pitchDegrees)
    var goal = start
    private var shown = start
    private var lastFrame = 0L

    fun scroll(steps: Float, anchorX: Float, anchorY: Float) {
        val zoom = goal.zoomed(steps).zoom
        if (zoom == goal.zoom) return
        goal = when {
            zoom <= 1f -> goal.copy(zoom = zoom, panX = 0f, panY = 0f)
            steps > 0f -> {
                val fraction = zoomOffset(zoom) / zoomOffset(goal.zoom)
                goal.copy(zoom = zoom, panX = goal.panX * fraction, panY = goal.panY * fraction)
            }
            else -> {
                val shift = zoomOffset(shown.zoom) - zoomOffset(zoom)
                goal.copy(zoom = zoom, panX = shown.panX + anchorX * shift, panY = shown.panY + anchorY * shift)
            }
        }
    }

    fun at(frameNanos: Long, instant: Boolean): Camera {
        val elapsed = if (lastFrame == 0L) 0L else frameNanos - lastFrame
        lastFrame = frameNanos
        val remaining = ln(shown.zoom / goal.zoom) * exp(-elapsed / ZoomEaseNanos)
        shown = when {
            instant || abs(remaining) < ZoomSnap -> goal
            elapsed == 0L -> goal.copy(zoom = shown.zoom, panX = shown.panX, panY = shown.panY)
            else -> {
                val zoom = goal.zoom * exp(remaining)
                val distance = zoomOffset(goal.zoom) - zoomOffset(shown.zoom)
                val fraction = if (distance == 0f) 1f else (zoomOffset(zoom) - zoomOffset(shown.zoom)) / distance
                goal.copy(
                    zoom = zoom,
                    panX = shown.panX + (goal.panX - shown.panX) * fraction,
                    panY = shown.panY + (goal.panY - shown.panY) * fraction,
                )
            }
        }
        return shown
    }
}

private fun zoomOffset(zoom: Float): Float = (1f - 1f / zoom).coerceAtLeast(0f)

private const val ZoomEaseNanos = 60_000_000f
private const val ZoomSnap = 0.001f
