package gg.hideouthq.feature.discover.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import gg.hideouthq.core.designsystem.image.LocalRemoteImages
import gg.hideouthq.core.designsystem.theme.HideoutDimens
import gg.hideouthq.core.designsystem.theme.HideoutMotion
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import gg.hideouthq.core.ui.catalog.CardTransition.cover
import gg.hideouthq.core.ui.transition.TravelShape
import gg.hideouthq.feature.discover.FeaturedMod
import gg.hideouthq.resources.Res
import gg.hideouthq.resources.discover_art
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

private const val RenderZoom = 1.4f
private val RenderSlideOffset = 24.dp

@Composable
internal fun HeroStage(featured: List<FeaturedMod>, current: FeaturedMod, modifier: Modifier = Modifier, scale: Float = 1f) {
    val description = stringResource(Res.string.discover_art)
    val slideOffset = with(LocalDensity.current) { (RenderSlideOffset * scale).toPx() }
    Box(
        modifier
            .size(HideoutDimens.Hero.StageWidth * scale, HideoutDimens.Hero.Height)
            .cover(current.item.id, TravelShape.Cookie),
    ) {
        featured.forEach { mod ->
            val url = mod.renderUrl ?: return@forEach
            val shown = mod.item.id == current.item.id
            key(url) {
                HeroRender(
                    url = url,
                    shown = shown,
                    slideOffset = slideOffset,
                    modifier = if (shown) Modifier.semantics { contentDescription = description } else Modifier,
                )
            }
        }
    }
}

@Composable
private fun HeroRender(url: String, shown: Boolean, slideOffset: Float, modifier: Modifier = Modifier) {
    val motion = HideoutTheme.motion
    val image = LocalRemoteImages.current.rememberImage(url, ContentScale.FillHeight) ?: return
    val alpha by animateFloatAsState(
        targetValue = if (shown && image.loaded) 1f else 0f,
        animationSpec = if (shown) motion.effectsDefault() else motion.exitFade(),
        label = "heroRenderFade",
    )
    val offset by animateFloatAsState(
        targetValue = if (shown) 0f else slideOffset,
        animationSpec = if (shown) motion.spatialDefault() else snap(HideoutMotion.ExitFadeMillis),
        label = "heroRenderSlide",
    )
    Image(
        image.painter,
        null,
        // Laid out at the zoomed size rather than scaled once drawn, so the picture is decoded as large as it shows.
        modifier
            .fillMaxSize()
            .zoomedFromTop(RenderZoom)
            .then(image.layoutModifier)
            .graphicsLayer {
                this.alpha = alpha
                translationX = offset
            },
        contentScale = ContentScale.FillHeight,
        alignment = Alignment.TopCenter,
    )
}

private fun Modifier.zoomedFromTop(zoom: Float) = layout { measurable, constraints ->
    val width = (constraints.maxWidth * zoom).roundToInt()
    val height = (constraints.maxHeight * zoom).roundToInt()
    val placeable = measurable.measure(Constraints.fixed(width, height))
    layout(constraints.maxWidth, constraints.maxHeight) { placeable.place((constraints.maxWidth - width) / 2, 0) }
}
