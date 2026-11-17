package gg.hideouthq.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned

@Stable
class InstallOrigins {
    private val claimed = mutableStateMapOf<String, InstallOrigin>()

    internal fun claim(modId: String, origin: InstallOrigin) {
        claimed[modId] = origin
    }

    internal fun release(modId: String, origin: InstallOrigin) {
        if (claimed[modId] === origin) claimed.remove(modId)
    }

    /** The button that asked to install [modId], while it is still on screen. */
    fun of(modId: String): InstallOrigin? = claimed[modId]?.takeIf { it.bounds() != null }
}

val LocalInstallOrigins = staticCompositionLocalOf<InstallOrigins?> { null }

/** One Install button: put [modifier] on it and call [claim] when it is clicked. */
@Stable
class InstallOrigin internal constructor(private val modId: String, private val origins: InstallOrigins?) {
    private var coordinates: LayoutCoordinates? = null
    private val focus = FocusRequester()

    val modifier: Modifier = Modifier
        .onGloballyPositioned { coordinates = it }
        .focusRequester(focus)

    fun claim() {
        origins?.claim(modId, this)
    }

    /** Where the button is in the window, or null once it has left the screen. */
    fun bounds(): Rect? = coordinates?.takeIf { it.isAttached }?.boundsInWindow()

    /** Gives focus back to the button; false when it is no longer there to take it. */
    fun restoreFocus(): Boolean = bounds() != null && runCatching { focus.requestFocus() }.getOrDefault(false)

    internal fun release() {
        origins?.release(modId, this)
    }
}

@Composable
fun rememberInstallOrigin(modId: String): InstallOrigin {
    val origins = LocalInstallOrigins.current
    val origin = remember(modId, origins) { InstallOrigin(modId, origins) }
    DisposableEffect(origin) { onDispose { origin.release() } }
    return origin
}
