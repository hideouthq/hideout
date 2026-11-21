package gg.hideouthq.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.PopupPositionProvider
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import gg.hideouthq.core.ui.AppPopup
import kotlinx.coroutines.delay

/** Where a tooltip opens against its anchor. It flips to the opposite side when the window has no room. */
enum class TooltipSide { Beside, Below, Above }

@Composable
fun HideoutTooltip(text: String, modifier: Modifier = Modifier, side: TooltipSide = TooltipSide.Beside, content: @Composable () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(hovered) {
        shown = false
        if (hovered) { delay(300); shown = true }
    }
    val gap = with(LocalDensity.current) { 14.dp.roundToPx() }
    Box(modifier.hoverable(source)) {
        content()
        if (hovered && shown) AppPopup(popupPositionProvider = remember(gap, side) { if (side == TooltipSide.Beside) BesideAnchor(gap) else StackedAnchor(gap / 2, above = side == TooltipSide.Above) }) {
            Box(
                Modifier.shadow(8.dp, RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(10.dp))
                    .height(32.dp).padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(text, style = HideoutTheme.typography.labelChip.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold), color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

private class StackedAnchor(private val gap: Int, private val above: Boolean) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val x = anchorBounds.left + (anchorBounds.width - popupContentSize.width) / 2
        val under = anchorBounds.bottom + gap
        val over = anchorBounds.top - gap - popupContentSize.height
        val underFits = under + popupContentSize.height <= windowSize.height
        val y = if (above && over >= 0 || !above && !underFits) over else under
        return IntOffset(x.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)), y.coerceAtLeast(0))
    }
}

internal class BesideAnchor(private val gap: Int, private val align: Alignment.Vertical = Alignment.CenterVertically) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val after = anchorBounds.right + gap
        val before = anchorBounds.left - gap - popupContentSize.width
        val afterFits = after + popupContentSize.width <= windowSize.width
        val x = if (layoutDirection == LayoutDirection.Ltr) { if (afterFits || before < 0) after else before } else { if (before >= 0 || !afterFits) before else after }
        val y = anchorBounds.top + align.align(popupContentSize.height, anchorBounds.height)
        return IntOffset(x.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)), y.coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0)))
    }
}
