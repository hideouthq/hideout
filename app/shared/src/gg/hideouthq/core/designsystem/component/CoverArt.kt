package gg.hideouthq.core.designsystem.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import gg.hideouthq.core.designsystem.image.LocalRemoteImages
import gg.hideouthq.core.designsystem.theme.HideoutDimens
import gg.hideouthq.core.designsystem.theme.HideoutMotion
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import gg.hideouthq.core.model.CoverImage

/**
 * A cover: a flat tint, optionally with [icon] centred at 30% or a sound's [waveform] (bar heights,
 * 0..1) across it, under the image when there is one.
 * The tint comes from [seed] (stable for the same seed) unless [tintIndex] picks one of the six
 * placeholder tints directly. Takes any [shape], so it can be a card, a 40dp thumbnail or the
 * scalloped hero cut-out; this is the element that travels in the card-to-detail transition.
 */
@Composable
fun CoverArt(
    seed: String,
    modifier: Modifier = Modifier,
    shape: Shape = RectangleShape,
    imageUrl: String? = null,
    icon: ImageVector? = null,
    tintIndex: Int? = null,
    tint: Color? = null,
    waveform: List<Float>? = null,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val image = imageUrl?.let { LocalRemoteImages.current.rememberImage(it, contentScale) }
    val placeholders = HideoutTheme.colors.coverPlaceholders
    val color = tint ?: placeholders[(tintIndex ?: stableIndex(seed, placeholders.size)).mod(placeholders.size)]
    Box(modifier.clip(shape), contentAlignment = Alignment.Center) {
        Box(Modifier.fillMaxSize().background(color))
        if (waveform != null && image?.loaded != true) CoverWaveform(waveform, HideoutTheme.colors.onImage.copy(alpha = HideoutMotion.CoverWaveformAlpha))
        if (icon != null && image?.loaded != true) {
            HideoutIcon(
                icon = icon,
                contentDescription = null,
                size = HideoutDimens.Icon.Large,
                tint = HideoutTheme.colors.onImage,
                modifier = Modifier.alpha(HideoutMotion.CoverIconAlpha),
            )
        }
        if (image != null) Image(image.painter, null, Modifier.fillMaxSize().then(image.layoutModifier), contentScale = contentScale)
    }
}

@Composable
fun CoverArt(
    seed: String,
    image: CoverImage,
    modifier: Modifier = Modifier,
    tintIndex: Int? = null,
    tint: Color? = null,
    contentScale: ContentScale = ContentScale.Crop,
) {
    Box(modifier) {
        key(image.url, image.fullUrl) {
            val original = image.fullUrl?.takeIf { it != image.url }?.let { LocalRemoteImages.current.rememberImage(it, contentScale) }
            CoverArt(
                seed,
                Modifier.fillMaxSize(),
                imageUrl = image.url.takeUnless { original?.loaded == true },
                tintIndex = tintIndex,
                tint = tint,
                contentScale = contentScale,
            )
            if (original != null) Image(original.painter, null, Modifier.fillMaxSize().then(original.layoutModifier), contentScale = contentScale)
        }
    }
}

@Composable
private fun CoverWaveform(bars: List<Float>, color: Color) {
    Canvas(Modifier.fillMaxSize()) {
        val width = size.width * WaveformWidthFraction
        val pitch = HideoutDimens.Cover.WaveformBarPitch.toPx()
        val count = minOf(bars.size, (width / pitch).toInt()).coerceAtLeast(1)
        val stroke = pitch * WaveformStrokeFraction
        val start = (size.width - pitch * count) / 2 + pitch / 2
        val maxLength = size.height * WaveformHeightFraction
        val centre = size.height / 2
        repeat(count) { index ->
            val length = (bars[index * bars.size / count] * maxLength).coerceAtLeast(stroke)
            val x = start + pitch * index
            drawLine(color, Offset(x, centre - length / 2), Offset(x, centre + length / 2), strokeWidth = stroke, cap = StrokeCap.Round)
        }
    }
}

private const val WaveformWidthFraction = 0.72f
private const val WaveformHeightFraction = 0.46f
private const val WaveformStrokeFraction = 0.5f

private fun stableIndex(seed: String, buckets: Int): Int {
    var hash = FnvOffset
    seed.forEach { char ->
        hash = hash xor char.code
        hash *= FnvPrime
    }
    return (hash and Int.MAX_VALUE) % buckets
}

private const val FnvOffset = -0x7ee3623b
private const val FnvPrime = 16777619
