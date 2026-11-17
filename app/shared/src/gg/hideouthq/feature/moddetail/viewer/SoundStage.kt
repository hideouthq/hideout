package gg.hideouthq.feature.moddetail.viewer

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.min
import gg.hideouthq.core.designsystem.component.PlayPauseButton
import gg.hideouthq.core.designsystem.component.RollingText
import gg.hideouthq.core.designsystem.component.SeekableWaveform
import gg.hideouthq.core.designsystem.icon.HideoutIcons
import gg.hideouthq.core.designsystem.theme.HideoutDimens
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import gg.hideouthq.core.designsystem.theme.cssLineHeight
import gg.hideouthq.core.ui.PlaybackShortcutEffect

@Immutable
data class SoundStageState(
    /** Whether the mod has a preview to play at all. */
    val available: Boolean,
    /** Playing now, neither paused nor loading. */
    val playing: Boolean = false,
    val loading: Boolean = false,
    /** Null until the preview has loaded. */
    val lengthMillis: Int? = null,
    val looping: Boolean = false,
)

interface SoundStageActions {
    fun toggleSample()
    fun seekSample(fraction: Float)
    fun setSampleLooping(looping: Boolean)
}

@Immutable
data class SoundStageLabels(val hint: String, val play: String, val pause: String, val loop: String, val loading: String, val cancel: String)

/**
 * [position] is read every frame, so the stage moves smoothly without the page recomposing.
 */
@Composable
fun SoundStage(
    word: String,
    waveform: List<Float>,
    sound: SoundStageState,
    position: () -> Int,
    actions: SoundStageActions,
    labels: SoundStageLabels,
    modifier: Modifier = Modifier,
    choreography: StageChoreography = StageChoreography.None,
) {
    val colors = HideoutTheme.colors
    PlaybackShortcutEffect(enabled = sound.available, onToggle = actions::toggleSample)
    val clock = rememberSampleClock(position, sound.lengthMillis, sound.playing && !sound.loading)
    val height = HideoutDimens.SoundStage.Height
    BoxWithConstraints(modifier.fillMaxWidth().height(height).stageBackdrop(colors.stage, colors.stageSpotlight)) {
        StageWord(word, Modifier.align(Alignment.TopCenter).padding(top = HideoutDimens.Stage.WordTop).then(choreography.resolve))
        val waveHeight = HideoutDimens.SoundStage.WaveformHeight
        SeekableWaveform(
            bars = waveform,
            progress = { clock.progress },
            playing = sound.playing && !sound.loading,
            onSeek = actions::seekSample,
            enabled = sound.available && sound.lengthMillis != null,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = height * HideoutDimens.SoundStage.WaveformCenterFraction - waveHeight / 2)
                .widthIn(max = min(HideoutDimens.SoundStage.WaveformMaxWidth, maxWidth - HideoutDimens.SoundStage.WaveformSideInset))
                .fillMaxWidth()
                .height(waveHeight)
                .then(choreography.model),
        )
        Hint(labels.hint, HideoutIcons.Sound, Modifier.padding(start = HideoutDimens.Stage.HintStart, top = HideoutDimens.Stage.HintTop).then(choreography.resolve))
        WavyScrubber(
            clock = clock,
            playing = sound.playing && !sound.loading,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = HideoutDimens.Scrubber.Bottom)
                .widthIn(max = min(HideoutDimens.Scrubber.MaxWidth, maxWidth - HideoutDimens.Scrubber.SideInset))
                .fillMaxWidth(),
        )
        ToolbarFrame(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = HideoutDimens.Toolbar.Bottom)
                .then(choreography.toolbar),
        ) {
            PlayPauseButton(
                playing = sound.playing,
                onClick = actions::toggleSample,
                playLabel = if (sound.loading) labels.cancel else labels.play,
                pauseLabel = labels.pause,
                enabled = sound.available,
                loading = sound.loading,
            )
            if (sound.loading) Text(labels.loading, style = HideoutTheme.typography.caption, color = MaterialTheme.colorScheme.onSurface)
            LoopToggle(sound.looping, actions::setSampleLooping, labels.loop)
        }
    }
}

private fun Modifier.stageBackdrop(stage: Color, spotlight: Color): Modifier = drawBehind {
    drawRect(stage)
    val radius = size.width * HideoutDimens.Stage.SpotlightRadiusFraction
    val radiusY = size.height * HideoutDimens.Stage.SpotlightRadiusFraction
    val center = Offset(size.width / 2, size.height * HideoutDimens.Stage.SpotlightCenterY)
    scale(scaleX = 1f, scaleY = radiusY / radius, pivot = center) {
        drawCircle(
            brush = Brush.radialGradient(
                0f to spotlight,
                HideoutDimens.Stage.SpotlightFadeStop to spotlight.copy(alpha = 0f),
                center = center,
                radius = radius,
            ),
            radius = radius,
            center = center,
        )
    }
}

@Composable
private fun StageWord(word: String, modifier: Modifier) {
    val density = LocalDensity.current
    val windowWidth = with(density) { LocalWindowInfo.current.containerSize.width.toDp() }
    val size = with(density) { (windowWidth * HideoutDimens.Stage.WordWindowFraction).coerceIn(HideoutDimens.Stage.WordMinSize, HideoutDimens.Stage.WordMaxSize).toSp() }
    RollingText(
        text = word,
        style = HideoutTheme.typography.stageWord.copy(fontSize = size, lineHeight = cssLineHeight(size, 1f)),
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentAlignment = Alignment.TopCenter,
        modifier = modifier,
    )
}
