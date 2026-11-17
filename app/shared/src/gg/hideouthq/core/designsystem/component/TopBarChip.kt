package gg.hideouthq.core.designsystem.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.expand
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import gg.hideouthq.core.designsystem.icon.HideoutIcons
import gg.hideouthq.core.designsystem.interaction.collectInteractionStates
import gg.hideouthq.core.designsystem.interaction.focusRing
import gg.hideouthq.core.designsystem.interaction.hideoutClickable
import gg.hideouthq.core.designsystem.theme.HideoutDimens
import gg.hideouthq.core.designsystem.theme.HideoutMotion
import gg.hideouthq.core.designsystem.theme.HideoutShapes
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import gg.hideouthq.core.ui.AppPopup
import kotlinx.coroutines.delay

/**
 * A top-bar chip that opens [panel] under itself, aligned to its right edge. The panel takes the focus as it opens, and
 * Esc, a click outside or the chip again closes it, Esc putting the focus back on the chip. [label] is the chip's whole
 * accessible name, since some chips show no text.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun TopBarChip(
    label: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    panelWidth: Dp,
    modifier: Modifier = Modifier,
    container: Color = MaterialTheme.colorScheme.surfaceContainer,
    hoverContainer: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    outlined: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    gap: Dp = HideoutDimens.TopBarChip.Gap,
    panel: @Composable ColumnScope.(first: FocusRequester) -> Unit,
    content: @Composable RowScope.() -> Unit,
) {
    val dimens = HideoutDimens.TopBarChip
    val source = remember { MutableInteractionSource() }
    val states = source.collectInteractionStates()
    val chipFocus = remember { FocusRequester() }
    var dismissedOverChip by remember { mutableStateOf(false) }
    LaunchedEffect(dismissedOverChip) {
        if (dismissedOverChip) {
            delay(DismissSettleMillis)
            dismissedOverChip = false
        }
    }
    Box(modifier) {
        Row(
            Modifier
                .height(dimens.Height)
                .widthIn(min = dimens.Height)
                .focusRequester(chipFocus)
                .background(if (states.hovered || expanded) hoverContainer else container, HideoutShapes.Full)
                .then(if (outlined) Modifier.border(HideoutDimens.Hairline, Color.White.copy(alpha = InnerLineAlpha), HideoutShapes.Full) else Modifier)
                .focusRing(states.focusVisible, HideoutShapes.Full)
                .hideoutClickable(source) { if (dismissedOverChip) dismissedOverChip = false else onToggle() }
                .semantics(mergeDescendants = true) {
                    contentDescription = label
                    if (expanded) collapse { onToggle(); true } else expand { onToggle(); true }
                }
                .padding(contentPadding),
            horizontalArrangement = Arrangement.spacedBy(gap, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
        if (expanded) {
            val panelGap = with(LocalDensity.current) { dimens.PanelGap.roundToPx() }
            AppPopup(
                popupPositionProvider = remember(panelGap) { UnderEnd(panelGap) },
                onDismissRequest = {
                    if (states.hovered) dismissedOverChip = true
                    onToggle()
                },
                properties = remember { PopupProperties(focusable = true, consumePointerInputOutside = false) },
                onPreviewKeyEvent = { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                        onToggle()
                        chipFocus.requestFocus()
                        true
                    } else false
                },
            ) {
                ChipPanel(label, panelWidth, panel)
            }
        }
    }
}

@Composable
private fun ChipPanel(label: String, width: Dp, content: @Composable ColumnScope.(first: FocusRequester) -> Unit) {
    val dimens = HideoutDimens.TopBarChip
    val motion = HideoutTheme.motion
    val shown = remember { MutableTransitionState(false).apply { targetState = true } }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    AnimatedVisibility(
        visibleState = shown,
        enter = fadeIn(motion.effectsDefault()) + scaleIn(motion.spatialFast(), HideoutMotion.EnterScaleModel, TransformOrigin(1f, 0f)),
    ) {
        Column(
            Modifier
                .width(width)
                .dropShadow(PanelShape, Shadow(radius = dimens.PanelShadowBlur, color = HideoutTheme.colors.dialogShadow, offset = DpOffset(0.dp, dimens.PanelShadowOffsetY)))
                .background(MaterialTheme.colorScheme.surfaceContainer, PanelShape)
                .border(HideoutDimens.Hairline, Color.White.copy(alpha = OutlineAlpha), PanelShape)
                .padding(dimens.PanelPadding)
                .semantics { paneTitle = label },
            verticalArrangement = Arrangement.spacedBy(dimens.PanelGapInside),
        ) { content(first) }
    }
}

@Composable
fun ChipChevron(open: Boolean) {
    val turn by animateFloatAsState(if (open) 180f else 0f, HideoutTheme.motion.spatialFast(), label = "chipChevron")
    HideoutIcon(HideoutIcons.ChevronDown, null, Modifier.graphicsLayer { rotationZ = turn }, size = HideoutDimens.TopBarChip.Chevron, tint = MaterialTheme.colorScheme.outline)
}

private class UnderEnd(private val gap: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val x = (anchorBounds.right - popupContentSize.width).coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
        return IntOffset(x, anchorBounds.bottom + gap)
    }
}

private val PanelShape = RoundedCornerShape(HideoutDimens.TopBarChip.PanelRadius)
private const val InnerLineAlpha = 0.06f
private const val OutlineAlpha = 0.07f
private const val DismissSettleMillis = 400L
