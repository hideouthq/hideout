package gg.hideouthq.core.designsystem.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import gg.hideouthq.core.designsystem.interaction.collectInteractionStates
import gg.hideouthq.core.designsystem.interaction.focusRing
import gg.hideouthq.core.designsystem.interaction.hideoutToggleable
import gg.hideouthq.core.designsystem.interaction.pressScale
import gg.hideouthq.core.designsystem.theme.HideoutDimens
import gg.hideouthq.core.designsystem.theme.HideoutMotion
import gg.hideouthq.core.designsystem.theme.HideoutRadius
import gg.hideouthq.core.designsystem.theme.HideoutShapes
import gg.hideouthq.core.designsystem.theme.HideoutTheme

@Composable
fun SoundRow(
    title: String,
    subtitle: String,
    waveform: List<Dp>,
    playing: Boolean,
    onPlayingChange: (Boolean) -> Unit,
    playDescription: String,
    installIcon: ImageVector,
    installDescription: String,
    installTooltip: String,
    /** Null while installing is unavailable: the button shows, disabled. */
    onInstall: (() -> Unit)?,
    modifier: Modifier = Modifier,
    playable: Boolean = true,
    installModifier: Modifier = Modifier,
    progress: Float = 0f,
    loading: Boolean = false,
) {
    val rowSource = remember { MutableInteractionSource() }
    val rowStates = rowSource.collectInteractionStates()
    val scheme = MaterialTheme.colorScheme
    val motion = HideoutTheme.motion
    val selected = HideoutTheme.colors.selectedRow
    val fill = when {
        playing -> selected
        rowStates.pressed -> scheme.surfaceContainerHigh
        else -> scheme.surfaceContainer
    }
    val background by animateColorAsState(
        targetValue = if (playing || rowStates.hovered || rowStates.pressed) fill else fill.copy(alpha = 0f),
        animationSpec = motion.effectsDefault(),
        label = "soundRowBackground",
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(HideoutDimens.ListRow.Height)
            .drawBehind { drawRoundRect(background, cornerRadius = CornerRadius(HideoutRadius.Row.toPx())) }
            .focusRing(rowStates.focusVisible, HideoutShapes.Row)
            .hideoutToggleable(playing, rowSource, enabled = playable, role = Role.Switch, onValueChange = onPlayingChange)
            .semantics { contentDescription = playDescription }
            .padding(start = HideoutDimens.ListRow.StartPadding, end = HideoutDimens.ListRow.EndPadding),
        horizontalArrangement = Arrangement.spacedBy(HideoutDimens.ListRow.Gap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlayButton(
            playing = playing,
            loading = loading,
            pressed = rowStates.pressed,
            modifier = Modifier
                .padding(start = HideoutDimens.SoundRow.PlayButtonStartMargin)
                .alpha(if (playable) 1f else HideoutMotion.DisabledAlpha),
        )
        Column(Modifier.weight(1f)) {
            Text(title, style = HideoutTheme.typography.titleRow, color = scheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = HideoutTheme.typography.subtitle, color = scheme.outline, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Waveform(waveform, playing, progress = progress)
        HideoutTooltip(installTooltip) {
            HideoutIconButton(icon = installIcon, contentDescription = installDescription, onClick = onInstall ?: {}, modifier = installModifier, enabled = onInstall != null)
        }
    }
}

@Composable
private fun PlayButton(playing: Boolean, loading: Boolean, pressed: Boolean, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    val motion = HideoutTheme.motion
    val container by animateColorAsState(if (playing) scheme.primary else scheme.surfaceContainerHigh, motion.effectsDefault(), label = "playButton")
    val content by animateColorAsState(if (playing) scheme.onPrimary else scheme.onSurface, motion.effectsDefault(), label = "playButtonGlyph")
    Box(
        modifier = modifier
            .pressScale(pressed)
            .size(HideoutDimens.SoundRow.PlayButton)
            .drawBehind { drawCircle(container) },
        contentAlignment = Alignment.Center,
    ) {
        if (loading) CircularProgressIndicator(Modifier.size(HideoutDimens.Icon.Small), color = content)
        else PlayPauseIcon(playing = playing, contentDescription = null, size = HideoutDimens.Icon.Small, tint = content)
    }
}
