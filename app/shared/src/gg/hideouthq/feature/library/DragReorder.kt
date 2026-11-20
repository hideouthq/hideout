package gg.hideouthq.feature.library

import gg.hideouthq.core.library.OrderView
import kotlin.math.roundToInt

/**
 * A drag in progress: [order] as the full load order would be if dropped now, placing the dragged row immediately
 * before or after the visible row it now sits next to. It begins from the full ids [basis] and the [visible] ids
 * shown then, under the [view] shown then; both stay fixed for the drag's whole lifetime.
 */
data class DragPreview(
    val order: List<String>,
    val draggingId: String? = null,
    /** The dragged row's index within [visible] when the drag began. */
    val startIndex: Int = 0,
    val accumulatedPx: Float = 0f,
    val basis: List<String> = order,
    val visible: List<String> = order,
    val view: OrderView? = null,
)

object DragReorder {
    fun start(order: List<String>, installId: String, visible: List<String> = order, view: OrderView? = null): DragPreview {
        val index = visible.indexOf(installId)
        return if (index < 0) DragPreview(order, visible = visible, view = view) else DragPreview(order, installId, index, visible = visible, view = view)
    }

    /** Shifts the dragged row by whole rows of [DragPreview.visible], then [place]s it there. */
    fun drag(preview: DragPreview, deltaY: Float, rowStridePx: Float): DragPreview {
        val draggingId = preview.draggingId ?: return preview
        if (rowStridePx <= 0f) return preview
        val accumulated = preview.accumulatedPx + deltaY
        val shift = (accumulated / rowStridePx).roundToInt()
        return preview.copy(order = place(preview.basis, preview.visible, draggingId, preview.startIndex + shift), accumulatedPx = accumulated)
    }

    /**
     * [order] with [id] moved to index [to] of [visible]: immediately before or after the visible row it then sits
     * next to, past any hidden rows between, every other id keeping its relative order.
     */
    fun place(order: List<String>, visible: List<String>, id: String, to: Int): List<String> {
        val from = visible.indexOf(id)
        val target = to.coerceIn(0, visible.lastIndex)
        if (from < 0 || target == from) return order
        val movedUp = target < from
        val nextVisible = visible.toMutableList().apply { add(target, removeAt(from)) }
        val neighbour = if (movedUp) nextVisible.getOrNull(target + 1) else nextVisible.getOrNull(target - 1)
        val without = order.filter { it != id }
        val anchor = neighbour?.let(without::indexOf)?.takeIf { it >= 0 } ?: return order
        return without.toMutableList().apply { add(if (movedUp) anchor else anchor + 1, id) }
    }

    fun offset(preview: DragPreview, layoutIndex: Int, rowStridePx: Float): Float = (preview.startIndex - layoutIndex) * rowStridePx + preview.accumulatedPx

    /** The finished drag when it moved its row, or null when it ends where it began. */
    fun drop(preview: DragPreview): DragPreview? = preview.takeIf { it.draggingId != null && it.order != it.basis }

    /** Keeps a drag only while dragging is allowed and the ids, the visible ids, and the view it began from are still the ones shown. */
    fun reconcile(preview: DragPreview?, order: List<String>, canDrag: Boolean, view: OrderView?, visible: List<String> = order): DragPreview? =
        preview?.takeIf { canDrag && order == it.basis && visible == it.visible && view === it.view }
}
