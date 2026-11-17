package gg.hideouthq.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf

@Stable
class PlaybackShortcut {
    private val handlers = mutableListOf<() -> Unit>()

    fun toggle(): Boolean {
        val handler = handlers.lastOrNull() ?: return false
        handler()
        return true
    }

    internal fun register(handler: () -> Unit) {
        handlers.add(handler)
    }

    internal fun unregister(handler: () -> Unit) {
        handlers.remove(handler)
    }
}

val LocalPlaybackShortcut = staticCompositionLocalOf { PlaybackShortcut() }

@Composable
fun PlaybackShortcutEffect(enabled: Boolean, onToggle: () -> Unit) {
    val shortcut = LocalPlaybackShortcut.current
    val latest = rememberUpdatedState(onToggle)
    DisposableEffect(shortcut, enabled) {
        val handler = { latest.value() }
        if (enabled) shortcut.register(handler)
        onDispose { shortcut.unregister(handler) }
    }
}
