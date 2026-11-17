package gg.hideouthq.core.ui

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Whether a modal covers the whole window, from the moment it starts to open until it has fully closed. The window
 * sees every key before the modal does, so its own shortcuts stand aside meanwhile: nothing acts on the page behind.
 * Several modals can hold it at once, such as a row menu and the install dialog; it stays open until all let go.
 */
@Stable
class WindowModal {
    private var holders by mutableStateOf(emptySet<Any>())

    val isOpen: Boolean get() = holders.isNotEmpty()

    fun hold(holder: Any) {
        holders = holders + holder
    }

    fun release(holder: Any) {
        holders = holders - holder
    }
}
