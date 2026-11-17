package gg.hideouthq.feature.collections.components

import gg.hideouthq.core.designsystem.component.pictureRing
import gg.hideouthq.core.model.ModCardStyle
import gg.hideouthq.core.designsystem.theme.HideoutWeights
import gg.hideouthq.core.designsystem.theme.HideoutShapes
import gg.hideouthq.core.designsystem.theme.HideoutMotion
import gg.hideouthq.core.designsystem.component.gaps
import gg.hideouthq.core.designsystem.component.LocalModCardStyle
import gg.hideouthq.core.designsystem.component.CardScrim
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Rect
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import gg.hideouthq.core.designsystem.interaction.collectInteractionStates
import gg.hideouthq.core.designsystem.interaction.focusRing
import gg.hideouthq.core.designsystem.interaction.hideoutClickable
import gg.hideouthq.core.designsystem.theme.HideoutDimens
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import gg.hideouthq.feature.collections.CollectionHero
import gg.hideouthq.feature.collections.CollectionSummary
import gg.hideouthq.resources.Res
import gg.hideouthq.resources.collection_card_label
import gg.hideouthq.resources.collection_counts
import gg.hideouthq.resources.collection_heroes
import gg.hideouthq.resources.collection_skins
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun CollectionGrid(collections: List<CollectionSummary>, onOpen: (String) -> Unit, modifier: Modifier = Modifier) {
    val dimens = HideoutDimens.Collection
    val gaps = LocalModCardStyle.current.gaps
    val gap = gaps.column
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val columns = if (maxWidth >= dimens.MinCardWidth * CollectionsInARow + gap * (CollectionsInARow - 1)) CollectionsInARow else NarrowColumns
        Column(verticalArrangement = Arrangement.spacedBy(gaps.row)) {
            collections.chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    row.forEach { collection -> CollectionCard(collection, { onOpen(collection.id) }, Modifier.weight(1f)) }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

internal const val CollectionsInARow = 4

private const val NarrowColumns = 2

@Composable
private fun CollectionCard(collection: CollectionSummary, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val dimens = HideoutDimens.Collection
    val scheme = MaterialTheme.colorScheme
    val colors = HideoutTheme.colors
    val type = HideoutTheme.typography
    val motion = HideoutTheme.motion
    val source = remember { MutableInteractionSource() }
    val states = source.collectInteractionStates()
    val active = states.hovered || states.focusVisible
    val moves = !motion.reduced
    val under = LocalModCardStyle.current == ModCardStyle.UnderImage
    val lift by animateFloatAsState(if (active && moves) 1f else 0f, motion.spatialFast(), label = "collectionLift")
    val rise by animateFloatAsState(if (active && moves) 1f else 0f, motion.figureRise(), label = "collectionRise")
    val scale by animateFloatAsState(if (states.hovered && moves && !under) HideoutMotion.HoverScaleCard else 1f, motion.spatialFast(), label = "collectionScale")
    val ring by animateFloatAsState(if (active && under) 1f else 0f, motion.effectsDefault(), label = "collectionRing")
    val skins = pluralStringResource(Res.plurals.collection_skins, collection.skins, collection.skins)
    val heroes = pluralStringResource(Res.plurals.collection_heroes, collection.heroes, collection.heroes)
    val label = stringResource(Res.string.collection_card_label, collection.title, skins)
    val shape = if (under) UnderImageShape else HideoutShapes.Card
    val light = collection.look.main.toColor()
    val picture: @Composable () -> Unit = {
        Box(
            Modifier
                .fillMaxWidth()
                .then(if (under) Modifier.aspectRatio(HideoutDimens.Card.UnderImageRatio) else Modifier.height(dimens.ArtHeight))
                .drawBehind {
                    val up = dimens.HoverLift.toPx() * lift
                    stackLayer(light.copy(alpha = 0.15f).compositeOver(StackBackGround), dimens.StackBackInset, dimens.StackSpace, up + dimens.StackBackFan.toPx() * lift)
                    stackLayer(light.copy(alpha = 0.32f).compositeOver(StackFrontGround), dimens.StackFrontInset, dimens.StackFrontPeek, up + dimens.StackFrontFan.toPx() * lift)
                }
                .graphicsLayer {
                    translationY = -dimens.HoverLift.toPx() * lift
                    scaleX = scale
                    scaleY = scale
                }
                .pictureRing(scheme.primary) { ring }
                .clip(shape)
                .then(if (under) Modifier else Modifier.focusRing(states.focusVisible, shape)),
        ) {
            CollectionBackdrop(collection.look, FigureLayout.Card, Modifier.matchParentSize())
            CollectionFigures(collection.figures, FigureLayout.Card, collection.look.rim, Modifier.matchParentSize(), rise = { dimens.FigureRise * rise })
            collection.seasonEnd?.let { SeasonChip(it, Modifier.padding(dimens.ChipInset)) }
            if (!under) {
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(CardScrim.Image).background(Brush.verticalGradient(listOf(colors.imageScrim.copy(alpha = 0f), colors.imageScrim))))
                Row(
                    Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(
                        start = HideoutDimens.Card.CaptionHorizontalInset, end = HideoutDimens.Card.CaptionHorizontalInset, bottom = HideoutDimens.Card.CaptionBottomInset,
                    ),
                    horizontalArrangement = Arrangement.spacedBy(dimens.InfoGap),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(collection.title, style = type.titleCard.copy(fontSize = 16.sp, fontWeight = HideoutWeights.Title), color = colors.onImage, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(stringResource(Res.string.collection_counts, skins, heroes), style = type.subtitle.copy(fontSize = 12.5.sp), color = colors.onImageVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    HeroPortraits(collection.topHeroes, ring = PortraitRingOnImage)
                }
            }
        }
    }
    Column(
        modifier
            .padding(top = dimens.StackSpace)
            .semantics { contentDescription = label }
            .hideoutClickable(source, onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(HideoutDimens.Card.UnderTextGap),
    ) {
        picture()
        if (under) Row(
            Modifier.fillMaxWidth().padding(horizontal = HideoutDimens.Card.UnderTextInset),
            horizontalArrangement = Arrangement.spacedBy(dimens.InfoGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(HideoutDimens.Card.UnderLineGap)) {
                Text(collection.title, style = type.titleCard.copy(fontSize = 15.sp, fontWeight = HideoutWeights.Title), color = scheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(stringResource(Res.string.collection_counts, skins, heroes), style = type.subtitle.copy(fontSize = 12.5.sp), color = scheme.outline, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            HeroPortraits(collection.topHeroes, ring = scheme.surface)
        }
    }
}

private fun DrawScope.stackLayer(color: Color, inset: Dp, peek: Dp, up: Float) {
    val radius = CornerRadius(HideoutDimens.Collection.StackRadius.toPx())
    val left = inset.toPx()
    val top = -peek.toPx() - up
    drawPath(
        Path().apply {
            addRoundRect(RoundRect(Rect(left, top, size.width - left, top + HideoutDimens.Collection.StackHeight.toPx()), topLeft = radius, topRight = radius))
        },
        color,
    )
}

@Composable
private fun HeroPortraits(heroes: List<CollectionHero>, ring: Color) {
    val dimens = HideoutDimens.Collection
    if (heroes.isEmpty()) return
    val ringed = dimens.Portrait + dimens.PortraitRing * 2
    val step = dimens.Portrait - dimens.PortraitOverlap
    Box(Modifier.size(ringed + step * (heroes.size - 1), ringed)) {
        heroes.forEachIndexed { index, hero ->
            Box(Modifier.offset(x = step * index).size(ringed).background(ring, CircleShape), contentAlignment = Alignment.Center) {
                HeroPortrait(hero, dimens.Portrait)
            }
        }
    }
}

private val UnderImageShape = RoundedCornerShape(HideoutDimens.Card.UnderImageRadius)

private val StackFrontGround = Color(0xFF2E2B2D)
private val StackBackGround = Color(0xFF211F21)
private val PortraitRingOnImage = Color(0xE60A080B)
