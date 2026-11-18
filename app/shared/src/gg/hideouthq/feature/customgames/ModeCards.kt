package gg.hideouthq.feature.customgames

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import gg.hideouthq.core.customgames.ModeArt
import gg.hideouthq.core.customgames.ModeKind
import gg.hideouthq.core.customgames.ModeListing
import gg.hideouthq.core.customgames.capacities
import gg.hideouthq.core.designsystem.component.CoverArt
import gg.hideouthq.core.designsystem.component.GlassBadge
import gg.hideouthq.core.designsystem.component.HideoutIcon
import gg.hideouthq.core.designsystem.component.LocalModCardStyle
import gg.hideouthq.core.designsystem.component.gaps
import gg.hideouthq.core.designsystem.component.pictureRing
import gg.hideouthq.core.designsystem.icon.HideoutIcons
import gg.hideouthq.core.designsystem.interaction.InteractionStates
import gg.hideouthq.core.designsystem.interaction.collectInteractionStates
import gg.hideouthq.core.designsystem.interaction.focusRing
import gg.hideouthq.core.designsystem.interaction.hideoutClickable
import gg.hideouthq.core.designsystem.theme.HideoutDimens
import gg.hideouthq.core.designsystem.theme.HideoutMotion
import gg.hideouthq.core.designsystem.theme.HideoutShapes
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import gg.hideouthq.core.designsystem.theme.HideoutWeights
import gg.hideouthq.core.model.ModCardStyle
import gg.hideouthq.core.ui.transition.staggeredEnter
import gg.hideouthq.resources.*
import org.jetbrains.compose.resources.stringResource
import kotlin.math.max

@Composable
fun ModeCardView(card: ModeCard, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val source = remember { MutableInteractionSource() }
    val states = source.collectInteractionStates()
    val listing = card.listing
    val players = playersText(listing)
    val minutes = minutesText(listing, about = false)
    val label = listOfNotNull("${listing.name}.", listing.tagline.takeIf { it.isNotEmpty() }, listOfNotNull(players, minutes).joinToString(", ") + ".").joinToString(" ")
    val target = Modifier.semantics { contentDescription = label }.hideoutClickable(source, onClick = onClick)
    if (LocalModCardStyle.current == ModCardStyle.UnderImage) {
        UnderImageModeCard(card, players, minutes, states, modifier.then(target))
    } else {
        OnImageModeCard(card, players, minutes, states, modifier.then(target))
    }
}

@Composable
private fun OnImageModeCard(card: ModeCard, players: String, minutes: String?, states: InteractionStates, modifier: Modifier) {
    val motion = HideoutTheme.motion
    val colors = HideoutTheme.colors
    val type = HideoutTheme.typography
    val moves = !motion.reduced
    val listing = card.listing
    val active = states.hovered || states.focusVisible
    val scale by animateFloatAsState(
        when {
            !moves -> 1f
            states.pressed -> HideoutMotion.PressScaleCard
            states.hovered -> HideoutMotion.HoverScaleCard
            else -> 1f
        },
        motion.spatialFast(), label = "modeCardScale",
    )
    val fade by animateFloatAsState(if (active) FadeActive else FadeRest, if (moves) motion.effectsDefault() else snap(), label = "modeCardFade")
    Box(
        modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .height(OnImageHeight)
            .clip(HideoutShapes.Card)
            .focusRing(states.focusVisible, HideoutShapes.Card),
    ) {
        CardArt(card.id.value, listing.art, raised = states.hovered && moves, Modifier.fillMaxSize(), glyphTop = true)
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight().drawBehind {
                val top = size.height * (1f - fade)
                drawRect(Brush.verticalGradient(listOf(colors.imageScrim.copy(alpha = 0f), colors.imageScrim), startY = top, endY = size.height), topLeft = Offset(0f, top))
            },
        )
        card.badge?.let { ModeBadgeChip(it, Modifier.padding(HideoutDimens.Card.BadgeInset)) }
        Column(
            Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(
                start = HideoutDimens.Card.CaptionHorizontalInset, end = HideoutDimens.Card.CaptionHorizontalInset, bottom = HideoutDimens.Card.CaptionBottomInset,
            ),
        ) {
            Text(listing.name, style = type.titleCard.copy(fontSize = 16.sp, fontWeight = HideoutWeights.Title), color = colors.onImage, maxLines = 1, overflow = TextOverflow.Ellipsis)
            AnimatedVisibility(
                active && listing.tagline.isNotEmpty(),
                enter = if (moves) expandVertically(motion.spatialDefault()) + fadeIn(motion.enterFade()) else EnterTransition.None,
                exit = if (moves) shrinkVertically(motion.spatialDefault()) + fadeOut(motion.exitFade()) else ExitTransition.None,
            ) {
                Text(
                    listing.tagline, Modifier.padding(top = 2.dp),
                    style = type.caption.copy(fontSize = 12.5.sp, lineHeight = 17.5.sp), color = colors.onImage.copy(alpha = 0.86f), maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MetaItem(HideoutIcons.Users, players, colors.onImageVariant, 12.5.sp)
                minutes?.let { MetaItem(HideoutIcons.Clock, it, colors.onImageVariant, 12.5.sp) }
            }
        }
    }
}

@Composable
private fun UnderImageModeCard(card: ModeCard, players: String, minutes: String?, states: InteractionStates, modifier: Modifier) {
    val motion = HideoutTheme.motion
    val scheme = MaterialTheme.colorScheme
    val type = HideoutTheme.typography
    val moves = !motion.reduced
    val listing = card.listing
    val scale by animateFloatAsState(if (moves && states.pressed) HideoutMotion.PressScaleCard else 1f, motion.spatialFast(), label = "modeCardScale")
    val ring by animateFloatAsState(if (states.hovered || states.focusVisible) 1f else 0f, motion.effectsDefault(), label = "modeCardRing")
    Column(
        modifier.graphicsLayer {
            scaleX = scale
            scaleY = scale
        },
        verticalArrangement = Arrangement.spacedBy(HideoutDimens.Card.UnderTextGap),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(HideoutDimens.Card.UnderImageRatio).pictureRing(scheme.primary) { ring }) {
            CardArt(card.id.value, listing.art, raised = states.hovered && moves, Modifier.fillMaxSize(), shape = UnderImageShape)
            card.badge?.let { ModeBadgeChip(it, Modifier.padding(HideoutDimens.Card.BadgeInset)) }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = HideoutDimens.Card.UnderTextInset), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(listing.name, style = type.titleCard.copy(fontSize = 15.sp, fontWeight = HideoutWeights.Title), color = scheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listing.tagline,
                style = type.caption.copy(fontSize = 12.5.sp, lineHeight = 17.5.sp), color = scheme.onSurfaceVariant, minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            Row(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                MetaItem(HideoutIcons.Users, players, scheme.outline, 12.sp)
                minutes?.let { MetaItem(HideoutIcons.Clock, it, scheme.outline, 12.sp) }
                if (listing.creators.isNotEmpty()) Text(
                    buildAnnotatedString {
                        append(stringResource(Res.string.cg_by) + " ")
                        withStyle(SpanStyle(color = scheme.onSurfaceVariant, fontWeight = HideoutWeights.Label)) { append(listing.creators.joinToString(", ")) }
                    },
                    Modifier.weight(1f), style = type.caption.copy(fontSize = 12.sp), color = scheme.outline, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun CardArt(seed: String, art: ModeArt, raised: Boolean, modifier: Modifier = Modifier, shape: Shape = HideoutShapes.Card, glyphTop: Boolean = false) {
    if (!art.layered) {
        if (art.card == null && glyphTop) {
            Box(modifier) {
                CoverArt(seed, Modifier.fillMaxSize(), shape = shape, imageUrl = null)
                HideoutIcon(HideoutIcons.Gamepad, null, Modifier.padding(start = 18.dp, top = 58.dp), size = 38.dp, tint = Color.White.copy(alpha = 0.88f))
            }
        } else {
            ModeArtSlot(seed, art.card, modifier, shape)
        }
        return
    }
    val rise by animateDpAsState(if (raised) LayerRise else 0.dp, tween(350, easing = LayerEasing), label = "modeCardLayers")
    val zoom by animateFloatAsState(if (raised) 1.04f else 1f, tween(450, easing = LayerEasing), label = "modeCardBackground")
    BoxWithConstraints(modifier.clip(shape)) {
        val unit = maxHeight / OnImageHeight
        CoverArt(
            seed,
            Modifier.fillMaxSize().graphicsLayer {
                scaleX = zoom
                scaleY = zoom
                transformOrigin = TransformOrigin(0.3f, 0.6f)
            },
            shape = shape, imageUrl = art.card,
        )
        val lifted = Modifier.graphicsLayer { translationY = -rise.toPx() }
        ArtLayer(art.render, Modifier.offset((-4).dp * unit, 30.dp * unit).size(158.dp * unit, 182.dp * unit).then(lifted))
        ArtLayer(art.overlay, Modifier.offset(74.dp * unit, 12.dp * unit).size(204.dp * unit, 112.dp * unit).then(lifted))
    }
}

@Composable
private fun MetaItem(icon: ImageVector, text: String, color: Color, size: TextUnit) {
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        HideoutIcon(icon, null, size = 14.dp, tint = color)
        Text(text, style = HideoutTheme.typography.caption.copy(fontSize = size), color = color, maxLines = 1)
    }
}

@Composable
private fun ModeBadgeChip(badge: ModeBadge, modifier: Modifier = Modifier) {
    val (text, dot) = when (badge) {
        ModeBadge.New -> Res.string.cg_badge_new to HideoutTheme.colors.sectionCustomGames
        ModeBadge.Installed -> Res.string.cg_badge_installed to MaterialTheme.colorScheme.secondary
        ModeBadge.Update -> Res.string.cg_badge_update to MaterialTheme.colorScheme.primary
        ModeBadge.Folder -> Res.string.cg_badge_folder to MaterialTheme.colorScheme.primary
    }
    GlassBadge(stringResource(text), modifier, dot = true, dotColor = dot)
}

@Composable
fun ModeGrid(cards: List<ModeCard>, onOpen: (String) -> Unit, modifier: Modifier = Modifier, minCell: Dp = 250.dp, maxColumns: Int = Int.MAX_VALUE) {
    val gaps = LocalModCardStyle.current.gaps
    Layout({ cards.forEachIndexed { index, card -> ModeCardView(card, { onOpen(card.key) }, Modifier.staggeredEnter(index)) } }, modifier) { measurables, constraints ->
        val columnGap = gaps.column.roundToPx()
        val rowGap = gaps.row.roundToPx()
        val width = constraints.maxWidth
        val columns = max(1, (width + columnGap) / (minCell.roundToPx() + columnGap)).coerceAtMost(maxColumns)
        val cell = (width - columnGap * (columns - 1)) / columns
        val rows = measurables.chunked(columns).map { row ->
            val height = row.maxOf { it.minIntrinsicHeight(cell) }
            row.map { it.measure(Constraints.fixed(cell, height)) }
        }
        val heights = rows.map { row -> row.maxOf { it.height } }
        layout(width, heights.sum() + rowGap * (rows.size - 1).coerceAtLeast(0)) {
            var y = 0
            rows.forEachIndexed { r, row ->
                row.forEachIndexed { c, placeable -> placeable.place(c * (cell + columnGap), y) }
                y += heights[r] + rowGap
            }
        }
    }
}

/** How a listing's player count reads: two even sides for a team mode with a fixed count, else the count or its range. */
sealed interface PlayerCount {
    data class Sides(val perSide: Int) : PlayerCount
    data class Exactly(val players: Int) : PlayerCount
    data class Between(val players: IntRange) : PlayerCount
}

fun playerCount(listing: ModeListing): PlayerCount {
    val range = listing.capacities
    return when {
        range.first != range.last -> PlayerCount.Between(range)
        listing.kind == ModeKind.Teams && range.first % 2 == 0 -> PlayerCount.Sides(range.first / 2)
        else -> PlayerCount.Exactly(range.first)
    }
}

@Composable
fun playersText(listing: ModeListing): String = when (val count = playerCount(listing)) {
    is PlayerCount.Sides -> stringResource(Res.string.cg_players_sides, count.perSide)
    is PlayerCount.Exactly -> stringResource(Res.string.cg_players_one, count.players)
    is PlayerCount.Between -> stringResource(Res.string.cg_players_range, count.players.first, count.players.last)
}

@Composable
fun minutesText(listing: ModeListing, about: Boolean): String? {
    val minutes = listing.minutes ?: return null
    return when {
        minutes.first != minutes.last -> stringResource(Res.string.cg_minutes_range, minutes.first, minutes.last)
        about -> stringResource(Res.string.cg_about_minutes, minutes.first)
        else -> stringResource(Res.string.cg_minutes, minutes.first)
    }
}

private val OnImageHeight = 210.dp
private val UnderImageShape = RoundedCornerShape(HideoutDimens.Card.UnderImageRadius)

private const val FadeRest = 0.56f
private const val FadeActive = 0.86f
private val LayerRise = 3.dp
private val LayerEasing = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)
