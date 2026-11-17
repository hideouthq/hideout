package gg.hideouthq.core.designsystem.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import gg.hideouthq.core.designsystem.icon.HideoutIcons
import gg.hideouthq.core.designsystem.interaction.InteractionStates
import gg.hideouthq.core.designsystem.interaction.animatedHoverBrightness
import gg.hideouthq.core.designsystem.interaction.collectInteractionStates
import gg.hideouthq.core.designsystem.interaction.focusRing
import gg.hideouthq.core.designsystem.interaction.hideoutClickable
import gg.hideouthq.core.designsystem.interaction.hideoutSelectable
import gg.hideouthq.core.designsystem.interaction.hideoutToggleable
import gg.hideouthq.core.designsystem.interaction.insetRing
import gg.hideouthq.core.designsystem.theme.HideoutDimens
import gg.hideouthq.core.designsystem.theme.HideoutMotion
import gg.hideouthq.core.designsystem.theme.HideoutRadius
import gg.hideouthq.core.designsystem.theme.HideoutShapes
import gg.hideouthq.core.designsystem.theme.HideoutWeights
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import gg.hideouthq.core.designsystem.theme.brightened

enum class ChipTone { Positive, Good, Warning, Neutral }

enum class ChipSize { Regular, Small, Badge, Avatar }

enum class FilterChipStyle { Filled, Outlined }

@Composable
fun FilterChip(
    text: String,
    selected: Boolean,
    onSelectedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    style: FilterChipStyle = FilterChipStyle.Filled,
    count: String? = null,
) {
    val source = remember { MutableInteractionSource() }
    val states = source.collectInteractionStates()
    val scheme = MaterialTheme.colorScheme
    val motion = HideoutTheme.motion
    val outlined = style == FilterChipStyle.Outlined
    val pop = rememberPopScale(trigger = selected, peak = HideoutMotion.ChipPopScale, enabled = selected)
    val container by animateColorAsState(
        targetValue = when {
            selected -> if (outlined) scheme.surfaceContainerHighest else scheme.primary
            states.hovered -> scheme.surfaceContainerHigh
            outlined -> scheme.surfaceContainerHigh.copy(alpha = 0f)
            else -> scheme.surfaceContainer
        },
        animationSpec = motion.effectsDefault(),
        label = "filterChipContainer",
    )
    val content by animateColorAsState(
        targetValue = when {
            !selected -> scheme.onSurfaceVariant
            outlined -> scheme.onSurface
            else -> scheme.onPrimary
        },
        animationSpec = motion.effectsDefault(),
        label = "filterChipContent",
    )
    val padding = HideoutDimens.Chip.HorizontalPadding
    ChipFrame(
        states = states,
        container = { container },
        size = ChipSize.Regular,
        padding = PaddingValues(start = if (outlined && selected) HideoutDimens.Chip.LeadingPadding else padding, end = padding),
        radius = if (outlined) HideoutRadius.FilterChip else null,
        modifier = modifier
            .graphicsLayer {
                scaleX = pop.value
                scaleY = pop.value
            }
            .then(if (outlined) Modifier.insetRing(RoundedCornerShape(HideoutRadius.FilterChip), scheme.surfaceContainerHighest) { HideoutDimens.Hairline.toPx() } else Modifier)
            .hideoutToggleable(selected, source, role = Role.Checkbox, onValueChange = onSelectedChange),
    ) {
        if (outlined && selected) HideoutIcon(HideoutIcons.CheckBold, null, size = HideoutDimens.Chip.Check, tint = content)
        ChipLabel(text, content)
        if (count != null) ChipLabel(count, scheme.outline, HideoutTheme.typography.labelChip.copy(fontWeight = HideoutWeights.Medium))
    }
}

@Composable
fun RemovableChip(
    text: String,
    removeContentDescription: String,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val source = remember { MutableInteractionSource() }
    val states = source.collectInteractionStates()
    val scheme = MaterialTheme.colorScheme
    val container by animateColorAsState(
        targetValue = if (states.hovered) scheme.surfaceContainerHigh else scheme.surfaceContainer,
        animationSpec = HideoutTheme.motion.effectsDefault(),
        label = "removableChipContainer",
    )
    ChipFrame(
        states = states,
        container = { container },
        size = ChipSize.Regular,
        padding = PaddingValues(start = HideoutDimens.Chip.HorizontalPadding, end = HideoutDimens.Chip.TrailingIconPadding),
        modifier = modifier
            .semantics { contentDescription = removeContentDescription }
            .hideoutClickable(source, onClick = onRemove),
    ) {
        ChipLabel(text, scheme.onSurfaceVariant)
        HideoutIcon(HideoutIcons.Close, contentDescription = null, size = HideoutDimens.Icon.Small, tint = scheme.onSurfaceVariant)
    }
}

@Composable
fun OutlinedChip(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val source = remember { MutableInteractionSource() }
    val states = source.collectInteractionStates()
    val scheme = MaterialTheme.colorScheme
    val container by animateColorAsState(
        targetValue = if (states.hovered) scheme.surfaceContainerHigh else scheme.surfaceContainerHigh.copy(alpha = 0f),
        animationSpec = HideoutTheme.motion.effectsDefault(),
        label = "outlinedChipContainer",
    )
    ChipFrame(
        states = states,
        container = { container },
        size = ChipSize.Regular,
        padding = PaddingValues(horizontal = HideoutDimens.Chip.HorizontalPadding),
        modifier = modifier
            .insetRing(HideoutShapes.Full, scheme.outlineVariant) { HideoutDimens.Chip.DashedBorder.toPx() }
            .hideoutClickable(source, onClick = onClick),
    ) {
        ChipLabel(text, scheme.onSurfaceVariant)
    }
}

@Composable
fun StatusChip(
    text: String,
    tone: ChipTone,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null,
    size: ChipSize = ChipSize.Regular,
    onClick: (() -> Unit)? = null,
    busy: Boolean = false,
) {
    val source = remember { MutableInteractionSource() }
    val states = source.collectInteractionStates()
    val scheme = MaterialTheme.colorScheme
    val (container, content) = when (tone) {
        ChipTone.Positive -> scheme.primaryContainer to scheme.primary
        ChipTone.Good -> scheme.secondaryContainer to scheme.secondary
        ChipTone.Warning -> scheme.tertiaryContainer to scheme.onTertiaryContainer
        ChipTone.Neutral -> scheme.surfaceContainerHigh to scheme.onSurfaceVariant
    }
    val interactive = onClick != null
    val brightness = animatedHoverBrightness(states.hovered && interactive)
    val start = if ((leadingIcon != null || busy) && size != ChipSize.Badge) HideoutDimens.Chip.LeadingPadding else horizontalPadding(size)
    val iconSize = if (size == ChipSize.Badge) HideoutDimens.Chip.BadgeIcon else HideoutDimens.Icon.Small
    ChipFrame(
        states = states,
        container = { container.brightened(brightness.value) },
        size = size,
        padding = PaddingValues(start = start, end = horizontalPadding(size)),
        pressable = interactive,
        modifier = modifier
            .then(if (onClick != null) Modifier.hideoutClickable(source, onClick = onClick) else Modifier),
    ) {
        when {
            busy -> Box(Modifier.size(iconSize), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(HideoutDimens.Chip.Spinner), color = content, strokeWidth = HideoutDimens.Chip.SpinnerStroke)
            }
            leadingIcon != null -> HideoutIcon(leadingIcon, null, size = iconSize, tint = content)
        }
        ChipLabel(text, content, if (size == ChipSize.Badge) HideoutTheme.typography.badge else HideoutTheme.typography.labelChip)
    }
}

@Composable
fun AvatarFilterChip(
    text: String,
    count: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    avatar: (@Composable () -> Unit)? = null,
) {
    val source = remember { MutableInteractionSource() }
    val states = source.collectInteractionStates()
    val scheme = MaterialTheme.colorScheme
    val motion = HideoutTheme.motion
    val container by animateColorAsState(
        targetValue = when {
            selected -> scheme.surfaceContainerHigh
            states.hovered -> scheme.surfaceContainer
            else -> scheme.surfaceContainer.copy(alpha = 0f)
        },
        animationSpec = motion.effectsDefault(),
        label = "avatarChipContainer",
    )
    val ring by animateColorAsState(if (selected) scheme.primary else scheme.surfaceContainerHighest, motion.effectsDefault(), label = "avatarChipRing")
    val ringWidth by animateDpAsState(if (selected) HideoutDimens.Chip.SelectedRing else HideoutDimens.Hairline, motion.effectsDefault(), label = "avatarChipRingWidth")
    ChipFrame(
        states = states,
        container = { container },
        size = ChipSize.Avatar,
        padding = PaddingValues(start = if (avatar != null) HideoutDimens.Chip.AvatarStartPadding else horizontalPadding(ChipSize.Avatar), end = horizontalPadding(ChipSize.Avatar)),
        modifier = modifier
            .insetRing(HideoutShapes.Full, ring) { ringWidth.toPx() }
            .hideoutSelectable(selected, source, role = Role.RadioButton, onClick = onClick),
    ) {
        avatar?.invoke()
        ChipLabel(text, if (selected) scheme.onSurface else scheme.onSurfaceVariant, HideoutTheme.typography.labelPill)
        ChipLabel(count, scheme.outline, HideoutTheme.typography.labelPill.copy(fontWeight = HideoutWeights.Medium))
    }
}

@Composable
fun AvatarChip(
    text: String,
    modifier: Modifier = Modifier,
    container: Color = HideoutTheme.colors.heroInset,
    avatar: @Composable () -> Unit,
) {
    val states = remember { MutableInteractionSource() }.collectInteractionStates()
    ChipFrame(
        states = states,
        container = { container },
        size = ChipSize.Regular,
        padding = PaddingValues(start = HideoutDimens.Chip.AvatarLeadingPadding, end = HideoutDimens.Chip.HorizontalPadding),
        modifier = modifier,
    ) {
        avatar()
        ChipLabel(text, MaterialTheme.colorScheme.onSurface)
    }
}

private fun horizontalPadding(size: ChipSize) = when (size) {
    ChipSize.Regular -> HideoutDimens.Chip.HorizontalPadding
    ChipSize.Small -> HideoutDimens.Chip.HorizontalPaddingSmall
    ChipSize.Badge -> HideoutDimens.Chip.BadgePadding
    ChipSize.Avatar -> HideoutDimens.Chip.AvatarHorizontalPadding
}

@Composable
private fun ChipLabel(text: String, color: Color, style: TextStyle = HideoutTheme.typography.labelChip) {
    Text(text = text, style = style, color = color, maxLines = 1)
}

@Composable
private fun ChipFrame(
    states: InteractionStates,
    container: () -> Color,
    size: ChipSize,
    padding: PaddingValues,
    modifier: Modifier,
    pressable: Boolean = true,
    radius: Dp? = null,
    content: @Composable () -> Unit,
) {
    val height = when (size) {
        ChipSize.Regular -> HideoutDimens.Chip.Height
        ChipSize.Small -> HideoutDimens.Chip.HeightSmall
        ChipSize.Badge -> HideoutDimens.Chip.HeightBadge
        ChipSize.Avatar -> HideoutDimens.Chip.HeightAvatar
    }
    val paint: State<() -> Color> = rememberUpdatedState(container)
    val press by animateFloatAsState(
        targetValue = if (states.pressed && pressable) HideoutMotion.PressScale else 1f,
        animationSpec = HideoutTheme.motion.spatialFast(),
        label = "chipPress",
    )
    Row(
        modifier = modifier
            .height(height)
            .drawBehind { scale(press) { drawRoundRect(paint.value(), cornerRadius = CornerRadius(radius?.toPx() ?: (this.size.height / 2))) } }
            .focusRing(states.focusVisible, if (radius != null) RoundedCornerShape(radius) else HideoutShapes.Full)
            .padding(padding),
        horizontalArrangement = Arrangement.spacedBy(
            when (size) {
                ChipSize.Badge -> HideoutDimens.Chip.BadgeGap
                ChipSize.Avatar -> HideoutDimens.Chip.AvatarGap
                else -> HideoutDimens.Chip.Gap
            },
        ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        content()
    }
}
