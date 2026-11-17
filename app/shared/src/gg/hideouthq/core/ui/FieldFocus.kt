package gg.hideouthq.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalFocusManager

/**
 * Remembers where the focused text field is, so a press anywhere else in the window can take
 * focus away from it, as clicking outside an input does in a browser. Compose keeps focus otherwise.
 */
@Stable
class FieldFocusTracker {
    internal var field: LayoutCoordinates? = null
    internal var root: LayoutCoordinates? = null

    /** Called by a text field while it is focused (with its coordinates) and when it loses focus (null). */
    fun update(coordinates: LayoutCoordinates?, focused: Boolean) {
        if (focused) field = coordinates else if (field === coordinates || coordinates == null) field = null
    }
}

val LocalFieldFocusTracker = staticCompositionLocalOf { FieldFocusTracker() }

@Composable
fun Modifier.trackFieldFocus(focused: Boolean): Modifier {
    val tracker = LocalFieldFocusTracker.current
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    LaunchedEffect(focused, coordinates) { tracker.update(coordinates, focused) }
    return onGloballyPositioned { coordinates = it }
}

@Composable
fun Modifier.leaveOnEscape(): Modifier {
    val focusManager = LocalFocusManager.current
    return onPreviewKeyEvent { event ->
        val leave = event.type == KeyEventType.KeyDown && event.key == Key.Escape
        if (leave) focusManager.clearFocus()
        leave
    }
}

/** Put on the window root: a press outside the focused text field clears focus. */
fun Modifier.clearFieldFocusOnOutsidePress(tracker: FieldFocusTracker, focusManager: FocusManager): Modifier = this
    .onGloballyPositioned { tracker.root = it }
    .pointerInput(tracker, focusManager) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.type != PointerEventType.Press) continue
                val field = tracker.field?.takeIf { it.isAttached } ?: continue
                val root = tracker.root?.takeIf { it.isAttached } ?: continue
                val position = event.changes.firstOrNull()?.position ?: continue
                if (!root.localBoundingBoxOf(field).contains(position)) {
                    tracker.field = null
                    focusManager.clearFocus()
                }
            }
        }
    }
