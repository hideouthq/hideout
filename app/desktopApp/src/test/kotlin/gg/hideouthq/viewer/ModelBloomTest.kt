package gg.hideouthq.viewer

import androidx.compose.ui.graphics.toPixelMap
import gg.hideouthq.core.model.SceneLighting
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ModelBloomTest {
    @Test
    fun aFrameIsTurnedTheRightWayUpAndBloomSpreadsOnlyItsHighlights() {
        val rgba = ByteArray(Side * Side * 4)
        // GL reads rows bottom-up: a white square in the first rows and a dim one beside it sit at the picture's bottom.
        for (y in 0 until Square) for (x in Bright until Bright + Square) rgba.fill(Side, x, y, 220)
        for (y in 0 until Square) for (x in Dim until Dim + Square) rgba.fill(Side, x, y, 90)
        val image = Image.makeRaster(ImageInfo(Side, Side, ColorType.RGBA_8888, ColorAlphaType.PREMUL), rgba, Side * 4)
        val frame = RenderedFrame(FrameRequest(Side, Side, null, 0f, Camera(0f), SceneLighting.Studio), image, AtomicInteger())
        try {
            val plain = frame.upright(bloom = false).toPixelMap()
            assertEquals(1f, plain[Bright + Square / 2, Side - Square / 2].alpha, "the square is at the bottom")
            assertEquals(0f, plain[Bright + Square / 2, Side - Square - Gap].alpha)
            val bloomed = frame.upright(bloom = true).toPixelMap()
            val highlight = bloomed[Bright + Square / 2, Side - Square / 2].red
            assertTrue(highlight > plain[Bright + Square / 2, Side - Square / 2].red && highlight < 1f, "bright shading lightens without clipping to white")
            assertTrue(bloomed[Bright + Square / 2, Side - Square - Gap].alpha > 0.05f, "light spreads above the bright square")
            assertEquals(0f, bloomed[Dim + Square / 2, Side - Square - Gap].alpha, "but not from the dim one")
            assertEquals(plain[Dim + Square / 2, Side - Square / 2], bloomed[Dim + Square / 2, Side - Square / 2], "and the dim one is unchanged")
        } finally {
            frame.close()
        }
    }

    private fun ByteArray.fill(side: Int, x: Int, y: Int, value: Int) {
        val at = (y * side + x) * 4
        for (channel in 0 until 3) this[at + channel] = value.toByte()
        this[at + 3] = 255.toByte()
    }

    private companion object {
        const val Side = 200
        const val Square = 40
        const val Bright = 20
        const val Dim = 130
        const val Gap = 6
    }
}
