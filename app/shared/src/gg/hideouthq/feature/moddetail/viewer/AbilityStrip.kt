package gg.hideouthq.feature.moddetail.viewer

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import gg.hideouthq.core.designsystem.image.LocalRemoteImages
import gg.hideouthq.core.designsystem.interaction.collectInteractionStates
import gg.hideouthq.core.designsystem.interaction.focusRing
import gg.hideouthq.core.designsystem.interaction.hideoutSelectable
import gg.hideouthq.core.designsystem.interaction.pressScale
import gg.hideouthq.core.designsystem.theme.HideoutDimens
import gg.hideouthq.core.designsystem.theme.HideoutDimens.AbilityStrip as Strip
import gg.hideouthq.core.designsystem.theme.HideoutMotion
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import kotlinx.coroutines.delay

/**
 * The strip's words: its name, and the tooltip's second line for an ability whose icon and animation the mod [changed], only its
 * icon ([changedIcon]) or animation ([changedAnimation]), neither ([unchanged]), or one the bundle has no animation for, with the
 * mod's icon ([noClipChanged]) or the game's ([noClip]).
 */
@Immutable
data class AbilityLabels(
    val strip: String,
    val changed: String,
    val changedIcon: String,
    val changedAnimation: String,
    val unchanged: String,
    val noClip: String,
    val noClipChanged: String,
)

/** One ability; [changed] when the mod changes its icon or the animation it plays, which a dot on the tile marks. */
@Immutable
data class AbilityTile(
    val slot: Int,
    val name: String,
    val image: String,
    val detail: String,
    val played: Boolean,
    val selected: Boolean,
    val changed: Boolean,
)

/**
 * While not [enabled] every tile is dimmed and takes no input; while [dimmed] too, the whole strip is at the stage's
 * loading opacity instead.
 */
@Composable
fun AbilityStrip(tiles: List<AbilityTile>, label: String, onPlay: (Int) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, dimmed: Boolean = false) {
    Column(
        modifier
            .then(if (dimmed) Modifier.alpha(HideoutDimens.Stage.LoadingAlpha) else Modifier)
            .dropShadow(StripShape, Shadow(radius = HideoutDimens.Toolbar.ShadowBlur, color = HideoutTheme.colors.toolbarShadow, offset = DpOffset(0.dp, HideoutDimens.Toolbar.ShadowOffsetY)))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, StripShape)
            .padding(Strip.Padding)
            .semantics { contentDescription = label },
        verticalArrangement = Arrangement.spacedBy(Strip.Gap),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        tiles.forEach { tile ->
            if (tile.slot == UltimateSlot) {
                Box(
                    Modifier
                        .padding(vertical = Strip.DividerMargin)
                        .size(Strip.DividerWidth, Strip.DividerHeight)
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(Strip.DividerHeight)),
                )
            }
            AbilityTileButton(tile, enabled, dimmed, onPlay)
        }
    }
}

@Composable
private fun AbilityTileButton(tile: AbilityTile, enabled: Boolean, dimmed: Boolean, onPlay: (Int) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val motion = HideoutTheme.motion
    val source = remember { MutableInteractionSource() }
    val states = source.collectInteractionStates()
    var rested by remember { mutableStateOf(false) }
    LaunchedEffect(states.hovered) {
        if (states.hovered) delay(TooltipDelayMillis)
        rested = states.hovered
    }
    val ring by animateFloatAsState(if (tile.selected) 1f else 0f, motion.effectsDefault(), label = "abilityRing")
    val gap = scheme.surfaceContainerHigh
    val primary = scheme.primary
    val target = if (tile.played) {
        // Compose still runs a disabled control's click through its accessibility action.
        Modifier.hideoutSelectable(tile.selected, source, enabled = enabled, role = Role.Button, onClick = { if (enabled) onPlay(tile.slot) })
    } else {
        Modifier.hoverable(source, enabled).focusable(enabled, source).semantics { stateDescription = tile.detail }
    }
    Box(
        Modifier
            .size(Strip.Tile)
            .pressScale(states.pressed && !motion.reduced, HideoutMotion.TilePressScale)
            .drawBehind {
                if (ring <= 0f) return@drawBehind
                val gapWidth = Strip.RingGap.toPx()
                val ringWidth = Strip.Ring.toPx()
                val radius = Strip.TileRadius.toPx()
                drawRoundRect(gap, Offset(-gapWidth / 2, -gapWidth / 2), Size(size.width + gapWidth, size.height + gapWidth), CornerRadius(radius + gapWidth / 2), Stroke(gapWidth), alpha = ring)
                val reach = gapWidth + ringWidth / 2
                drawRoundRect(primary, Offset(-reach, -reach), Size(size.width + 2 * reach, size.height + 2 * reach), CornerRadius(radius + reach), Stroke(ringWidth), alpha = ring)
            }
            .focusRing(states.focusVisible, TileShape)
            .then(target)
            .semantics { contentDescription = tile.name },
    ) {
        Box(Modifier.matchParentSize().alpha(if (enabled || dimmed) 1f else HideoutMotion.DisabledAlpha)) {
            TileImage(tile.image, Modifier.matchParentSize().clip(TileShape))
            KeyBadge(tile.slot, Modifier.align(Alignment.BottomEnd).padding(Strip.KeyInset))
            if (tile.changed) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(Strip.KeyInset)
                        .size(HideoutDimens.Toolbar.ChangedDot)
                        .background(scheme.primary, CircleShape),
                )
            }
        }
        TileTooltip(tile.name, tile.detail, visible = enabled && (rested || states.focusVisible), Modifier.align(Alignment.CenterStart))
    }
}

@Composable
private fun TileImage(url: String, modifier: Modifier) {
    val image = LocalRemoteImages.current.rememberImage(url, ContentScale.Crop)
    Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
        if (image != null) Image(image.painter, contentDescription = null, Modifier.fillMaxSize().then(image.layoutModifier), contentScale = ContentScale.Crop)
    }
}

@Composable
private fun KeyBadge(slot: Int, modifier: Modifier) {
    Box(
        modifier
            .defaultMinSize(minWidth = Strip.KeyHeight)
            .height(Strip.KeyHeight)
            .background(HideoutTheme.colors.glass, KeyShape)
            .padding(horizontal = Strip.KeyHorizontal)
            .clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        Text(slot.toString(), style = HideoutTheme.typography.monoKey, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TileTooltip(name: String, detail: String, visible: Boolean, modifier: Modifier) {
    val shown by animateFloatAsState(if (visible) 1f else 0f, HideoutTheme.motion.effectsFast(), label = "abilityTooltip")
    if (shown <= 0f) return
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier
            .layout { measurable, _ ->
                val placeable = measurable.measure(Constraints())
                layout(0, 0) { placeable.place(-placeable.width - Strip.TooltipGap.roundToPx(), -placeable.height / 2) }
            }
            .graphicsLayer { alpha = shown }
            .background(scheme.surfaceContainerHighest, TooltipShape)
            .padding(horizontal = Strip.TooltipHorizontal, vertical = Strip.TooltipVertical)
            .clearAndSetSemantics {},
    ) {
        Text(name, style = HideoutTheme.typography.labelChip, color = scheme.onSurface, maxLines = 1)
        Text(detail, style = HideoutTheme.typography.tooltipDetail, color = scheme.onSurfaceVariant, maxLines = 1)
    }
}

private const val UltimateSlot = 4
private const val TooltipDelayMillis = 300L

private val StripShape = RoundedCornerShape(Strip.Radius)
private val TileShape = RoundedCornerShape(Strip.TileRadius)
private val KeyShape = RoundedCornerShape(Strip.KeyRadius)
private val TooltipShape = RoundedCornerShape(Strip.TooltipRadius)
