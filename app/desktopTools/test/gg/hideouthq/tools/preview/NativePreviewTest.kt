package gg.hideouthq.tools.preview

import gg.hideouthq.preview.PreviewJson
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals

class NativePreviewTest {
    @Test
    fun nativeAlphaFactsMatchTheEncodedPngIncludingHalfOpaquePixels() {
        val rgba = ByteArray(10 * 3 * 4)
        val alphas = listOf(0, 1, 15, 16, 127, 128, 254, 255)
        alphas.forEachIndexed { x, alpha -> rgba[(10 + x + 1) * 4 + 3] = alpha.toByte() }
        val pixels = Pixels(10, 3, rgba)
        val facts = pixels.alphaFacts()
        assertEquals(PreviewJson.parseToJsonElement("""{
            "width":10,"height":3,"pixels":30,"transparentPixels":23,"opaquePixels":3,"visiblePixels":5,"borderMaxAlpha":0
        }"""), facts)
        val png = pixels.png()
        assertEquals(listOf(8, 6, 0), listOf(png[24].toInt(), png[25].toInt(), png[28].toInt()))
        val decoded = ImageIO.read(png.inputStream())
        val encodedAlpha = (0 until decoded.height).flatMap { y -> (0 until decoded.width).map { x -> decoded.getRGB(x, y) ushr 24 } }
        assertEquals(23, encodedAlpha.count { it == 0 })
        assertEquals(3, encodedAlpha.count { it >= 128 })
        assertEquals(5, encodedAlpha.count { it >= 16 })
        assertEquals(alphas, (1..8).map { decoded.getRGB(it, 1) ushr 24 })
    }

    @Test
    fun everyBorderPixelCountsEvenWhenItsAlphaIsBelow128() {
        for ((x, y) in listOf(0 to 0, 2 to 0, 4 to 0, 0 to 1, 4 to 1, 0 to 2, 2 to 2, 4 to 2)) {
            for (alpha in listOf(1, 127, 128, 254, 255)) {
                val rgba = ByteArray(5 * 3 * 4)
                rgba[(y * 5 + x) * 4 + 3] = alpha.toByte()
                val pixels = Pixels(5, 3, rgba)
                assertEquals(alpha, pixels.alphaFacts().getValue("borderMaxAlpha").jsonPrimitive.int)
                val decoded = ImageIO.read(pixels.png().inputStream())
                assertEquals(alpha, decoded.getRGB(x, 2 - y) ushr 24)
            }
        }
    }
}
