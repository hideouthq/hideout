package gg.hideouthq.feature.library

import gg.hideouthq.core.library.GameRoot
import gg.hideouthq.core.library.OrderView
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class DragReorderTest {
    private val order = listOf("a", "b", "c", "d")
    private val stride = 56f

    @Test
    fun draggingPastHalfARowMovesTheRowBeforeTheNext() {
        var preview = DragReorder.start(order, "b")
        preview = DragReorder.drag(preview, deltaY = 100f, rowStridePx = stride)
        assertEquals(listOf("a", "c", "d", "b"), preview.order)
    }

    @Test
    fun smallMovesBelowHalfARowStrideDoNotReorder() {
        var preview = DragReorder.start(order, "b")
        preview = DragReorder.drag(preview, deltaY = 20f, rowStridePx = stride)
        assertEquals(order, preview.order)
    }

    @Test
    fun theTargetIsClampedToTheEndsOfTheList() {
        var preview = DragReorder.start(order, "a")
        preview = DragReorder.drag(preview, deltaY = 1000f, rowStridePx = stride)
        assertEquals(listOf("b", "c", "d", "a"), preview.order)
    }

    @Test
    fun movingAmongTheShownRowsPassesTheHiddenOnesBetweenSoTheRowVisiblyMoves() {
        val shown = listOf("a", "b", "d")
        assertEquals(listOf("a", "d", "b", "c"), DragReorder.place(order, shown, "d", 1), "up one: above b, past the hidden c")
        assertEquals(listOf("a", "c", "d", "b"), DragReorder.place(order, shown, "b", 2), "down one: below d, past the hidden c")
        assertEquals(listOf("d", "a", "b", "c"), DragReorder.place(order, shown, "d", 0))
        assertEquals(order, DragReorder.place(order, shown, "a", -1), "the first shown row has nowhere higher to go")
        assertEquals(listOf("b", "a", "c", "d"), DragReorder.place(order, order, "a", 1), "with nothing hidden it is a plain move")
    }

    @Test
    fun startingOnAnUnknownIdLeavesTheOrderUntouchedAndNothingDragging() {
        val preview = DragReorder.start(order, "missing")
        assertNull(preview.draggingId)
        assertEquals(order, preview.order)
    }

    @Test
    fun grabbedRowFollowsEveryPixelAcrossBothThresholds() {
        var preview = DragReorder.start(order, "a")
        for (distance in 4..120 step 4) {
            preview = DragReorder.drag(preview, 4f, stride)
            assertEquals(distance.toFloat(), DragReorder.offset(preview, 0, stride))
        }
        assertEquals(listOf("b", "c", "a", "d"), preview.order)
    }

    @Test
    fun upwardDragAndReversalKeepTheGrabbedRowUnderThePointer() {
        var preview = DragReorder.drag(DragReorder.start(order, "c"), -80f, stride)
        assertEquals(listOf("a", "c", "b", "d"), preview.order)
        assertEquals(-80f, DragReorder.offset(preview, 2, stride))
        preview = DragReorder.drag(preview, 100f, stride)
        assertEquals(order, preview.order)
        assertEquals(20f, DragReorder.offset(preview, 2, stride))
    }

    @Test
    fun aNewDragUsesVisualPositionEvenWhenLayoutSlotsAreStillInTheOldOrder() {
        val preview = DragReorder.drag(DragReorder.start(listOf("b", "c", "a", "d"), "a"), -12f, stride)
        assertEquals(100f, DragReorder.offset(preview, 0, stride))
    }

    @Test
    fun aDropHandsTheMovedOrderToTheLibraryInsteadOfKeepingAPreview() {
        val dragging = DragReorder.drag(DragReorder.start(order, "a"), 96f, stride)
        val dropped = requireNotNull(DragReorder.drop(dragging))
        assertEquals("a", dropped.draggingId)
        assertEquals(listOf("b", "c", "a", "d"), dropped.order)
        assertEquals(order, dropped.basis)
    }

    @Test
    fun unchangedDropAsksForNothing() {
        assertNull(DragReorder.drop(DragReorder.drag(DragReorder.start(order, "a"), 12f, stride)))
        assertNull(DragReorder.drop(DragReorder.drag(DragReorder.drag(DragReorder.start(order, "a"), 96f, stride), -96f, stride)))
        assertNull(DragReorder.drop(DragReorder.start(order, "missing")))
    }

    @Test
    fun membershipOrOrderChangesInvalidateAnActiveDrag() {
        val dragging = DragReorder.drag(DragReorder.start(order, "a"), 96f, stride)
        assertNull(DragReorder.reconcile(dragging, listOf("b", "c", "d"), canDrag = true, view = null))
        assertNull(DragReorder.reconcile(dragging, order + "e", canDrag = true, view = null))
        assertNull(DragReorder.reconcile(dragging, order.reversed(), canDrag = true, view = null))
        assertEquals(dragging, DragReorder.reconcile(dragging, order, canDrag = true, view = null))
    }

    @Test
    fun aDragLastsOnlyAsLongAsTheViewItBeganFrom() {
        val shown = OrderView(GameRoot("/games/Deadlock"), order)
        val dragging = DragReorder.drag(DragReorder.start(order, "a", view = shown), 96f, stride)
        assertSame(shown, dragging.view)
        assertEquals(dragging, DragReorder.reconcile(dragging, order, canDrag = true, view = shown), "its own earlier drop finishing keeps the view")
        assertNull(DragReorder.reconcile(dragging, order, canDrag = true, view = OrderView(GameRoot("/games/Deadlock"), order)), "the same ids under a new view")
        assertNull(DragReorder.reconcile(dragging, order, canDrag = true, view = null))
    }

    @Test
    fun losingPermissionCancelsAnActiveDrag() {
        val dragging = DragReorder.start(order, "a")
        assertNull(DragReorder.reconcile(dragging, order, canDrag = false, view = null))
    }

    @Test
    fun consumedScrollDistanceAdvancesTheTargetWithoutMovingTheRowOnScreen() {
        val dragging = DragReorder.drag(DragReorder.start(order, "a"), 24f, stride)
        val scrolled = DragReorder.drag(dragging, 56f, stride)
        assertEquals(listOf("b", "a", "c", "d"), scrolled.order)
        assertEquals(24f, DragReorder.offset(scrolled, 0, stride) - 56f)
    }

    @Test
    fun aFilteredDragPlacesTheDraggedRowNextToItsVisibleTargetKeepingHiddenRowsInPlace() {
        val full = listOf("a", "b", "c", "d", "e")
        val visible = listOf("a", "b", "d", "e")
        val preview = DragReorder.drag(DragReorder.start(full, "a", visible), deltaY = 120f, rowStridePx = stride)
        assertEquals(listOf("b", "c", "d", "a", "e"), preview.order, "a lands right after its visible target, d; c keeps its place after b")
    }

    @Test
    fun aFilteredDragUpwardPlacesTheDraggedRowBeforeItsVisibleTarget() {
        val full = listOf("a", "b", "c", "d", "e")
        val visible = listOf("a", "b", "d", "e")
        val preview = DragReorder.drag(DragReorder.start(full, "e", visible), deltaY = -120f, rowStridePx = stride)
        assertEquals(listOf("a", "e", "b", "c", "d"), preview.order, "e lands right before its visible target, b")
    }

    @Test
    fun reconcileInvalidatesAnActiveDragWhenTheVisibleRowsChangeUnderFiltering() {
        val full = listOf("a", "b", "c", "d")
        val visible = listOf("a", "b", "d")
        val dragging = DragReorder.start(full, "a", visible)
        assertNull(DragReorder.reconcile(dragging, full, canDrag = true, view = null, visible = full), "clearing the filter mid-drag must cancel it")
        assertEquals(dragging, DragReorder.reconcile(dragging, full, canDrag = true, view = null, visible = visible))
    }
}
