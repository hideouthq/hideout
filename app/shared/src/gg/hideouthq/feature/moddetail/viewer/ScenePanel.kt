package gg.hideouthq.feature.moddetail.viewer

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import gg.hideouthq.core.designsystem.component.ButtonSize
import gg.hideouthq.core.designsystem.component.ButtonVariant
import gg.hideouthq.core.designsystem.component.HideoutButton
import gg.hideouthq.core.designsystem.component.HideoutIcon
import gg.hideouthq.core.designsystem.component.HideoutSwitch
import gg.hideouthq.core.designsystem.component.SegmentedControl
import gg.hideouthq.core.designsystem.icon.HideoutIcons
import gg.hideouthq.core.designsystem.interaction.collectInteractionStates
import gg.hideouthq.core.designsystem.interaction.focusRing
import gg.hideouthq.core.designsystem.interaction.hideoutSelectable
import gg.hideouthq.core.designsystem.theme.HideoutMotion
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import gg.hideouthq.core.model.Backdrop
import gg.hideouthq.core.model.SceneLighting
import gg.hideouthq.core.model.SceneSettings
import gg.hideouthq.core.ui.AppPopup
import gg.hideouthq.resources.Res
import gg.hideouthq.resources.backdrop_greenech
import gg.hideouthq.resources.backdrop_greenech_thumb
import gg.hideouthq.resources.backdrop_broadway
import gg.hideouthq.resources.backdrop_broadway_thumb
import gg.hideouthq.resources.backdrop_york
import gg.hideouthq.resources.backdrop_york_thumb
import org.jetbrains.compose.resources.imageResource
import gg.hideouthq.core.designsystem.theme.HideoutDimens.Scene as Panel

@Composable
internal fun ScenePanel(
    settings: SceneSettings,
    labels: SceneLabels,
    customBackdrop: ImageBitmap?,
    onChange: (SceneSettings) -> Unit,
    onChooseImage: () -> Unit,
    onClose: () -> Unit,
) {
    val density = LocalDensity.current
    val placement = remember(density) { with(density) { StageCorner(Panel.Top.roundToPx(), Panel.EndOverhang.roundToPx()) } }
    AppPopup(popupPositionProvider = placement, onDismissRequest = onClose, properties = PopupProperties(focusable = true)) {
        val motion = HideoutTheme.motion
        val shown = remember { MutableTransitionState(false).apply { targetState = true } }
        val first = remember { FocusRequester() }
        LaunchedEffect(Unit) { first.requestFocus() }
        AnimatedVisibility(shown, enter = fadeIn(motion.effectsDefault()) + scaleIn(motion.spatialFast(), HideoutMotion.EnterScaleModel, TransformOrigin(1f, 0f))) {
            val shape = RoundedCornerShape(Panel.Radius)
            Column(
                Modifier
                    .width(Panel.Width)
                    .dropShadow(shape, Shadow(radius = Panel.ShadowBlur, color = HideoutTheme.colors.dialogShadow, offset = DpOffset(0.dp, Panel.ShadowOffsetY)))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh, shape)
                    .padding(Panel.Padding)
                    .semantics { paneTitle = labels.title }
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                            onClose()
                            true
                        } else {
                            false
                        }
                    },
                verticalArrangement = Arrangement.spacedBy(Panel.Gap),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(start = Panel.HeaderStart, end = Panel.HeaderEnd, bottom = Panel.HeaderBottom),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(labels.title, Modifier.weight(1f).semantics { heading() }, style = HideoutTheme.typography.titlePanel, color = MaterialTheme.colorScheme.onSurface)
                    HideoutButton(
                        labels.reset,
                        { onChange(settings.reset()) },
                        Modifier.height(Panel.ResetHeight),
                        variant = ButtonVariant.Text,
                        size = ButtonSize.Small,
                        contentPadding = PaddingValues(horizontal = Panel.ResetHorizontal),
                    )
                }
                SectionTitle(labels.backdrop)
                BackdropGrid(settings, labels, customBackdrop, first, { onChange(settings.copy(backdrop = it)) }, onChooseImage)
                SectionTitle(labels.lighting)
                SegmentedControl(
                    options = SceneLighting.entries,
                    selected = settings.lighting,
                    onSelect = { onChange(settings.copy(lighting = it)) },
                    label = { labels.lightings.getValue(it) },
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = labels.lighting },
                )
                SectionTitle(labels.stage)
                SceneSwitch(labels.turntable, settings.turntable) { onChange(settings.copy(turntable = it)) }
                SceneSwitch(labels.bloom, settings.bloom) { onChange(settings.copy(bloom = it)) }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        Modifier.padding(start = Panel.SectionHorizontal, end = Panel.SectionHorizontal, top = Panel.SectionTop, bottom = Panel.SectionBottom).semantics { heading() },
        style = HideoutTheme.typography.labelSection,
        color = MaterialTheme.colorScheme.outline,
    )
}

@Composable
private fun BackdropGrid(
    settings: SceneSettings,
    labels: SceneLabels,
    customBackdrop: ImageBitmap?,
    first: FocusRequester,
    onPick: (Backdrop) -> Unit,
    onChooseImage: () -> Unit,
) {
    val selected = settings.shownBackdrop
    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(Panel.TileGap)) {
        Backdrop.entries.chunked(Columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(Panel.TileGap)) {
                row.forEach { backdrop ->
                    val custom = backdrop == Backdrop.Custom
                    BackdropTile(
                        label = labels.backdrops.getValue(backdrop),
                        selected = backdrop == selected,
                        onClick = if (custom) onChooseImage else ({ onPick(backdrop) }),
                        modifier = Modifier.weight(1f).then(if (backdrop == selected) Modifier.focusRequester(first) else Modifier),
                    ) {
                        BackdropThumbnail(backdrop, customBackdrop.takeIf { custom && settings.customBackdrop != null })
                    }
                }
            }
        }
    }
}

@Composable
private fun BackdropTile(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier, thumbnail: @Composable () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val source = remember { MutableInteractionSource() }
    val states = source.collectInteractionStates()
    val shape = RoundedCornerShape(Panel.TileRadius)
    val ring = scheme.primary
    Column(
        modifier
            .focusRing(states.focusVisible, shape)
            .hideoutSelectable(selected, source, role = Role.RadioButton, onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(Panel.TileLabelGap),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(Panel.TileHeight)
                .drawBehind {
                    if (!selected) return@drawBehind
                    val reach = (Panel.TileRingGap + Panel.TileRing / 2).toPx()
                    val radius = (Panel.TileRadius + Panel.TileRingGap + Panel.TileRing / 2).toPx()
                    drawRoundRect(ring, Offset(-reach, -reach), Size(size.width + 2 * reach, size.height + 2 * reach), CornerRadius(radius), style = Stroke(Panel.TileRing.toPx()))
                }
                .clip(shape),
        ) { thumbnail() }
        Text(
            label,
            Modifier.padding(start = Panel.TileLabelStart),
            style = HideoutTheme.typography.labelSection,
            color = if (selected) scheme.onSurface else scheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun BackdropThumbnail(backdrop: Backdrop, custom: ImageBitmap?) {
    val colors = HideoutTheme.colors
    val scheme = MaterialTheme.colorScheme
    val photo = if (backdrop == Backdrop.Custom) custom else backdropPhoto(backdrop, thumbnail = true)
    val edge = Color.White.copy(alpha = Panel.TileEdgeAlpha)
    val outline = scheme.surfaceContainerHighest
    Box(
        Modifier
            .fillMaxWidth()
            .height(Panel.TileHeight)
            .drawBehind {
                when {
                    photo != null -> drawCover(photo)
                    backdrop == Backdrop.Studio -> drawBackdrop(Scenery(SceneSettings(), null, colors, scheme.primary, scheme.secondary, outline, scheme.outlineVariant))
                }
                val radius = CornerRadius(Panel.TileRadius.toPx())
                if (backdrop == Backdrop.Custom && photo == null) {
                    val inset = Panel.AddOutline.toPx() / 2
                    drawRoundRect(outline, Offset(inset, inset), Size(size.width - 2 * inset, size.height - 2 * inset), radius, style = Stroke(Panel.AddOutline.toPx()))
                } else {
                    val inset = Panel.TileEdge.toPx() / 2
                    drawRoundRect(edge, Offset(inset, inset), Size(size.width - 2 * inset, size.height - 2 * inset), radius, style = Stroke(Panel.TileEdge.toPx()))
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (backdrop == Backdrop.Custom && photo == null) HideoutIcon(HideoutIcons.Plus, contentDescription = null, size = Panel.AddIcon, tint = scheme.onSurfaceVariant)
    }
}

@Composable
private fun SceneSwitch(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = Panel.RowMinHeight)
            .padding(horizontal = Panel.RowHorizontal),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f), style = HideoutTheme.typography.labelMenuItem, color = MaterialTheme.colorScheme.onSurface)
        HideoutSwitch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            contentDescription = label,
            modifier = Modifier.graphicsLayer {
                scaleX = Panel.SwitchScale
                scaleY = Panel.SwitchScale
                transformOrigin = TransformOrigin(1f, 0.5f)
            },
        )
    }
}

@Composable
internal fun backdropPhoto(backdrop: Backdrop, thumbnail: Boolean = false): ImageBitmap? = when (backdrop) {
    Backdrop.Greenech -> imageResource(if (thumbnail) Res.drawable.backdrop_greenech_thumb else Res.drawable.backdrop_greenech)
    Backdrop.Broadway -> imageResource(if (thumbnail) Res.drawable.backdrop_broadway_thumb else Res.drawable.backdrop_broadway)
    Backdrop.York -> imageResource(if (thumbnail) Res.drawable.backdrop_york_thumb else Res.drawable.backdrop_york)
    Backdrop.Studio, Backdrop.Custom -> null
}

private class StageCorner(private val top: Int, private val overhang: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize) = IntOffset(
        (anchorBounds.right + overhang - popupContentSize.width).coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)),
        (anchorBounds.top + top).coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0)),
    )
}

private const val Columns = 3
