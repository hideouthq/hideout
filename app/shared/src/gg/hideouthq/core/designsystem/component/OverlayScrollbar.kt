package gg.hideouthq.core.designsystem.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.ScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.Stable
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.Modifier
import gg.hideouthq.core.designsystem.theme.HideoutDimens
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val IdleHideMillis = 900L
private const val ThumbAlpha = 0.22f
private const val ThumbHoverAlpha = 0.38f

/** What an [OverlayScrollbar] needs to know about a scrollable, in pixels. */
@Stable
interface ScrollbarAdapter {
    val scrollOffset: Float
    val contentSize: Float
    val viewportSize: Float
    suspend fun scrollTo(offset: Float)
}

@Composable
fun rememberScrollbarAdapter(state: ScrollState): ScrollbarAdapter = remember(state) {
    object : ScrollbarAdapter {
        override val scrollOffset get() = state.value.toFloat()
        override val contentSize get() = state.maxValue.toFloat() + state.viewportSize
        override val viewportSize get() = state.viewportSize.toFloat()
        override suspend fun scrollTo(offset: Float) {
            state.scrollTo(offset.toInt())
        }
    }
}

@Composable
fun rememberScrollbarAdapter(state: LazyGridState, columns: () -> Int): ScrollbarAdapter = remember(state) {
    object : ScrollbarAdapter {
        private val rowHeight: Float
            get() = state.layoutInfo.visibleItemsInfo.firstOrNull()?.size?.height?.plus(state.layoutInfo.mainAxisItemSpacing)?.toFloat() ?: 0f
        private val columnCount get() = columns().coerceAtLeast(1)
        override val scrollOffset get() = state.firstVisibleItemIndex / columnCount * rowHeight + state.firstVisibleItemScrollOffset
        override val contentSize get() = ((state.layoutInfo.totalItemsCount + columnCount - 1) / columnCount) * rowHeight +
            state.layoutInfo.beforeContentPadding + state.layoutInfo.afterContentPadding
        override val viewportSize get() = state.layoutInfo.viewportSize.height.toFloat()
        override suspend fun scrollTo(offset: Float) {
            val row = rowHeight.takeIf { it > 0f } ?: return
            state.scrollToItem((offset / row).toInt() * columnCount, (offset % row).toInt())
        }
    }
}

/** For a list of rows of different heights: positions are estimated from the average height of the rows in view. */
@Composable
fun rememberScrollbarAdapter(state: LazyListState): ScrollbarAdapter = remember(state) {
    object : ScrollbarAdapter {
        private val itemHeight: Float
            get() = state.layoutInfo.visibleItemsInfo.takeIf { it.isNotEmpty() }
                ?.let { items -> items.sumOf { it.size }.toFloat() / items.size + state.layoutInfo.mainAxisItemSpacing } ?: 0f
        override val scrollOffset get() = state.firstVisibleItemIndex * itemHeight + state.firstVisibleItemScrollOffset
        override val contentSize get() = state.layoutInfo.totalItemsCount * itemHeight +
            state.layoutInfo.beforeContentPadding + state.layoutInfo.afterContentPadding
        override val viewportSize get() = state.layoutInfo.viewportSize.height.toFloat()
        override suspend fun scrollTo(offset: Float) {
            val item = itemHeight.takeIf { it > 0f } ?: return
            state.scrollToItem((offset / item).toInt(), (offset % item).toInt())
        }
    }
}

@Composable
fun OverlayScrollbar(adapter: ScrollbarAdapter, modifier: Modifier = Modifier) {
    val motion = HideoutTheme.motion
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val dragged by source.collectIsDraggedAsState()
    var scrolling by remember { mutableStateOf(false) }
    LaunchedEffect(adapter) {
        var first = true
        snapshotFlow { adapter.scrollOffset }.collect {
            if (first) {
                first = false
                return@collect
            }
            scrolling = true
            delay(IdleHideMillis)
            scrolling = false
        }
    }
    val scrollable = adapter.contentSize > adapter.viewportSize + 1f
    val visible = scrollable && (scrolling || hovered || dragged)
    val alpha by animateFloatAsState(if (visible) 1f else 0f, motion.effectsDefault(), label = "scrollbarAlpha")
    val thickness by animateFloatAsState(if (hovered || dragged) 1f else 0f, motion.effectsFast(), label = "scrollbarThickness")
    val thumbColor = MaterialTheme.colorScheme.onSurface
    val scope = rememberCoroutineScope()

    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(HideoutDimens.Scrollbar.ThicknessHovered + HideoutDimens.Scrollbar.Inset * 2)
            .hoverable(source)
            .draggable(
                state = rememberDraggableState { delta ->
                    val ratio = adapter.contentSize / adapter.viewportSize.coerceAtLeast(1f)
                    scope.launch { adapter.scrollTo((adapter.scrollOffset + delta * ratio).coerceAtLeast(0f)) }
                },
                orientation = Orientation.Vertical,
                interactionSource = source,
            )
            .padding(HideoutDimens.Scrollbar.Inset)
            .drawBehind {
                if (alpha <= 0f || adapter.contentSize <= 0f) return@drawBehind
                val minThumb = HideoutDimens.Scrollbar.MinThumb.toPx()
                val thumbHeight = (size.height * adapter.viewportSize / adapter.contentSize).coerceIn(minThumb, size.height)
                val travel = size.height - thumbHeight
                val maxScroll = (adapter.contentSize - adapter.viewportSize).coerceAtLeast(1f)
                val top = travel * (adapter.scrollOffset / maxScroll).coerceIn(0f, 1f)
                val idleWidth = HideoutDimens.Scrollbar.Thickness.toPx()
                val width = idleWidth + (size.width - idleWidth) * thickness
                val thumbAlpha = ThumbAlpha + (ThumbHoverAlpha - ThumbAlpha) * thickness
                drawRoundRect(
                    color = thumbColor.copy(alpha = thumbAlpha * alpha),
                    topLeft = Offset(size.width - width, top),
                    size = Size(width, thumbHeight),
                    cornerRadius = CornerRadius(width / 2),
                )
            },
    )
}
