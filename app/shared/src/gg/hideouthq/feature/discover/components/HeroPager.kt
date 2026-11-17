package gg.hideouthq.feature.discover.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import gg.hideouthq.core.designsystem.component.AvatarFill
import gg.hideouthq.core.designsystem.component.HideoutIconButton
import gg.hideouthq.core.designsystem.component.ShapedAvatar
import gg.hideouthq.core.designsystem.icon.HideoutIcons
import gg.hideouthq.core.designsystem.interaction.collectInteractionStates
import gg.hideouthq.core.designsystem.interaction.focusRing
import gg.hideouthq.core.designsystem.interaction.hideoutClickable
import gg.hideouthq.core.designsystem.shape.Cookie
import gg.hideouthq.core.designsystem.theme.HideoutDimens
import gg.hideouthq.core.designsystem.theme.HideoutShapes
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import gg.hideouthq.feature.discover.FeaturedMod
import gg.hideouthq.resources.Res
import gg.hideouthq.resources.discover_featured_mods
import gg.hideouthq.resources.discover_pause_rotation
import gg.hideouthq.resources.discover_resume_rotation
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun HeroPager(featured: List<FeaturedMod>, current: Int, rotation: HeroRotation, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val pagerLabel = stringResource(Res.string.discover_featured_mods)
    Row(
        modifier = modifier
            .background(HideoutTheme.colors.heroPillGlass, HideoutShapes.Full)
            .padding(
                start = HideoutDimens.Hero.PagerPillPaddingStart,
                top = HideoutDimens.Hero.PagerPillPaddingTop,
                end = HideoutDimens.Hero.PagerPillPaddingEnd,
                bottom = HideoutDimens.Hero.PagerPillPaddingBottom,
            ),
        horizontalArrangement = Arrangement.spacedBy(HideoutDimens.Hero.PagerPillGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HideoutIconButton(
            icon = if (rotation.userPaused) HideoutIcons.Play else HideoutIcons.Pause,
            contentDescription = stringResource(if (rotation.userPaused) Res.string.discover_resume_rotation else Res.string.discover_pause_rotation),
            onClick = { rotation.userPaused = !rotation.userPaused },
            size = HideoutDimens.Hero.PagerPauseButton,
            tint = HideoutTheme.colors.onImage,
            hoverColor = HideoutTheme.colors.glassOnHero,
        )
        Row(
            modifier = Modifier
                .semantics { contentDescription = pagerLabel }
                .onKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                    val count = featured.size
                    when (event.key) {
                        Key.DirectionLeft, Key.DirectionUp -> {
                            onSelect((current - 1).mod(count))
                            true
                        }
                        Key.DirectionRight, Key.DirectionDown -> {
                            onSelect((current + 1).mod(count))
                            true
                        }
                        else -> false
                    }
                },
            horizontalArrangement = Arrangement.spacedBy(HideoutDimens.Hero.PagerThumbGap),
        ) {
            featured.forEachIndexed { index, mod ->
                val progress = when {
                    index < current -> 1f
                    index == current -> rotation.progress
                    else -> 0f
                }
                HeroPagerThumbnail(mod, active = index == current, progress = progress, onClick = { onSelect(index) })
            }
        }
    }
}

@Composable
private fun HeroPagerThumbnail(mod: FeaturedMod, active: Boolean, progress: Float, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val motion = HideoutTheme.motion
    val source = remember { MutableInteractionSource() }
    val states = source.collectInteractionStates()
    val scale by animateFloatAsState(if (active) HideoutDimens.Hero.PagerThumbActiveScale else 1f, motion.spatialFast(), label = "pagerThumbScale")
    val alpha by animateFloatAsState(if (active) 1f else HideoutDimens.Hero.PagerThumbInactiveAlpha, motion.effectsDefault(), label = "pagerThumbAlpha")
    Box(modifier.width(HideoutDimens.Hero.PagerThumbSlot)) {
        Column(
            modifier = Modifier.align(Alignment.TopCenter),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(HideoutDimens.Hero.PagerThumbGap),
        ) {
            Box(
                Modifier
                    .size(HideoutDimens.Hero.PagerThumb)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        this.alpha = alpha
                    }
                    .focusRing(states.focusVisible, Cookie.Six.shape)
                    .hideoutClickable(source, role = Role.Tab, onClick = onClick)
                    .semantics { contentDescription = mod.item.title; selected = active },
            ) {
                ShapedAvatar(
                    initials = mod.item.heroName?.take(ThumbInitialsLength).orEmpty(),
                    shape = Cookie.Six.shape,
                    fill = AvatarFill.Neutral,
                    size = HideoutDimens.Hero.PagerThumb,
                    textStyle = HideoutTheme.typography.initialsChip,
                    imageUrl = mod.item.heroIconUrl,
                )
            }
            PagerProgressLine(progress)
        }
        if (states.hovered || states.focusVisible) {
            Text(
                text = mod.item.title,
                style = HideoutTheme.typography.tooltipDetail,
                color = HideoutTheme.colors.onImage,
                maxLines = 1,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(y = -TooltipRiseOffset)
                    .background(HideoutTheme.colors.heroPillGlass, HideoutShapes.Tile)
                    .padding(horizontal = HideoutDimens.Tooltip.HorizontalPadding, vertical = HideoutDimens.Tooltip.VerticalPadding),
            )
        }
    }
}

@Composable
private fun PagerProgressLine(progress: Float, modifier: Modifier = Modifier) {
    Box(
        modifier
            .width(HideoutDimens.Hero.PagerProgressWidth)
            .height(HideoutDimens.Hero.PagerProgressHeight)
            .clip(HideoutShapes.Full)
            .background(Color.White.copy(alpha = ProgressTrackAlpha)),
    ) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(progress.coerceIn(0f, 1f)).background(HideoutTheme.colors.onImage, HideoutShapes.Full))
    }
}

private const val ThumbInitialsLength = 2
private const val ProgressTrackAlpha = 0.22f
private val TooltipRiseOffset = 36.dp
