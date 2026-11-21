package gg.hideouthq.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis

/**
 * Whether a control that acts on [target] should take a press: false for a moment after [target] changes, so a double
 * click's second press doesn't act on the item that slid under the pointer. True at once for the first target.
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
