package gg.hideouthq.feature.moddetail.viewer

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.roundToInt

internal suspend fun drawStagePicture(stage: DpSize, scenery: Scenery, pose: TurntablePose, capture: ViewportCapture): ImageBitmap? {
    val render = capture.model ?: return null
    val density = Density(PictureScale)
    val size = Size(stage.width.value * PictureScale, stage.height.value * PictureScale)
    val area = modelArea(size, density)
    val model = render(area.width.roundToInt(), area.height.roundToInt()) ?: return null
    val picture = ImageBitmap(size.width.roundToInt(), size.height.roundToInt())
    CanvasDrawScope().draw(density, LayoutDirection.Ltr, Canvas(picture), size) {
        drawBackdrop(scenery)
        if (scenery.settings.turntable) drawCone(scenery.settings.lighting, scenery.primary)
        drawTurntable(scenery, area, pose)
        drawImage(model, dstOffset = IntOffset(area.left.roundToInt(), area.top.roundToInt()))
        drawVignette()
    }
    return picture
}

internal const val PictureScale = 2f
