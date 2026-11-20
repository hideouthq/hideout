package gg.hideouthq.core.designsystem.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.sp
import gg.hideouthq.core.model.ModCardStyle
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import gg.hideouthq.core.designsystem.icon.HideoutIcons
import gg.hideouthq.core.designsystem.interaction.collectInteractionStates
import gg.hideouthq.core.designsystem.interaction.focusRing
import gg.hideouthq.core.designsystem.interaction.hideoutClickable
import gg.hideouthq.core.designsystem.interaction.hideoutToggleable
import gg.hideouthq.core.designsystem.theme.HideoutDimens
import gg.hideouthq.core.designsystem.theme.HideoutMotion
import gg.hideouthq.core.designsystem.theme.HideoutShapes
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import gg.hideouthq.core.designsystem.theme.HideoutWeights

object CardScrim {
    const val Image = 0.64f
    const val HudPreview = 0.46f
    internal const val HoverLighten = 0.8f
}

val LocalModCardStyle = staticCompositionLocalOf { ModCardStyle.OnImage }

data class ModCardGaps(val column: Dp, val row: Dp)

val ModCardStyle.gaps: ModCardGaps
    get() = when (this) {
        ModCardStyle.OnImage -> ModCardGaps(HideoutDimens.Card.Gap, HideoutDimens.Card.Gap)
        ModCardStyle.UnderImage -> ModCardGaps(HideoutDimens.Card.UnderColumnGap, HideoutDimens.Card.UnderRowGap)
    }

/**
 * A mod as a card, in the style [LocalModCardStyle] names.
 *
 * On the image, it is a full-bleed image card: [cover], a bottom scrim, title and subtitle bottom-left, a stat
 * bottom-right, an optional rank badge top-left and feature badge top-right. The whole card is
 * one click target. Hover scales it to 1.015, lightens the scrim and lifts the badges; press dips
 * to 0.985.
 *
 * Under the image, the whole 16:9 picture shows with no scrim, [height] is not used, and the title, subtitle,
 * [libraryBadge] and stat sit below it. Hover and keyboard focus ring the image.
 *
 * [titleModifier] and [decorationModifier] let a screen attach its transition (the title travels;
 * scrim, badges and subtitle fade) without the card knowing about navigation.
 *
 * With [favourite], a star in the top-right corner, beside any feature badge, stars the mod. It shows while the card
 * is hovered or the star has keyboard focus, and stays once the mod is a favourite.
 */
@Composable
fun ModCard(
    title: String,
    subtitle: String,
    stat: String?,
    onClick: () -> Unit,
    cover: @Composable BoxScope.() -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = HideoutDimens.Card.HeightBrowse,
    rank: Int? = null,
    libraryBadge: (@Composable () -> Unit)? = null,
    badgeIcon: ImageVector? = null,
    badgeDescription: String? = null,
    statDescription: String? = null,
    scrimFraction: Float = CardScrim.Image,
    titleModifier: Modifier = Modifier,
    decorationModifier: Modifier = Modifier,
    favourite: CardFavourite? = null,
) {
    val source = remember { MutableInteractionSource() }
    val states = source.collectInteractionStates()
    val motion = HideoutTheme.motion
    val colors = HideoutTheme.colors
    val type = HideoutTheme.typography
    if (LocalModCardStyle.current == ModCardStyle.UnderImage) {
        UnderImageCard(title, subtitle, stat, onClick, cover, modifier, rank, libraryBadge, badgeIcon, badgeDescription, statDescription, titleModifier, decorationModifier, favourite)
        return
    }
    val scale by animateFloatAsState(
        targetValue = when {
            states.pressed -> HideoutMotion.PressScaleCard
            states.hovered -> HideoutMotion.HoverScaleCard
            else -> 1f
        },
        animationSpec = motion.spatialFast(),
        label = "cardScale",
    )
    val hover by animateFloatAsState(if (states.hovered) 1f else 0f, motion.effectsDefault(), label = "cardHover")
    val lift by animateFloatAsState(if (states.hovered) 1f else 0f, motion.spatialFast(), label = "cardBadgeLift")

    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .height(height)
            .clip(HideoutShapes.Card)
            .focusRing(states.focusVisible, HideoutShapes.Card)
            .hideoutClickable(source, role = Role.Button, onClick = onClick),
    ) {
        cover()
        val scrimTop = colors.imageScrim.copy(alpha = 0f)
        Box(
            modifier = decorationModifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(scrimFraction)
                .drawBehind {
                    val bottom = colors.imageScrim.copy(alpha = colors.imageScrim.alpha * (1f - (1f - CardScrim.HoverLighten) * hover))
                    drawRect(Brush.verticalGradient(listOf(scrimTop, bottom)))
                },
        )
        val badgeLift = Modifier.graphicsLayer { translationY = -HideoutDimens.Card.BadgeHoverLift.toPx() * lift }
        if (libraryBadge != null) {
            Box(decorationModifier.then(badgeLift).align(Alignment.TopStart).padding(HideoutDimens.Card.BadgeInset)) { libraryBadge() }
        } else if (rank != null) {
            GlassBadge(decorationModifier.then(badgeLift).align(Alignment.TopStart).padding(HideoutDimens.Card.BadgeInset)) {
                Text(rank.toString(), style = type.labelSection.copy(fontWeight = HideoutWeights.Heavy), color = colors.onImage)
            }
        }
        TopEndBadges(decorationModifier.then(badgeLift).align(Alignment.TopEnd), favourite, states.hovered, badgeIcon, badgeDescription)
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(
                    start = HideoutDimens.Card.CaptionHorizontalInset,
                    end = HideoutDimens.Card.CaptionHorizontalInset,
                    bottom = HideoutDimens.Card.CaptionBottomInset,
                ),
        ) {
            Text(
                text = title,
                style = type.titleCard,
                color = colors.onImage,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = titleModifier,
            )
            Row(
                modifier = decorationModifier,
                horizontalArrangement = Arrangement.spacedBy(HideoutDimens.Card.CaptionGap),
                verticalAlignment = Alignment.Bottom,
            ) {
                Text(
                    text = subtitle,
                    style = type.subtitle,
                    color = colors.onImageVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (stat != null) Row(
                    modifier = Modifier.semantics(mergeDescendants = true) {
                        if (statDescription != null) contentDescription = statDescription
                    },
                    horizontalArrangement = Arrangement.spacedBy(HideoutDimens.Card.StatGap),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    HideoutIcon(HideoutIcons.Download, contentDescription = null, size = HideoutDimens.Icon.Small, tint = colors.onImageVariant)
                    Text(stat, style = type.subtitle, color = colors.onImageVariant, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun UnderImageCard(
    title: String,
    subtitle: String,
    stat: String?,
    onClick: () -> Unit,
    cover: @Composable BoxScope.() -> Unit,
    modifier: Modifier,
    rank: Int?,
    libraryBadge: (@Composable () -> Unit)?,
    badgeIcon: ImageVector?,
    badgeDescription: String?,
    statDescription: String?,
    titleModifier: Modifier,
    decorationModifier: Modifier,
    favourite: CardFavourite?,
) {
    val source = remember { MutableInteractionSource() }
    val states = source.collectInteractionStates()
    val motion = HideoutTheme.motion
    val colors = HideoutTheme.colors
    val type = HideoutTheme.typography
    val scheme = MaterialTheme.colorScheme
    val scale by animateFloatAsState(if (states.pressed) HideoutMotion.PressScaleCard else 1f, motion.spatialFast(), label = "cardScale")
    val ring by animateFloatAsState(if (states.hovered || states.focusVisible) 1f else 0f, motion.effectsDefault(), label = "cardRing")
    val shape = RoundedCornerShape(HideoutDimens.Card.UnderImageRadius)
    Column(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .hideoutClickable(source, role = Role.Button, onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(HideoutDimens.Card.UnderTextGap),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(HideoutDimens.Card.UnderImageRatio)
                .pictureRing(scheme.primary) { ring },
        ) {
            Box(Modifier.fillMaxWidth().fillMaxHeight().clip(shape), content = cover)
            if (rank != null) {
                GlassBadge(decorationModifier.align(Alignment.TopStart).padding(HideoutDimens.Card.BadgeInset)) {
                    Text(rank.toString(), style = type.labelSection.copy(fontWeight = HideoutWeights.Heavy), color = colors.onImage)
                }
            }
            TopEndBadges(decorationModifier.align(Alignment.TopEnd), favourite, states.hovered, badgeIcon, badgeDescription)
        }
        Column(
            Modifier.fillMaxWidth().padding(horizontal = HideoutDimens.Card.UnderTextInset),
            verticalArrangement = Arrangement.spacedBy(HideoutDimens.Card.UnderLineGap),
        ) {
            Text(
                text = title,
                style = type.titleCard.copy(fontSize = 14.5.sp, fontWeight = HideoutWeights.Label),
                color = scheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = titleModifier,
            )
            Row(
                modifier = decorationModifier,
                horizontalArrangement = Arrangement.spacedBy(HideoutDimens.Card.CaptionGap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val meta = type.subtitle.copy(fontSize = 12.5.sp)
                Text(subtitle, style = meta, color = scheme.outline, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                libraryBadge?.invoke()
                if (stat != null) Row(
                    modifier = Modifier.semantics(mergeDescendants = true) {
                        if (statDescription != null) contentDescription = statDescription
                    },
                    horizontalArrangement = Arrangement.spacedBy(HideoutDimens.Card.StatGap),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    HideoutIcon(HideoutIcons.Download, contentDescription = null, size = HideoutDimens.Icon.Small, tint = scheme.outline)
                    Text(stat, style = meta.copy(fontFeatureSettings = "tnum"), color = scheme.outline, maxLines = 1)
                }
            }
        }
    }
}

fun Modifier.pictureRing(color: Color, strength: () -> Float): Modifier = drawWithContent {
    drawContent()
    val alpha = strength()
    if (alpha > 0f) {
        val width = HideoutDimens.Card.UnderRing.toPx()
        val out = HideoutDimens.Card.UnderRingGap.toPx() + width / 2
        drawRoundRect(
            color.copy(alpha = alpha), Offset(-out, -out), Size(size.width + out * 2, size.height + out * 2),
            CornerRadius(HideoutDimens.Card.UnderImageRadius.toPx() + out), style = Stroke(width),
        )
    }
}

class CardFavourite(val starred: Boolean, val label: String, val onToggle: (Boolean) -> Unit)

@Composable
private fun TopEndBadges(modifier: Modifier, star: CardFavourite?, cardHovered: Boolean, badgeIcon: ImageVector?, badgeDescription: String?) {
    if (star == null && badgeIcon == null) return
    val colors = HideoutTheme.colors
    Row(modifier.padding(HideoutDimens.Card.BadgeInset), horizontalArrangement = Arrangement.spacedBy(HideoutDimens.Card.BadgeGap)) {
        if (star != null) FavouriteStar(star, cardHovered)
        if (badgeIcon != null) {
            GlassBadge(Modifier.semantics { if (badgeDescription != null) contentDescription = badgeDescription }) {
                HideoutIcon(badgeIcon, contentDescription = null, size = HideoutDimens.Icon.Small, tint = colors.onImage)
            }
        }
    }
}

@Composable
private fun FavouriteStar(star: CardFavourite, cardHovered: Boolean) {
    val source = remember { MutableInteractionSource() }
    val states = source.collectInteractionStates()
    val shown by animateFloatAsState(if (star.starred || cardHovered || states.focusVisible) 1f else 0f, HideoutTheme.motion.effectsFast(), label = "cardStar")
    GlassBadge(
        Modifier
            .graphicsLayer { alpha = shown }
            .focusRing(states.focusVisible, HideoutShapes.Full)
            .semantics { contentDescription = star.label }
            .hideoutToggleable(star.starred, source, role = Role.Checkbox, onValueChange = star.onToggle),
    ) {
        HideoutIcon(if (star.starred) HideoutIcons.StarFilled else HideoutIcons.Star, contentDescription = null, size = HideoutDimens.Icon.Small, tint = HideoutTheme.colors.onImage)
    }
}

@Composable
private fun GlassBadge(modifier: Modifier, content: @Composable () -> Unit) {
    Box(
        modifier = modifier
            .size(HideoutDimens.Card.Badge)
            .background(HideoutTheme.colors.glass, HideoutShapes.Full),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}
