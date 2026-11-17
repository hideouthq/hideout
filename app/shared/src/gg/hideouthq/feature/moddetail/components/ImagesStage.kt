package gg.hideouthq.feature.moddetail.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import gg.hideouthq.core.designsystem.component.CoverArt
import gg.hideouthq.core.designsystem.component.HideoutIcon
import gg.hideouthq.core.designsystem.component.ImageOverlayButton
import gg.hideouthq.core.designsystem.icon.HideoutIcons
import gg.hideouthq.core.designsystem.interaction.collectInteractionStates
import gg.hideouthq.core.designsystem.interaction.focusRing
import gg.hideouthq.core.designsystem.interaction.hideoutClickable
import gg.hideouthq.core.designsystem.interaction.hideoutSelectable
import gg.hideouthq.core.designsystem.theme.HideoutShapes
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import gg.hideouthq.core.model.CoverImage
import gg.hideouthq.core.ui.ModLabels
import gg.hideouthq.resources.Res
import gg.hideouthq.resources.carousel_next
import gg.hideouthq.resources.carousel_previous
import gg.hideouthq.resources.detail_image
import gg.hideouthq.resources.images_full_size
import gg.hideouthq.resources.images_position
import org.jetbrains.compose.resources.stringResource
import gg.hideouthq.core.designsystem.theme.HideoutDimens.ImagesStage as Gallery

@Composable
internal fun ImagesStage(modId: String, images: List<CoverImage>, height: Dp, onOpen: (Int) -> Unit, modifier: Modifier = Modifier) {
    var selected by rememberSaveable(modId) { mutableIntStateOf(0) }
    val count = images.size
    val current = selected.coerceIn(0, (count - 1).coerceAtLeast(0))
    fun go(index: Int) {
        selected = index.coerceIn(0, (count - 1).coerceAtLeast(0))
    }
    val colors = HideoutTheme.colors
    val source = remember { MutableInteractionSource() }
    val states = source.collectInteractionStates()
    Column(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(HideoutShapes.Stage)
            .background(colors.stage)
            .focusRing(states.focusVisible, HideoutShapes.Stage)
            .focusable(interactionSource = source)
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.DirectionLeft -> go(current - 1)
                    Key.DirectionRight -> go(current + 1)
                    Key.Enter -> onOpen(current)
                    else -> return@onKeyEvent false
                }
                true
            }
            .padding(Gallery.Padding),
        verticalArrangement = Arrangement.spacedBy(Gallery.Gap),
    ) {
        val shape = RoundedCornerShape(Gallery.Radius)
        Box(Modifier.weight(1f).fillMaxWidth().clip(shape)) {
            val motion = HideoutTheme.motion
            AnimatedContent(current, transitionSpec = { fadeIn(motion.effectsDefault()) togetherWith fadeOut(motion.effectsDefault()) }, label = "galleryImage") { index ->
                val picture = remember { MutableInteractionSource() }
                val description = stringResource(Res.string.detail_image, index + 1, count)
                CoverArt(
                    seed = "$modId-image-$index",
                    image = images[index],
                    modifier = Modifier
                        .fillMaxSize()
                        .semantics { contentDescription = description }
                        .hideoutClickable(picture, onClick = { onOpen(index) }),
                    tintIndex = ModLabels.tint(images[index].tone),
                )
            }
            if (count > 1) {
                ImageOverlayButton(true, HideoutIcons.ChevronLeft, stringResource(Res.string.carousel_previous), { go(current - 1) }, Modifier.align(Alignment.CenterStart).padding(start = Gallery.CornerInset), Gallery.NavButton)
                ImageOverlayButton(true, HideoutIcons.ChevronRight, stringResource(Res.string.carousel_next), { go(current + 1) }, Modifier.align(Alignment.CenterEnd).padding(end = Gallery.CornerInset), Gallery.NavButton)
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(Gallery.CornerInset)
                        .height(Gallery.CountHeight)
                        .background(colors.glass, HideoutShapes.Full)
                        .padding(horizontal = Gallery.CountHorizontal),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(stringResource(Res.string.images_position, current + 1, count), style = HideoutTheme.typography.labelSection, color = colors.onImage)
                }
            }
            FullSizeButton(stringResource(Res.string.images_full_size), { onOpen(current) }, Modifier.align(Alignment.BottomEnd).padding(Gallery.CornerInset))
        }
        if (count > 1) Thumbnails(modId, images, current, ::go)
    }
}

@Composable
private fun FullSizeButton(label: String, onClick: () -> Unit, modifier: Modifier) {
    val colors = HideoutTheme.colors
    val source = remember { MutableInteractionSource() }
    val states = source.collectInteractionStates()
    Row(
        modifier
            .height(Gallery.FullSizeHeight)
            .background(if (states.hovered) colors.imageInsetHover else colors.glass, HideoutShapes.Full)
            .focusRing(states.focusVisible, HideoutShapes.Full)
            .hideoutClickable(source, role = Role.Button, onClick = onClick)
            .padding(PaddingValues(start = Gallery.FullSizeStart, end = Gallery.FullSizeEnd)),
        horizontalArrangement = Arrangement.spacedBy(Gallery.FullSizeGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HideoutIcon(HideoutIcons.Fullscreen, contentDescription = null, size = Gallery.FullSizeIcon, tint = colors.onImage)
        Text(label, style = HideoutTheme.typography.labelChip, color = colors.onImage)
    }
}

@Composable
private fun Thumbnails(modId: String, images: List<CoverImage>, selected: Int, onPick: (Int) -> Unit) {
    val list = rememberLazyListState()
    LaunchedEffect(selected) {
        val visible = list.layoutInfo.visibleItemsInfo
        val fullyShown = visible.any { it.index == selected && it.offset >= 0 && it.offset + it.size <= list.layoutInfo.viewportEndOffset }
        if (!fullyShown) list.animateScrollToItem(selected)
    }
    val ring = HideoutTheme.colors.stage
    val primary = MaterialTheme.colorScheme.primary
    LazyRow(
        state = list,
        modifier = Modifier.fillMaxWidth().height(Gallery.ThumbHeight + Gallery.StripTop + Gallery.ThumbRing * 2),
        contentPadding = PaddingValues(top = Gallery.StripTop, start = Gallery.ThumbRing * 2, end = Gallery.ThumbRing * 2),
        horizontalArrangement = Arrangement.spacedBy(Gallery.ThumbGap),
    ) {
        itemsIndexed(images) { index, image ->
            val source = remember { MutableInteractionSource() }
            val states = source.collectInteractionStates()
            val chosen = index == selected
            val shape = RoundedCornerShape(Gallery.ThumbRadius)
            val description = stringResource(Res.string.detail_image, index + 1, images.size)
            Box(
                Modifier
                    .size(Gallery.ThumbWidth, Gallery.ThumbHeight)
                    .drawBehind {
                        if (!chosen) return@drawBehind
                        val gap = Gallery.ThumbRingGap.toPx()
                        val width = Gallery.ThumbRing.toPx()
                        val radius = Gallery.ThumbRadius.toPx()
                        drawRoundRect(ring, Offset(-gap / 2, -gap / 2), Size(size.width + gap, size.height + gap), CornerRadius(radius + gap / 2), style = Stroke(gap))
                        val reach = gap + width / 2
                        drawRoundRect(primary, Offset(-reach, -reach), Size(size.width + 2 * reach, size.height + 2 * reach), CornerRadius(radius + reach), style = Stroke(width))
                    }
                    .focusRing(states.focusVisible, shape)
                    .hideoutSelectable(chosen, source, role = Role.Tab, onClick = { onPick(index) })
                    .semantics { contentDescription = description }
                    .alpha(if (chosen) 1f else if (states.hovered) Gallery.ThumbHoverAlpha else Gallery.ThumbIdleAlpha)
                    .clip(shape),
            ) {
                CoverArt(seed = "$modId-image-$index", modifier = Modifier.fillMaxSize(), imageUrl = image.url, tintIndex = ModLabels.tint(image.tone))
            }
        }
    }
}
