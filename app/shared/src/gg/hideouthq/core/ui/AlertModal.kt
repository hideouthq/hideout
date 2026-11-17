package gg.hideouthq.core.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.dialog
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import gg.hideouthq.core.designsystem.theme.HideoutDimens
import gg.hideouthq.core.designsystem.theme.HideoutTheme

/**
 * An alert dialog over the whole window, sidebar included, while [visible]. The scrim fades in and the surface grows
 * from 96% as it fades in; closing reverses both, and [content] stays on screen until it has gone. While it is open or
 * closing it takes every key and pointer and holds [modal]. Esc calls [onDismiss]. [initialFocus] is focused once it
 * opens, so Enter and Space act on that control first.
 */
@Composable
fun AlertModal(
    visible: Boolean,
    modal: WindowModal,
    title: String,
    onDismiss: () -> Unit,
    initialFocus: FocusRequester,
    width: Dp = HideoutDimens.AlertDialog.Width,
    padding: Dp = HideoutDimens.AlertDialog.Padding,
    content: @Composable () -> Unit,
) {
    val state = remember { MutableTransitionState(false) }
    state.targetState = visible
    val showing = state.currentState || state.targetState
    DisposableEffect(showing) {
        if (showing) modal.hold(state)
        onDispose { modal.release(state) }
    }
    if (!showing) return
    val motion = HideoutTheme.motion
    val transition = rememberTransition(state, label = "alertModal")
    val scrim by transition.animateFloat({ motion.effectsDefault() }, label = "alertScrim") { if (it) 1f else 0f }
    val dim = HideoutTheme.colors.dialogScrim
    val shadow = HideoutTheme.colors.dialogShadow
    val dimens = HideoutDimens.AlertDialog
    AppPopup(
        popupPositionProvider = WholeWindow,
        onDismissRequest = {},
        properties = PopupProperties(focusable = true, dismissOnBackPress = false, dismissOnClickOutside = false),
    ) {
        Box(Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .drawBehind { drawRect(dim.copy(alpha = dim.alpha * scrim)) }
                    .onPreviewKeyEvent { event ->
                        val escape = event.key == Key.Escape && event.type == KeyEventType.KeyDown && visible
                        if (escape) onDismiss()
                        escape
                    }
                    .padding(dimens.WindowMargin),
                contentAlignment = Alignment.Center,
            ) {
                transition.AnimatedVisibility(
                    visible = { it },
                    enter = fadeIn(motion.effectsDefault()) + scaleIn(motion.spatialDefault(), initialScale = EnterScale),
                    exit = fadeOut(motion.exitFade()) + scaleOut(motion.spatialDefault(), targetScale = EnterScale),
                ) {
                    LaunchedEffect(Unit) { initialFocus.requestFocus() }
                    Box(
                        Modifier
                            .widthIn(max = width)
                            .fillMaxWidth()
                            .semantics {
                                dialog()
                                paneTitle = title
                            }
                            .dropShadow(Shape, Shadow(radius = dimens.ShadowBlur, color = shadow, offset = DpOffset(0.dp, dimens.ShadowOffsetY)))
                            .background(MaterialTheme.colorScheme.surfaceContainer, Shape)
                            .padding(padding),
                    ) {
                        content()
                    }
                }
            }
            ModalWindowControls()
        }
    }
}

private const val EnterScale = 0.96f

private val Shape = RoundedCornerShape(HideoutDimens.AlertDialog.Radius)

private object WholeWindow : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize) = IntOffset.Zero
}
