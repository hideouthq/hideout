package gg.hideouthq.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf

@Stable
class EscapeClaim {
    internal var claim: (() -> Unit)? = null

    /** Runs the page's claim; false when it has none. */
    fun take(): Boolean {
        val claimed = claim ?: return false
        claimed()
        return true
    }
}

val LocalEscapeClaim = staticCompositionLocalOf { EscapeClaim() }

/** While [active], Esc anywhere in the window runs [onEscape] instead of going back. */
@Composable
fun EscapeClaimEffect(active: Boolean, onEscape: () -> Unit) {
    val escape = LocalEscapeClaim.current
    val latest = rememberUpdatedState(onEscape)
    DisposableEffect(escape, active) {
        val claim = { latest.value() }
        if (active) escape.claim = claim
        onDispose { if (escape.claim === claim) escape.claim = null }
    }
}
