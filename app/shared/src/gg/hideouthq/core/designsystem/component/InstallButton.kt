package gg.hideouthq.core.designsystem.component

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import gg.hideouthq.core.designsystem.icon.HideoutIcons
import gg.hideouthq.core.designsystem.theme.HideoutDimens
import gg.hideouthq.core.designsystem.theme.HideoutMotion
import gg.hideouthq.core.designsystem.theme.HideoutShapes
import gg.hideouthq.core.designsystem.theme.HideoutTheme

@Immutable
sealed interface InstallButtonState {
    data object Idle : InstallButtonState
    data class Downloading(val percent: Int) : InstallButtonState
    data object Installed : InstallButtonState
}

/** The button's three labels; [downloading] already carries the current percentage. */
@Immutable
data class InstallButtonLabels(val install: String, val downloading: String, val installed: String)

@Composable
fun InstallButton(
    state: InstallButtonState,
    labels: InstallButtonLabels,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    installedVariant: ButtonVariant = ButtonVariant.Tonal,
    showInstalledMenu: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(horizontal = HideoutDimens.Button.HorizontalPadding),
) {
    val motion = HideoutTheme.motion
    val percent = (state as? InstallButtonState.Downloading)?.percent ?: 0
    val progress by animateFloatAsState(
        targetValue = percent / PercentMax,
        animationSpec = motion.effectsDefault(),
        label = "installProgress",
    )
    if (state is InstallButtonState.Downloading) {
        val scheme = MaterialTheme.colorScheme
        Box(
            modifier.height(HideoutDimens.Button.HeightLarge)
                .clip(HideoutShapes.Full)
                .background(scheme.surfaceContainerHigh)
                .drawBehind { drawRect(scheme.secondary.copy(alpha = 0.22f), size = Size(size.width * progress, size.height)) }
                .semantics { progressBarRangeInfo = ProgressBarRangeInfo(percent / PercentMax, 0f..1f) }
                .padding(contentPadding),
            contentAlignment = Alignment.Center,
        ) {
            Text(labels.downloading, style = HideoutTheme.typography.labelButtonLarge, color = scheme.onSurface, maxLines = 1)
        }
        return
    }
    val pop = rememberPopScale(trigger = state, peak = HideoutMotion.InstalledPopScale, enabled = state == InstallButtonState.Installed)
    val roll = with(LocalDensity.current) { HideoutMotion.RollOffset.roundToPx() }
    ButtonFrame(
        variant = if (state == InstallButtonState.Installed) installedVariant else ButtonVariant.Primary,
        size = ButtonSize.Large,
        enabled = onClick != null,
        modifier = modifier,
        contentPadding = contentPadding,
        onClick = onClick ?: {},
        progress = { progress },
    ) { content ->
        AnimatedContent(
            targetState = state,
            transitionSpec = {
                (fadeIn(motion.effectsDefault()) + slideInVertically(motion.spatialDefault()) { roll }) togetherWith
                    (fadeOut(motion.exitFade()) + slideOutVertically(motion.spatialDefault()) { -roll }) using
                    SizeTransform(clip = false)
            },
            contentAlignment = Alignment.Center,
            label = "installPhase",
        ) { shown ->
            val installed = shown == InstallButtonState.Installed
            Row(
                horizontalArrangement = Arrangement.spacedBy(HideoutDimens.Button.Gap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HideoutIcon(
                    icon = if (installed) HideoutIcons.Check else HideoutIcons.Download,
                    contentDescription = null,
                    tint = if (installed) MaterialTheme.colorScheme.secondary else content,
                    modifier = Modifier.graphicsLayer {
                        scaleX = pop.value
                        scaleY = pop.value
                    },
                )
                Text(
                    text = if (installed) labels.installed else labels.install,
                    style = HideoutTheme.typography.labelButtonLarge,
                    color = content,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (installed && showInstalledMenu) HideoutIcon(HideoutIcons.ChevronUp, null, tint = content, modifier = Modifier.graphicsLayer { rotationZ = 180f })
            }
        }
    }
}

private const val PercentMax = 100f
