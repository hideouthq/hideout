package gg.hideouthq.feature.library.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import gg.hideouthq.feature.library.SelectGesture

internal fun Modifier.selectGestures(installId: String, selected: Boolean, onSelect: (String, SelectGesture) -> Unit, focus: FocusRequester, onMenu: (() -> Unit)? = null): Modifier =
    pointerInput(installId, selected) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.type != PointerEventType.Press) continue
                val keys = event.keyboardModifiers
                val gesture = when {
                    event.buttons.isSecondaryPressed -> if (onMenu == null) continue else null
                    keys.isCtrlPressed || keys.isMetaPressed -> SelectGesture.Toggle
                    keys.isShiftPressed -> SelectGesture.Range
                    else -> continue
                }
                event.changes.forEach { it.consume() }
                if (gesture != null) {
                    onSelect(installId, gesture)
                    focus.requestFocus()
                } else {
                    if (!selected) onSelect(installId, SelectGesture.Only)
                    onMenu?.invoke()
                }
            }
        }
    }
