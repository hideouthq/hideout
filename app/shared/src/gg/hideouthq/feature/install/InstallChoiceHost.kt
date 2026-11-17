package gg.hideouthq.feature.install

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.HorizontalAlignmentLine
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.VerticalAlignmentLine
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.dialog
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toRect
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import gg.hideouthq.core.data.CatalogRepository
import gg.hideouthq.core.designsystem.theme.HideoutDimens
import gg.hideouthq.core.designsystem.theme.HideoutMotion
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import gg.hideouthq.core.library.ChoiceId
import gg.hideouthq.core.library.ModLibrary
import gg.hideouthq.core.navigation.HideoutNavigator
import gg.hideouthq.core.ui.AppPopup
import gg.hideouthq.core.ui.LocalInstallOrigins
import gg.hideouthq.core.ui.ModalWindowControls
import gg.hideouthq.core.ui.WindowModal
import gg.hideouthq.feature.install.components.InstallDialogContent
import gg.hideouthq.feature.install.components.SkinSwapDialog
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.min
import kotlin.math.roundToInt

/** Test tags of the install dialog's moving parts, for rendered checks of its motion. */
object InstallDialogTags {
    const val Surface = "installDialogSurface"
    const val Content = "installDialogContent"
}

/**
 * While it is open or closing it takes every key and pointer, and [modal] tells the window's own shortcuts to stand aside.
 */
@Composable
fun InstallChoiceHost(library: ModLibrary, catalog: CatalogRepository, navigator: HideoutNavigator, modal: WindowModal) {
    val viewModel = viewModel { InstallChoiceViewModel(library, catalog) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val origins = LocalInstallOrigins.current
    val motion by rememberUpdatedState(HideoutTheme.motion)
    var shown by remember { mutableStateOf<InstallDialogState?>(null) }
    var ready by remember { mutableStateOf(false) }
    val progress = remember { Animatable(0f) }
    val fade = remember { Animatable(0f) }
    val scrim = remember { Animatable(0f) }

    LaunchedEffect(viewModel) {
        while (true) {
            val opened = viewModel.uiState.filterNotNull().first { !it.closing }
            progress.snapTo(0f)
            fade.snapTo(0f)
            scrim.snapTo(0f)
            shown = opened
            modal.hold(InstallDialogTags.Surface)
            val ended = coroutineScope {
                val entering = launch {
                    launch { scrim.animateTo(1f, motion.effectsDefault()) }
                    launch { fade.animateTo(1f, motion.effectsDefault()) }
                    progress.animateTo(1f, motion.spatialDefault())
                    ready = true
                }
                viewModel.uiState.first { it?.id != opened.id || it.closing }.also { entering.cancel() }
            }
            ready = false
            coroutineScope {
                launch { scrim.animateTo(0f, motion.effectsDefault()) }
                launch { fade.animateTo(0f, motion.exitFade()) }
                progress.animateTo(0f, motion.spatialDefault())
            }
            shown = null
            modal.release(InstallDialogTags.Surface)
            val preview = ended?.takeIf { it.id == opened.id }?.preview
            if (preview != null) {
                navigator.previewFile(preview.modId, preview.fileId, preview.fileMd5)
                snapshotFlow { navigator.settledEntryId == navigator.current.id }.first { it }
            } else {
                origins?.of(opened.modId)?.restoreFocus()
            }
            viewModel.finished(opened.id)
        }
    }

    val swap by viewModel.swap.collectAsStateWithLifecycle()
    var lastSwap by remember { mutableStateOf<ChoiceId?>(null) }
    swap?.let { lastSwap = it.id }
    key(lastSwap) {
        SkinSwapDialog(swap.takeIf { shown == null }, modal) { answer -> swap?.let { library.answer(it.id, answer) } }
    }

    val current = shown ?: return
    val dialog = state?.takeIf { it.id == current.id } ?: current
    val focus = remember { FocusRequester() }
    val root = remember { PopupRoot() }
    AppPopup(
        popupPositionProvider = WholeWindow,
        onDismissRequest = {},
        properties = PopupProperties(focusable = true, dismissOnBackPress = false, dismissOnClickOutside = false),
    ) {
        LaunchedEffect(dialog.id) { focus.requestFocus() }
        val dimScrim = HideoutTheme.colors.dialogScrim
        Box(
            Modifier
                .fillMaxSize()
                .onGloballyPositioned { root.coordinates = it }
                .drawBehind { drawRect(dimScrim.copy(alpha = dimScrim.alpha * scrim.value)) }
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown || dialog.closing || event.key != Key.Escape) return@onPreviewKeyEvent false
                    viewModel.cancel(dialog.id)
                    true
                }
                .onKeyEvent { event ->
                    val enter = event.key == Key.Enter || event.key == Key.NumPadEnter
                    if (enter && event.type == KeyEventType.KeyDown && ready) viewModel.confirm(dialog.id)
                    enter
                }
                .focusRequester(focus)
                .focusable(),
        ) {
            TransformedDialog(
                dialog = dialog,
                viewModel = viewModel,
                ready = ready,
                origin = { origins?.of(dialog.modId)?.bounds()?.let(root::fromWindow) },
                progress = { progress.value },
                fade = { fade.value },
                reduced = motion.reduced,
            )
            ModalWindowControls()
        }
    }
}

@Composable
private fun TransformedDialog(
    dialog: InstallDialogState,
    viewModel: InstallChoiceViewModel,
    ready: Boolean,
    origin: () -> Rect?,
    progress: () -> Float,
    fade: () -> Float,
    reduced: Boolean,
) {
    val dimens = HideoutDimens.InstallDialog
    val scheme = MaterialTheme.colorScheme
    val shadow = HideoutTheme.colors.dialogShadow
    val shape = RoundedCornerShape(dimens.Radius)
    val travels = remember(dialog.id) { origin() != null } && !reduced
    val rowsScroll = rememberScrollState()
    Layout(
        content = {
            Layout(
                content = {
                    Box(
                        Modifier
                            .testTag(InstallDialogTags.Content)
                            .semantics {
                                dialog()
                                paneTitle = dialog.header.title
                            }
                            .graphicsLayer { alpha = fade() },
                    ) {
                        InstallDialogContent(dialog, viewModel, ready, rowsScroll)
                    }
                },
                modifier = Modifier
                    .testTag(InstallDialogTags.Surface)
                    .graphicsLayer { alpha = if (travels) 1f else fade() }
                    .dropShadow(shape, Shadow(radius = dimens.ShadowBlur, color = shadow, offset = DpOffset(0.dp, dimens.ShadowOffsetY)))
                    .clip(shape)
                    .drawBehind { drawRect(lerp(scheme.primary, scheme.surfaceContainer, if (travels) (progress() * FillSpeed).coerceIn(0f, 1f) else 1f)) },
            ) { measurables, constraints ->
                val margin = dimens.WindowMargin.roundToPx()
                val width = min(dimens.Width.roundToPx(), constraints.maxWidth - 2 * margin).coerceAtLeast(0)
                val content = measurables.single().measure(Constraints(minWidth = width, maxWidth = width, maxHeight = (constraints.maxHeight - 2 * margin).coerceAtLeast(0)))
                val frame = dialogFrame(
                    window = IntSize(constraints.maxWidth, constraints.maxHeight),
                    content = IntSize(content.width, content.height),
                    from = if (travels) origin() else null,
                    progress = if (travels) progress() else fade(),
                    reduced = reduced,
                )
                val now = frame.now
                layout(now.width, now.height, mapOf(SurfaceLeft to now.left, SurfaceTop to now.top)) {
                    content.place(frame.target.topLeft - now.topLeft)
                }
            }
        },
    ) { measurables, constraints ->
        val surface = measurables.single().measure(constraints.copy(minWidth = 0, minHeight = 0))
        layout(constraints.maxWidth, constraints.maxHeight) { surface.place(surface[SurfaceLeft], surface[SurfaceTop]) }
    }
}

internal data class DialogFrame(val now: IntRect, val target: IntRect)

internal fun dialogFrame(window: IntSize, content: IntSize, from: Rect?, progress: Float, reduced: Boolean): DialogFrame {
    val target = IntRect(IntOffset((window.width - content.width) / 2, (window.height - content.height) / 2), content)
    val settled = target.toRect()
    val start = when {
        reduced -> settled
        from != null -> from
        else -> settled.scaledAboutCentre(HideoutMotion.PanelEnterScale)
    }
    val now = lerp(start, settled, progress).intersect(Rect(Offset.Zero, window.toSize()))
    val size = IntSize(now.width.roundToInt().coerceAtLeast(0), now.height.roundToInt().coerceAtLeast(0))
    return DialogFrame(IntRect(IntOffset(now.left.roundToInt(), now.top.roundToInt()), size), target)
}

private class PopupRoot {
    var coordinates: LayoutCoordinates? = null

    fun fromWindow(bounds: Rect): Rect {
        val at = coordinates?.takeIf { it.isAttached } ?: return bounds
        return Rect(at.windowToLocal(bounds.topLeft), bounds.size)
    }
}

private fun Rect.scaledAboutCentre(scale: Float): Rect {
    val halfWidth = width * scale / 2
    val halfHeight = height * scale / 2
    return Rect(center.x - halfWidth, center.y - halfHeight, center.x + halfWidth, center.y + halfHeight)
}

private object WholeWindow : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize) = IntOffset.Zero
}

private const val FillSpeed = 2f

private val SurfaceLeft = VerticalAlignmentLine(::min)
private val SurfaceTop = HorizontalAlignmentLine(::min)
