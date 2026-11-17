package gg.hideouthq.feature.stats.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import gg.hideouthq.core.designsystem.image.LocalRemoteImages
import gg.hideouthq.core.designsystem.shape.Cookie
import gg.hideouthq.core.designsystem.shape.fitTo
import gg.hideouthq.core.designsystem.shape.toPath
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import gg.hideouthq.feature.stats.RankVisual

private fun DrawScope.drawArtPlaceholder() {
    drawRect(Brush.linearGradient(listOf(StatsColors.ArtBaseStart, StatsColors.ArtBaseEnd), start = Offset.Zero, end = Offset(size.width, size.height)))
    glow(0.80f, 0.08f, 0.60f, 0.95f, StatsColors.ArtPlaceholder[0])
    glow(1.00f, 1.00f, 0.40f, 0.70f, StatsColors.ArtPlaceholder[1])
    glow(0.58f, 1.15f, 0.30f, 0.60f, StatsColors.ArtPlaceholder[2])
}

private fun DrawScope.glow(cx: Float, cy: Float, rx: Float, ry: Float, color: Color) {
    val radiusX = size.width * rx
    val radiusY = size.height * ry
    val center = Offset(size.width * cx, size.height * cy)
    translate(center.x, center.y) {
        scale(1f, radiusY / radiusX, pivot = Offset.Zero) {
            drawCircle(Brush.radialGradient(0f to color, 0.7f to color.copy(alpha = 0f), center = Offset.Zero, radius = radiusX), radius = radiusX, center = Offset.Zero)
        }
    }
}

@Composable
internal fun RankBadge(rank: RankVisual?, size: Dp, modifier: Modifier = Modifier) {
    val image = rank?.imageUrl?.let { LocalRemoteImages.current.rememberImage(it, ContentScale.Fit) }
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        if (image?.loaded != true) DrawnBadge(rank?.subrank?.takeIf { it in 1..6 }, size)
        if (image != null) Image(image.painter, contentDescription = null, modifier = Modifier.fillMaxSize().then(image.layoutModifier), contentScale = ContentScale.Fit)
    }
}

private val BadgeLight = Color(0xFFF3E7C9)
private val BadgeDark = Color(0xFF92805A)
private val BadgeInner = Color(0xFF211E19)
private val BadgeNumber = Color(0xFFEADBB8)

@Composable
private fun DrawnBadge(number: Int?, badgeSize: Dp) {
    Box(Modifier.size(badgeSize), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val glowRadius = size.minDimension * 0.62f
            drawCircle(Brush.radialGradient(listOf(StatsColors.Gold.copy(alpha = 0.22f), Color.Transparent), radius = glowRadius), radius = glowRadius)
            val outer = Cookie.Twelve.polygon.cubics.toPath().fitTo(Size(size.width * 0.96f, size.height * 0.96f))
            translate(size.width * 0.02f, size.height * 0.02f) {
                drawPath(outer, Brush.linearGradient(listOf(BadgeLight, BadgeDark), start = Offset.Zero, end = Offset(size.width * 0.4f, size.height)))
            }
            val innerSize = Size(size.width * 0.76f, size.height * 0.76f)
            val inner = Cookie.Twelve.polygon.cubics.toPath().fitTo(innerSize)
            translate(size.width * 0.12f, size.height * 0.12f) {
                drawPath(inner, BadgeInner)
                drawPath(inner, BadgeLight.copy(alpha = 0.22f), style = Stroke(1.2.dp.toPx()))
            }
        }
        if (number != null) Text("$number", style = statsType().kpiValue.resized((badgeSize.value * 31f / 88f).sp).copy(fontWeight = FontWeight.Bold, letterSpacing = 0.sp), color = BadgeNumber)
    }
}

@Composable
internal fun ArtSurface(artUrl: String?, shade: Brush, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val image = artUrl?.let { LocalRemoteImages.current.rememberImage(it, ContentScale.Crop) }
    val shown by animateFloatAsState(if (image?.loaded == true) 1f else 0f, HideoutTheme.motion.effectsSlow(), label = "heroArt")
    Box(modifier) {
        Box(Modifier.matchParentSize().clipToBounds().drawBehind { drawArtPlaceholder() }) {
            if (image != null) {
                Image(
                    image.painter, contentDescription = null, modifier = Modifier.fillMaxSize().graphicsLayer { alpha = shown }.then(image.layoutModifier),
                    contentScale = ContentScale.Crop, alignment = Alignment.CenterEnd,
                )
            }
        }
        Box(Modifier.matchParentSize().drawBehind { drawRect(shade) })
        content()
    }
}

@Composable
internal fun SelfAvatar(initial: String, avatarUrl: String?, size: Dp, modifier: Modifier = Modifier) {
    val image = avatarUrl?.let { LocalRemoteImages.current.rememberImage(it, ContentScale.Crop) }
    val shape = Cookie.Twelve.shape
    Box(modifier.size(size).clip(shape).drawBehind { drawRect(StatsColors.SelfAvatar) }, contentAlignment = Alignment.Center) {
        if (image?.loaded != true) Text(initial, style = statsType().name.resized((size.value * 30f / 84f).sp).copy(fontWeight = FontWeight.Bold), color = StatsColors.OnSelfAvatar)
        if (image != null) Image(image.painter, contentDescription = null, modifier = Modifier.fillMaxSize().then(image.layoutModifier), contentScale = ContentScale.Crop)
    }
}
