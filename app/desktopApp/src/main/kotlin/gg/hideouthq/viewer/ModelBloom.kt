package gg.hideouthq.viewer

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.BlendMode
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.ColorFilter
import org.jetbrains.skia.ColorMatrix
import org.jetbrains.skia.FilterTileMode
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageFilter
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface

/** Draws a frame the GL thread read bottom-up the right way up. */
internal fun Canvas.drawModel(image: Image, width: Float, height: Float, bloom: Boolean) {
    save()
    translate(0f, height)
    scale(1f, -1f)
    val source = Rect.makeWH(image.width.toFloat(), image.height.toFloat())
    val target = Rect.makeWH(width, height)
    drawImageRect(image, source, target)
    if (bloom) Paint().use { glow ->
        val sigma = minOf(width, height) * BloomSpread
        glow.blendMode = BlendMode.SCREEN
        glow.alpha = BloomAlpha
        ColorFilter.makeMatrix(BrightPass).use { bright ->
            ImageFilter.makeColorFilter(bright, null, null).use { highlights ->
                ImageFilter.makeBlur(sigma, sigma, FilterTileMode.DECAL, highlights, null).use { blur ->
                    glow.imageFilter = blur
                    drawImageRect(image, source, target, glow)
                }
            }
        }
    }
    restore()
}

internal fun RenderedFrame.upright(bloom: Boolean): ImageBitmap {
    val width = image.width
    val height = image.height
    return Surface.makeRasterN32Premul(width, height).use { surface ->
        surface.canvas.drawModel(image, width.toFloat(), height.toFloat(), bloom)
        surface.makeImageSnapshot().use { it.toComposeImageBitmap() }
    }
}

private val BrightPass = ColorMatrix(
    BrightGain, 0f, 0f, 0f, -BrightGain * BrightThreshold,
    0f, BrightGain, 0f, 0f, -BrightGain * BrightThreshold,
    0f, 0f, BrightGain, 0f, -BrightGain * BrightThreshold,
    BrightGain * LumaRed, BrightGain * LumaGreen, BrightGain * LumaBlue, 0f, -BrightGain * BrightThreshold,
)

private const val LumaRed = 0.299f
private const val LumaGreen = 0.587f
private const val LumaBlue = 0.114f

private const val BrightGain = 2.5f
private const val BrightThreshold = 0.55f
private const val BloomSpread = 0.025f
private const val BloomAlpha = 180
