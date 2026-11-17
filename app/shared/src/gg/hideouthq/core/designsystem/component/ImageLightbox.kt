package gg.hideouthq.core.designsystem.component

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import gg.hideouthq.core.designsystem.icon.HideoutIcons
import gg.hideouthq.core.designsystem.interaction.hideoutClickable
import gg.hideouthq.core.designsystem.theme.HideoutDimens
import gg.hideouthq.core.designsystem.theme.HideoutMotion
import gg.hideouthq.core.designsystem.theme.HideoutShapes
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import gg.hideouthq.core.ui.AppPopup
import gg.hideouthq.core.ui.WindowModal

/** What the viewer's controls say. [position] reads like "3 of 6". */
class LightboxLabels(val close: String, val previous: String, val next: String, val position: String)

/**
 * Pictures large, over the whole window: the current one fitted in the middle on a near-black
 * backdrop, with arrows at the sides, the position and a close button at the top and thumbnails
 * at the bottom. Esc, a click on the backdrop or the close button calls [onDismiss]; the arrow
 * keys and arrows call [onIndexChange]. [image] draws picture `index` fitted in its box;
 * [thumbnail] draws it cropped. While it is open it holds [modal].
 */
@Composable
fun ImageLightbox(
    count: Int,
    index: Int,
    onIndexChange: (Int) -> Unit,
    onDismiss: () -> Unit,
    labels: LightboxLabels,
    modal: WindowModal,
    image: @Composable BoxScope.(index: Int) -> Unit,
    thumbnail: @Composable BoxScope.(index: Int) -> Unit,
) {
    DisposableEffect(modal) {
        val holder = Any()
        modal.hold(holder)
        onDispose { modal.release(holder) }
    }
    val focus = remember { FocusRequester() }
    val appear = remember { MutableTransitionState(false).apply { targetState = true } }
    val motion = HideoutTheme.motion
    val colors = HideoutTheme.colors
    fun move(by: Int) {
        val target = index + by
        if (target in 0 until count) onIndexChange(target)
    }
    AppPopup(popupPositionProvider = WholeWindow, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        LaunchedEffect(Unit) { focus.requestFocus() }
        AnimatedVisibility(appear, enter = fadeIn(motion.effectsDefault())) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(colors.lightboxScrim)
                    .focusRequester(focus)
                    .focusable()
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (event.key) {
                            Key.Escape -> onDismiss()
                            Key.DirectionLeft -> move(-1)
                            Key.DirectionRight -> move(1)
                            else -> return@onPreviewKeyEvent false
                        }
                        true
                    }
                    .hideoutClickable(remember { MutableInteractionSource() }, role = null, onClick = onDismiss),
            ) {
                AnimatedContent(
                    targetState = index,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(
                            start = HideoutDimens.Lightbox.ImagePaddingHorizontal,
                            end = HideoutDimens.Lightbox.ImagePaddingHorizontal,
                            top = HideoutDimens.Lightbox.ImagePaddingTop,
                            bottom = HideoutDimens.Lightbox.ImagePaddingBottom,
                        )
                        .clickable(remember { MutableInteractionSource() }, indication = null, onClick = {}),
                    transitionSpec = {
                        val direction = if (targetState > initialState) 1 else -1
                        (fadeIn(motion.effectsDefault()) + slideInHorizontally(motion.spatialDefault()) { it / SlideFraction * direction } +
                            scaleIn(motion.spatialDefault(), initialScale = HideoutMotion.PanelEnterScale))
                            .togetherWith(fadeOut(motion.exitFade()))
                    },
                    label = "lightboxImage",
                ) { shown ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { image(shown) }
                }
                Text(
                    text = labels.position,
                    style = HideoutTheme.typography.mono,
                    color = colors.onImageVariant,
                    modifier = Modifier.align(Alignment.TopStart).padding(HideoutDimens.Lightbox.BarPadding),
                )
                ImageOverlayButton(true, HideoutIcons.Close, labels.close, onDismiss, Modifier.align(Alignment.TopEnd).padding(HideoutDimens.Lightbox.BarPadding))
                val arrow = HideoutDimens.Lightbox.ArrowButton
                val inset = HideoutDimens.Lightbox.ArrowInset
                ImageOverlayButton(index > 0, HideoutIcons.ChevronLeft, labels.previous, { move(-1) }, Modifier.align(Alignment.CenterStart).padding(start = inset), arrow)
                ImageOverlayButton(index < count - 1, HideoutIcons.ChevronRight, labels.next, { move(1) }, Modifier.align(Alignment.CenterEnd).padding(end = inset), arrow)
                if (count > 1) Thumbnails(count, index, onIndexChange, thumbnail, Modifier.align(Alignment.BottomCenter).padding(bottom = HideoutDimens.Lightbox.ThumbsBottom))
            }
        }
    }
}

@Composable
private fun Thumbnails(
    count: Int,
    selected: Int,
    onSelect: (Int) -> Unit,
    thumbnail: @Composable BoxScope.(index: Int) -> Unit,
    modifier: Modifier,
) {
    val list = rememberLazyListState()
    val motion = HideoutTheme.motion
    LaunchedEffect(selected) { if (motion.reduced) list.scrollToItem(selected) else list.animateScrollToItem(selected) }
    LazyRow(modifier, state = list, horizontalArrangement = Arrangement.spacedBy(HideoutDimens.Lightbox.ThumbGap)) {
        items(count, key = { it }) { index ->
            val current = index == selected
            Box(
                Modifier
                    .size(HideoutDimens.Lightbox.Thumb)
                    .clip(HideoutShapes.Thumb)
                    .then(if (current) Modifier.border(HideoutDimens.Lightbox.ThumbSelectedBorder, HideoutTheme.colors.onImage, HideoutShapes.Thumb) else Modifier)
                    .alpha(if (current) 1f else InactiveThumbAlpha)
                    .semantics { contentDescription = "${index + 1}" }
                    .hideoutClickable(remember { MutableInteractionSource() }) { onSelect(index) },
            ) { thumbnail(index) }
        }
    }
}

private object WholeWindow : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize) = IntOffset.Zero
}

private const val SlideFraction = 12
private const val InactiveThumbAlpha = 0.55f
