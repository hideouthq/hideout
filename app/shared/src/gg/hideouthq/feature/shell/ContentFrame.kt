package gg.hideouthq.feature.shell

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import gg.hideouthq.core.designsystem.component.HideoutIconButton
import gg.hideouthq.core.designsystem.icon.HideoutIcons
import gg.hideouthq.core.designsystem.theme.HideoutDimens
import gg.hideouthq.core.designsystem.theme.HideoutMotion
import gg.hideouthq.core.designsystem.theme.HideoutRadius
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import gg.hideouthq.core.navigation.HideoutNavigator
import gg.hideouthq.core.navigation.Route
import gg.hideouthq.core.navigation.hasSidePanel
import gg.hideouthq.core.ui.LocalPaneFrame
import gg.hideouthq.core.ui.LocalWindowChrome
import gg.hideouthq.core.ui.PaneFrame
import gg.hideouthq.core.ui.SidePanelOverlay
import gg.hideouthq.core.ui.WindowChromeControls
import gg.hideouthq.core.ui.windowWidth
import gg.hideouthq.resources.Res
import gg.hideouthq.resources.topbar_back
import gg.hideouthq.resources.topbar_forward
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

@Composable
fun ContentFrame(
    navigator: HideoutNavigator,
    overlayState: SidePanelOverlay,
    modifier: Modifier = Modifier,
    chips: @Composable () -> Unit = {},
    content: @Composable BoxScope.() -> Unit,
) {
    val density = LocalDensity.current
    val motion = HideoutTheme.motion
    val current = navigator.current
    val scope = rememberCoroutineScope()
    val overlay = windowWidth() < HideoutDimens.Window.DockedSidePanelMinWidth
    val frame = remember(density, overlayState, scope) {
        ShellPaneFrame(density, overlayState, scope, startsWithSidePanel = current.route.hasSidePanel && !overlay)
    }
    val showForward = current.route !is Route.ModDetail
    frame.topBarStartInset = navButtonsWidth(showForward)
    frame.overlay = overlay

    LaunchedEffect(current.id, overlay) { overlayState.close() }
    val wanted = (frame.requests[current.id] ?: current.route.hasSidePanel) && (!overlay || overlayState.isOpen)
    frame.settledProgress = if (wanted) 1f else 0f
    LaunchedEffect(frame, wanted) {
        frame.progress.animateTo(if (wanted) 1f else 0f, if (wanted) motion.paneSpatial() else motion.paneSpatialExit())
    }
    LaunchedEffect(frame, wanted, overlay) {
        frame.scrim.animateTo(if (wanted && overlay) 1f else 0f, motion.effectsSlow())
    }

    val paneColor = MaterialTheme.colorScheme.surface
    Box(
        modifier = modifier
            .fillMaxSize()
            .clipToBounds()
            .onSizeChanged { frame.containerWidth = it.width.toFloat() }
            .drawBehind {
                val radius = CornerRadius(HideoutRadius.Pane.toPx())
                drawRoundRect(paneColor, size = Size(frame.mainWidth, size.height), cornerRadius = radius)
                if (!frame.overlay && frame.sidePanelAlpha > 0f) {
                    drawRoundRect(
                        color = paneColor,
                        topLeft = Offset(frame.sidePanelX, 0f),
                        size = Size(frame.sidePanelWidth, size.height),
                        cornerRadius = radius,
                        alpha = frame.sidePanelAlpha,
                    )
                }
            },
    ) {
        CompositionLocalProvider(LocalPaneFrame provides frame) {
            content()
        }
        NavButtons(navigator, showForward = showForward)
        val chrome = LocalWindowChrome.current != null
        val controlsWidth = if (chrome) HideoutDimens.WindowControls.Width + HideoutDimens.WindowControls.TopBarGap else 0.dp
        fun controlsShown() = if (frame.overlay) 1f else 1f - frame.progress.value.coerceIn(0f, 1f)
        TopBarChips(
            room = {
                val bar = HideoutDimens.TopBar
                frame.mainWidth - (bar.HorizontalPadding * 2 + frame.topBarStartInset + bar.SlotGap * 2 + HideoutDimens.Search.MinWidth + HideoutDimens.WindowControls.TopBarGap).toPx() -
                    controlsWidth.toPx() * controlsShown()
            },
            modifier = Modifier
                .height(HideoutDimens.TopBar.Height)
                .onSizeChanged { frame.chipsWidth = it.width }
                .offset {
                    val x = frame.mainWidth - HideoutDimens.TopBar.HorizontalPadding.toPx() - controlsWidth.toPx() * controlsShown() - frame.chipsWidth
                    IntOffset(x.roundToInt(), 0)
                },
            chips = chips,
        )
        FrameWindowControls(
            Modifier
                .align(Alignment.TopEnd)
                .padding(top = (HideoutDimens.TopBar.Height - HideoutDimens.WindowControls.Button) / 2)
                .offset {
                    val inset = lerp(HideoutDimens.TopBar.HorizontalPadding, HideoutDimens.WindowControls.PanelEdgeInset, frame.progress.value.coerceIn(0f, 1f))
                    IntOffset(-inset.roundToPx(), 0)
                },
        )
    }
}

/** Marks a top bar chip that shows only its icon when its label would leave the search field less than [HideoutDimens.Search.MinWidth]. */
internal object CollapsibleChip

/** The chips in a row; every [CollapsibleChip] gets what [room] leaves after the others, so the top bar's search keeps its least width. */
@Composable
private fun TopBarChips(room: Density.() -> Float, modifier: Modifier, chips: @Composable () -> Unit) {
    Layout(chips, modifier) { measurables, constraints ->
        val gap = HideoutDimens.TopBarChip.Gap.roundToPx()
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val fixed = measurables.filter { it.layoutId != CollapsibleChip }.associateWith { it.measure(loose) }
        var left = room().roundToInt() - fixed.values.sumOf { it.width } - gap * (measurables.size - 1).coerceAtLeast(0)
        val placeables = measurables.map { measurable ->
            fixed[measurable] ?: measurable.measure(loose.copy(maxWidth = left.coerceIn(HideoutDimens.TopBarChip.Height.roundToPx(), loose.maxWidth))).also { left -= it.width }
        }
        val width = placeables.sumOf { it.width } + gap * (placeables.size - 1).coerceAtLeast(0)
        layout(width, constraints.maxHeight) {
            var x = 0
            placeables.forEach {
                it.placeRelative(x, (constraints.maxHeight - it.height) / 2)
                x += it.width + gap
            }
        }
    }
}

@Composable
internal fun FrameWindowControls(modifier: Modifier) = WindowChromeControls(modifier)

@Composable
private fun NavButtons(navigator: HideoutNavigator, showForward: Boolean) {
    Row(
        modifier = Modifier
            .height(HideoutDimens.TopBar.Height)
            .padding(start = HideoutDimens.TopBar.HorizontalPadding),
        horizontalArrangement = Arrangement.spacedBy(HideoutDimens.TopBar.NavButtonGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HideoutIconButton(
            icon = HideoutIcons.Back,
            contentDescription = stringResource(Res.string.topbar_back),
            onClick = { navigator.back() },
            enabled = navigator.canGoBack,
        )
        if (showForward) {
            HideoutIconButton(
                icon = HideoutIcons.Forward,
                contentDescription = stringResource(Res.string.topbar_forward),
                onClick = { navigator.forward() },
                enabled = navigator.canGoForward,
            )
        }
    }
}

private fun navButtonsWidth(showForward: Boolean): Dp {
    val one = HideoutDimens.IconButton.Size
    return if (showForward) one * 2 + HideoutDimens.TopBar.NavButtonGap else one + HideoutDimens.TopBar.BreadcrumbGap
}

@Stable
private class ShellPaneFrame(
    private val density: Density,
    private val overlayState: SidePanelOverlay,
    private val scope: CoroutineScope,
    startsWithSidePanel: Boolean,
) : PaneFrame {
    val progress = Animatable(if (startsWithSidePanel) 1f else 0f)
    var settledProgress by mutableFloatStateOf(progress.value)
    val scrim = Animatable(0f)
    val requests = mutableStateMapOf<Any, Boolean>()
    var containerWidth by mutableFloatStateOf(0f)
    override var topBarStartInset: Dp = HideoutDimens.IconButton.Size
    var chipsWidth by mutableIntStateOf(0)
    override val topBarEndInset: Dp
        get() = if (chipsWidth == 0) 0.dp else with(density) { chipsWidth.toDp() } + HideoutDimens.WindowControls.TopBarGap
    override var overlay by mutableStateOf(false)

    private val gap get() = with(density) { HideoutDimens.Window.PaneGap.toPx() }
    private val enterOffset get() = with(density) { HideoutDimens.SidePanel.EnterOffset.toPx() }

    override val sidePanelWidth: Float get() = with(density) { HideoutDimens.SidePanel.Width.toPx() }
    override val mainWidth: Float get() = mainWidthAt(progress.value)
    override val sidePanelX: Float get() = sidePanelXAt(progress.value)
    override val settledSidePanelX: Float get() = sidePanelXAt(settledProgress)

    private fun mainWidthAt(progress: Float): Float =
        if (overlay) containerWidth else containerWidth - progress * (sidePanelWidth + gap)

    private fun sidePanelXAt(progress: Float): Float =
        if (overlay) containerWidth - progress * sidePanelWidth else mainWidthAt(progress) + gap + (1f - progress) * enterOffset
    override val sidePanelAlpha: Float
        get() = if (overlay) (if (progress.value > 0f) 1f else 0f) else progress.value.coerceIn(0f, 1f)
    override val scrimAlpha: Float get() = if (overlay) minOf(scrim.value, progress.value).coerceIn(0f, 1f) else 0f

    override fun requestSidePanel(owner: Any, wanted: Boolean) {
        requests[owner] = wanted
    }

    override fun release(owner: Any) {
        requests.remove(owner)
    }

    override fun openSidePanel() {
        if (overlay) overlayState.open()
    }

    override fun closeSidePanel() {
        overlayState.close()
    }

    override fun dragSidePanel(deltaPx: Float) {
        if (!overlay || !overlayState.isOpen) return
        scope.launch { progress.snapTo((progress.value - deltaPx / sidePanelWidth).coerceIn(0f, 1f)) }
    }

    override fun settleSidePanel(velocityPx: Float) {
        if (!overlay || !overlayState.isOpen) return
        val flung = with(density) { velocityPx > HideoutDimens.SidePanel.DismissVelocity.toPx() }
        if (flung || progress.value < DismissFraction) {
            overlayState.close()
        } else {
            scope.launch { progress.animateTo(1f, spring(stiffness = HideoutMotion.PaneStiffness)) }
        }
    }

    private companion object {
        const val DismissFraction = 0.66f
    }
}
