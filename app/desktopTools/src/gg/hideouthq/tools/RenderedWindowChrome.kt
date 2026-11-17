package gg.hideouthq.tools

import androidx.compose.ui.Modifier
import gg.hideouthq.core.ui.WindowChrome

internal object RenderedWindowChrome : WindowChrome {
    override val isMaximized: Boolean = false
    override val dragArea: Modifier = Modifier

    override fun minimize() = Unit

    override fun toggleMaximize() = Unit

    override fun close() = Unit
}
