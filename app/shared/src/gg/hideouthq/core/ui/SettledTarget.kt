package gg.hideouthq.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis

/**
 * Whether a control that acts on [target] should take a press: false for a moment after [target] changes, so the
 * second press of a double click on a button whose item just went doesn't act on the item that took its place under
 * the pointer. A control shown for its first target takes presses at once.
 */
@Composable
fun rememberTargetSettled(target: Any?): State<Boolean> {
    val first = remember { booleanArrayOf(true) }
    val settled = remember(target) { mutableStateOf(first[0]).also { first[0] = false } }
    LaunchedEffect(target) {
        val changed = withFrameMillis { it }
        while (withFrameMillis { it } - changed < TargetSettleMillis) continue
        settled.value = true
    }
    return settled
}

private const val TargetSettleMillis = 450L
