package gg.hideouthq.tools

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsProperties
import gg.hideouthq.core.data.fake.SampleGameRoot
import gg.hideouthq.core.data.fake.sampleLibraryManifest
import gg.hideouthq.core.library.ChoiceSync
import gg.hideouthq.core.library.FileImage
import gg.hideouthq.core.library.PakNumber
import kotlinx.coroutines.CompletableDeferred
import java.io.File
import kotlin.math.abs
import org.jetbrains.skia.Bitmap

private const val DragTitle = "Lilith, the Hell Warden"
private const val NextTitle = "Night of the Werecat"
private const val RowHeight = 56f

fun main(args: Array<String>) {
    val out = File(args.firstOrNull() ?: "build/verification/library-drag")
    val checks = linkedMapOf<String, () -> Unit>(
        "tracking" to { checkTracking(out, false) },
        "reduced_motion" to { checkTracking(out, true) },
        "reverse" to { checkReverse(out) },
        "neighbors" to { checkNeighbors(out) },
        "pending_drop" to { checkPendingDrop(out) },
        "pending_redrag" to { checkPendingRedrag(out) },
        "ack_during_drag" to { checkPendingRedrag(out, acknowledgeDuringGesture = true) },
        "pending_navigation" to { checkPendingRedrag(out, navigateWhilePending = true) },
        "pending_filter" to { checkPendingFilter(out) },
        "filtered_drag" to { checkFilteredDrag(out) },
        "move_to_position" to { checkMoveToPosition(out) },
        "regrab" to { checkRegrab(out) },
        "around_foreign" to { checkDropAroundForeignFile(out) },
        "edge_scroll" to { checkEdgeScroll(out) },
    )
    val selected = args.getOrNull(1)?.split(',')?.toSet() ?: checks.keys
    check(selected.all { it in checks }) { "Unknown drag check: $selected" }
    checks.filterKeys { it in selected }.forEach { (name, run) ->
        run()
        println("PASS: Library drag $name.")
    }
}

private fun Script.handle(title: String): Rect = nodes().single { it.hasLabel("Reorder $title") }.boundsInRoot
private fun Script.handleY(title: String) = handle(title).center.y / scale

private fun Script.heldMove(x: Float, y: Float) {
    scene.sendPointerEvent(
        PointerEventType.Move, Offset(x * scale, y * scale), timeMillis = time,
        buttons = PointerButtons(isPrimaryPressed = true),
    )
    advance(16)
}

private fun Script.dropAt(x: Float, y: Float) {
    scene.sendPointerEvent(
        PointerEventType.Release, Offset(x * scale, y * scale), timeMillis = time,
        buttons = PointerButtons(isPrimaryPressed = false), button = PointerButton.Primary,
    )
}

private fun Script.begin(title: String): Offset {
    capture("before")
    val center = handle(title).center / scale
    moveToDp(center.x, center.y)
    press()
    advance(32)
    return center
}

private fun checkTracking(out: File, reducedMotion: Boolean) {
    val fixture = InstalledFixture()
    val name = if (reducedMotion) "reduced_motion" else "pointer_tracking"
    runCheck(out, name, fixture.state(), reducedMotion = reducedMotion) {
        advance(900)
        val start = begin(DragTitle)
        val samples = mutableListOf<Pair<Float, Float>>()
        for (distance in 20..96 step 4) {
            heldMove(start.x, start.y + distance)
            samples += distance.toFloat() to handleY(DragTitle)
            capture("held_$distance")
        }
        dropAt(start.x, start.y + 96)
        advance(900)
        capture("dropped")
        val evidence = samples.joinToString("\n", prefix = "pointer_distance_dp\thandle_center_dp\n") { (pointer, row) -> "$pointer\t$row" }
        File(dir, "tracking.tsv").writeText(evidence + "\n")
        println(evidence)
        val first = samples.first()
        val largestError = samples.maxOf { (pointer, row) -> abs((row - first.second) - (pointer - first.first)) }
        check(largestError <= 2f) { "Dragged row must follow the pointer continuously; largest tracking error was $largestError dp" }
        check(fixture.record(DragTitle)?.numbers?.single() == PakNumber(3)) { "The dragged row should finish two slots below its initial position" }
        check(handleY(NextTitle) < handleY("Faun Celeste") && handleY("Faun Celeste") < handleY(DragTitle))
        println("PASS: Dragged row follows the pointer continuously across row boundaries.")
    }
}

private fun checkReverse(out: File) {
    val fixture = InstalledFixture()
    val state = fixture.state()
    runCheck(out, "reverse", state) {
        advance(900)
        val start = begin("HUDlock 2.0")
        val distances = (-20 downTo -112 step 4).toList() + (-108..0 step 4).toList()
        var initialGap: Float? = null
        distances.forEach { distance ->
            heldMove(start.x, start.y + distance)
            val gap = handleY("HUDlock 2.0") - (start.y + distance)
            val expected = initialGap ?: gap.also { initialGap = it }
            check(abs(gap - expected) <= 2f) { "Reversing a drag must not jump at row boundaries; gap changed from $expected to $gap" }
        }
        dropAt(start.x, start.y)
        advance(900)
        check(fixture.record("HUDlock 2.0")?.numbers?.single() == PakNumber(9)) { "Returning to the starting slot must preserve order" }
        check(fixture.files.linkedPaths.isEmpty() && fixture.files.removedPaths.isEmpty()) { "An unchanged drop must not move files" }
        capture("returned_to_start")
    }
}

private fun checkNeighbors(out: File) {
    val fixture = InstalledFixture()
    runCheck(out, "neighbors", fixture.state()) {
        advance(900)
        val firstY = handleY(DragTitle)
        val secondY = handleY(NextTitle)
        val start = begin(DragTitle)
        heldMove(start.x, start.y + 32f)
        val positions = List(16) {
            advance(16)
            handleY(NextTitle)
        }
        check(positions.any { it > firstY + 2f && it < secondY - 2f }) { "Displaced neighbors must animate between slots rather than teleport" }
        check(abs(positions.last() - firstY) <= 2f) { "The displaced row should settle into the open slot" }
        check(fixture.record(DragTitle)?.numbers?.single() == PakNumber(1)) { "Dragging must only preview the order until release" }
        capture("neighbor_settled_while_held")
        dropAt(start.x, start.y + 32f)
        advance(900)
    }
}

private fun checkPendingDrop(out: File) {
    val fixture = InstalledFixture()
    runCheck(out, "pending_drop", fixture.state()) {
        advance(900)
        val firstY = handleY(DragTitle)
        val secondY = handleY(NextTitle)
        fun controlPixels(label: String): List<Byte> {
            val bounds = nodes().single { it.hasLabel(label) }.boundsInRoot
            return scene.render(time * 1_000_000).use { Bitmap.makeFromImage(it) }.use { bitmap ->
                val pixels = checkNotNull(bitmap.readPixels())
                buildList {
                    for (y in bounds.top.toInt() until bounds.bottom.toInt()) {
                        for (x in bounds.left.toInt() until bounds.right.toInt()) {
                            val offset = y * bitmap.rowBytes + x * 4
                            for (channel in 0..2) add(pixels[offset + channel])
                        }
                    }
                }
            }
        }
        val stableControls = listOf("Turn HUDlock 2.0 on or off", "Reorder HUDlock 2.0")
            .associateWith(::controlPixels)
        val hold = CompletableDeferred<Unit>()
        fixture.processes.gate = hold
        try {
            val start = begin(DragTitle)
            for (distance in 8..60 step 4) heldMove(start.x, start.y + distance)
            dropAt(start.x, start.y + 60)
            advance(700)
            check(fixture.record(DragTitle)?.numbers?.single() == PakNumber(1)) { "The held process scan must still block persistence" }
            capture("awaiting_persistence")
            check(abs(handleY(DragTitle) - secondY) <= 2f && abs(handleY(NextTitle) - firstY) <= 2f) {
                "Dropping must retain the requested visual order while persistence waits, not snap back to the original order"
            }
            for ((label, before) in stableControls) {
                val pending = controlPixels(label)
                val changed = before.zip(pending).count { (a, b) -> a != b }
                println("Pending reorder $label: $changed changed colour channels")
                check(before == pending) { "Post-drop controls must not dim while files settle: $label changed $changed colour channels" }
                val disabled = nodes().single { it.hasLabel(label) }.config.contains(SemanticsProperties.Disabled)
                check(!disabled) { "Pending reorder must keep both dragging and saved toggles available" }
            }
            check(nodes().any { it.hasLabel("Applying…") }) { "A pending drop must show Applying feedback" }
            advance(1000)
            capture("still_applying")
            check(fixture.record(DragTitle)?.numbers?.single() == PakNumber(1))
            check(stableControls.all { (label, before) -> controlPixels(label) == before }) {
                "Controls must remain undimmed throughout a slow reorder"
            }
        } finally {
            fixture.processes.gate = null
            hold.complete(Unit)
        }
        advance(900)
        check(fixture.record(DragTitle)?.numbers?.single() == PakNumber(3) && fixture.record(NextTitle)?.numbers?.single() == PakNumber(2)) { "Only the dragged row's files move, past $NextTitle, which keeps 02" }
        check(abs(handleY(DragTitle) - secondY) <= 2f) { "Acknowledging a successful drop must not move the row again" }
        check(nodes().none { it.hasLabel("Applying…") })
        check(stableControls.keys.all { label -> !nodes().single { it.hasLabel(label) }.config.contains(SemanticsProperties.Disabled) })
        println("PASS: Post-drop controls keep their pixels while Applying; interactions resume after persistence.")
        capture("acknowledged")
    }
}

private fun checkPendingRedrag(out: File, acknowledgeDuringGesture: Boolean = false, navigateWhilePending: Boolean = false) {
    val fixture = InstalledFixture(sampleLibraryManifest().records)
    val name = when {
        acknowledgeDuringGesture -> "ack_during_drag"
        navigateWhilePending -> "pending_navigation"
        else -> "pending_redrag"
    }
    runCheck(out, name, fixture.state()) {
        advance(900)
        val firstY = handleY(DragTitle)
        val hold = CompletableDeferred<Unit>()
        fixture.processes.gate = hold
        try {
            val first = begin(DragTitle)
            for (distance in 8..60 step 4) heldMove(first.x, first.y + distance)
            dropAt(first.x, first.y + 60)
            val dropFrames = List(4) {
                advance(16)
                handleY(DragTitle)
            }
            println("Immediate drop positions: $dropFrames; requested=${firstY + RowHeight}")
            check(dropFrames.all { abs(it - firstY - RowHeight) <= 6f }) {
                "A dropped row must not jump toward its old slot before the requested order reaches the screen"
            }
            capture("immediate_drop")
            advance(700)
            check(fixture.record(DragTitle)?.numbers?.single() == PakNumber(1))
            capture("first_drop_pending")
            check(!nodes().single { it.hasLabel("Reorder HUDlock 2.0") }.config.contains(SemanticsProperties.Disabled)) {
                "Another row must remain draggable while the first drop applies"
            }
            val second = begin("HUDlock 2.0")
            for (distance in 8..80 step 8) heldMove(second.x, second.y - distance)
            if (acknowledgeDuringGesture) {
                val heldY = handleY("HUDlock 2.0")
                fixture.processes.gate = null
                hold.complete(Unit)
                advance(900)
                capture("acknowledged_while_held")
                check(fixture.record(DragTitle)?.numbers?.single() == PakNumber(3))
                check(abs(handleY("HUDlock 2.0") - heldY) <= 2f) {
                    "An earlier drop's acknowledgement must not cancel or move the active drag"
                }
            }
            for (distance in 88..168 step 8) heldMove(second.x, second.y - distance)
            dropAt(second.x, second.y - 168)
            advance(700)
            capture("second_drop_pending")
            check(abs(handleY("HUDlock 2.0") - firstY) <= 2f)
            check(handleY("HUDlock 2.0") < handleY(NextTitle) && handleY(NextTitle) < handleY(DragTitle)) {
                "The second drop must build on the first requested order, not the confirmed order"
            }
            val third = begin(NextTitle)
            for (distance in 8..112 step 8) heldMove(third.x, third.y + distance)
            dropAt(third.x, third.y + 112)
            advance(700)
            capture("latest_drop_pending")
            check(handleY("HUDlock 2.0") < handleY(DragTitle) && handleY(DragTitle) < handleY("Faun Celeste") && handleY("Faun Celeste") < handleY(NextTitle))
            if (!acknowledgeDuringGesture) {
                val progress = if (fixture.container.modLibrary.state.value.saving) "Saving…" else "Applying…"
                check(nodes().any { it.hasLabel(progress) }) { "Queued drops must show their actual saving or applying progress" }
                check(fixture.record(DragTitle)?.numbers?.single() == PakNumber(1) && fixture.record("HUDlock 2.0")?.numbers?.single() == PakNumber(9))
            }
            if (navigateWhilePending) {
                clickLabel("Browse")
                check(fixture.container.modLibrary.state.value.applying) {
                    "Leaving Library must not abandon accepted drops"
                }
                capture("browse_while_applying")
            }
        } finally {
            fixture.processes.gate = null
            hold.complete(Unit)
        }
        advance(1600)
        if (navigateWhilePending) clickLabel("Library")
        check(fixture.record("HUDlock 2.0")?.numbers?.single() == PakNumber(1))
        check(fixture.record(DragTitle)?.numbers?.single() == PakNumber(3))
        check(fixture.record("Faun Celeste")?.enabled == false)
        check(fixture.record(NextTitle)?.numbers?.single() == PakNumber(4))
        check(nodes().none { it.hasLabel("Applying…") })
        capture("latest_order_persisted")
        println("PASS: Further drags work during Applying and the latest requested order persists.")
    }
}

private fun checkPendingFilter(out: File) {
    val fixture = InstalledFixture()
    runCheck(out, "pending_filter", fixture.state()) {
        advance(900)
        val firstY = handleY(DragTitle)
        val hold = CompletableDeferred<Unit>()
        fixture.processes.gate = hold
        try {
            val start = begin(DragTitle)
            for (distance in 8..116 step 4) heldMove(start.x, start.y + distance)
            dropAt(start.x, start.y + 116)
            advance(500)
            key(Key.K, ctrl = true)
            type("Faun")
            advance(900)
            capture("filtered_pending_drop")
            check(abs(handleY("Faun Celeste") - firstY) < 2f) {
                "A pending drop must use visible slots after filtering. Expected $firstY, got ${handleY("Faun Celeste")}."
            }
        } finally {
            fixture.processes.gate = null
            hold.complete(Unit)
        }
        advance(900)
        check(abs(handleY("Faun Celeste") - firstY) < 2f) { "Acknowledgement must not shift the filtered row" }
        key(Key.Escape)
        advance(900)
        check(handleY(NextTitle) < handleY("Faun Celeste") && handleY("Faun Celeste") < handleY(DragTitle))
    }
}

private fun checkFilteredDrag(out: File) {
    val fixture = InstalledFixture()
    runCheck(out, "filtered_drag", fixture.state()) {
        advance(900)
        key(Key.K, ctrl = true)
        type("e")
        advance(900)
        check(!hasText("HUDlock 2.0")) { "The search must actually filter the rendered rows" }
        check(!nodes().single { it.hasLabel("Reorder $NextTitle") }.config.contains(SemanticsProperties.Disabled)) {
            "Searching must no longer disable reordering"
        }
        capture("filtered")
        val start = begin(NextTitle)
        heldMove(start.x, start.y + RowHeight)
        capture("dragging_while_filtered")
        dropAt(start.x, start.y + RowHeight)
        advance(900)
        check(fixture.record(DragTitle)?.numbers?.single() == PakNumber(1)) { "A visible row above the drag must keep its place" }
        check(handleY(DragTitle) < handleY("Faun Celeste") && handleY("Faun Celeste") < handleY(NextTitle)) { "The dragged row lands right after its visible target" }
        check(fixture.record(NextTitle)?.numbers?.single() == PakNumber(2)) { "Dragged past a mod that is off, it keeps its number" }
        check(fixture.container.modLibrary.state.value.orderView?.sets == listOf("lilith", "faun-celeste", "werecat", "hudlock")) { "A row hidden by the search must keep its place in the full order" }
        check(fixture.record("HUDlock 2.0")?.numbers?.single() == PakNumber(9))
        capture("filtered_drop_applied")
    }
}

private fun checkMoveToPosition(out: File) {
    val fixture = InstalledFixture()
    runCheck(out, "move_to_position", fixture.state()) {
        advance(900)
        openRowMenu(NextTitle)
        capture("menu_open")
        repeat(5) { key(Key.DirectionDown) }
        key(Key.Enter)
        advance(400)
        check(hasText("Move $NextTitle to position")) { "Enter on the keyboard-reached item must open the position dialog" }
        capture("dialog_open")
        repeat(3) { key(Key.Backspace) }
        type("1")
        key(Key.Enter)
        advance(700)
        check(handleY(NextTitle) < handleY(DragTitle)) { "Confirming must move the set to the typed position" }
        check(fixture.record(NextTitle)?.numbers?.single() == PakNumber(2) && fixture.record(DragTitle)?.numbers?.single() == PakNumber(3)) { "Everyone else keeps their relative order, and only $DragTitle's files move" }
        capture("position_applied")
    }
}

private fun checkRegrab(out: File) {
    val fixture = InstalledFixture(InstalledFixture.many().take(4))
    val title = "Sample mod 1"
    runCheck(out, "regrab", fixture.state()) {
        advance(900)
        val start = begin(title)
        for (distance in 8..32 step 8) heldMove(start.x, start.y + distance)
        dropAt(start.x, start.y + 32)
        val deadline = System.nanoTime() + 3_000_000_000L
        while ((fixture.record(title)?.numbers?.single() != PakNumber(2) || fixture.container.modLibrary.state.value.applying) && System.nanoTime() < deadline) runUiQueue(10)
        advance(48)
        check(fixture.record(title)?.numbers?.single() == PakNumber(2) && !fixture.container.modLibrary.state.value.applying) { "The first drop must be acknowledged before re-grabbing; order=${fixture.container.modLibrary.state.value.choiceSync}" }
        val actual = handle(title).center / scale
        println("Re-grab at y=${actual.y}, target=${start.y + RowHeight}, order=${fixture.container.modLibrary.state.value.choiceSync}")
        scene.sendPointerEvent(PointerEventType.Move, actual * scale, timeMillis = time)
        scene.sendPointerEvent(
            PointerEventType.Press, actual * scale, timeMillis = time,
            buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary,
        )
        heldMove(actual.x, actual.y - 20f)
        capture("regrabbed_during_settle")
        val jump = abs(handleY(title) - (actual.y - 20f))
        check(jump < 4f) { "Re-grabbing a settling row must preserve its rendered position; tracking jump was $jump dp" }
        dropAt(actual.x, actual.y - 20f)
        advance(900)
    }
}

private fun checkDropAroundForeignFile(out: File) {
    val fixture = InstalledFixture(sampleLibraryManifest().records)
    val foreign = FileImage(123, "a".repeat(64))
    fixture.files.putFile(SampleGameRoot, "game/citadel/addons/pak03_dir.vpk", foreign.sizeBytes, foreign.sha256)
    runCheck(out, "around_foreign", fixture.state()) {
        advance(900)
        val start = begin(DragTitle)
        for (distance in 8..60 step 4) heldMove(start.x, start.y + distance)
        capture("before_drop")
        dropAt(start.x, start.y + 60)
        advance(1000)
        check(handleY(NextTitle) < handleY(DragTitle) && handleY(DragTitle) < handleY("HUDlock 2.0")) { "The drop keeps the order asked for" }
        check(fixture.record(NextTitle)?.numbers?.single() == PakNumber(2) && fixture.record(DragTitle)?.numbers?.single() == PakNumber(4) && fixture.record("HUDlock 2.0")?.numbers?.single() == PakNumber(9)) {
            "$DragTitle lands at 04, past the file at 03"
        }
        check(fixture.files.addonsFiles(SampleGameRoot)["pak03_dir.vpk"] == foreign) { "The file Hideout did not put there is untouched" }
        check(fixture.container.modLibrary.state.value.choiceSync !is ChoiceSync.Failed && !hasText("Your choices are saved but could not be applied.")) {
            "A file Hideout did not put there gives no notice"
        }
        check(nodes().none { it.hasLabel("Applying…") })
        capture("landed_around_foreign_file")
    }
}

private fun checkEdgeScroll(out: File) {
    val fixture = InstalledFixture(InstalledFixture.many())
    runCheck(out, "edge_scroll", fixture.state(), widthDp = 1440, heightDp = 520) {
        advance(900)
        val row = handle("Sample mod 1").center
        fun scrollNode() = nodes().single { it.config.contains(SemanticsProperties.VerticalScrollAxisRange) && it.boundsInRoot.contains(row) }
        fun scrollOffset() = scrollNode().config[SemanticsProperties.VerticalScrollAxisRange].value() / scale
        val viewport = scrollNode().boundsInRoot
        val start = begin("Sample mod 1")
        val target = viewport.bottom / scale - 20f
        for (step in 1..16) heldMove(start.x, start.y + (target - start.y) * step / 16)
        val before = scrollOffset()
        advance(1200)
        capture("held_at_bottom")
        check(scrollOffset() > before + RowHeight) { "Holding a dragged row near the bottom must scroll the list, before=$before after=${scrollOffset()}" }
        check(abs(handleY("Sample mod 1") - target) < 20f) { "The grabbed row must stay under the pointer while the list scrolls" }
        dropAt(start.x, target)
        advance(1000)
        check((fixture.record("Sample mod 1")?.numbers?.single()?.value ?: 0) > 4) { "Edge scrolling must allow dropping beyond initially visible rows" }
        capture("scrolled_drop")
        val back = begin("Sample mod 1")
        val top = viewport.top / scale + 20f
        for (step in 1..16) heldMove(back.x, back.y + (top - back.y) * step / 16)
        val scrolledDown = scrollOffset()
        advance(1500)
        check(scrollOffset() < scrolledDown - RowHeight) { "Holding the row near the top must scroll back up" }
        check(abs(handleY("Sample mod 1") - top) < 20f) { "Scrolling upward must also retain the pointer-to-row offset" }
        dropAt(back.x, top)
        advance(1000)
        check(fixture.record("Sample mod 1")?.numbers?.single() == PakNumber(1)) { "Dragging back through the top edge must restore the first slot" }
        capture("scrolled_back_to_start")
    }
}
