package gg.hideouthq.core.ui

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

@Stable
class SidePanelOverlay {
    var isOpen by mutableStateOf(false)
        private set

    fun open() {
        isOpen = true
    }

    /** Closes the panel; false if it was not open. */
    fun close(): Boolean {
        val wasOpen = isOpen
        isOpen = false
        return wasOpen
    }
}
