package gg.hideouthq.core.designsystem.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import gg.hideouthq.core.designsystem.interaction.collectInteractionStates
import gg.hideouthq.core.designsystem.interaction.focusRing
import gg.hideouthq.core.designsystem.interaction.hideoutSelectable
import gg.hideouthq.core.designsystem.interaction.insetRing
import gg.hideouthq.core.designsystem.theme.HideoutDimens
import gg.hideouthq.core.designsystem.theme.HideoutMotion
import gg.hideouthq.core.designsystem.theme.HideoutTheme

private val CardShape = RoundedCornerShape(HideoutDimens.OptionCard.Radius)

/**
 * One choice of a single-choice group drawn as a card: an icon tile, a title with an optional [badge], a line under it
 * and a radio mark. Idle surface2, hover and [selected] surface3; selected adds a 2dp primary ring and fills the tile and
 * the mark with primary. [dimmed] draws it at half strength while it stays selectable, for a choice that is unlikely
 * but allowed; a disabled card fades and ignores clicks. Put the cards in a `selectableGroup()`.
 */
@Composable
fun OptionCard(
    title: String,
    detail: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    dimmed: Boolean = false,
    badge: (@Composable () -> Unit)? = null,
) {
    val source = remember { MutableInteractionSource() }
    val states = source.collectInteractionStates()
    val scheme = MaterialTheme.colorScheme
    val motion = HideoutTheme.motion
    val container by animateColorAsState(
        if (selected || (states.hovered && enabled)) scheme.surfaceContainerHigh else scheme.surfaceContainer,
        motion.effectsFast(),
        label = "optionCardContainer",
    )
    val ring by animateFloatAsState(if (selected) 1f else 0f, motion.effectsFast(), label = "optionCardRing")
    val tile by animateColorAsState(if (selected) scheme.primary else scheme.surfaceContainerHighest, motion.effectsFast(), label = "optionCardTile")
    val tileContent by animateColorAsState(if (selected) scheme.onPrimary else scheme.onSurface, motion.effectsFast(), label = "optionCardTileContent")
    val ringColor = scheme.primary
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = HideoutDimens.OptionCard.MinHeight)
            .alpha(
                when {
                    !enabled -> HideoutMotion.DisabledAlpha
                    dimmed -> DimmedAlpha
                    else -> 1f
                },
            )
            .drawBehind { drawRoundRect(container, cornerRadius = CornerRadius(HideoutDimens.OptionCard.Radius.toPx())) }
            .insetRing(CardShape, ringColor.copy(alpha = ring)) { HideoutDimens.OptionCard.Ring.toPx() }
            .focusRing(states.focusVisible, CardShape)
            .hideoutSelectable(selected, source, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(
                start = HideoutDimens.OptionCard.StartPadding,
                end = HideoutDimens.OptionCard.EndPadding,
                top = HideoutDimens.OptionCard.VerticalPadding,
                bottom = HideoutDimens.OptionCard.VerticalPadding,
            ),
        horizontalArrangement = Arrangement.spacedBy(HideoutDimens.OptionCard.Gap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(HideoutDimens.OptionCard.Tile).background(tile, RoundedCornerShape(HideoutDimens.OptionCard.TileRadius)),
            contentAlignment = Alignment.Center,
        ) {
            HideoutIcon(icon, contentDescription = null, tint = tileContent)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(HideoutDimens.OptionCard.DetailGap)) {
            Row(horizontalArrangement = Arrangement.spacedBy(HideoutDimens.OptionCard.TitleGap), verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = HideoutTheme.typography.titleCard, color = scheme.onSurface, maxLines = 1)
                badge?.invoke()
            }
            Text(detail, style = HideoutTheme.typography.notice, color = scheme.outline)
        }
        RadioMark(selected)
    }
}

@Composable
private fun RadioMark(selected: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val motion = HideoutTheme.motion
    val ring by animateColorAsState(if (selected) scheme.primary else scheme.surfaceContainerHighest, motion.effectsFast(), label = "radioRing")
    val dot by animateFloatAsState(if (selected) 1f else 0f, motion.spatialFast(), label = "radioDot")
    Box(
        Modifier.size(HideoutDimens.OptionCard.Radio).drawBehind {
            val width = HideoutDimens.OptionCard.Ring.toPx()
            drawCircle(ring, radius = size.minDimension / 2 - width / 2, style = Stroke(width))
            if (dot > 0f) drawCircle(scheme.primary, radius = (size.minDimension / 2 - HideoutDimens.OptionCard.RadioDotInset.toPx()) * dot)
        },
    )
}

private const val DimmedAlpha = 0.5f
