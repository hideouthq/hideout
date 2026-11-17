package gg.hideouthq.feature.crosshairs.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import gg.hideouthq.core.crosshair.ConvarNumber
import gg.hideouthq.core.crosshair.CrosshairColor
import gg.hideouthq.core.crosshair.CrosshairOrigin
import gg.hideouthq.core.crosshair.SavedCrosshair
import gg.hideouthq.core.designsystem.component.ButtonSize
import gg.hideouthq.core.designsystem.component.ButtonVariant
import gg.hideouthq.core.designsystem.component.GlassBadge
import gg.hideouthq.core.designsystem.component.GlassBadgeSize
import gg.hideouthq.core.designsystem.component.HideoutButton
import gg.hideouthq.core.designsystem.component.HideoutTooltip
import gg.hideouthq.core.designsystem.component.PillButtonPadding
import gg.hideouthq.core.designsystem.component.ScrollColumn
import gg.hideouthq.core.designsystem.component.ScrollHairline
import gg.hideouthq.core.designsystem.component.isScrolled
import gg.hideouthq.core.designsystem.interaction.collectInteractionStates
import gg.hideouthq.core.designsystem.interaction.focusRing
import gg.hideouthq.core.designsystem.interaction.hideoutClickable
import gg.hideouthq.core.designsystem.interaction.insetRing
import gg.hideouthq.core.designsystem.theme.HideoutShapes
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import gg.hideouthq.core.designsystem.theme.HideoutWeights
import gg.hideouthq.core.designsystem.theme.cssLineHeight
import gg.hideouthq.core.ui.crosshair.CrosshairBackdrop
import gg.hideouthq.core.ui.crosshair.CrosshairTile
import gg.hideouthq.feature.crosshairs.CannotUse
import gg.hideouthq.feature.imports.managerName
import gg.hideouthq.resources.Res
import gg.hideouthq.resources.crosshairs_backdrop_dark
import gg.hideouthq.resources.crosshairs_backdrop_grey
import gg.hideouthq.resources.crosshairs_backdrop_sky
import gg.hideouthq.resources.crosshairs_by
import gg.hideouthq.resources.crosshairs_draws_nothing
import gg.hideouthq.resources.crosshairs_edit
import gg.hideouthq.resources.crosshairs_edit_in_use
import gg.hideouthq.resources.crosshairs_from_code
import gg.hideouthq.resources.crosshairs_from_community
import gg.hideouthq.resources.crosshairs_from_copy
import gg.hideouthq.resources.crosshairs_from_copy_unnamed
import gg.hideouthq.resources.crosshairs_from_game
import gg.hideouthq.resources.crosshairs_from_made
import gg.hideouthq.resources.crosshairs_from_manager
import gg.hideouthq.resources.crosshairs_from_preset
import gg.hideouthq.resources.crosshairs_in_use
import gg.hideouthq.resources.crosshairs_percent
import gg.hideouthq.resources.crosshairs_settings_unreadable
import gg.hideouthq.resources.crosshairs_settings_unreadable_short
import gg.hideouthq.resources.crosshairs_start_deadlock_once
import gg.hideouthq.resources.crosshairs_start_deadlock_once_short
import gg.hideouthq.resources.crosshairs_use_in_game
import gg.hideouthq.resources.crosshairs_yours_unreadable
import gg.hideouthq.resources.crosshairs_yours_unreadable_short
import gg.hideouthq.resources.problem_game_not_found
import gg.hideouthq.resources.settings_game_not_found_title
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

@Composable
internal fun DrawsNothingNote(modifier: Modifier) {
    GlassBadge(stringResource(Res.string.crosshairs_draws_nothing), modifier, size = GlassBadgeSize.Regular)
}

@Composable
internal fun HeaderButton(text: String, icon: ImageVector, onClick: () -> Unit) {
    HideoutButton(
        text, onClick,
        variant = ButtonVariant.Subtle, size = ButtonSize.Medium, leadingIcon = icon, leadingIconSize = 18.dp,
        contentColor = MaterialTheme.colorScheme.onSurface, contentPadding = PillButtonPadding,
    )
}

@Composable
internal fun UseInGameButton(cannotUse: CannotUse?, onClick: () -> Unit, fillWidth: Boolean = false, size: ButtonSize = ButtonSize.Regular) {
    val width = if (fillWidth) Modifier.fillMaxWidth() else Modifier
    val (why, short) = when (cannotUse) {
        null, CannotUse.Reading -> null to null
        CannotUse.NoGame -> Res.string.problem_game_not_found to Res.string.settings_game_not_found_title
        CannotUse.NotSavedYet -> Res.string.crosshairs_start_deadlock_once to Res.string.crosshairs_start_deadlock_once_short
        CannotUse.Unreadable -> Res.string.crosshairs_settings_unreadable to Res.string.crosshairs_settings_unreadable_short
        CannotUse.YoursUnreadable -> Res.string.crosshairs_yours_unreadable to Res.string.crosshairs_yours_unreadable_short
    }
    val button = @Composable { HideoutButton(stringResource(Res.string.crosshairs_use_in_game), onClick, width, size = size, enabled = cannotUse == null) }
    if (why == null || short == null) return button()
    Column(width, horizontalAlignment = if (fillWidth) Alignment.CenterHorizontally else Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        HideoutTooltip(stringResource(why), width) { button() }
        Text(stringResource(short), style = HideoutTheme.typography.captionSmall, color = MaterialTheme.colorScheme.outline, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun InUsePill(crosshair: SavedCrosshair, onEdit: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val type = HideoutTheme.typography
    val source = remember { MutableInteractionSource() }
    val states = source.collectInteractionStates()
    val container by animateColorAsState(if (states.hovered) scheme.surfaceContainerHigh else scheme.surfaceContainer, HideoutTheme.motion.effectsFast(), label = "inUsePill")
    val label = stringResource(Res.string.crosshairs_edit_in_use, crosshair.name)
    Row(
        Modifier
            .height(48.dp)
            .background(container, HideoutShapes.Full)
            .focusRing(states.focusVisible, HideoutShapes.Full)
            .semantics { contentDescription = label }
            .hideoutClickable(source, onClick = onEdit)
            .padding(horizontal = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CrosshairTile(crosshair.crosshair, CrosshairBackdrop.Card, PillZoom, CircleShape, Modifier.size(36.dp))
        Column(Modifier.padding(end = 4.dp)) {
            Text(stringResource(Res.string.crosshairs_in_use), style = type.captionCompact.copy(fontWeight = HideoutWeights.Title, lineHeight = 14.sp), color = HideoutTheme.colors.status)
            Text(crosshair.name, Modifier.widthIn(max = PillNameMaxWidth), style = type.labelMenuItem.copy(fontWeight = HideoutWeights.Title, lineHeight = 16.sp), color = scheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(Modifier.height(36.dp).background(scheme.surfaceContainerHighest, HideoutShapes.Full).padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
            Text(stringResource(Res.string.crosshairs_edit), style = type.labelPill, color = scheme.onSurface)
        }
    }
}

@Composable
internal fun rememberCopied(): MutableState<Boolean> {
    val copied = remember { mutableStateOf(false) }
    LaunchedEffect(copied.value) {
        if (copied.value) {
            delay(CopiedMillis)
            copied.value = false
        }
    }
    return copied
}

internal fun Modifier.outerRing(color: Color, radius: Dp): Modifier = drawBehind {
    if (color.alpha == 0f) return@drawBehind
    val width = 2.dp.toPx()
    val inset = 2.dp.toPx() + width / 2
    drawRoundRect(color, Offset(-inset, -inset), Size(size.width + 2 * inset, size.height + 2 * inset), CornerRadius(radius.toPx() + inset), style = Stroke(width))
}

internal fun CrosshairColor.toColor(): Color = Color(r, g, b)

internal fun Modifier.swatch(color: Color, shape: Shape, edgeAlpha: Float = 0.2f): Modifier =
    background(color, shape).insetRing(shape, Color.White.copy(alpha = edgeAlpha)) { 1.dp.toPx() }

@Composable
internal fun backdropLabel(backdrop: CrosshairBackdrop): String = stringResource(
    when (backdrop) {
        CrosshairBackdrop.Sky -> Res.string.crosshairs_backdrop_sky
        CrosshairBackdrop.Dark, CrosshairBackdrop.Card -> Res.string.crosshairs_backdrop_dark
        CrosshairBackdrop.Grey -> Res.string.crosshairs_backdrop_grey
    },
)

@Composable
internal fun originLabel(origin: CrosshairOrigin): String = when (origin) {
    is CrosshairOrigin.Preset -> stringResource(Res.string.crosshairs_from_preset)
    is CrosshairOrigin.Community -> origin.creator?.let { stringResource(Res.string.crosshairs_by, it) } ?: stringResource(Res.string.crosshairs_from_community)
    is CrosshairOrigin.CommunityCopy -> origin.creator?.let { stringResource(Res.string.crosshairs_from_copy, it) } ?: stringResource(Res.string.crosshairs_from_copy_unnamed)
    is CrosshairOrigin.Imported -> stringResource(Res.string.crosshairs_from_code)
    is CrosshairOrigin.FromManager -> stringResource(Res.string.crosshairs_from_manager, managerName(origin.manager))
    CrosshairOrigin.Made -> stringResource(Res.string.crosshairs_from_made)
    is CrosshairOrigin.Mine -> stringResource(Res.string.crosshairs_from_game)
}

@Composable
internal fun percent(value: ConvarNumber): String = stringResource(Res.string.crosshairs_percent, (value.value * 100).roundToInt())

@Composable
internal fun valueStyle() = HideoutTheme.typography.monoPath.copy(fontWeight = HideoutWeights.Medium)

@Composable
internal fun headingStyle() = HideoutTheme.typography.labelMenuItem.copy(fontWeight = HideoutWeights.Title, lineHeight = cssLineHeight(13.5.sp, 1.35f))

private val PillNameMaxWidth = 180.dp
private const val PillZoom = 1.4f
private const val CopiedMillis = 1_500L

@Composable
internal fun ScrollPane(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    val scroll = rememberScrollState()
    Box(modifier) {
        ScrollColumn(Modifier.fillMaxSize(), scroll, PaddingValues(bottom = PageBottom), content = content)
        ScrollHairline(scroll.isScrolled, Modifier.align(Alignment.TopCenter))
    }
}

internal val PageBottom = 16.dp
