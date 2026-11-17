package gg.hideouthq.viewer

import gg.hideouthq.core.analytics.LocalUsageAnalytics
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.skiaCanvas
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import gg.hideouthq.core.ui.LocalReducedMotion
import gg.hideouthq.data.preview.PreviewCache
import gg.hideouthq.feature.moddetail.viewer.ModelPlaceholder
import gg.hideouthq.feature.moddetail.viewer.ModelViewport
import gg.hideouthq.feature.moddetail.viewer.PreviewModel
import gg.hideouthq.feature.moddetail.viewer.ViewportCapture
import gg.hideouthq.feature.moddetail.viewer.ViewportLabels
import gg.hideouthq.feature.moddetail.viewer.ViewportScene
import gg.hideouthq.feature.moddetail.viewer.ViewportStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.jetbrains.skia.Image
import org.jetbrains.skia.Rect

class NativeModelViewport(
    private val cache: PreviewCache?,
    private val glThread: Lazy<GlThread>,
) : ModelViewport {
    private val open = MutableStateFlow<Set<ViewportSession>>(emptySet())

    val sessions: StateFlow<Set<ViewportSession>> = open.asStateFlow()

    val gl: GlThread get() = glThread.value

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
        val model = scene.model
        val analytics = LocalUsageAnalytics.current
        val session = remember(model, scene.loadAttempts) { if (model != null && cache != null) ViewportSession(model, cache, glThread, analytics) else null }
        val cameras = remember { Cameras() }
        DisposableEffect(session) {
            session?.let { opened -> open.update { it + opened } }
            onDispose {
                session?.let { closed ->
                    closed.close()
                    open.update { it - closed }
                }
            }
        }
        val state = session?.state?.collectAsState()?.value
        val still = (state as? SessionState.Unavailable)?.still?.let { bytes -> remember(bytes) { DecodedStill(bytes) }.image }
        val status = when {
            model == null -> ViewportStatus.NoModel
            state == null -> ViewportStatus.Unavailable()
            state is SessionState.Unavailable -> ViewportStatus.Unavailable(still = still != null)
            else -> state.status
        }
        LaunchedEffect(status) { onStatus(status) }
        if (session != null) LaunchedEffect(session) { session.load() }
        when {
            session != null && (state is SessionState.FirstFrame || state is SessionState.Showing) -> Frames(session, cameras.of(session.model.startAzimuth(), scene.cameraResets), scene, timeMillis, spinDegrees, labels, capture, modifier)
            still != null -> Still(still, labels, modifier)
            else -> ModelPlaceholder(scene, labels, modifier)
        }
    }

    @Composable
    private fun Frames(
        session: ViewportSession,
        camera: ViewCamera,
        scene: ViewportScene,
        timeMillis: () -> Float,
        spinDegrees: () -> Float,
        labels: ViewportLabels,
        capture: ViewportCapture,
        modifier: Modifier,
    ) {
        val currentCamera by rememberUpdatedState(camera)
        val latest by rememberUpdatedState(scene)
        val time by rememberUpdatedState(timeMillis)
        val spin by rememberUpdatedState(spinDegrees)
        val instant by rememberUpdatedState(LocalReducedMotion.current)
        LaunchedEffect(session) { session.renderFrames() }
        DisposableEffect(session, capture) {
            val render: suspend (Int, Int) -> ImageBitmap? = { width, height ->
                session.lastRequest?.let { shown -> session.capture(shown.copy(width = width, height = height))?.use { it.upright(latest.bloom) } }
            }
            capture.model = render
            onDispose { if (capture.model === render) capture.model = null }
        }
        BoxWithConstraints(
            modifier
                .clipToBounds()
                .semantics { contentDescription = labels.description }
                .pointerInput(session) {
                    awaitEachGesture {
                        var press = awaitPointerEvent()
                        while (press.type != PointerEventType.Press) press = awaitPointerEvent()
                        val down = press.changes.firstOrNull { it.pressed && !it.isConsumed } ?: return@awaitEachGesture
                        if (down.type == PointerType.Mouse && !press.buttons.isPrimaryPressed) return@awaitEachGesture
                        var dragging = false
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            val held = if (down.type == PointerType.Mouse) event.buttons.isPrimaryPressed else change.pressed
                            if (!held) break
                            if (event.type != PointerEventType.Move) continue
                            if (change.isConsumed) break
                            val by = if (dragging) change.positionChange() else change.position - down.position
                            if (!dragging && by.getDistance() <= viewConfiguration.touchSlop) continue
                            dragging = true
                            change.consume()
                            val view = currentCamera
                            view.goal = view.goal.orbit(by.x / density, by.y / density)
                        }
                    }
                }
                .pointerInput(session) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.type != PointerEventType.Scroll) continue
                            val pointer = event.changes.firstOrNull()?.position ?: continue
                            if (size.height <= 0) continue
                            val steps = event.changes.sumOf { it.scrollDelta.y.toDouble() }.toFloat()
                            currentCamera.scroll(
                                steps,
                                (pointer.x - size.width / 2f) / size.height,
                                (pointer.y - size.height / 2f) / size.height,
                            )
                            event.changes.forEach { it.consume() }
                        }
                    }
                },
        ) {
            val width = constraints.maxWidth
            val height = constraints.maxHeight
            LaunchedEffect(session, width, height) {
                if (width <= 0 || height <= 0) return@LaunchedEffect
                while (true) {
                    val now = withFrameNanos { it }
                    val view = currentCamera.at(now, instant).let { it.copy(azimuthDegrees = it.azimuthDegrees + spin()) }
                    session.request(FrameRequest(width, height, latest.clip?.id, time() / MillisPerSecond, view, latest.lighting, currentCamera.framing))
                }
            }
            Canvas(Modifier.fillMaxSize()) {
                val drawn = session.take() ?: return@Canvas
                if (drawn.drawn == 0L) {
                    drawn.drawn = System.nanoTime()
                    session.stats.drawn(drawn)
                }
                drawIntoCanvas { it.skiaCanvas.drawModel(drawn.image, size.width, size.height, latest.bloom) }
            }
        }
    }

    @Composable
    private fun Still(still: Image, labels: ViewportLabels, modifier: Modifier) {
        Canvas(modifier.clipToBounds().semantics { contentDescription = labels.description }) {
            val scale = size.height * StillHeight / still.height
            val width = still.width * scale
            val top = size.height * StillBottom - still.height * scale
            drawIntoCanvas {
                it.skiaCanvas.drawImageRect(
                    still,
                    Rect.makeWH(still.width.toFloat(), still.height.toFloat()),
                    Rect.makeXYWH((size.width - width) / 2, top, width, still.height * scale),
                )
            }
        }
    }

    private class Cameras {
        private var camera: ViewCamera? = null
        private var resets = 0

        fun of(startAzimuth: Float, cameraResets: Int): ViewCamera {
            val kept = camera?.takeIf { cameraResets == resets }
            if (kept != null) return kept
            resets = cameraResets
            return ViewCamera(Camera(startAzimuth)).also { camera = it }
        }
    }

    private class DecodedStill(bytes: ByteArray) : RememberObserver {
        val image: Image? = runCatching { Image.makeFromEncoded(bytes) }.getOrNull()

        override fun onRemembered() = Unit

        override fun onForgotten() {
            image?.close()
        }

        override fun onAbandoned() {
            image?.close()
        }
    }

    private companion object {
        const val MillisPerSecond = 1000f

        const val StillHeight = 0.95f
        const val StillBottom = 0.97f

        const val CaptureAzimuth = -70f
    }

    private fun PreviewModel.startAzimuth(): Float =
        (fallbackPicture ?: manifest.pictures.firstOrNull())?.azimuth?.toFloat() ?: CaptureAzimuth
}
