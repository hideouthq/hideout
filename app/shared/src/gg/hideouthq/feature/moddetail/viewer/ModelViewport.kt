package gg.hideouthq.feature.moddetail.viewer

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import gg.hideouthq.core.designsystem.component.HideoutIcon
import gg.hideouthq.core.designsystem.component.rememberPopScale
import gg.hideouthq.core.designsystem.icon.HideoutIcons
import gg.hideouthq.core.designsystem.theme.HideoutDimens
import gg.hideouthq.core.designsystem.theme.HideoutMotion
import gg.hideouthq.core.designsystem.theme.HideoutShapes
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import gg.hideouthq.core.model.SceneLighting
import gg.hideouthq.preview.PreviewClip

@Immutable
data class ViewportScene(
    val heroName: String,
    val styleName: String,
    /** The verified bundle to draw, or null when there is none. */
    val model: PreviewModel?,
    /** The clip the stage's timeline plays, from [model]'s real clips. */
    val clip: PreviewClip?,
    /** The Scene panel's lights for the model, and its bloom over it. */
    val lighting: SceneLighting,
    val bloom: Boolean,
    val cameraResets: Int,
    /** Counts Retry presses after the model failed to load; each one loads it again. */
    val loadAttempts: Int = 0,
)

@Immutable
data class ViewportLabels(val title: String, val description: String)

/** What a viewport can show, so the stage offers playback only for a model that is really drawn. */
sealed interface ViewportStatus {
    /** No verified model to draw. */
    data object NoModel : ViewportStatus

    /** Fetching and loading the model; [fraction] of its bytes have arrived, when known: [doneBytes] of [totalBytes]. */
    data class Loading(val fraction: Float?, val doneBytes: Long = 0, val totalBytes: Long = 0) : ViewportStatus

    /** The model is drawn at the timeline's position. */
    data object Showing : ViewportStatus

    /**
     * The model cannot be drawn here. [still] says whether a verified still stands in;
     * [canRetry] is false where trying again cannot help, as without a renderer.
     */
    data class Unavailable(val still: Boolean = false, val canRetry: Boolean = true) : ViewportStatus
}

/**
 * The seam for the 3D renderer. It fills the stage's square model area (above the turntable) and draws
 * [ViewportScene.model]. [timeMillis] is the stage timeline's position in [ViewportScene.clip], read every frame: a
 * renderer samples the pose there and keeps no clock of its own. [spinDegrees], also read every frame, is how far the
 * turntable has turned the model, on top of the user's own camera. It reports what it shows through [onStatus], and
 * offers the model alone for Save image through [capture]. [PlaceholderModelViewport] stands in where no renderer
 * is installed.
 */
interface ModelViewport {
    @Composable
    fun Content(
        scene: ViewportScene,
        timeMillis: () -> Float,
        spinDegrees: () -> Float,
        labels: ViewportLabels,
        onStatus: (ViewportStatus) -> Unit,
        capture: ViewportCapture,
        modifier: Modifier,
    )
}

/**
 * Where a viewport that can draw its model offscreen puts the way to do it: [model] draws the model alone, as the
 * viewport shows it now, into a transparent picture of the given pixel size. Null while there is nothing to draw.
 */
@Stable
class ViewportCapture {
    var model: (suspend (width: Int, height: Int) -> ImageBitmap?)? = null
}

val LocalModelViewport = staticCompositionLocalOf<ModelViewport> { PlaceholderModelViewport }

/** Where no renderer is installed: the [ModelPlaceholder] capsule, reporting that a model cannot be drawn. */
object PlaceholderModelViewport : ModelViewport {
    @Composable
    override fun Content(
        scene: ViewportScene,
        timeMillis: () -> Float,
        spinDegrees: () -> Float,
        labels: ViewportLabels,
        onStatus: (ViewportStatus) -> Unit,
        capture: ViewportCapture,
        modifier: Modifier,
    ) {
        LaunchedEffect(scene.model) { onStatus(if (scene.model == null) ViewportStatus.NoModel else ViewportStatus.Unavailable(canRetry = false)) }
        ModelPlaceholder(scene, labels, modifier)
    }
}

@Composable
fun ModelPlaceholder(scene: ViewportScene, labels: ViewportLabels, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    val settle = rememberPopScale(trigger = scene.styleName, peak = HideoutMotion.PanelEnterScale)
    BoxWithConstraints(modifier.semantics { contentDescription = labels.description }) {
        val border = scheme.primary.copy(alpha = HideoutDimens.Stage.CapsuleBorderAlpha)
        val fill = Brush.verticalGradient(listOf(scheme.primary.copy(alpha = HideoutDimens.Stage.CapsuleTopAlpha), scheme.primary.copy(alpha = HideoutDimens.Stage.CapsuleBottomAlpha)))
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth(1f - 2 * HideoutDimens.Stage.CapsuleInsetFraction)
                .height(HideoutDimens.Stage.CapsuleHeight)
                .graphicsLayer {
                    scaleX = settle.value
                    scaleY = settle.value
                }
                .clip(HideoutShapes.Full)
                .background(fill)
                .drawWithContent {
                    drawContent()
                    val stroke = HideoutDimens.Stage.CapsuleBorder.toPx()
                    drawRoundRect(
                        color = border,
                        topLeft = Offset(stroke / 2, stroke / 2),
                        size = Size(size.width - stroke, size.height - stroke),
                        cornerRadius = CornerRadius(size.width / 2),
                        style = Stroke(
                            width = stroke,
                            pathEffect = PathEffect.dashPathEffect(
                                floatArrayOf(HideoutDimens.Stage.CapsuleDash.toPx(), HideoutDimens.Stage.CapsuleDashGap.toPx()),
                            ),
                        ),
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            PlaceholderLabel(labels.title, "${scene.heroName} · ${scene.styleName}")
        }
    }
}

@Composable
private fun PlaceholderLabel(title: String, caption: String) {
    val scheme = MaterialTheme.colorScheme
    val type = HideoutTheme.typography
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(HideoutDimens.Stage.CapsuleGap),
    ) {
        HideoutIcon(HideoutIcons.Cube, contentDescription = null, size = HideoutDimens.Icon.Large, tint = scheme.onSurfaceVariant)
        Text(title, style = type.bodyStrong, color = scheme.onSurfaceVariant)
        val motion = HideoutTheme.motion
        AnimatedContent(
            targetState = caption,
            transitionSpec = { fadeIn(motion.effectsDefault()) togetherWith fadeOut(motion.effectsDefault()) },
            label = "viewportCaption",
        ) { text ->
            Text(text, style = type.mono, color = scheme.outline, textAlign = TextAlign.Center)
        }
    }
}

