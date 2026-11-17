package gg.hideouthq.feature.moddetail.viewer

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import gg.hideouthq.core.designsystem.component.HideoutIcon
import gg.hideouthq.core.designsystem.icon.HideoutIcons
import gg.hideouthq.core.designsystem.interaction.collectInteractionStates
import gg.hideouthq.core.designsystem.interaction.focusRing
import gg.hideouthq.core.designsystem.interaction.hideoutSelectable
import gg.hideouthq.core.designsystem.theme.HideoutDimens
import gg.hideouthq.core.designsystem.theme.HideoutDimens.AnimationMenu as Menu
import gg.hideouthq.core.designsystem.theme.HideoutMotion
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import gg.hideouthq.core.designsystem.theme.HideoutWeights

@Immutable
data class AnimationMenuRow(val id: String, val name: String, val length: String, val changed: Boolean)

@Immutable
data class AnimationMenuContent(val category: ViewerCategory, val title: String, val rows: List<AnimationMenuRow>)

/** Where a menu sits: its start edge from the stage's, and the fraction of its width at which its pill's centre is. */
@Immutable
data class MenuAnchor(val start: Dp, val pillCenter: Float)

@Composable
fun AnimationMenu(
    content: AnimationMenuContent?,
    anchor: MenuAnchor?,
    playingId: String?,
    changedTag: String,
    changedDescription: String,
    footer: String,
    onPlay: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val motion = HideoutTheme.motion
    val visibility = remember { MutableTransitionState(false) }
    visibility.targetState = content != null
    val held = remember { arrayOfNulls<Pair<AnimationMenuContent, MenuAnchor>>(1) }
    if (content != null && anchor != null) held[0] = content to anchor
    val (shown, placed) = held[0] ?: return
    val left = remember { Animatable(placed.start, Dp.VectorConverter) }
    LaunchedEffect(placed.start) {
        if (visibility.currentState) left.animateTo(placed.start, motion.spatialDefault()) else left.snapTo(placed.start)
    }
    val origin = TransformOrigin(placed.pillCenter, 1f)
    val enter = fadeIn(motion.effectsFast()) +
        if (motion.reduced) EnterTransition.None else scaleIn(motion.spatialFast(), initialScale = HideoutMotion.EnterScaleModel, transformOrigin = origin)
    val exit = fadeOut(tween(HideoutMotion.ExitFadeMillis)) +
        if (motion.reduced) ExitTransition.None else scaleOut(tween(HideoutMotion.ExitFadeMillis), targetScale = HideoutMotion.PanelEnterScale, transformOrigin = origin)
    AnimatedVisibility(visibility, Modifier.offset { IntOffset(left.value.roundToPx(), 0) }.then(modifier), enter, exit) {
        val scheme = MaterialTheme.colorScheme
        Column(
            Modifier
                .width(Menu.Width)
                .dropShadow(MenuShape, Shadow(radius = Menu.ShadowBlur, color = HideoutTheme.colors.toolbarShadow, offset = DpOffset(0.dp, Menu.ShadowOffsetY)))
                .background(scheme.surfaceContainerHigh, MenuShape)
                .padding(Menu.Padding),
        ) {
            AnimatedContent(
                targetState = shown,
                contentKey = { it.category },
                transitionSpec = {
                    (fadeIn(motion.effectsDefault()) togetherWith fadeOut(motion.effectsFast()))
                        .using(SizeTransform(clip = false) { _, _ -> motion.spatialDefault() })
                },
                label = "animationMenu",
            ) { menu ->
                val opening = remember { transition.currentState == EnterExitState.Visible }
                Column {
                    MenuHeader(menu.title, menu.rows.size)
                    MenuRows(menu.rows, playingId, opening, changedTag, changedDescription, onPlay)
                }
            }
            MenuFooter(footer)
        }
    }
}

internal fun menuAnchor(pillStart: Dp, pillEnd: Dp, start: Dp, end: Dp): MenuAnchor {
    val before = pillStart - Menu.PillOverhang
    val placed = if (before + Menu.Width <= end) before else pillEnd + Menu.PillOverhang - Menu.Width
    val menuStart = placed.coerceAtMost(end - Menu.Width).coerceAtLeast(start)
    return MenuAnchor(menuStart, ((pillStart + pillEnd) / 2 - menuStart) / Menu.Width)
}

@Composable
private fun MenuHeader(title: String, count: Int) {
    val color = MaterialTheme.colorScheme.outline
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = Menu.HeaderHorizontal, end = Menu.HeaderHorizontal, top = Menu.HeaderTop, bottom = Menu.HeaderBottom),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = HideoutTheme.typography.labelSection, color = color, maxLines = 1)
        Text(count.toString(), style = HideoutTheme.typography.monoCount, color = color)
    }
}

@Composable
private fun MenuRows(
    rows: List<AnimationMenuRow>,
    playingId: String?,
    opening: Boolean,
    changedTag: String,
    changedDescription: String,
    onPlay: (String) -> Unit,
) {
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    val motion = HideoutTheme.motion
    LaunchedEffect(playingId) {
        val index = rows.indexOfFirst { it.id == playingId }.takeIf { it >= 0 } ?: return@LaunchedEffect
        with(density) { scroll.reveal(index * (Menu.RowHeight + Menu.RowGap).toPx(), Menu.RowHeight.toPx(), motion) }
    }
    Column(
        Modifier
            .heightIn(max = Menu.ListMaxHeight)
            .verticalScroll(scroll)
            .selectableGroup(),
        verticalArrangement = Arrangement.spacedBy(Menu.RowGap),
    ) {
        rows.forEachIndexed { index, row ->
            MenuRow(
                row = row,
                playing = row.id == playingId,
                changedTag = changedTag,
                changedDescription = changedDescription,
                onPlay = { onPlay(row.id) },
                modifier = if (opening) Modifier.rowEnter(index) else Modifier,
            )
        }
    }
}

private suspend fun ScrollState.reveal(top: Float, height: Float, motion: HideoutMotion) {
    val target = when {
        top < value -> top
        top + height > value + viewportSize -> top + height - viewportSize
        else -> return
    }
    animateScrollTo(target.toInt(), motion.spatialDefault())
}

@Composable
private fun MenuRow(row: AnimationMenuRow, playing: Boolean, changedTag: String, changedDescription: String, onPlay: () -> Unit, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    val type = HideoutTheme.typography
    val source = remember { MutableInteractionSource() }
    val states = source.collectInteractionStates()
    val fill by animateColorAsState(
        targetValue = when {
            playing -> scheme.primary.copy(alpha = PlayingRowAlpha)
            states.hovered -> scheme.surfaceContainerHighest
            else -> scheme.surfaceContainerHighest.copy(alpha = 0f)
        },
        animationSpec = HideoutTheme.motion.effectsDefault(),
        label = "menuRow",
    )
    Row(
        modifier
            .fillMaxWidth()
            .height(Menu.RowHeight)
            .background(fill, RowShape)
            .focusRing(states.focusVisible, RowShape)
            .hideoutSelectable(playing, source, role = Role.RadioButton, onClick = onPlay)
            .padding(start = Menu.RowStart, end = Menu.RowEnd),
        horizontalArrangement = Arrangement.spacedBy(Menu.RowContentGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(Menu.NowPlayingSlot), contentAlignment = Alignment.Center) {
            if (playing) HideoutIcon(HideoutIcons.NowPlaying, contentDescription = null, size = HideoutDimens.Icon.Small, tint = scheme.primary)
        }
        Text(
            text = row.name,
            style = if (playing) type.labelMenuItem.copy(fontWeight = HideoutWeights.Label) else type.labelMenuItem,
            color = if (playing) scheme.onSurface else scheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (row.changed) ChangedTag(changedTag, changedDescription, playing)
        Text(row.length, style = type.mono, color = scheme.outline, maxLines = 1)
    }
}

@Composable
private fun ChangedTag(text: String, description: String, playing: Boolean) {
    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier
            .height(Menu.TagHeight)
            .background(if (playing) scheme.primary else scheme.surfaceContainerHighest, TagShape)
            .padding(horizontal = Menu.TagHorizontal)
            .clearAndSetSemantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = HideoutTheme.typography.labelTag, color = if (playing) scheme.onPrimary else scheme.primary, maxLines = 1)
    }
}

@Composable
private fun MenuFooter(text: String) {
    val scheme = MaterialTheme.colorScheme
    val line = scheme.outlineVariant
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = Menu.FooterMarginTop)
            .drawBehind { drawRect(line, Offset.Zero, Size(size.width, HideoutDimens.Hairline.toPx())) }
            .padding(start = Menu.FooterHorizontal, end = Menu.FooterHorizontal, top = Menu.FooterTop, bottom = Menu.FooterBottom),
        horizontalArrangement = Arrangement.spacedBy(Menu.FooterGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KeyCap { HideoutIcon(HideoutIcons.Back, contentDescription = null, size = Menu.KeyCapGlyph, tint = scheme.onSurfaceVariant) }
        KeyCap { HideoutIcon(HideoutIcons.Forward, contentDescription = null, size = Menu.KeyCapGlyph, tint = scheme.onSurfaceVariant) }
        Text(text, style = HideoutTheme.typography.captionCompact, color = scheme.outline, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun KeyCap(glyph: @Composable () -> Unit) {
    Box(Modifier.size(Menu.KeyCap).background(MaterialTheme.colorScheme.surfaceContainerHighest, KeyCapShape), contentAlignment = Alignment.Center) { glyph() }
}

@Composable
private fun Modifier.rowEnter(index: Int): Modifier {
    val motion = HideoutTheme.motion
    val delay = motion.staggerDelay(index)
    val alpha = remember { Animatable(0f) }
    val rise = remember { Animatable(1f) }
    val offset = with(LocalDensity.current) { motion.enterOffset.toPx() }
    LaunchedEffect(Unit) { alpha.animateTo(1f, motion.enterFade(delay)) }
    LaunchedEffect(Unit) {
        rise.animateTo(0f, tween(HideoutMotion.StaggerRiseMillis, delayMillis = HideoutMotion.EnterDelayMillis + delay, easing = HideoutMotion.EmphasizedDecelerate))
    }
    return graphicsLayer {
        this.alpha = alpha.value
        translationY = rise.value * offset
    }
}

private val MenuShape = RoundedCornerShape(Menu.Radius)
private val RowShape = RoundedCornerShape(Menu.RowRadius)
private val TagShape = RoundedCornerShape(Menu.TagRadius)
private val KeyCapShape = RoundedCornerShape(Menu.KeyCapRadius)

private const val PlayingRowAlpha = 0.14f
