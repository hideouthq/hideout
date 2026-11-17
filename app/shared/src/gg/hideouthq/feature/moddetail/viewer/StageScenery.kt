package gg.hideouthq.feature.moddetail.viewer

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import gg.hideouthq.core.designsystem.component.ButtonSize
import gg.hideouthq.core.designsystem.component.ButtonVariant
import gg.hideouthq.core.designsystem.component.HideoutButton
import gg.hideouthq.core.designsystem.theme.HideoutColors
import gg.hideouthq.core.designsystem.theme.HideoutShapes
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import gg.hideouthq.core.model.Backdrop
import gg.hideouthq.core.model.SceneLighting
import gg.hideouthq.core.model.SceneSettings
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import gg.hideouthq.core.designsystem.theme.HideoutDimens.Stage as StageDimens
import gg.hideouthq.core.designsystem.theme.HideoutDimens.Turntable as Disc

internal class Scenery(
    val settings: SceneSettings,
    val photo: ImageBitmap?,
    val colors: HideoutColors,
    val primary: Color,
    val accent: Color,
    val edge: Color,
    val line: Color,
) {
    val backdrop: Backdrop get() = settings.shownBackdrop
    val photoShown: Boolean get() = backdrop.photo && photo != null
}

internal class TurntablePose(val spinDegrees: Float, val loading: Boolean, val progress: Float)

internal fun modelArea(stage: Size, density: Density): Rect = with(density) {
    val side = StageDimens.ModelArea.toPx()
    val width = minOf(side, stage.width - StageDimens.ModelAreaMinInset.toPx())
    val top = stage.height * StageDimens.ModelCenterFraction - side / 2
    Rect(Offset((stage.width - width) / 2, top), Size(width, side))
}

internal fun litTicks(progress: Float): Int = (Disc.Ticks * progress.coerceIn(0f, 1f)).roundToInt()

internal fun DrawScope.drawBackdrop(scenery: Scenery) {
    val photo = scenery.photo
    when {
        scenery.backdrop.photo && photo != null -> {
            drawCover(photo)
            drawRect(
                Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = PhotoShadeTop),
                    StageDimens.PhotoShadeMiddleStop to Color.Black.copy(alpha = PhotoShadeMiddle),
                    1f to Color.Black.copy(alpha = PhotoShadeBottom),
                ),
            )
        }
        else -> drawStudio(scenery.colors, scenery.primary)
    }
}

private fun DrawScope.drawStudio(colors: HideoutColors, primary: Color) {
    drawRect(Brush.verticalGradient(0f to colors.stageTop, StageDimens.StudioMiddleStop to colors.stage, 1f to colors.stageBottom))
    val radius = size.width * StageDimens.FloorGlowWidthFraction
    val center = Offset(size.width / 2, size.height)
    scale(scaleX = 1f, scaleY = size.height * StageDimens.FloorGlowHeightFraction / radius, pivot = center) {
        drawCircle(
            Brush.radialGradient(0f to primary.copy(alpha = FloorGlowAlpha), StageDimens.FloorGlowFadeStop to Color.Transparent, center = center, radius = radius),
            radius = radius,
            center = center,
        )
    }
}

internal fun DrawScope.drawCover(image: ImageBitmap) {
    val scale = max(size.width / image.width, size.height / image.height)
    val width = size.width / scale
    val height = size.height / scale
    drawImage(
        image = image,
        srcOffset = IntOffset(((image.width - width) / 2).roundToInt(), ((image.height - height) / 2).roundToInt()),
        srcSize = IntSize(width.roundToInt().coerceAtMost(image.width), height.roundToInt().coerceAtMost(image.height)),
        dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
    )
}

internal fun DrawScope.drawCone(lighting: SceneLighting, primary: Color) {
    val width = StageDimens.ConeWidth.toPx()
    val height = size.height * StageDimens.ConeHeightFraction
    val left = (size.width - width) / 2
    val (top, bottom) = when (lighting) {
        SceneLighting.Dramatic -> StageDimens.DramaticConeTopInset to StageDimens.DramaticConeBottomInset
        else -> StageDimens.ConeTopInset to StageDimens.ConeBottomInset
    }
    val strength = when (lighting) {
        SceneLighting.Soft -> SoftCone
        SceneLighting.Studio -> 1f
        SceneLighting.Dramatic -> DramaticCone
    }
    val cone = Path().apply {
        moveTo(left + width * top, 0f)
        lineTo(left + width * (1 - top), 0f)
        lineTo(left + width * (1 - bottom), height)
        lineTo(left + width * bottom, height)
        close()
    }
    drawPath(
        cone,
        Brush.verticalGradient(
            0f to primary.copy(alpha = ConeTop * strength),
            StageDimens.ConeMiddleStop to primary.copy(alpha = ConeMiddle * strength),
            1f to Color.Transparent,
            endY = height,
        ),
    )
}

internal fun DrawScope.drawVignette() {
    val radius = size.width * StageDimens.VignetteWidthFraction
    val center = Offset(size.width / 2, size.height * StageDimens.VignetteCenterY)
    scale(scaleX = 1f, scaleY = size.height * StageDimens.VignetteHeightFraction / radius, pivot = center) {
        val reach = max(size.width, size.height) * 2
        drawRect(
            Brush.radialGradient(StageDimens.VignetteClearStop to Color.Transparent, 1f to Color.Black.copy(alpha = VignetteAlpha), center = center, radius = radius),
            topLeft = Offset(center.x - reach, center.y - reach),
            size = Size(reach * 2, reach * 2),
        )
    }
}

internal fun DrawScope.drawTurntable(scenery: Scenery, area: Rect, pose: TurntablePose) {
    val bottom = area.bottom + Disc.Overhang.toPx()
    if (!scenery.settings.turntable) {
        val width = Disc.ContactShadowWidth.toPx()
        val height = Disc.ContactShadowHeight.toPx()
        val center = Offset(area.center.x, area.bottom - Disc.ContactShadowBottom.toPx() - height / 2)
        drawShadow(center, width / 2, height / 2, Disc.ContactShadowAlpha)
        return
    }
    val origin = Offset(area.center.x - Disc.Width.toPx() / 2, bottom - Disc.Height.toPx())
    val layered = scenery.photoShown
    if (layered) drawIntoCanvas { it.saveLayer(Rect(origin, Size(Disc.Width.toPx(), Disc.Height.toPx())), Paint().apply { alpha = StageDimens.PhotoTurntableAlpha }) }
    translate(origin.x, origin.y) { drawDisc(scenery, pose) }
    if (layered) drawIntoCanvas { it.restore() }
}

private fun DrawScope.drawDisc(scenery: Scenery, pose: TurntablePose) {
    val cx = Disc.Width.toPx() / 2
    val cy = Disc.CenterY.toPx()
    val rx = Disc.RadiusX.toPx()
    val ry = Disc.RadiusY.toPx()
    val depth = Disc.SideDepth.toPx()
    val top = Rect(cx - rx, cy - ry, cx + rx, cy + ry)
    val side = Path().apply {
        arcTo(top, 180f, -180f, forceMoveTo = true)
        lineTo(cx + rx, cy + depth)
        arcTo(top.translate(0f, depth), 0f, 180f, forceMoveTo = false)
        close()
    }
    val colors = scenery.colors
    drawPath(side, colors.turntableSide)
    if (pose.loading) {
        drawOval(colors.turntableLoading, top.topLeft, top.size)
    } else {
        drawOval(Brush.verticalGradient(listOf(colors.turntableTop, colors.turntableTopEnd), startY = top.top, endY = top.bottom), top.topLeft, top.size)
        val sheen = Offset(cx, top.top + top.height * Disc.SheenCenterY)
        val radius = top.width * Disc.SheenRadius
        clipPath(Path().apply { addOval(top) }) {
            scale(scaleX = 1f, scaleY = top.height / top.width, pivot = sheen) {
                drawCircle(Brush.radialGradient(listOf(scenery.primary.copy(alpha = Disc.SheenAlpha), Color.Transparent), center = sheen, radius = radius), radius, sheen)
            }
        }
    }
    drawOval(scenery.edge, top.topLeft, top.size, style = Stroke(Disc.EdgeStroke.toPx()))
    drawTicks(scenery, pose, cx, cy, rx, ry)
    val inner = Size(Disc.InnerRadiusX.toPx() * 2, Disc.InnerRadiusY.toPx() * 2)
    drawOval(scenery.line, Offset(cx - inner.width / 2, cy - inner.height / 2), inner, style = Stroke(Disc.InnerStroke.toPx()))
    if (!pose.loading) drawShadow(Offset(cx, Disc.ShadowCenterY.toPx()), Disc.ShadowRadiusX.toPx(), Disc.ShadowRadiusY.toPx(), Disc.ShadowAlpha)
}

private fun DrawScope.drawTicks(scenery: Scenery, pose: TurntablePose, cx: Float, cy: Float, rx: Float, ry: Float) {
    val lit = litTicks(pose.progress)
    for (i in 0 until Disc.Ticks) {
        val step = 360f / Disc.Ticks
        val (degrees, inner, outer) = if (pose.loading) {
            Triple(BackDegrees + step * i, Disc.LoadingTickInner, Disc.LoadingTickOuter)
        } else {
            Triple(step * i + pose.spinDegrees, Disc.TickInner, Disc.TickOuter)
        }
        val angle = degrees * PI.toFloat() / 180f
        val start = Offset(cx + rx * inner * cos(angle), cy + ry * inner * sin(angle))
        val end = Offset(cx + rx * outer * cos(angle), cy + ry * outer * sin(angle))
        when {
            pose.loading && i < lit -> drawLine(scenery.accent.copy(alpha = Disc.LitTickAlpha), start, end, Disc.LoadingTickStroke.toPx(), StrokeCap.Round)
            pose.loading -> drawLine(scenery.primary.copy(alpha = Disc.UnlitTickAlpha), start, end, Disc.LoadingTickStroke.toPx(), StrokeCap.Round)
            i % Disc.MajorTickEvery == 0 -> drawLine(scenery.primary.copy(alpha = Disc.MajorTickAlpha), start, end, Disc.MajorTickStroke.toPx())
            else -> drawLine(scenery.primary.copy(alpha = Disc.MinorTickAlpha), start, end, Disc.MinorTickStroke.toPx())
        }
    }
}

private fun DrawScope.drawShadow(center: Offset, rx: Float, ry: Float, alpha: Float) {
    scale(scaleX = 1f, scaleY = ry / rx, pivot = center) {
        drawCircle(Brush.radialGradient(listOf(Color.Black.copy(alpha = alpha), Color.Transparent), center = center, radius = rx), radius = rx, center = center)
    }
}

@Composable
internal fun LoadingCapsule(title: String, progress: String?, fraction: Float?, modifier: Modifier = Modifier, overPicture: Boolean = false) {
    val scheme = MaterialTheme.colorScheme
    val motion = HideoutTheme.motion
    val transition = rememberInfiniteTransition(label = "loading")
    val sweep by transition.animateFloat(
        initialValue = 0f,
        targetValue = if (motion.continuousMotion) 1f else 0f,
        animationSpec = infiniteRepeatable(tween(ShimmerMillis, easing = LinearEasing), RepeatMode.Restart),
        label = "shimmer",
    )
    val turn by transition.animateFloat(
        initialValue = 0f,
        targetValue = if (motion.continuousMotion && fraction == null) 360f else 0f,
        animationSpec = infiniteRepeatable(tween(SpinnerMillis, easing = LinearEasing), RepeatMode.Restart),
        label = "spinner",
    )
    BoxWithConstraints(modifier) {
        val primary = scheme.primary
        Box(
            Modifier
                .align(if (overPicture) Alignment.Center else Alignment.TopCenter)
                .fillMaxWidth(1f - 2 * StageDimens.CapsuleInsetFraction)
                .height(StageDimens.CapsuleHeight)
                .clip(HideoutShapes.Full)
                .drawBehind {
                    val band = size.width * ShimmerBand
                    val middle = -ShimmerReach * size.width + sweep * (1 + 2 * ShimmerReach) * size.width
                    val slant = size.height * ShimmerSlant
                    drawRect(
                        Brush.linearGradient(
                            0.3f to primary.copy(alpha = ShimmerDim),
                            0.5f to primary.copy(alpha = ShimmerBright),
                            0.7f to primary.copy(alpha = ShimmerDim),
                            start = Offset(middle - band / 2 - slant, 0f),
                            end = Offset(middle + band / 2 + slant, size.height),
                        ),
                    )
                    drawRoundRect(
                        primary.copy(alpha = LoadingEdgeAlpha),
                        cornerRadius = CornerRadius(size.width / 2),
                        style = Stroke(StageDimens.CapsuleBorder.toPx()),
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = if (overPicture) {
                    val scrim = scheme.surfaceContainer.copy(alpha = OverPictureScrimAlpha)
                    Modifier
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(0f to Color.Transparent, ScrimEdge to scrim, 1f - ScrimEdge to scrim, 1f to Color.Transparent))
                        .padding(vertical = StageDimens.ScrimPadding)
                } else {
                    Modifier
                },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(StageDimens.CapsuleGap),
            ) {
                ProgressRing(fraction, turn)
                Text(title, style = HideoutTheme.typography.labelButton, color = scheme.onSurface, textAlign = TextAlign.Center)
                if (progress != null) Text(progress, style = HideoutTheme.typography.mono, color = scheme.outline, textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
private fun ProgressRing(fraction: Float?, turn: Float) {
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val arc = MaterialTheme.colorScheme.secondary
    Canvas(
        Modifier
            .size(StageDimens.LoadingRing)
            .semantics { progressBarRangeInfo = if (fraction == null) ProgressBarRangeInfo.Indeterminate else ProgressBarRangeInfo(fraction, 0f..1f) },
    ) {
        val stroke = StageDimens.LoadingRingStroke.toPx()
        val inset = (size.minDimension - RingDiameter.toPx()) / 2
        val box = Size(size.width - 2 * inset, size.height - 2 * inset)
        drawArc(track, 0f, 360f, false, Offset(inset, inset), box, style = Stroke(stroke))
        val sweep = if (fraction == null) IndeterminateSweep else 360f * fraction.coerceIn(0f, 1f)
        if (sweep > 0f) drawArc(arc, BackDegrees + turn, sweep, false, Offset(inset, inset), box, style = Stroke(stroke, cap = StrokeCap.Round))
    }
}

@Composable
internal fun LoadFailure(labels: LoadingLabels, onRetry: () -> Unit, onSeeImages: () -> Unit, modifier: Modifier = Modifier, overPicture: Boolean = false) {
    val scrim = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = OverPictureScrimAlpha)
    Column(
        modifier
            .widthIn(max = StageDimens.ErrorMaxWidth)
            .then(if (overPicture) Modifier.background(scrim, HideoutShapes.Tile).padding(StageDimens.ErrorPadding) else Modifier),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(StageDimens.ErrorGap),
    ) {
        Text(labels.failed, style = HideoutTheme.typography.body, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Row(horizontalArrangement = Arrangement.spacedBy(StageDimens.ErrorButtonGap)) {
            HideoutButton(labels.retry, onRetry, variant = ButtonVariant.Tonal, size = ButtonSize.Small)
            HideoutButton(labels.seeImages, onSeeImages, variant = ButtonVariant.Text, size = ButtonSize.Small)
        }
    }
}

private const val PhotoShadeTop = 0.18f
private const val PhotoShadeMiddle = 0.42f
private const val PhotoShadeBottom = 0.6f
private const val FloorGlowAlpha = 0.05f
private const val ConeTop = 0.12f
private const val ConeMiddle = 0.03f
private const val SoftCone = 0.55f
private const val DramaticCone = 1.6f
private const val VignetteAlpha = 0.55f
private const val BackDegrees = -90f
private const val ShimmerMillis = 1_600
private const val SpinnerMillis = 1_000
private const val OverPictureScrimAlpha = 0.86f
private const val ScrimEdge = 0.22f
private const val ShimmerBand = 2.2f
private const val ShimmerReach = 0.34f
private const val ShimmerSlant = 0.36f
private const val ShimmerDim = 0.04f
private const val ShimmerBright = 0.13f
private const val LoadingEdgeAlpha = 0.16f
private const val IndeterminateSweep = 90f
private val RingDiameter = 24.dp
