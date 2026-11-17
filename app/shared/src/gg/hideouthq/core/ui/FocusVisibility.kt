package gg.hideouthq.core.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.Modifier

/**
 * CSS `:focus-visible` for Compose: focus rings show only while the keyboard is being used,
 * not after a mouse click moves focus.
 */
@Stable
class FocusVisibility {
    var keyboardInUse by mutableStateOf(false)
        internal set
}

val LocalFocusVisibility = staticCompositionLocalOf { FocusVisibility() }

/** Put on the window root: keys switch focus rings on, pointer presses switch them off. */
fun Modifier.trackFocusVisibility(state: FocusVisibility): Modifier = this
    .onPreviewKeyEvent {
        state.keyboardInUse = true
        false
    }
    .pointerInput(state) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.type == PointerEventType.Press) state.keyboardInUse = false
            }
        }
    }
