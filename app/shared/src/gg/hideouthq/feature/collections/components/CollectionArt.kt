package gg.hideouthq.feature.collections.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.toRect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import gg.hideouthq.core.designsystem.image.LocalRemoteImages
import gg.hideouthq.core.designsystem.theme.HideoutDimens
import gg.hideouthq.core.designsystem.theme.HideoutShapes
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import gg.hideouthq.core.designsystem.theme.HideoutWeights
import gg.hideouthq.core.model.CalendarDate
import gg.hideouthq.core.model.CollectionLook
import gg.hideouthq.core.model.Rgb
import gg.hideouthq.core.ui.ModLabels
import gg.hideouthq.resources.Res
import gg.hideouthq.resources.collection_seasonal
import gg.hideouthq.resources.grain
import org.jetbrains.compose.resources.imageResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

@Composable
fun CollectionBackdrop(look: CollectionLook, stance: FigureLayout, modifier: Modifier = Modifier) {
    val grain = imageResource(Res.drawable.grain)
    Spacer(
        modifier.drawWithCache {
            val stage = when (stance) {
                FigureLayout.Card -> size.toRect()
                FigureLayout.Banner -> Size(size.height * BannerStageAspect, size.height).let { Rect(Offset(stance.centres[0] * size.width - it.width / 2, 0f), it) }
            }
            fun x(fraction: Float) = stage.left + fraction * stage.width
            fun y(fraction: Float) = stage.top + fraction * stage.height
            val wash = look.glows.firstOrNull()?.takeIf { stance == FigureLayout.Banner }?.let {
                Light(Offset(size.width * 0.55f, size.height * 0.2f), Size(size.width * 0.6f, size.height * 0.9f), 0f to it.color.toColor(0.16f), 1f to it.color.toColor(0f))
            }
            val glows = look.glows.map {
                val radius = it.radius * stage.width
                Light(Offset(x(it.x), y(it.y)), Size(radius, radius * 0.8f), 0f to it.color.toColor(it.alpha), 1f to it.color.toColor(0f))
            }
            val main = Light(
                Offset(x(0.5f), y(0.62f)), stage.size / 2f,
                0f to look.main.toColor(0.42f), 0.55f to look.main.toColor(0.12f), 1f to look.main.toColor(0f),
            )
            val lights = listOfNotNull(wash) + glows + main
            val arch = if (look.arch) {
                Path().apply {
                    moveTo(x(0.33f), y(1f))
                    lineTo(x(0.33f), y(0.4f))
                    quadraticTo(x(0.33f), y(0.14f), x(0.5f), y(0.08f))
                    quadraticTo(x(0.67f), y(0.14f), x(0.67f), y(0.4f))
                    lineTo(x(0.67f), y(1f))
                    close()
                }
            } else {
                null
            }
            val base = Brush.verticalGradient(listOf(look.top.toColor(), look.bottom.toColor()))
            val floor = Brush.verticalGradient(0.6f to Color.Black.copy(alpha = 0f), 1f to Color.Black.copy(alpha = 0.5f))
            val vignette = Light(Offset(size.width * 0.5f, size.height * 0.45f), size * 0.75f, 0.55f to Color.Black.copy(alpha = 0f), 1f to Color.Black.copy(alpha = 0.55f))
            val noise = ShaderBrush(ImageShader(grain, TileMode.Repeated, TileMode.Repeated))
            onDrawBehind {
                drawRect(base)
                lights.forEach { fill(it) }
                arch?.let { drawPath(it, look.main.toColor(0.1f)) }
                drawRect(floor)
                fill(vignette)
                drawRect(noise, alpha = GrainAlpha)
            }
        },
    )
}

private class Light(val center: Offset, val radii: Size, vararg stops: Pair<Float, Color>) {
    val brush = Brush.radialGradient(*stops, center = center, radius = radii.width)
}

private fun DrawScope.fill(light: Light) {
    val squeeze = light.radii.height / light.radii.width
    scale(1f, squeeze, light.center) {
        drawRect(light.brush, Offset(0f, light.center.y * (1 - 1 / squeeze)), Size(size.width, size.height / squeeze))
    }
}

internal fun Rgb.toColor(alpha: Float = 1f): Color = Color(0xFF000000 or value.toLong()).copy(alpha = alpha)

/**
 * Where a collection's renders stand: the centre of each slot across the art, middle first, and the middle render's
 * height and the top of its head, both as fractions of the art's height.
 */
enum class FigureLayout(val centres: List<Float>, val height: Float, val headTop: Float) {
    Card(listOf(0.50f, 0.24f, 0.76f), height = 1.35f, headTop = 0.16f),

    Banner(listOf(0.72f, 0.58f, 0.86f), height = 1.25f, headTop = 0.14f),
}

@Composable
internal fun CollectionFigures(urls: List<String>, stance: FigureLayout, rims: List<Rgb>, modifier: Modifier = Modifier, rise: () -> Dp = { 0.dp }) {
    Box(modifier.graphicsLayer { translationY = -rise().toPx() }) {
        DrawOrder.filter { it < urls.size }.forEach { slot ->
            key(urls[slot]) { Figure(urls[slot], stance, slot, rims) }
        }
    }
}

@Composable
private fun Figure(url: String, stance: FigureLayout, slot: Int, rims: List<Rgb>) {
    val image = LocalRemoteImages.current.rememberImage(url, ContentScale.Fit) ?: return
    val placed = Modifier.fillMaxSize().standing(stance, slot)
    if (image.loaded) {
        val dimens = HideoutDimens.Collection
        rims.forEachIndexed { index, rim ->
            val towardRight = rims.size == 1 || index > 0
            Image(
                image.painter,
                contentDescription = null,
                modifier = placed
                    .offset(if (towardRight) dimens.RimOffsetX else -dimens.RimOffsetX, -dimens.RimOffsetY)
                    .blur(dimens.RimBlur, BlurredEdgeTreatment.Unbounded),
                contentScale = ContentScale.Fit,
                alpha = RimAlpha,
                colorFilter = ColorFilter.tint(rim.toColor(), BlendMode.SrcIn),
            )
        }
    }
    Image(image.painter, contentDescription = null, modifier = placed.then(image.layoutModifier), contentScale = ContentScale.Fit)
}

private fun Modifier.standing(stance: FigureLayout, slot: Int): Modifier = layout { measurable, constraints ->
    val width = constraints.maxWidth
    val height = constraints.maxHeight
    val middle = stance.height * height / (FigureBottom - FigureTop)
    val middleTop = stance.headTop * height - FigureTop * middle
    val pictureHeight = if (slot == 0) middle else middle * SideScale
    val pictureTop = middleTop + FigureBottom * (middle - pictureHeight)
    val pictureWidth = pictureHeight * PictureAspect
    val left = stance.centres[slot] * width - FigureCentre * pictureWidth
    val placeable = measurable.measure(Constraints.fixed(pictureWidth.roundToInt(), pictureHeight.roundToInt()))
    layout(width, height) { placeable.place(left.roundToInt(), pictureTop.roundToInt()) }
}

@Composable
internal fun SeasonChip(end: CalendarDate, modifier: Modifier = Modifier) {
    val dimens = HideoutDimens.Collection
    val colors = HideoutTheme.colors
    Row(
        modifier
            .height(dimens.ChipHeight)
            .background(colors.glass, HideoutShapes.Full)
            .padding(start = dimens.ChipStartPadding, end = dimens.ChipEndPadding),
        horizontalArrangement = Arrangement.spacedBy(dimens.ChipGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(dimens.ChipDot).background(CollectionWarm, CircleShape))
        Text(
            stringResource(Res.string.collection_seasonal, ModLabels.dayMonth(end)),
            style = HideoutTheme.typography.captionCompact.copy(fontWeight = HideoutWeights.Title),
            color = colors.onImage,
            maxLines = 1,
        )
    }
}

internal val CollectionWarm = Color(0xFFF2A65A)

private val DrawOrder = listOf(1, 2, 0)
private const val FigureTop = 0.075f
private const val FigureBottom = 0.89f
private const val FigureCentre = 0.49f
private const val PictureAspect = 1.5f
private const val SideScale = 0.85f
private const val BannerStageAspect = 1.2f / 0.7f

private const val GrainAlpha = 0.45f

private const val RimAlpha = 0.6f
