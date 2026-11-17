package gg.hideouthq.feature.settings.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import gg.hideouthq.core.designsystem.component.HideoutIcon
import gg.hideouthq.core.designsystem.icon.HideoutIcons
import gg.hideouthq.core.designsystem.interaction.collectInteractionStates
import gg.hideouthq.core.designsystem.interaction.focusRing
import gg.hideouthq.core.designsystem.interaction.hideoutSelectable
import gg.hideouthq.core.designsystem.interaction.insetRing
import gg.hideouthq.core.designsystem.theme.HideoutDimens
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import gg.hideouthq.core.designsystem.theme.HideoutWeights
import gg.hideouthq.core.designsystem.theme.palette
import gg.hideouthq.core.model.Accent
import gg.hideouthq.core.model.ModCardStyle
import gg.hideouthq.resources.Res
import gg.hideouthq.resources.accent_ember
import gg.hideouthq.resources.accent_graphite
import gg.hideouthq.resources.accent_ocean
import gg.hideouthq.resources.accent_rose
import gg.hideouthq.resources.accent_soul
import gg.hideouthq.resources.accent_violet
import gg.hideouthq.resources.mod_cards_on_image
import gg.hideouthq.resources.mod_cards_on_image_detail
import gg.hideouthq.resources.mod_cards_under_image
import gg.hideouthq.resources.mod_cards_under_image_detail
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun AccentPicker(selected: Accent, onPick: (Accent) -> Unit, label: String, modifier: Modifier = Modifier) {
    val accents = Accent.entries
    val focus = remember { accents.map { FocusRequester() } }
    FlowRow(
        modifier.semantics { contentDescription = label }.selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(HideoutDimens.Settings.SwatchGap),
        verticalArrangement = Arrangement.spacedBy(HideoutDimens.Settings.SwatchGap),
    ) {
        accents.forEachIndexed { index, accent ->
            Swatch(
                accent, accent == selected, { onPick(accent) },
                Modifier.focusRequester(focus[index]).onKeyEvent { event ->
                    val step = when (event.key.takeIf { event.type == KeyEventType.KeyDown }) {
                        Key.DirectionRight, Key.DirectionDown -> 1
                        Key.DirectionLeft, Key.DirectionUp -> -1
                        else -> return@onKeyEvent false
                    }
                    val next = (index + step).mod(accents.size)
                    focus[next].requestFocus()
                    onPick(accents[next])
                    true
                },
            )
        }
    }
}

@Composable
private fun Swatch(accent: Accent, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    val motion = HideoutTheme.motion
    val source = remember { MutableInteractionSource() }
    val states = source.collectInteractionStates()
    val palette = accent.palette
    val shape = RoundedCornerShape(HideoutDimens.Settings.SwatchRadius)
    val hover by animateColorAsState(if (states.hovered) scheme.surfaceContainerHigh else scheme.surfaceContainerHigh.copy(alpha = 0f), motion.effectsDefault(), label = "swatchHover")
    val on by animateFloatAsState(if (selected) 1f else 0f, motion.effectsFast(), label = "swatchOn")
    val check by animateFloatAsState(if (selected) 1f else CheckRestScale, motion.spatialFast(), label = "swatchCheck")
    val name by animateColorAsState(if (selected) scheme.onSurface else scheme.onSurfaceVariant, motion.effectsDefault(), label = "swatchName")
    Column(
        modifier
            .width(HideoutDimens.Settings.SwatchWidth)
            .clip(shape)
            .drawBehind { drawRect(hover) }
            .focusRing(states.focusVisible, shape)
            .hideoutSelectable(selected, source, role = Role.RadioButton, onClick = onClick)
            .padding(top = 10.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(HideoutDimens.Settings.SwatchDot).drawBehind {
                val radius = size.minDimension / 2
                clipPath(Path().apply { addOval(Rect(Offset.Zero, size)) }) {
                    drawRect(palette.primaryContainer)
                    drawPath(Path().apply { moveTo(0f, 0f); lineTo(size.width, 0f); lineTo(0f, size.height); close() }, palette.primary)
                }
                drawCircle(Color.White.copy(alpha = 0.08f), radius - 0.5.dp.toPx(), style = Stroke(1.dp.toPx()))
                if (on > 0f) {
                    drawCircle(palette.primary.copy(alpha = on), radius)
                    val ring = HideoutDimens.Settings.SwatchRing.toPx()
                    drawCircle(palette.primary.copy(alpha = on), radius + HideoutDimens.Settings.SwatchRingGap.toPx() + ring / 2, style = Stroke(ring))
                }
            },
            contentAlignment = Alignment.Center,
        ) {
            HideoutIcon(
                HideoutIcons.CheckBold, null,
                Modifier.graphicsLayer { alpha = on; scaleX = check; scaleY = check },
                size = HideoutDimens.Settings.SwatchCheck, tint = palette.onPrimary,
            )
        }
        Text(accentName(accent), style = HideoutTheme.typography.caption.copy(fontSize = 12.5.sp, fontWeight = HideoutWeights.Label), color = name, maxLines = 1)
    }
}

@Composable
internal fun accentName(accent: Accent): String = stringResource(
    when (accent) {
        Accent.Graphite -> Res.string.accent_graphite
        Accent.Ember -> Res.string.accent_ember
        Accent.Soul -> Res.string.accent_soul
        Accent.Ocean -> Res.string.accent_ocean
        Accent.Violet -> Res.string.accent_violet
        Accent.Rose -> Res.string.accent_rose
    },
)

@Composable
internal fun ModCardPicker(selected: ModCardStyle, onPick: (ModCardStyle) -> Unit, label: String, modifier: Modifier = Modifier) {
    Row(
        modifier.semantics { contentDescription = label }.selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(HideoutDimens.Settings.CardTileGap),
    ) {
        ModCardStyle.entries.forEach { style ->
            CardTile(style, style == selected, { onPick(style) }, Modifier.weight(1f, fill = false).widthIn(max = HideoutDimens.Settings.CardTileMaxWidth))
        }
    }
}

@Composable
private fun CardTile(style: ModCardStyle, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    val motion = HideoutTheme.motion
    val type = HideoutTheme.typography
    val source = remember { MutableInteractionSource() }
    val states = source.collectInteractionStates()
    val shape = RoundedCornerShape(HideoutDimens.Settings.CardTileRadius)
    val on by animateFloatAsState(if (selected) 1f else 0f, motion.effectsFast(), label = "cardTileOn")
    val line by animateColorAsState(if (selected) scheme.primary else scheme.surfaceContainerHighest, motion.effectsFast(), label = "cardTileLine")
    val under = style == ModCardStyle.UnderImage
    Column(
        modifier
            .clip(shape)
            .drawBehind { drawRect(scheme.primary.copy(alpha = SelectedTileTint * on).compositeOver(scheme.surface)) }
            .insetRing(shape, line) { (1.5.dp + 0.5.dp * on).toPx() }
            .focusRing(states.focusVisible, shape)
            .hideoutSelectable(selected, source, role = Role.RadioButton, onClick = onClick)
            .padding(HideoutDimens.Settings.CardTilePadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CardPreview(under)
        Row(Modifier.padding(horizontal = 2.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier.padding(top = 1.dp).size(HideoutDimens.Settings.CardTileRadio).drawBehind {
                    val ring = 2.dp.toPx()
                    drawCircle(if (selected) scheme.primary else scheme.outline, size.minDimension / 2 - ring / 2, style = Stroke(ring))
                    if (on > 0f) drawCircle(scheme.primary, (size.minDimension / 2 - 4.dp.toPx()) * on)
                },
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    stringResource(if (under) Res.string.mod_cards_under_image else Res.string.mod_cards_on_image),
                    style = type.bodyStrong.copy(fontSize = 14.sp, fontWeight = HideoutWeights.Title), color = scheme.onSurface,
                )
                Text(
                    stringResource(if (under) Res.string.mod_cards_under_image_detail else Res.string.mod_cards_on_image_detail),
                    style = type.caption.copy(fontSize = 12.5.sp, lineHeight = 17.5.sp), color = scheme.outline,
                )
            }
        }
    }
}

@Composable
private fun CardPreview(under: Boolean) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .height(HideoutDimens.Settings.CardTilePreviewHeight)
            .clip(RoundedCornerShape(HideoutDimens.Settings.CardTilePreviewRadius))
            .drawBehind { drawRect(PreviewGround) }
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PreviewArts.forEach { art ->
            Column(Modifier.weight(1f)) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .then(if (under) Modifier.height(66.dp) else Modifier.fillMaxHeight())
                        .clip(RoundedCornerShape(9.dp))
                        .drawBehind {
                            drawArt(art)
                            if (!under) {
                                drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.78f)), startY = size.height * 0.45f))
                                val inset = 7.dp.toPx()
                                drawLines(scheme.onSurface, scheme.outline, 0.62f, 0.40f, inset, size.height - inset - 13.dp.toPx(), size.width - inset * 2, 4.dp.toPx())
                            }
                        },
                )
                if (under) Box(
                    Modifier.fillMaxWidth().height(21.dp).drawBehind {
                        drawLines(scheme.onSurface, scheme.outline, 0.70f, 0.46f, 2.dp.toPx(), 7.dp.toPx(), size.width - 4.dp.toPx(), 5.dp.toPx())
                    },
                )
            }
        }
    }
}

private fun DrawScope.drawLines(title: Color, meta: Color, titleWidth: Float, metaWidth: Float, left: Float, top: Float, width: Float, gap: Float) {
    val titleHeight = 5.dp.toPx()
    val metaHeight = 4.dp.toPx()
    drawRoundRect(title, Offset(left, top), Size(width * titleWidth, titleHeight), CornerRadius(3.dp.toPx()))
    drawRoundRect(meta, Offset(left, top + titleHeight + gap), Size(width * metaWidth, metaHeight), CornerRadius(3.dp.toPx()))
}

private fun DrawScope.drawArt(art: PreviewArt) {
    drawRect(Brush.linearGradient(listOf(art.from, art.to), Offset.Zero, Offset(size.width, size.height)))
    drawRect(Brush.radialGradient(listOf(art.glow, Color.Transparent), Offset(size.width * 0.7f, size.height * 0.25f), size.width * 0.7f))
    val width = size.width * 0.26f
    val height = size.height * 0.84f
    val top = size.height * 1.08f - height
    drawRoundRect(
        Brush.verticalGradient(listOf(art.figure.copy(alpha = art.figureTop), art.figure.copy(alpha = art.figureBottom)), top, top + height),
        Offset((size.width - width) / 2, top), Size(width, height), CornerRadius(width / 2),
    )
}

private class PreviewArt(val from: Color, val to: Color, val glow: Color, val figure: Color, val figureTop: Float, val figureBottom: Float)

private val PreviewArts = listOf(
    PreviewArt(Color(0xFF3B3127), Color(0xFF1B1713), Color(0x80E2C08C), Color.White, 0.28f, 0.06f),
    PreviewArt(Color(0xFF27232F), Color(0xFF141218), Color(0x809678BE), Color(0xFFBEAAF0), 0.45f, 0.08f),
)
private val PreviewGround = Color(0xFF0E0D0E)
private const val SelectedTileTint = 0.06f
private const val CheckRestScale = 0.6f
