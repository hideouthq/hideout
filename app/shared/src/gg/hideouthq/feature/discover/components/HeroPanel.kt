package gg.hideouthq.feature.discover.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import gg.hideouthq.core.designsystem.image.LocalRemoteImages
import gg.hideouthq.core.designsystem.theme.HideoutDimens
import gg.hideouthq.core.designsystem.theme.HideoutShapes
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import gg.hideouthq.core.ui.LocalReducedMotion
import gg.hideouthq.feature.discover.DiscoverActions
import gg.hideouthq.feature.discover.DiscoverUiState
import gg.hideouthq.feature.discover.FeaturedMod
import gg.hideouthq.resources.Res
import gg.hideouthq.resources.discover_eyebrow
import org.jetbrains.compose.resources.stringResource

private const val BackgroundEnterScale = 1.04f

@Composable
internal fun HeroPanel(state: DiscoverUiState, actions: DiscoverActions, modifier: Modifier = Modifier) {
    val featured = state.current ?: return
    val motion = HideoutTheme.motion
    val label = stringResource(Res.string.discover_eyebrow)
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val rotation = rememberHeroRotation(count = state.featured.size, current = state.featuredIndex, onAdvance = actions::advanceFeatured)
    rotation.hovered = hovered

    BoxWithConstraints(
        modifier
            .semantics { contentDescription = label }
            .height(HideoutDimens.Hero.Height)
            .clip(HideoutShapes.Hero)
            .hoverable(hover)
            .onFocusChanged { rotation.focused = it.hasFocus },
    ) {
        val cardWidth = maxWidth
        HeroBackground(featured, Modifier.fillMaxSize())

        val textWidth = minOf(HideoutDimens.Hero.TextMaxWidth, cardWidth * HideoutDimens.Hero.TextWidthFraction)
        HeroInfo(
            featured = featured,
            actions = actions,
            cardWidth = cardWidth,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = HideoutDimens.Hero.PaddingHorizontal, top = HideoutDimens.Hero.PaddingVertical, bottom = HideoutDimens.Hero.PaddingVertical)
                .width(textWidth),
        )

        val stageScale = stageScale(cardWidth, textWidth)
        val stageLeft = stageLeft(cardWidth, textWidth, stageScale)
        HeroStage(
            featured = state.featured,
            current = featured,
            scale = stageScale,
            modifier = Modifier.align(Alignment.TopStart).offset(x = stageLeft),
        )

        val nameWidth = minOf(240.dp, cardWidth - stageLeft - HideoutDimens.Hero.StageWidth * stageScale - HideoutDimens.Hero.PaddingHorizontal)
        if (nameWidth > 0.dp) {
            AnimatedContent(
                targetState = featured.heroNameImageUrl,
                transitionSpec = { fadeIn(motion.effectsDefault()) togetherWith fadeOut(motion.exitFade()) },
                modifier = Modifier.align(Alignment.CenterEnd)
                    .padding(end = HideoutDimens.Hero.PaddingHorizontal)
                    .width(nameWidth).height(112.dp),
                label = "heroNameArt",
            ) { url ->
                val image = url?.let { LocalRemoteImages.current.rememberImage(it, ContentScale.Fit) }
                if (image != null) {
                    Image(image.painter, null, Modifier.fillMaxSize().then(image.layoutModifier), contentScale = ContentScale.Fit)
                }
            }
        }

        HeroPager(
            featured = state.featured,
            current = state.featuredIndex,
            rotation = rotation,
            onSelect = actions::selectFeatured,
            modifier = Modifier.align(Alignment.BottomEnd).padding(HideoutDimens.Hero.PagerEdgeInset),
        )
    }
}

private val PagerEstimatedWidth = HideoutDimens.Hero.PagerPauseButton + HideoutDimens.Hero.PagerPillGap +
    HideoutDimens.Hero.PagerThumbSlot * 4 + HideoutDimens.Hero.PagerPillGap * 4 +
    HideoutDimens.Hero.PagerPillPaddingStart + HideoutDimens.Hero.PagerPillPaddingEnd

private const val MinStageScale = 0.66f

private fun stageScale(cardWidth: Dp, textWidth: Dp): Float {
    val leftBound = HideoutDimens.Hero.PaddingHorizontal + textWidth + HideoutDimens.Hero.StageMinGap
    val rightBound = cardWidth - HideoutDimens.Hero.PagerEdgeInset - PagerEstimatedWidth - HideoutDimens.Hero.StageMinGap
    val available = (rightBound - leftBound).coerceAtLeast(0.dp)
    val needed = HideoutDimens.Hero.StageWidth
    return (available / needed).coerceIn(MinStageScale, 1f)
}

private fun stageLeft(cardWidth: Dp, textWidth: Dp, scale: Float): Dp {
    val design = cardWidth * HideoutDimens.Hero.StageLeftFraction
    val clearOfText = HideoutDimens.Hero.PaddingHorizontal + textWidth + HideoutDimens.Hero.StageMinGap
    val clearOfPager = cardWidth - HideoutDimens.Hero.PagerEdgeInset - PagerEstimatedWidth - HideoutDimens.Hero.StageMinGap -
        HideoutDimens.Hero.StageWidth * scale
    return design.coerceIn(clearOfText, maxOf(clearOfText, clearOfPager))
}

@Composable
private fun HeroBackground(featured: FeaturedMod, modifier: Modifier = Modifier) {
    val motion = HideoutTheme.motion
    val reducedMotion = LocalReducedMotion.current
    AnimatedContent(
        targetState = featured,
        contentKey = { it.item.id },
        transitionSpec = { fadeIn(motion.effectsSlow()) togetherWith fadeOut(motion.effectsSlow()) },
        modifier = modifier,
        label = "heroBackground",
    ) { shown ->
        val scale = remember(shown.item.id) { Animatable(if (reducedMotion) 1f else BackgroundEnterScale) }
        LaunchedEffect(shown.item.id, reducedMotion) {
            if (reducedMotion) scale.snapTo(1f) else scale.animateTo(1f, motion.effectsSlow())
        }
        Box(Modifier.fillMaxSize().graphicsLayer { scaleX = scale.value; scaleY = scale.value }) {
            // The dark base stays under the image always, not only while it loads: the API's hero art
            // has transparent margins meant to sit over a dark backdrop, not fill the frame opaquely.
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainer))
            // Mounted as soon as there is a URL, loaded or not: the layout modifier below is what gives
            // Coil's constraint-based size resolver something to measure, so it must never wait on
            // [RemoteImage.loaded] first (that would be circular: nothing to measure, nothing loads).
            val image = shown.backgroundUrl?.let { LocalRemoteImages.current.rememberImage(it, ContentScale.Crop) }
            if (image != null) {
                Image(image.painter, null, Modifier.fillMaxSize().then(image.layoutModifier), contentScale = ContentScale.Crop, alignment = Alignment.CenterEnd)
            }
        }
    }
}
