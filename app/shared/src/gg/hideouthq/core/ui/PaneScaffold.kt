package gg.hideouthq.core.ui

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import gg.hideouthq.core.designsystem.component.ScrollHairline
import gg.hideouthq.core.designsystem.component.TopBar
import gg.hideouthq.core.designsystem.theme.HideoutDimens
import gg.hideouthq.core.designsystem.theme.HideoutShapes
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import gg.hideouthq.core.ui.transition.LocalNavEntryKey
import gg.hideouthq.core.ui.transition.navBodyEnterExit
import gg.hideouthq.core.ui.transition.navEnterExit
import gg.hideouthq.core.ui.transition.navStayInPlace
import kotlin.math.roundToInt

/**
 * A screen's parts, placed into whatever [PaneFrame] it is shown in: a top bar and body in the
 * main pane, and an optional side panel. In the desktop shell the panes' backgrounds, positions and
 * the back/forward buttons belong to the frame and never move; only these parts change.
 *
 * @param scrolled draws the hairline under the top bar once the body has scrolled beneath it.
 * @param staggered the body's blocks cascade in themselves (with
 *   [gg.hideouthq.core.ui.transition.staggeredEnter]); otherwise the body fades in and rises as one.
 */
@Composable
fun PaneScaffold(
    topBar: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    scrolled: Boolean = false,
    staggered: Boolean = false,
    sidePanel: (@Composable () -> Unit)? = null,
    main: @Composable () -> Unit,
) {
    val frame = LocalPaneFrame.current
    val owner = LocalNavEntryKey.current
    val wantsSidePanel = sidePanel != null
    var lastSidePanel by remember { mutableStateOf(sidePanel) }
    if (sidePanel != null) lastSidePanel = sidePanel
    val panelShowing by remember(frame) { derivedStateOf { (frame?.sidePanelAlpha ?: 0f) > 0f } }
    val shownSidePanel = sidePanel ?: lastSidePanel?.takeIf { panelShowing }
    if (frame != null) {
        DisposableEffect(frame, owner, wantsSidePanel) {
            frame.requestSidePanel(owner, wantsSidePanel)
            onDispose { frame.release(owner) }
        }
    }
    val scrimShowing by remember(frame) { derivedStateOf { (frame?.scrimAlpha ?: 0f) > 0f } }
    val controls = frame != null && LocalWindowChrome.current != null
    val controlsWidth = HideoutDimens.WindowControls.Width
    val chipsInset = frame?.topBarEndInset ?: 0.dp
    val topBarInset = chipsInset + if (controls && (!wantsSidePanel || frame.overlay)) HideoutDimens.WindowControls.TopBarGap + controlsWidth else 0.dp
    val panelInset = if (controls) HideoutDimens.WindowControls.PanelEdgeInset + controlsWidth + HideoutDimens.WindowControls.HeaderGap else 0.dp
    Layout(
        modifier = modifier.fillMaxSize(),
        contents = listOf(
            {
                Column(Modifier.clip(HideoutShapes.Pane)) {
                    Box {
                        CompositionLocalProvider(LocalWindowControlsInset provides topBarInset) { topBar() }
                        ScrollHairline(scrolled, Modifier.align(Alignment.BottomCenter))
                    }
                    Box(Modifier.weight(1f).fillMaxWidth().navBodyEnterExit(rise = !staggered)) { main() }
                }
            },
            {
                if (frame != null && scrimShowing) OverlayScrim(frame)
            },
            {
                if (shownSidePanel != null) {
                    Box(
                        Modifier
                            .graphicsLayer { alpha = frame?.sidePanelAlpha ?: 1f }
                            .then(if (frame?.overlay == true) Modifier.floatingPanel(frame) else Modifier)
                            .clip(HideoutShapes.Pane)
                            .navBodyEnterExit(),
                    ) {
                        CompositionLocalProvider(LocalWindowControlsInset provides panelInset) { shownSidePanel() }
                    }
                }
            },
        ),
    ) { (mainParts, scrimParts, sideParts), constraints ->
        val gap = HideoutDimens.Window.PaneGap.roundToPx()
        val standaloneSide = HideoutDimens.SidePanel.Width.roundToPx()
        val sideWidth = frame?.sidePanelWidth?.roundToInt() ?: standaloneSide
        val mainWidth = constraints.maxWidth - if (wantsSidePanel && frame?.overlay != true) sideWidth + gap else 0
        val sideX = (if (isLookingAhead) frame?.settledSidePanelX else frame?.sidePanelX)?.roundToInt() ?: (mainWidth + gap)
        val height = constraints.maxHeight
        val mainPlaceables = mainParts.map { it.measure(Constraints.fixed(mainWidth.coerceAtLeast(0), height)) }
        val scrimPlaceables = scrimParts.map { it.measure(Constraints.fixed(mainWidth.coerceAtLeast(0), height)) }
        val sidePlaceables = sideParts.map { it.measure(Constraints.fixed(sideWidth, height)) }
        layout(constraints.maxWidth, height) {
            mainPlaceables.forEach { it.place(0, 0) }
            scrimPlaceables.forEach { it.place(0, 0) }
            sidePlaceables.forEach { it.place(sideX, 0) }
        }
    }
}

@Composable
private fun OverlayScrim(frame: PaneFrame) {
    val color = HideoutTheme.colors.overlayScrim
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = frame.scrimAlpha }
            .clip(HideoutShapes.Pane)
            .background(color)
            .pointerInput(frame) { detectTapGestures { frame.closeSidePanel() } },
    )
}

@Composable
private fun Modifier.floatingPanel(frame: PaneFrame): Modifier {
    val color = MaterialTheme.colorScheme.surface
    return background(color, HideoutShapes.Pane)
        .draggable(
            state = rememberDraggableState { delta -> frame.dragSidePanel(delta) },
            orientation = Orientation.Horizontal,
            onDragStopped = { velocity -> frame.settleSidePanel(velocity) },
        )
}

private val LocalWindowControlsInset = compositionLocalOf { 0.dp }

@Composable
fun windowControlsInset(): Dp = LocalWindowControlsInset.current

/**
 * The main pane's top bar for a screen. The frame's back and forward buttons sit at its start, so
 * [start] (a breadcrumb, for example) is placed after them. [center] and [end] stay in place across a
 * section switch, so only their content is seen to change. Its empty parts drag the window.
 */
@Composable
fun PaneTopBar(
    modifier: Modifier = Modifier,
    start: @Composable () -> Unit = {},
    center: @Composable () -> Unit = {},
    end: @Composable () -> Unit = {},
) {
    val inset: Dp = LocalPaneFrame.current?.topBarStartInset ?: 0.dp
    val motion = HideoutTheme.motion
    val endInset = LocalWindowControlsInset.current
    TopBar(
        modifier = modifier.windowDragArea(),
        start = {
            Row {
                Spacer(Modifier.width(inset))
                Box(Modifier.navEnterExit(fadeIn(motion.enterFade()), fadeOut(motion.exitFade()))) { start() }
            }
        },
        center = { Box(Modifier.navStayInPlace()) { center() } },
        end = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.navStayInPlace()) { end() }
                Spacer(Modifier.width(endInset))
            }
        },
    )
}
