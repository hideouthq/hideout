package gg.hideouthq.core.ui

import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp

/**
 * The frame a screen is placed into. The desktop shell provides one with a main pane and an
 * optional side panel whose geometry it animates; a screen declares its parts with [PaneScaffold]
 * and never touches the shell. Values are in pixels relative to the frame and are read at layout
 * and draw time, so animating them never recomposes a screen.
 */
@Stable
interface PaneFrame {
    val mainWidth: Float
    val sidePanelX: Float

    /**
     * [sidePanelX] once the side panel has arrived where it is heading. Screens use this in the
     * lookahead pass so shared elements aim at the panel's destination rather than chasing it.
     */
    val settledSidePanelX: Float
    val sidePanelWidth: Float
    val sidePanelAlpha: Float

    /** Room the frame's own controls (back and forward) take at the start of the top bar. */
    val topBarStartInset: Dp

    /** Room the frame's top-bar chips take at the end of the main top bar, before the window controls. */
    val topBarEndInset: Dp

    /**
     * True on narrow windows, where the side panel floats over the main pane (opened on demand,
     * behind a scrim) instead of sitting beside it. The panel then draws its own background.
     */
    val overlay: Boolean

    /** How much of the scrim behind a floating side panel shows (0..1). */
    val scrimAlpha: Float

    fun requestSidePanel(owner: Any, wanted: Boolean)

    fun release(owner: Any)

    /** Opens a floating side panel (the "Filters" button, picking a row); docked panels are always shown. */
    fun openSidePanel()

    fun closeSidePanel()

    /** Follows a drag on a floating side panel ([deltaPx] to the right is positive). */
    fun dragSidePanel(deltaPx: Float)

    /** The drag ended: the panel closes if it was dragged far or fast enough, otherwise springs back. */
    fun settleSidePanel(velocityPx: Float)
}

val LocalPaneFrame = staticCompositionLocalOf<PaneFrame?> { null }
