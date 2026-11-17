package gg.hideouthq.tools.preview

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import gg.hideouthq.HideoutAppState
import gg.hideouthq.core.data.InstallStatus
import gg.hideouthq.core.data.preview.PreviewImageAddress
import gg.hideouthq.core.designsystem.image.LocalRemoteImages
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import gg.hideouthq.core.model.CalendarDate
import gg.hideouthq.core.model.ModCategory
import gg.hideouthq.core.model.NewsPost
import gg.hideouthq.core.model.SceneLighting
import gg.hideouthq.core.navigation.Route
import gg.hideouthq.core.ui.LocalWindowChrome
import gg.hideouthq.core.ui.catalog.CatalogItem
import gg.hideouthq.feature.discover.DiscoverActions
import gg.hideouthq.feature.discover.DiscoverScreen
import gg.hideouthq.feature.discover.DiscoverUiState
import gg.hideouthq.feature.discover.FeaturedMod
import gg.hideouthq.preview.PictureRole
import gg.hideouthq.tools.RenderedWindowChrome
import gg.hideouthq.tools.Script
import gg.hideouthq.tools.hasLabel
import gg.hideouthq.tools.nodes
import gg.hideouthq.tools.runScript
import gg.hideouthq.tools.selectTargets
import gg.hideouthq.viewer.Camera
import gg.hideouthq.viewer.FrameRequest
import gg.hideouthq.viewer.SessionState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.Surface
import java.io.File
import java.nio.file.Files
import kotlin.math.abs
import kotlin.math.round
import kotlin.system.exitProcess

internal class PreviewCheck(val name: String, val run: (PreviewHarness) -> Unit)

fun main(args: Array<String>) {
    val checks = listOf(
        PreviewCheck("draw", ::checkDraw),
        PreviewCheck("pause", ::checkPause),
        PreviewCheck("seek", ::checkSeek),
        PreviewCheck("clip_switch", ::checkClipSwitch),
        PreviewCheck("lighting_camera", ::checkLightingAndCamera),
        PreviewCheck("camera_orbit", ::checkVerticalOrbit),
        PreviewCheck("camera_buttons", ::checkMouseButtons),
        PreviewCheck("camera_zoom", ::checkZoom),
        PreviewCheck("camera_cursor_zoom", ::checkCursorZoom),
        PreviewCheck("camera_timeline", ::checkCameraKeepsTimeline),
        PreviewCheck("camera_hidpi", ::checkCameraNarrowHiDpi),
        PreviewCheck("resize", ::checkResize),
        PreviewCheck("disposal", ::checkDisposal),
        PreviewCheck("unsupported", ::checkUnsupported),
        PreviewCheck("featured_render_only", ::checkFeaturedRenderOnly),
    ) + viewerSceneChecks
    val selected = selectTargets(args.getOrNull(2), checks.map { it.name })
    val evidence = File(args.getOrNull(0) ?: "build/preview-implementation/app")
    val research = File(args.getOrNull(1) ?: "build/preview-research")
    val failures = PreviewHarness(evidence, research).use { harness ->
        log("store ${harness.store.root} served at ${harness.baseUrl}")
        val gl = runCatching { runBlocking { harness.gl.value.info() } }
        gl.exceptionOrNull()?.let { problem ->
            log("FAILED: these checks need an OpenGL context and the GL thread did not start: $problem")
            return@use listOf("opengl")
        }
        log("OpenGL ${gl.getOrThrow()}")
        checks.filter { it.name in selected }.mapNotNull { check ->
            try {
                check.run(harness)
                log("check '${check.name}' passed")
                null
            } catch (t: Throwable) {
                log("check '${check.name}' FAILED: ${t.message}")
                t.printStackTrace()
                check.name
            }
        }
    }
    log(if (failures.isEmpty()) "all ${selected.size} preview checks passed" else "failed: $failures")
    exitProcess(if (failures.isEmpty()) 0 else 1)
}

private fun checkDraw(harness: PreviewHarness) = viewer(harness, "draw") { app, _ ->
    val first = settleFrame(app.viewport)
    val pixels = first.pixels
    val bounds = pixels.bounds()
    log("draw: ${first.request} covered=${pixels.covered}/${pixels.width * pixels.height} bounds(l,t,r,b)=$bounds gl=${runBlocking { app.viewport.gl.info() }}")
    check(pixels.covered > pixels.width * pixels.height / 20) { "The model covers only ${pixels.covered} pixels" }
    check(abs(bounds[3] - 0.88f * pixels.height) < 0.03f * pixels.height) { "The feet are not on the disc: $bounds" }
    capture("drawn")
    press("Pause animation")
    seek(0.2f)
    val early = settleFrame(app.viewport).pixels
    seek(0.7f)
    val late = settleFrame(app.viewport).pixels
    val changed = late.changedFrom(early)
    log("draw: pose change between 20% and 70% of the clip: $changed pixels")
    check(changed > 300) { "Two times of the clip drew the same pose ($changed pixels changed)" }
    capture("pose_70_percent")
}

private fun checkPause(harness: PreviewHarness) = viewer(harness, "pause") { app, page ->
    settleFrame(app.viewport)
    advance(500)
    val playing = settleFrame(app.viewport)
    press("Pause animation")
    val paused = settleFrame(app.viewport)
    val pausedPixels = paused.pixels
    val stats = app.viewport.session().stats
    val rendered = stats.rendered.get()
    advance(1_000)
    val held = settleFrame(app.viewport)
    log("pause: playing t=${playing.request.timeSeconds} paused t=${paused.request.timeSeconds} after 1s t=${held.request.timeSeconds} rendered ${rendered}->${stats.rendered.get()}")
    check(held.request == paused.request) { "The timeline moved while paused" }
    check(stats.rendered.get() == rendered) { "Frames were drawn while paused" }
    check(held.pixels.changedFrom(pausedPixels) == 0) { "The paused frame changed" }
    capture("paused")
    press("Play animation")
    advance(400)
    val resumed = settleFrame(app.viewport)
    log("pause: resumed t=${resumed.request.timeSeconds}")
    check(abs(resumed.request.timeSeconds - (paused.request.timeSeconds + 0.4f)) < Tolerance) { "Playing did not resume from the paused time" }
    check(resumed.pixels.changedFrom(pausedPixels) > 0) { "Resuming did not change the pose" }
    capture("resumed")
    check(windowKey(page, Key.Spacebar)) { "Space had no action on a page whose model is drawn" }
    val spaced = settleFrame(app.viewport)
    advance(400)
    val spacedHeld = settleFrame(app.viewport)
    log("pause: Space while drawing paused at t=${spaced.request.timeSeconds}, after 0.4s t=${spacedHeld.request.timeSeconds}")
    check(nodes().any { it.hasLabel("Play animation") } && spacedHeld.request == spaced.request) { "Space did not pause the drawn model" }
}

private fun checkSeek(harness: PreviewHarness) = viewer(harness, "seek") { app, _ ->
    settleFrame(app.viewport)
    press("Pause animation")
    val duration = DurationSeconds
    seek(0.25f)
    val quarter = settleFrame(app.viewport)
    val quarterPixels = quarter.pixels
    capture("seek_25")
    seek(0.75f)
    val threeQuarters = settleFrame(app.viewport)
    capture("seek_75")
    press("Play animation")
    advance(700)
    press("Pause animation")
    seek(0.25f)
    val again = settleFrame(app.viewport)
    log("seek: t=${quarter.request.timeSeconds}, ${threeQuarters.request.timeSeconds}, back to ${again.request.timeSeconds}; " +
        "pose change ${threeQuarters.pixels.changedFrom(quarterPixels)} px; repeat difference ${again.pixels.changedFrom(quarterPixels)} px")
    check(abs(quarter.request.timeSeconds - duration * 0.25f) < 0.002f) { "Seeking to 25% did not set the time" }
    check(abs(threeQuarters.request.timeSeconds - duration * 0.75f) < 0.002f) { "Seeking to 75% did not set the time" }
    check(threeQuarters.pixels.changedFrom(quarterPixels) > 300) { "Seeking did not change the pose" }
    check(again.pixels.changedFrom(quarterPixels) == 0) { "The same time drew different pixels after playing" }
}

private fun checkClipSwitch(harness: PreviewHarness) = viewer(harness, "clip_switch") { app, page ->
    val first = settleFrame(app.viewport)
    press("Pause animation")
    seek(0.5f)
    val idle = settleFrame(app.viewport)
    capture("ui_shop_idle_50")
    press("Animations")
    press("Shop Menu Base")
    val switched = settleFrame(app.viewport)
    check(windowKey(page, Key.Escape)) { "Esc did not close the Animations menu" }
    press("Pause animation")
    seek(0.5f)
    val base = settleFrame(app.viewport)
    capture("shop_menu_base_50")
    val words = nodes().mapNotNull { node -> node.config.getOrElseNullable(SemanticsProperties.Text) { null }?.map { it.text } }.flatten()
    log("clip_switch: ${first.request.clip} -> ${switched.request.clip} at t=${switched.request.timeSeconds}; same time in both clips differs by ${idle.pixels.changedFrom(base.pixels)} px")
    check(first.request.clip == "models/heroes_wip/inferno/clips/ui_shop_idle") { "Started on ${first.request.clip}, not the clip of its stills" }
    check(switched.request.clip == "shop_menu_base") { "Switched to ${switched.request.clip}" }
    check(switched.request.timeSeconds < 0.05f) { "The new clip did not start at zero" }
    check("Shop Menu Base" in words) { "The stage does not name the new clip" }
}

private fun checkLightingAndCamera(harness: PreviewHarness) = viewer(harness, "lighting_camera") { app, page ->
    settleFrame(app.viewport)
    press("Pause animation")
    val plain = settleFrame(app.viewport)
    val plainPixels = plain.pixels
    check(plain.request.lighting == SceneLighting.Studio) { "The viewer did not start in Studio lighting" }
    capture("default_lighting")
    press("Scene")
    check(nodes().any { it.hasLabel("Dramatic") }) { "The Scene panel did not open" }
    capture("scene_panel")
    pressLighting("Dramatic")
    val dramatic = settleFrame(app.viewport)
    capture("dramatic_lighting")
    pressLighting("Soft")
    val soft = settleFrame(app.viewport)
    capture("soft_lighting")
    log("lighting: dramatic changed ${dramatic.pixels.changedFrom(plainPixels)} px, soft ${soft.pixels.changedFrom(plainPixels)} px, soft vs dramatic ${soft.pixels.changedFrom(dramatic.pixels)} px")
    for ((name, lit) in listOf(SceneLighting.Dramatic to dramatic, SceneLighting.Soft to soft)) {
        check(lit.request.lighting == name && lit.pixels.changedFrom(plainPixels) > 300) { "$name lighting did not relight the model" }
        check(lit.request.copy(lighting = SceneLighting.Studio) == plain.request) { "$name lighting changed the camera or pose" }
        check((0 until plainPixels.height).all { y -> (0 until plainPixels.width).all { x -> plainPixels.covers(x, y) == lit.pixels.covers(x, y) } }) { "$name lighting changed the model silhouette" }
    }
    check(soft.pixels.changedFrom(dramatic.pixels) > 300) { "Soft and Dramatic lit the model alike" }
    pressLighting("Studio")
    val restored = settleFrame(app.viewport)
    check(restored.pixels.sha256 == plainPixels.sha256) { "Studio lighting did not restore the original appearance" }
    windowKey(page, Key.Escape)
    check(nodes().none { it.hasLabel("Dramatic") }) { "Esc did not close the Scene panel" }
    val viewport = nodes().single { it.hasLabel("3D preview of Lilith, the Hell Warden") }.boundsInRoot.center
    moveToDp(viewport.x / scale, viewport.y / scale)
    dragByDp(120f, 0f, steps = 8)
    release()
    val turned = settleFrame(app.viewport)
    capture("turned")
    log("camera: azimuth ${plain.request.camera.azimuthDegrees} -> ${turned.request.camera.azimuthDegrees}")
    check(turned.request.camera.azimuthDegrees != plain.request.camera.azimuthDegrees && turned.pixels.changedFrom(plainPixels) > 300) { "Dragging did not turn the model" }
    check(turned.request.orbitChangeFrom(plain.request).let { (across, up) -> abs(across + 120 * Camera.DegreesPerDp) < 1e-3f && up == 0f }) { "A 120dp drag right orbited ${turned.request.orbitChangeFrom(plain.request)}, not ${-120 * Camera.DegreesPerDp}° across and 0° up" }
    press("Reset camera")
    val reset = settleFrame(app.viewport)
    check(reset.request == plain.request && reset.pixels.changedFrom(plainPixels) == 0) { "Resetting did not return to the first view" }
}

private fun checkVerticalOrbit(harness: PreviewHarness) = viewer(harness, "camera_orbit") { app, _ ->
    val expect = Expectations("camera_orbit")
    settleFrame(app.viewport)
    press("Pause animation")
    val plain = settleFrame(app.viewport)
    pointAtViewport()
    dragByDp(0f, 80f, steps = 8)
    release()
    val raised = settleFrame(app.viewport)
    capture("vertical_drag_80dp")
    val changed = raised.pixels.changedFrom(plain.pixels)
    log("camera_orbit: vertical drag 80dp: ${plain.request} -> ${raised.request}; $changed px changed")
    expect(raised.request != plain.request) { "A vertical drag did not change the camera" }
    expect(changed > MinCameraChange) { "A vertical drag redrew only $changed pixels" }
    expect(raised.request.timeSeconds == plain.request.timeSeconds) { "A vertical drag moved the paused timeline" }
    expect(nodes().any { it.hasLabel(TurnHint) }) { "The stage does not tell how to rotate and zoom" }
    expect(raised.request.orbitChangeFrom(plain.request).let { (across, up) -> across == 0f && abs(up - 80 * Camera.DegreesPerDp) < 1e-3f }) { "An 80dp drag down orbited ${raised.request.orbitChangeFrom(plain.request)}, not 0° across and ${80 * Camera.DegreesPerDp}° up" }
    pointAtViewport()
    dragByDp(0f, 2_000f, steps = 20)
    release()
    val top = settleFrame(app.viewport)
    pointAtViewport()
    dragByDp(0f, 300f, steps = 6)
    release()
    val beyond = settleFrame(app.viewport)
    capture("vertical_bound")
    log("camera_orbit: at the bound ${top.request}; after 300dp more ${beyond.request}; covered ${beyond.pixels.covered}")
    expect(top.request != raised.request) { "A long vertical drag did not orbit further" }
    expect(top.request.camera.pitchDegrees == Camera.MaxPitchDegrees) { "A long drag down stopped at ${top.request.camera.pitchDegrees}°, not the ${Camera.MaxPitchDegrees}° bound" }
    expect(beyond.request == top.request && beyond.pixels.changedFrom(top.pixels) == 0) { "Pitch kept moving past its bound" }
    expect(beyond.pixels.covered > 0) { "The model vanished at the pitch bound" }
    expect.verify()
}

private fun checkMouseButtons(harness: PreviewHarness) = viewer(harness, "camera_buttons") { app, _ ->
    val expect = Expectations("camera_buttons")
    settleFrame(app.viewport)
    press("Pause animation")
    val plain = settleFrame(app.viewport)
    capture("before")
    for ((name, button) in listOf("right" to PointerButton.Secondary, "middle" to PointerButton.Tertiary)) {
        press("Reset camera")
        settleFrame(app.viewport)
        pointAtViewport()
        dragByDp(20f, 10f, steps = 4, button = button)
        moveByDp(20f, 10f, steps = 4, shift = true, button = button)
        release(button = button)
        val moved = settleFrame(app.viewport)
        expect(moved.request == plain.request && moved.pixels.sha256 == plain.pixels.sha256) { "$name drag moved the model" }
        capture(name)
    }
    press("Reset camera")
    settleFrame(app.viewport)
    pointAtViewport()
    dragByDp(20f, 0f, steps = 4)
    moveByDp(20f, 0f, steps = 4, shift = true, button = PointerButton.Primary)
    release()
    val turned = settleFrame(app.viewport)
    expect(abs(turned.request.camera.azimuthDegrees - plain.request.camera.azimuthDegrees + 40 * Camera.DegreesPerDp) < 1e-3f) { "Pressing Shift during orbit changed the gesture" }
    expect(turned.request.camera.panX == 0f && turned.request.camera.panY == 0f) { "Orbit switched to pan mid-drag" }
    pointAtViewport()
    dragByDp(1000f, 0f, steps = 10)
    release()
    val released = settleFrame(app.viewport)
    pointAtViewport()
    moveByDp(20f, 10f)
    expect(settleFrame(app.viewport).request == released.request) { "Releasing outside the viewport left the camera dragging" }
    press("Reset camera")
    val reset = settleFrame(app.viewport)
    expect(reset.request == plain.request && reset.pixels.sha256 == plain.pixels.sha256) { "Reset did not restore the starting view" }
    expect.verify()
}

private fun checkZoom(harness: PreviewHarness) = viewer(harness, "camera_zoom") { app, _ ->
    val expect = Expectations("camera_zoom")
    settleFrame(app.viewport)
    press("Pause animation")
    val default = settleFrame(app.viewport)
    val session = app.viewport.session()
    pointAtViewport()
    scroll(20f)
    advance(EaseMillis)
    val blocked = settleFrame(app.viewport)
    expect(blocked.request == default.request && blocked.pixels.changedFrom(default.pixels) == 0) { "Scrolling out changed the default framing" }
    scroll(-2f)
    advance(EaseMillis)
    val plain = settleFrame(app.viewport)
    expect(plain.request.zoomed > default.request.zoomed) { "Zooming in after excess outward scrolling did not respond" }
    scroll(1f)
    val eased = mutableListOf(checkNotNull(session.lastRequest).zoomed)
    repeat(30) {
        advance(FrameMillis)
        Thread.sleep(2)
        eased += checkNotNull(session.lastRequest).zoomed
    }
    val step = settleFrame(app.viewport)
    val path = eased.distinct()
    log("camera_zoom: one wheel step from ${plain.request.zoomed}: zooms asked for over 31 frames $path; settled ${step.request.zoomed}")
    scroll(1f)
    advance(EaseMillis)
    val twoSteps = settleFrame(app.viewport)
    capture("zoom_out_two_steps")
    val ratio = twoSteps.request.zoomed / plain.request.zoomed
    val stepRatio = step.request.zoomed / plain.request.zoomed
    val stepExpected = default.pixels.bounds().scaledAboutCentre(default.pixels, step.request.zoomed / default.request.zoomed)
    expect(step.pixels.bounds().zip(stepExpected).all { (a, b) -> abs(a - b) <= PixelTolerance }) { "The first outward step moved the fitted target" }
    val expected = default.pixels.bounds().scaledAboutCentre(default.pixels, twoSteps.request.zoomed / default.request.zoomed)
    val got = twoSteps.pixels.bounds()
    log("camera_zoom: two steps: zoom ratio $ratio (one step $stepRatio); bounds $got, about the centre expected $expected")
    expect(got.zip(expected).all { (a, b) -> abs(a - b) <= PixelTolerance }) { "Zooming moved the target: bounds $got, expected $expected about the frame's centre" }
    expect(stepRatio < 1f) { "Turning the wheel towards the user did not zoom out" }
    expect(path.size >= 3 && path.zipWithNext().all { (a, b) -> b < a } && path.last() == step.request.zoomed) { "One wheel step did not ease monotonically onto its zoom: $path" }
    expect(abs(ratio - stepRatio * stepRatio) < 1e-4f) { "Wheel steps are not a constant ratio: $stepRatio then ${ratio / stepRatio}" }
    scroll(-0.5f)
    scroll(-0.5f)
    advance(EaseMillis)
    val halves = settleFrame(app.viewport)
    log("camera_zoom: two half steps back from ${twoSteps.request.zoomed} -> ${halves.request.zoomed}; one step was ${step.request.zoomed}")
    expect(abs(halves.request.zoomed - step.request.zoomed) < 1e-4f) { "Two half wheel steps did not equal one: ${halves.request.zoomed} vs ${step.request.zoomed}" }
    scroll(-60f)
    advance(EaseMillis)
    val closest = settleFrame(app.viewport)
    capture("zoom_in_bound")
    scroll(-5f)
    advance(EaseMillis)
    val pastClosest = settleFrame(app.viewport)
    val centre = closest.pixels.covers(closest.pixels.width / 2, closest.pixels.height / 2)
    log("camera_zoom: closest ${closest.request.zoomed} covering ${closest.pixels.covered}, centre covered $centre; 5 steps more ${pastClosest.request.zoomed}")
    expect(closest.request.zoomed > halves.request.zoomed) { "Turning the wheel away from the user did not zoom in" }
    expect(pastClosest.request == closest.request && pastClosest.pixels.changedFrom(closest.pixels) == 0) { "Zoom kept moving past its closest bound" }
    expect(closest.request.zoomed == Camera.MaxZoom) { "The closest zoom is ${closest.request.zoomed}, not ${Camera.MaxZoom}" }
    expect(centre && closest.pixels.covered > closest.pixels.width * closest.pixels.height / 4) { "The closest zoom does not hold the model on the fitted target" }
    scroll(120f)
    advance(EaseMillis)
    val farthest = settleFrame(app.viewport)
    capture("zoom_out_bound")
    scroll(5f)
    advance(EaseMillis)
    val pastFarthest = settleFrame(app.viewport)
    val far = farthest.pixels.bounds()
    val farExpected = default.pixels.bounds()
    log("camera_zoom: farthest ${farthest.request.zoomed} bounds $far, expected $farExpected; 5 steps more ${pastFarthest.request.zoomed}")
    expect(pastFarthest.request == farthest.request && pastFarthest.pixels.changedFrom(farthest.pixels) == 0) { "Zoom kept moving past its farthest bound" }
    expect(farthest.request == default.request && farthest.pixels.changedFrom(default.pixels) == 0) { "Zooming all the way out did not restore the default view exactly" }
    expect(far.zip(farExpected).all { (a, b) -> abs(a - b) <= PixelTolerance }) { "At the farthest zoom the model is not about the fitted target: $far, expected $farExpected" }
    expect(farthest.request.timeSeconds == plain.request.timeSeconds) { "Zooming moved the paused timeline" }
    expect.verify()
}

private fun checkCursorZoom(harness: PreviewHarness) {
    for (density in listOf(1f, 2f)) harness.app("camera_cursor_zoom_${density.toInt()}x").use { app ->
        val state = HideoutAppState(app.container, Route.ModDetail("lilith"))
        runScript(harness.evidence, "camera_cursor_zoom_${density.toInt()}x", 1440, 900, { HarnessRoot(remember { state }, app.viewport) }, scale = density, coroutineContext = OnRenderThread) {
            holdCamera(app)
            press("Pause animation")
            val plain = settleFrame(app.viewport)
            for ((x, y) in listOf(0.7f to 0.35f, 0.3f to 0.65f)) {
                val viewport = nodes().single { it.hasLabel(LilithViewport) }.boundsInRoot
                moveToDp((viewport.left + viewport.width * x) / scale, (viewport.top + viewport.height * y) / scale)
                scroll(-1f)
                advance(EaseMillis)
                val zoomed = settleFrame(app.viewport)
                val ratio = zoomed.request.camera.zoom / plain.request.camera.zoom
                val anchorX = plain.pixels.width * x
                val anchorY = plain.pixels.height * y
                val expected = plain.pixels.bounds().mapIndexed { index, value ->
                    val anchor = if (index % 2 == 0) anchorX else anchorY
                    round(anchor + (value - anchor) * ratio).toInt()
                }
                val bounds = zoomed.pixels.bounds()
                check(ratio > 1f && bounds.zip(expected).all { (actual, wanted) -> abs(actual - wanted) <= PixelTolerance }) {
                    "Cursor zoom at $x,$y and ${density}x moved bounds to $bounds, expected $expected"
                }
                check(zoomed.request.timeSeconds == plain.request.timeSeconds && zoomed.request.orbitChangeFrom(plain.request).all { it == 0f }) {
                    "Zoom changed rotation or paused playback"
                }
                capture("zoom_${x}_${y}")
                moveToDp((viewport.left + viewport.width * (1f - x)) / scale, (viewport.top + viewport.height * (1f - y)) / scale)
                scroll(1f)
                advance(EaseMillis)
                val returned = settleFrame(app.viewport)
                check(returned.pixels.bounds().zip(plain.pixels.bounds()).all { (actual, wanted) -> abs(actual - wanted) <= PixelTolerance }) {
                    "Reversing cursor zoom did not restore the original framing"
                }
                scroll(20f)
                advance(EaseMillis)
                val limited = settleFrame(app.viewport)
                check(limited.request == plain.request && limited.pixels.sha256 == plain.pixels.sha256) {
                    "Extra outward scrolling changed the default framing at ${density}x"
                }
                press("Reset camera")
                val reset = settleFrame(app.viewport)
                check(reset.request == plain.request && reset.pixels.sha256 == plain.pixels.sha256) { "Reset retained the zoom offset" }
            }
        }
    }
}

private fun checkCameraKeepsTimeline(harness: PreviewHarness) = viewer(harness, "camera_timeline") { app, _ ->
    val expect = Expectations("camera_timeline")
    settleFrame(app.viewport)
    press("Pause animation")
    seek(0.4f)
    val paused = settleFrame(app.viewport)
    fun progress() = nodes().single { it.config.contains(SemanticsProperties.ProgressBarRangeInfo) }.config[SemanticsProperties.ProgressBarRangeInfo].current
    val scrubber = progress()
    val session = app.viewport.session()
    val drawnBefore = session.stats.drawn.get()
    val scrubbed = mutableListOf<Float>()
    pointAtViewport()
    dragByDp(70f, 40f, steps = 8); scrubbed += progress(); release()
    dragByDp(-40f, 30f, steps = 8, shift = true); scrubbed += progress(); release(shift = true)
    scroll(-1.5f); advance(EaseMillis); scrubbed += progress()
    press("Reset camera"); scrubbed += progress()
    advance(1_000)
    val held = settleFrame(app.viewport)
    val drawn = session.stats.recent().takeLast((session.stats.drawn.get() - drawnBefore).toInt())
    val times = drawn.map { it.timeSeconds }.toSet()
    log("camera_timeline: paused at ${paused.request.timeSeconds}s, scrubber $scrubber; ${drawn.size} frames drawn during gestures at times $times; scrubber $scrubbed; after 1s ${held.request.timeSeconds}s")
    expect(drawn.size > 10) { "Camera gestures drew only ${drawn.size} frames" }
    expect(times == setOf(paused.request.timeSeconds) && held.request.timeSeconds == paused.request.timeSeconds) { "Camera gestures moved the paused timeline: $times" }
    expect(scrubbed.all { it == scrubber }) { "The scrubber moved during camera gestures: $scrubbed" }
    expect(nodes().any { it.hasLabel("Play animation") }) { "A camera gesture started playback" }
    expect(held.request == paused.request && held.pixels.changedFrom(paused.pixels) == 0) { "After reset the paused frame is not the one before the gestures" }
    press("Play animation")
    pointAtViewport()
    val start = checkNotNull(session.lastRequest).timeSeconds
    dragByDp(0f, 60f, steps = 25)
    release()
    val after = checkNotNull(session.lastRequest).timeSeconds
    log("camera_timeline: playing, a 27-frame drag moved time ${after - start}s")
    expect(abs((after - start) - 27 * FrameMillis / 1000f) < Tolerance) { "A drag while playing changed the playback rate: ${after - start}s in 27 frames" }
    expect.verify()
}

private fun checkCameraNarrowHiDpi(harness: PreviewHarness) {
    val expect = Expectations("camera_hidpi")
    val wide = harness.app("camera_hidpi_1x").use { app ->
        val state = HideoutAppState(app.container, Route.ModDetail("lilith"))
        var shots: Pair<Shot, Shot>? = null
        runScript(harness.evidence, "camera_hidpi_1x", 1440, 900, { HarnessRoot(remember { state }, app.viewport) }, scale = 1f, outputDirectory = File(harness.evidence, "checks/camera_hidpi/1x"), coroutineContext = OnRenderThread) {
            holdCamera(app)
            settleFrame(app.viewport)
            press("Pause animation")
            val plain = settleFrame(app.viewport)
            pointAtViewport()
            dragByDp(0f, 80f, steps = 8)
            release()
            shots = plain to settleFrame(app.viewport)
        }
        checkNotNull(shots)
    }
    harness.app("camera_hidpi").use { app ->
        val state = HideoutAppState(app.container, Route.ModDetail("lilith"))
        val narrow = mutableStateOf<Dp?>(1000.dp)
        runScript(harness.evidence, "camera_hidpi", 1440, 900, { HarnessRoot(remember { state }, app.viewport, narrow) }, scale = 2f, outputDirectory = File(harness.evidence, "checks/camera_hidpi/2x"), coroutineContext = OnRenderThread) {
            holdCamera(app)
            settleFrame(app.viewport)
            press("Pause animation")
            val plain = settleFrame(app.viewport)
            capture("narrow_2x")
            pointAtViewport()
            dragByDp(0f, 80f, steps = 8)
            release()
            val raised = settleFrame(app.viewport)
            capture("narrow_2x_vertical")
            val orbit = raised.request.orbitChangeFrom(plain.request)
            val wideOrbit = wide.second.request.orbitChangeFrom(wide.first.request)
            log("camera_hidpi: 2x ${plain.request.width}x${plain.request.height}: vertical 80dp ${plain.request} -> ${raised.request} orbits $orbit; at 1x $wideOrbit")
            expect(plain.request.width < plain.request.height) { "The 1000dp window did not narrow the model area" }
            expect(raised.request != plain.request && raised.pixels.changedFrom(plain.pixels) > MinCameraChange) { "A vertical drag did not orbit at 2x" }
            expect(orbit.zip(wideOrbit).all { (a, b) -> abs(a - b) < 1e-3f } && orbit.any { it != 0f }) { "The same 80dp drag orbits $orbit at 2x and $wideOrbit at 1x" }
            press("Reset camera")
            val again = settleFrame(app.viewport)
            pointAtViewport()
            dragByDp(30f, 20f, steps = 6, shift = true)
            release(shift = true)
            val shifted = settleFrame(app.viewport)
            capture("narrow_2x_shift_orbit")
            expect(again.pixels.sha256 == plain.pixels.sha256) { "Reset at 2x did not redraw the first frame exactly" }
            expect(shifted.request.orbitChangeFrom(again.request).let { (x, y) -> abs(x + 30 * Camera.DegreesPerDp) < 1e-3f && abs(y - 20 * Camera.DegreesPerDp) < 1e-3f }) { "Shift-drag at 2x did not rotate by the expected angle" }
            expect(shifted.request.camera.panX == 0f && shifted.request.camera.panY == 0f) { "Shift-drag at 2x moved the model off its anchor" }
            press("Reset camera")
            val reset = settleFrame(app.viewport)
            expect(reset.request == plain.request && reset.pixels.sha256 == plain.pixels.sha256) { "Reset at 2x did not return to the first frame" }
        }
    }
    expect.verify()
}

internal class Expectations(private val check: String) {
    private val failed = mutableListOf<String>()

    operator fun invoke(passed: Boolean, problem: () -> String) {
        if (!passed) failed += problem().also { log("$check: expectation FAILED: $it") }
    }

    fun verify() = check(failed.isEmpty()) { failed.joinToString("; ") }
}

private fun Script.pressLighting(label: String) {
    val control = nodes().single { it.hasLabel("Lighting") && it.children.isNotEmpty() }
    val segment = control.children.single { it.hasLabel(label) }
    checkNotNull(segment.config[SemanticsActions.OnClick].action).invoke()
    absorb()
}

private fun Script.pointAtViewport() {
    val centre = nodes().single { it.hasLabel(LilithViewport) }.boundsInRoot.center
    moveToDp(centre.x / scale, centre.y / scale)
}

private fun List<Int>.scaledAboutCentre(frame: Pixels, ratio: Float): List<Int> {
    val x = frame.width / 2f
    val y = frame.height / 2f
    return listOf(x + (this[0] - x) * ratio, y + (this[1] - y) * ratio, x + (this[2] - x) * ratio, y + (this[3] - y) * ratio).map { round(it).toInt() }
}

private fun FrameRequest.orbitChangeFrom(start: FrameRequest): List<Float> =
    listOf(camera.azimuthDegrees - start.camera.azimuthDegrees, camera.pitchDegrees - start.camera.pitchDegrees)

private val FrameRequest.zoomed: Float get() = camera.zoom

private fun checkResize(harness: PreviewHarness) {
    val width = mutableStateOf<Dp?>(null)
    val density = mutableStateOf<Float?>(null)
    harness.app("resize").use { app ->
        val state = HideoutAppState(app.container, Route.ModDetail("lilith"))
        runScript(harness.evidence, "resize", 1440, 900, { HarnessRoot(remember { state }, app.viewport, width, density) }, scale = 2f, outputDirectory = File(harness.evidence, "checks/resize"), coroutineContext = OnRenderThread) {
            val wide = settleFrame(app.viewport)
            width.value = 1000.dp
            advance(600)
            val narrowWindow = settleFrame(app.viewport)
            capture("window_1000dp")
            width.value = null
            density.value = 1f
            advance(600)
            val lowScale = settleFrame(app.viewport)
            capture("scale_1x")
            density.value = null
            advance(600)
            val restored = settleFrame(app.viewport)
            log("resize: ${wide.request.width}x${wide.request.height} at 2x; 1000dp window ${narrowWindow.request.width}x${narrowWindow.request.height}; " +
                "1x display ${lowScale.request.width}x${lowScale.request.height} covering ${lowScale.pixels.covered}; back ${restored.request.width}x${restored.request.height} covering ${restored.pixels.covered}")
            check(narrowWindow.request.width < wide.request.width && narrowWindow.request.height == wide.request.height && narrowWindow.pixels.covered > 0) {
                "The viewport did not redraw for the narrower window"
            }
            check(lowScale.request.width * 2 == wide.request.width && lowScale.pixels.covered > 0) { "The viewport did not redraw at the 1x size" }
            check(restored.request.width == wide.request.width && restored.pixels.covered > 0) { "The viewport did not return to the 2x size" }
        }
    }
}

private fun checkDisposal(harness: PreviewHarness) = harness.app("disposal").use { app ->
    val state = HideoutAppState(app.container, Route.Discover)
    runScript(harness.evidence, "disposal", 1440, 900, { HarnessRoot(remember { state }, app.viewport) }, scale = 1f, outputDirectory = File(harness.evidence, "checks/disposal"), coroutineContext = OnRenderThread) {
        repeat(3) { round ->
            state.navigator.navigate(Route.ModDetail("lilith"))
            settleFrame(app.viewport)
            check(app.viewport.gl.liveScenes.get() == 1) { "Expected one live scene, found ${app.viewport.gl.liveScenes.get()}" }
            check(app.viewport.gl.openFrames.get() in 1..2) { "Expected a shown and at most one waiting frame, found ${app.viewport.gl.openFrames.get()}" }
            press("Back")
            val left = advanceUntil("page to close") { app.viewport.sessions.value.isEmpty() }
            settle("the scene and its frames to be released") { app.viewport.gl.liveScenes.get() == 0 && app.viewport.gl.openFrames.get() == 0 }
            log("disposal: round $round closed after ${left}ms of frames; liveScenes=${app.viewport.gl.liveScenes.get()} openFrames=${app.viewport.gl.openFrames.get()}")
        }
        capture("released")
    }
}

/** Core's fixture skin leaves out the inverse bind matrices gdx-gltf 2.3.0 needs. */
private fun checkUnsupported(harness: PreviewHarness) {
    val server = serveStore(CoreFixture.root)
    try {
        viewer(harness, "unsupported", modId = "werecat", app = { harness.app("unsupported", base = server.baseUrl, attached = mapOf("werecat" to CoreFixture.entry)) }, block = { app, page -> unsupportedPage(app, page) })
    } finally {
        server.stop(0, 0)
    }
}

private fun Script.unsupportedPage(app: HarnessApp, page: HideoutAppState) {
    settle("the viewport to give up") { app.viewport.sessions.value.singleOrNull()?.state?.value is SessionState.Unavailable }
    val state = app.viewport.session().state.value as SessionState.Unavailable
    advance(400)
    log("unsupported: ${state.reason}; still=${state.still?.size} bytes; liveScenes=${app.viewport.gl.liveScenes.get()}")
    check("cannot read this model" in state.reason && state.still != null) { "Expected the loader's refusal and a still, got ${state.reason}" }
    check(!enabled("Play animation") && !enabled("Animations") && !enabled("Playback speed")) { "Playback controls are enabled without a drawn model" }
    check(nodes().none { it.hasLabel("Idle") && it.config.contains(SemanticsActions.OnClick) }) { "A clip's menu opened without a drawn model" }
    check(nodes().none { it.hasLabel("Pause animation") }) { "The toolbar shows a pause glyph for a model that is not drawn" }
    check(nodes().none { it.hasLabel("Save image") && !it.config.contains(SemanticsProperties.Disabled) }) { "Save image is offered without a drawn model" }
    check(app.viewport.gl.liveScenes.get() == 0) { "An unsupported scene holds GL resources" }
    val area = nodes().single { node -> node.config.getOrElseNullable(SemanticsProperties.ContentDescription) { null }?.any { it.startsWith("3D preview of") } == true }.boundsInRoot
    val shown = stillShown(checkNotNull(state.still), area)
    log("unsupported: $shown of the still's opaque middle is on screen")
    check(shown > 0.9f) { "The still is not in view after the model failed" }
    check(enabled("Retry") && enabled("See images instead")) { "A model that failed offers no Retry or See images instead beside its still" }
    fun progress() = nodes().single { it.config.contains(SemanticsProperties.ProgressBarRangeInfo) }.config[SemanticsProperties.ProgressBarRangeInfo].current
    check(progress() == 0f) { "The scrubber is not at zero without a drawn model" }
    capture("still_fallback")
    check(!windowKey(page, Key.Spacebar)) { "Space has a playback action on a page whose model is not drawn" }
    advance(400)
    check(nodes().none { it.hasLabel("Pause animation") } && progress() == 0f) { "Space started playback without a drawn model" }
    log("unsupported: Space ignored; toolbar paused; scrubber at ${progress()}")
    capture("space_ignored")
    val failed = app.viewport.session()
    press("Retry")
    settle("the retried model to give up") { app.viewport.sessions.value.singleOrNull()?.let { it !== failed && it.state.value is SessionState.Unavailable } == true }
    advance(400)
    check(stillShown(checkNotNull((app.viewport.session().state.value as SessionState.Unavailable).still), area) > 0.9f && enabled("Retry")) { "Retry lost the still or its own button" }
    capture("retried")
    press("See images instead")
    check(nodes().none { it.hasLabel("Auto-spin") } && nodes().any { it.hasLabel("Full size") }) { "See images instead did not show the Images tab" }
    capture("images")
}

private fun Script.stillShown(still: ByteArray, area: Rect): Float {
    val screen = scene.render(time * 1_000_000).use { Bitmap.makeFromImage(it) }
    val picture = Image.makeFromEncoded(still).use { Bitmap.makeFromImage(it) }
    val scale = area.height * 0.95f / picture.height
    val left = area.left + (area.width - picture.width * scale) / 2
    val top = area.top + area.height * 0.97f - picture.height * scale
    var opaque = 0
    var shown = 0
    for (y in picture.height / 4 until picture.height * 3 / 4) for (x in picture.width / 4 until picture.width * 3 / 4) {
        val colour = picture.getColor(x, y)
        if (colour ushr 24 != 0xff) continue
        opaque++
        val on = screen.getColor((left + (x + 0.5f) * scale).toInt(), (top + (y + 0.5f) * scale).toInt())
        if (intArrayOf(16, 8, 0).all { abs((on shr it and 0xff) - (colour shr it and 0xff)) <= StillTolerance }) shown++
    }
    screen.close()
    picture.close()
    return if (opaque == 0) 0f else shown.toFloat() / opaque
}

private fun checkFeaturedRenderOnly(harness: PreviewHarness) {
    val author = File(harness.evidence, "run/author-art.png").apply { parentFile.mkdirs() }
    Surface.makeRasterN32Premul(640, 640).use { surface ->
        surface.canvas.clear(AuthorRed)
        author.writeBytes(checkNotNull(surface.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)).bytes)
    }
    for ((name, base) in listOf("featured_generated" to harness.baseUrl, "featured_unreachable" to "http://127.0.0.1:9")) {
        harness.app(name, base = base).use { app ->
            if (base == harness.baseUrl) runBlocking { withTimeout(30_000) { app.previews.index.first { it != null } } }
            val item = CatalogItem(
                id = "harness-featured", title = "Harness fixture", category = ModCategory.Skins, heroName = "Infernus", kind = null,
                author = "harness", downloads = 0, has3dPreview = true, coverTone = null, coverUrl = author.toURI().toString(),
            )
            val renderUrl = PreviewImageAddress.Picture(harness.store.infernus.bundleId, PictureRole.Featured).url
            val ui = DiscoverUiState(loading = false, featured = listOf(FeaturedMod(item, null, CalendarDate(2026, 10, 7), InstallStatus.NotInstalled, backgroundUrl = null, renderUrl = renderUrl)))
            runScript(harness.evidence, name, 1440, 900, {
                CompositionLocalProvider(LocalWindowChrome provides RenderedWindowChrome, LocalRemoteImages provides app.container.images) {
                    HideoutTheme { Box(Modifier.fillMaxSize()) { DiscoverScreen(ui, NoDiscoverActions) } }
                }
            }, scale = 1f, outputDirectory = File(harness.evidence, "checks/$name"), coroutineContext = OnRenderThread) {
                val art = nodes().single { it.hasLabel("Featured mod art") }.boundsInRoot
                fun redShare(): Float = scene.render(time * 1_000_000).use { image ->
                    redShare(image, art.left.toInt(), art.top.toInt(), art.right.toInt(), art.bottom.toInt())
                }
                if (base == harness.baseUrl) settle("the featured art", timeoutMillis = 30_000) { generatedShown(app, HarnessPreviews.Still) }
                advance(600)
                val share = redShare()
                log("$name: author-art share of the featured shape = $share")
                check(share == 0f) { "Author art showed on the stage" }
                capture(name)
            }
        }
    }
}

private fun generatedShown(app: HarnessApp, featured: String): Boolean =
    app.cache.root.resolve("bundles").let { bundles -> Files.isDirectory(bundles) && Files.list(bundles).use { all -> all.anyMatch { Files.isRegularFile(it.resolve(featured)) } } }

private fun redShare(image: Image, left: Int, top: Int, right: Int, bottom: Int): Float {
    val bitmap = Bitmap.makeFromImage(image)
    var red = 0
    var all = 0
    for (y in top until bottom step 4) for (x in left until right step 4) {
        all++
        val color = bitmap.getColor(x, y)
        if ((color shr 16 and 0xff) > 200 && (color shr 8 and 0xff) < 60 && (color and 0xff) < 60) red++
    }
    bitmap.close()
    return red.toFloat() / all
}

private object NoDiscoverActions : DiscoverActions {
    override fun search(query: String) = Unit
    override fun openMod(modId: String) = Unit
    override fun previewMod(modId: String) = Unit
    override fun browseHero(heroId: String) = Unit
    override fun browseAllHeroes() = Unit
    override fun browseCategory(category: ModCategory) = Unit
    override fun openCollection(id: String) = Unit
    override fun openCollections() = Unit
    override fun selectFeatured(index: Int) = Unit
    override fun advanceFeatured() = Unit
    override fun toggleSample(modId: String) = Unit
    override fun install(modId: String) = Unit
    override fun refresh() = Unit
    override fun cancelInstall(modId: String) = Unit
    override fun readNews(post: NewsPost) = Unit
    override fun openAllNews() = Unit
}

internal fun viewer(harness: PreviewHarness, name: String, modId: String = "lilith", app: () -> HarnessApp = { harness.app(name) }, block: Script.(HarnessApp, HideoutAppState) -> Unit) =
    app().use { app ->
        val state = HideoutAppState(app.container, Route.ModDetail(modId))
        runScript(harness.evidence, name, 1440, 900, { HarnessRoot(remember { state }, app.viewport) }, scale = 1f, outputDirectory = File(harness.evidence, "checks/$name"), coroutineContext = OnRenderThread) {
            holdCamera(app)
            block(app, state)
        }
    }

private fun Script.holdCamera(app: HarnessApp) {
    settle("the model to load") { app.viewport.sessions.value.singleOrNull()?.state?.value.let { it is SessionState.Showing || it is SessionState.Unavailable } }
    advance(ArrivalMillis)
    press("Auto-spin")
    if (app.viewport.session().state.value is SessionState.Showing) press("Reset camera")
}

internal const val LilithViewport = "3D preview of Lilith, the Hell Warden"
private const val ArrivalMillis = 700L
private const val MinCameraChange = 300
private const val EaseMillis = 800L

private const val TurnHint = "Drag to rotate · Scroll to zoom"
private const val PixelTolerance = 3
private const val StillTolerance = 6
private const val FrameMillis = 16L
private const val DurationSeconds = 2.867f

private const val Tolerance = 0.035f
private const val AuthorRed = 0xFFE01010.toInt()
