package gg.hideouthq.core.designsystem.component

import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.Stable
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.Modifier
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import gg.hideouthq.core.designsystem.theme.HideoutMotion
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs

private const val MaxStretch = 0.16f
private const val StretchFullSpeedDpPerSecond = 2400f
private const val SquashPerStretch = 0.5f
private const val FillFadeCoverage = 0.1f

/**
 * One selection highlight shared by a group of items (pills, segments, tabs).
 * Items report their bounds with [indicatorTarget]; the host draws a single shape behind the
 * selected one with [selectionIndicator], and that shape slides (stretching slightly) to the next.
 * Items ask [coverage] how much of them the shape covers, so their colours change as it arrives
 * rather than before it gets there.
 */
@Stable
class SelectionIndicatorState internal constructor(private val squash: Boolean) {
    internal val targets = mutableStateMapOf<Any, Rect>()
    internal val backgrounds = mutableStateMapOf<Any, () -> Color>()
    internal var selectedKey by mutableStateOf<Any?>(null)
    private var host: LayoutCoordinates? = null
    private val targetCoordinates = mutableMapOf<Any, LayoutCoordinates>()

    internal val targetRect: Rect? by derivedStateOf { targets[selectedKey] }

    internal fun updateHost(coordinates: LayoutCoordinates) {
        host = coordinates
        targetCoordinates.forEach { (key, item) -> record(key, item) }
    }

    internal fun updateTarget(key: Any, coordinates: LayoutCoordinates) {
        targetCoordinates[key] = coordinates
        record(key, coordinates)
    }

    internal fun removeTarget(key: Any) {
        targetCoordinates.remove(key)
        targets.remove(key)
    }

    private fun record(key: Any, coordinates: LayoutCoordinates) {
        val origin = host?.takeIf { it.isAttached } ?: return
        if (!coordinates.isAttached) return
        val bounds = origin.localBoundingBoxOf(coordinates, clipBounds = false)
        if (targets[key] != bounds) targets[key] = bounds
    }

    private val shapeCenter = Animatable(0f)
    private val length = Animatable(0f)
    private val crossStart = Animatable(0f)
    private val crossEnd = Animatable(0f)
    private val alpha = Animatable(0f)
    private var previous by mutableStateOf<Rect?>(null)
    private val previousAlpha = Animatable(0f)
    private var placed by mutableStateOf(false)
    private var lastDensity = 1f

    internal fun animateTo(rect: Rect?, motion: HideoutMotion, scope: CoroutineScope) {
        if (rect == null) {
            scope.launch { alpha.animateTo(0f, motion.effectsDefault()) }
            return
        }
        val targetCenter = (rect.left + rect.right) / 2
        val targetLength = rect.right - rect.left
        if (!placed || motion.reduced) {
            if (placed) {
                previous = currentRect()
                scope.launch {
                    previousAlpha.snapTo(alpha.value)
                    previousAlpha.animateTo(0f, motion.effectsDefault())
                }
            }
            scope.launch {
                shapeCenter.snapTo(targetCenter)
                length.snapTo(targetLength)
                crossStart.snapTo(rect.top)
                crossEnd.snapTo(rect.bottom)
                if (placed) alpha.snapTo(0f)
                placed = true
                alpha.animateTo(1f, motion.effectsDefault())
            }
            return
        }
        scope.launch { shapeCenter.animateTo(targetCenter, motion.spatialDefault()) }
        scope.launch { length.animateTo(targetLength, motion.spatialDefault()) }
        scope.launch { crossStart.animateTo(rect.top, motion.spatialDefault()) }
        scope.launch { crossEnd.animateTo(rect.bottom, motion.spatialDefault()) }
        scope.launch { alpha.animateTo(1f, motion.effectsDefault()) }
    }

    /**
     * How much of the item with [key] the shape currently covers, 0..1. Before the shape has been
     * placed, the selected item counts as fully covered.
     */
    fun coverage(key: Any): Float {
        val item = targets[key] ?: return if (key == selectedKey) 1f else 0f
        if (!placed) return if (key == selectedKey) 1f else 0f
        val shapeStart = shapeCenter.value - length.value / 2
        val shapeEnd = shapeCenter.value + length.value / 2
        val along = overlap(item.left, item.right, shapeStart, shapeEnd) / (item.right - item.left).coerceAtLeast(1f)
        val across = overlap(item.top, item.bottom, crossStart.value, crossEnd.value) /
            (item.bottom - item.top).coerceAtLeast(1f)
        return (along * across * alpha.value).coerceIn(0f, 1f)
    }

    /** The shape's current outline box in root coordinates, or null while it is not showing. */
    fun shapeBoundsInRoot(): Rect? {
        val origin = host?.takeIf { it.isAttached } ?: return null
        if (!placed || alpha.value <= 0f) return null
        return currentRect(stretch(lastDensity)).translate(origin.positionInRoot())
    }

    private fun overlap(aStart: Float, aEnd: Float, bStart: Float, bEnd: Float) =
        (minOf(aEnd, bEnd) - maxOf(aStart, bStart)).coerceAtLeast(0f)

    private fun currentRect(stretch: Float = 0f): Rect {
        val half = length.value * (1f + stretch) / 2
        var cs = crossStart.value
        var ce = crossEnd.value
        if (squash && stretch > 0f) {
            val inset = (ce - cs) * stretch * SquashPerStretch / 2
            cs += inset
            ce -= inset
        }
        return Rect(shapeCenter.value - half, cs, shapeCenter.value + half, ce)
    }

    internal fun DrawScope.drawIndicator(color: Color, shape: Shape) {
        backgrounds.forEach { (key, background) ->
            val fill = ((1f - coverage(key)) / FillFadeCoverage).coerceIn(0f, 1f)
            targets[key]?.let { if (fill > 0f) drawRect(it, fill, background(), shape) }
        }
        previous?.let { if (previousAlpha.value > 0f) drawRect(it, previousAlpha.value, color, shape) }
        if (!placed || alpha.value <= 0f) return
        lastDensity = density
        drawRect(currentRect(stretch(density)), alpha.value, color, shape)
    }

    private fun stretch(density: Float): Float {
        val speedDp = abs(shapeCenter.velocity) / density
        return (speedDp / StretchFullSpeedDpPerSecond).coerceAtMost(1f) * MaxStretch
    }

    private fun DrawScope.drawRect(rect: Rect, alpha: Float, color: Color, shape: Shape) {
        val outline = shape.createOutline(Size(rect.width, rect.height), layoutDirection, this)
        translate(rect.left, rect.top) { drawOutline(outline, color, alpha = alpha) }
    }
}

@Composable
fun rememberSelectionIndicatorState(selectedKey: Any?, squash: Boolean = false): SelectionIndicatorState {
    val state = remember(squash) { SelectionIndicatorState(squash) }
    state.selectedKey = selectedKey
    val motion by rememberUpdatedState(HideoutTheme.motion)
    LaunchedEffect(state) {
        snapshotFlow { state.targetRect }.collect { state.animateTo(it, motion, this) }
    }
    return state
}

fun Modifier.selectionIndicator(state: SelectionIndicatorState, color: Color, shape: Shape, inFront: Boolean = false): Modifier = this
    .onGloballyPositioned { state.updateHost(it) }
    .then(
        if (inFront) {
            Modifier.drawWithContent {
                drawContent()
                with(state) { drawIndicator(color, shape) }
            }
        } else {
            Modifier.drawBehind { with(state) { drawIndicator(color, shape) } }
        },
    )

/**
 * On each selectable child: reports where it is so the indicator can find it, and forgets it when it
 * leaves. Children with an opaque idle background pass it as [background] so the host can paint it
 * *under* the indicator; otherwise the indicator would vanish behind them as it slides past.
 */
fun Modifier.indicatorTarget(
    state: SelectionIndicatorState,
    key: Any,
    background: (() -> Color)? = null,
): Modifier = this then IndicatorTargetElement(state, key, background)

private data class IndicatorTargetElement(
    val state: SelectionIndicatorState,
    val key: Any,
    val background: (() -> Color)?,
) : ModifierNodeElement<IndicatorTargetNode>() {
    override fun create() = IndicatorTargetNode(state, key, background)
    override fun update(node: IndicatorTargetNode) {
        if (node.key != key || node.state !== state) node.unregister()
        node.state = state
        node.key = key
        node.background = background
        if (node.isAttached) node.register()
    }
}

private class IndicatorTargetNode(
    var state: SelectionIndicatorState,
    var key: Any,
    var background: (() -> Color)?,
) : Modifier.Node(), GlobalPositionAwareModifierNode {
    fun register() {
        val paint = background
        if (paint != null) state.backgrounds[key] = paint else state.backgrounds.remove(key)
    }

    fun unregister() {
        state.removeTarget(key)
        state.backgrounds.remove(key)
    }

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        state.updateTarget(key, coordinates)
    }

    override fun onAttach() = register()

    override fun onDetach() = unregister()
}
