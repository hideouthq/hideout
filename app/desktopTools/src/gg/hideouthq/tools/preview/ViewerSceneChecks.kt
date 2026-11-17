package gg.hideouthq.tools.preview

import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import gg.hideouthq.HideoutAppState
import gg.hideouthq.core.data.ViewerFiles
import gg.hideouthq.core.model.Backdrop
import gg.hideouthq.core.model.PreviewVariant
import gg.hideouthq.core.model.SceneLighting
import gg.hideouthq.core.model.SceneSettings
import gg.hideouthq.core.navigation.Route
import gg.hideouthq.data.DesktopViewerFiles
import gg.hideouthq.tools.Script
import gg.hideouthq.tools.hasLabel
import gg.hideouthq.tools.nodes
import gg.hideouthq.tools.runScript
import gg.hideouthq.viewer.SessionState
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.Surface
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs

internal val viewerSceneChecks = listOf(
    PreviewCheck("loading_progress", ::checkLoadingProgress),
    PreviewCheck("scene_effects", ::checkSceneEffects),
    PreviewCheck("backdrop_import", ::checkBackdropImport),
    PreviewCheck("save_image", ::checkSaveImage),
    PreviewCheck("variant_switch", ::checkVariantSwitch),
    PreviewCheck("variant_switch_midway", ::checkVariantSwitchMidway),
)

private fun checkLoadingProgress(harness: PreviewHarness) {
    SlowProxy(harness.baseUrl, bytesPerSecond = 1_500_000).use { proxy ->
        harness.app("loading_progress", base = proxy.baseUrl).use { app ->
            val state = HideoutAppState(app.container, Route.ModDetail("lilith"))
            runScript(harness.evidence, "loading_progress", 1440, 900, { HarnessRoot(remember { state }, app.viewport) }, scale = 1f, outputDirectory = File(harness.evidence, "checks/loading_progress"), coroutineContext = OnRenderThread) {
                settle("part of the model to arrive") {
                    val loading = app.viewport.sessions.value.singleOrNull()?.state?.value as? SessionState.Loading
                    loading?.progress?.let { it.doneBytes > 1_000_000 && it.doneBytes < it.totalBytes } == true
                }
                advance(FrameMillis * 4)
                val progress = checkNotNull((app.viewport.session().state.value as SessionState.Loading).progress)
                capture("loading")
                val counted = nodes().mapNotNull { it.config.getOrElseNullable(SemanticsProperties.Text) { null }?.firstOrNull { text -> BytesLine.matches(text.text) }?.text }
                log("loading: ${progress.doneBytes} of ${progress.totalBytes} bytes; shown ${counted}")
                check(nodes().any { it.hasLabel("Loading Silver") }) { "The capsule does not say what is loading" }
                check(counted.size == 1) { "The capsule does not count the bytes: $counted" }
                val (done, total, percent) = BytesLine.matchEntire(counted.single())!!.destructured
                check(abs(total.toDouble() - progress.totalBytes / 1e6) < 0.06 && done.toDouble() <= total.toDouble() && percent.toInt() in 1..99) { "The count ${counted.single()} is not the download's ${progress.doneBytes} of ${progress.totalBytes}" }
                val ring = nodes().single { it.config.getOrElseNullable(SemanticsProperties.ProgressBarRangeInfo) { null }?.range == 0f..1f && it.boundsInRoot.width < 40 }
                check(ring.config[SemanticsProperties.ProgressBarRangeInfo].current in 0.01f..0.99f) { "The ring does not follow the download" }
                settleFrame(app.viewport)
                advance(FrameMillis * 30)
                capture("ready")
                check(nodes().none { it.hasLabel("Loading Silver") }) { "The capsule stayed after the model arrived" }
                log("loading: ready after ${proxy.requests.count { "/v1/bundles/" in it }} bundle requests")
            }
        }
    }
}

private fun checkSceneEffects(harness: PreviewHarness) = viewer(harness, "scene_effects") { app, page ->
    press("Pause animation")
    val plain = settleFrame(app.viewport)
    val viewport = nodes().single { it.hasLabel(LilithViewport) }.boundsInRoot
    val corner = Point(viewport.left - 180, viewport.top + 60)
    val studio = screen()
    capture("studio")
    press("Scene")
    val button = nodes().single { it.hasLabel("Scene") && it.config.contains(SemanticsActions.OnClick) }.boundsInRoot
    moveToDp(button.center.x, button.center.y)
    click()
    advance(FrameMillis * 20)
    check(nodes().none { it.hasLabel("Broadway") }) { "The Scene button did not close its panel" }
    moveToDp(viewport.center.x, viewport.top - 30)
    press("Scene")
    click()
    advance(FrameMillis * 20)
    check(nodes().none { it.hasLabel("Broadway") }) { "A click outside did not close the Scene panel" }
    press("Scene")
    press("Broadway")
    val broadway = screen()
    capture("broadway")
    log("scene: backdrop corner ${studio.colour(corner).hex()} -> ${broadway.colour(corner).hex()}")
    check(studio.colour(corner) != broadway.colour(corner)) { "The Broadway photo did not replace the Studio backdrop" }
    check(settleFrame(app.viewport).pixels.sha256 == plain.pixels.sha256) { "A backdrop changed the rendered model" }

    press("Bloom glow")
    windowKey(page, Key.Escape)
    val bloomFrame = settleFrame(app.viewport)
    val bloomed = screen()
    capture("broadway_bloom")
    val glow = changed(broadway, bloomed, viewport)
    log("scene: bloom changed $glow composed pixels in the model area; frame request kept=${bloomFrame.request == plain.request}")
    check(bloomFrame.request == plain.request && bloomFrame.pixels.sha256 == plain.pixels.sha256) { "Bloom changed the rendered frame rather than glowing over it" }
    check(glow > 200) { "Bloom changed only $glow pixels around the model" }

    press("Scene")
    press("Turntable and spotlight")
    windowKey(page, Key.Escape)
    val bare = screen()
    capture("turntable_off")
    val disc = Point(viewport.left + 10, viewport.bottom + 5)
    check(bloomed.colour(disc) != bare.colour(disc)) { "The turntable stayed when switched off" }

    val kept = runBlocking { app.container.preferences.sceneSettings() }
    log("scene: kept $kept")
    check(kept == SceneSettings(Backdrop.Broadway, SceneLighting.Studio, turntable = false, bloom = true)) { "The scene was not kept: $kept" }
    press("Scene")
    press("Reset")
    windowKey(page, Key.Escape)
    check(runBlocking { app.container.preferences.sceneSettings() } == SceneSettings()) { "Reset did not return to the default scene" }
    check(changed(studio, screen(), Rect(corner.x - 2, corner.y - 2, corner.x + 2, corner.y + 2)) == 0) { "Reset did not bring back the Studio backdrop" }
    capture("reset")
}

private fun checkBackdropImport(harness: PreviewHarness) {
    val folder = File(harness.evidence, "run/backdrop_import_files").apply { deleteRecursively(); mkdirs() }.toPath()
    val usable = folder.resolve("blue.png").also { Files.write(it, solidPng(320, 180, ImportedBlue)) }
    val refused = folder.resolve("notes.png").also { Files.writeString(it, "not a picture") }
    var chosen = usable.toFile()
    val backdrops = folder.resolve("backdrops")
    val files = DesktopViewerFiles(backdrops, folder.resolve("Pictures"), choose = { chosen })
    viewer(harness, "backdrop_import", app = { harness.app("backdrop_import", viewerFiles = files) }) { app, page ->
        val viewport = nodes().single { it.hasLabel(LilithViewport) }.boundsInRoot
        val corner = Point(viewport.left - 180, viewport.top + 60)
        val studio = screen().colour(corner)
        press("Scene")
        press("Your image")
        settle("the picture to become the backdrop") { runBlocking { app.container.preferences.sceneSettings() }.backdrop == Backdrop.Custom }
        windowKey(page, Key.Escape)
        advance(FrameMillis * 20)
        val kept = runBlocking { app.container.preferences.sceneSettings() }
        val copy = Path.of(checkNotNull(kept.customBackdrop))
        val bytes = Files.readAllBytes(copy)
        val custom = screen().colour(corner)
        capture("custom")
        log("backdrop: studio ${studio.hex()} -> own picture ${custom.hex()}; kept $kept")
        check(custom != studio) { "The picture did not replace the Studio backdrop" }

        chosen = refused.toFile()
        press("Scene")
        press("Your image")
        settle("the refusal to show") { nodes().any { it.hasLabel(BackdropRefused) } }
        advance(FrameMillis * 20)
        capture("refused")
        windowKey(page, Key.Escape)
        advance(FrameMillis * 20)
        val after = runBlocking { app.container.preferences.sceneSettings() }
        log("backdrop: after the refusal $after; copies ${Files.list(backdrops).use { it.toList() }}")
        check(after == kept) { "The refused picture changed the scene to $after" }
        check(Files.readAllBytes(copy).contentEquals(bytes) && Files.list(backdrops).use { it.count() } == 1L) { "The refused picture touched the backdrop's copy" }
        check(screen().colour(corner) == custom) { "The refused picture changed the backdrop on screen" }
        capture("kept")
    }
}

private fun checkSaveImage(harness: PreviewHarness) {
    val files = RecordedFiles(File(harness.evidence, "checks/save_image/saved.png"))
    viewer(harness, "save_image", app = { harness.app("save_image", viewerFiles = files) }) { app, _ ->
        press("Pause animation")
        settleFrame(app.viewport)
        val onScreen = screen()
        val viewport = nodes().single { it.hasLabel(LilithViewport) }.boundsInRoot
        val bar = nodes().single { it.hasLabel("Play animation") }.boundsInRoot
        press("Save image")
        settle("the picture to be saved") { files.saved != null && nodes().any { it.hasLabel("Saved") } }
        advance(FrameMillis * 20)
        capture("saved_notice")
        val picture = checkNotNull(files.picture)
        val stage = stageEdges(onScreen, viewport)
        log("save: ${picture.width}x${picture.height} offered as ${files.fileName}; stage on screen ${stage.first}..${stage.second}")
        check(files.fileName == "lilith-the-hell-warden.png") { "Offered ${files.fileName}" }
        check(picture.height == 2 * StageHeight && abs(picture.width - 2 * (stage.second - stage.first)) <= 2) { "The picture is ${picture.width}x${picture.height}, not twice the stage" }
        val pixels = picture.asSkiaBitmap()
        fun saved(x: Float, y: Float) = pixels.getColor(((x - stage.first) * 2).toInt(), ((y - viewport.top + StageModelTop) * 2).toInt())
        val modelCentre = saved(viewport.center.x, viewport.center.y)
        val toolbar = saved(bar.center.x, bar.center.y)
        log("save: model centre ${modelCentre.hex()}, under the toolbar ${toolbar.hex()} (on screen ${onScreen.colour(Point(bar.center.x, bar.center.y)).hex()})")
        check(onScreen.colour(Point(bar.center.x, bar.center.y)) != toolbar) { "The toolbar is in the picture" }
        check(brightness(toolbar) < 40) { "The backdrop under the toolbar is not in the picture" }
        check(Image.makeFromEncoded(Files.readAllBytes(files.output.toPath())).use { it.width == picture.width }) { "The PNG was not written" }
        files.failReveal = true
        press("Show in folder")
        settle("the file-manager failure notice") { nodes().any { it.hasLabel("Image saved, but couldn’t open its folder.") } }
        check(files.revealed == null && files.revealAttempts == 1)
        check(Files.isRegularFile(files.output.toPath())) { "A failed reveal must not undo the saved picture" }
        advance(FrameMillis * 20)
        capture("reveal_failed")
        files.failReveal = false
        press("Try again")
        settle("the saved picture to be revealed on retry") { files.revealed == files.saved && nodes().any { it.hasLabel("Saved") } }
        check(files.revealAttempts == 2)
        advance(FrameMillis * 20)
        capture("reveal_retried")
    }
}

private fun checkVariantSwitch(harness: PreviewHarness) {
    viewer(harness, "variant_switch", app = { harness.app("variant_switch", variants = lilithVariants(harness)) }) { app, _ ->
        check(nodes().any { it.hasLabel("Variants") }) { "The variants menu is missing" }
        press("Pause animation")
        settleFrame(app.viewport)
        val viewport = nodes().single { it.hasLabel(LilithViewport) }.boundsInRoot
        moveToDp(viewport.center.x, viewport.center.y)
        dragByDp(120f, 0f, steps = 8)
        release()
        val turned = settleFrame(app.viewport)
        capture("infernus_turned")
        press("Variants")
        advance(FrameMillis * 20)
        capture("variants_open")
        press("Klukai Wraith")
        settle("the other model") { app.viewport.sessions.value.singleOrNull()?.let { it.model.entry == harness.store.wraith && it.state.value is SessionState.Showing } == true }
        advance(ArrivalMillis)
        val wraith = settleFrame(app.viewport)
        capture("wraith")
        log("variant: ${turned.request.camera} -> ${wraith.request.camera}; liveScenes=${app.viewport.gl.liveScenes.get()}")
        check(app.viewport.gl.liveScenes.get() == 1) { "The first model's scene was not released" }
        check(wraith.request.camera == turned.request.camera) { "Switching variants moved the camera" }
        check(wraith.pixels.changedFrom(turned.pixels) > 300) { "The other variant drew the same model" }
    }
}

private fun checkVariantSwitchMidway(harness: PreviewHarness) {
    SlowProxy(harness.baseUrl, bytesPerSecond = 2_000_000).use { proxy ->
        harness.app("variant_switch_midway", base = proxy.baseUrl, variants = lilithVariants(harness)).use { app ->
            val state = HideoutAppState(app.container, Route.ModDetail("lilith"))
            val glb = "${HarnessPreviews.InfernusModel}.zst"
            val kept = app.cache.root.resolve("bundles/${harness.store.infernus.bundleId}/$glb")
            runScript(harness.evidence, "variant_switch_midway", 1440, 900, { HarnessRoot(remember { state }, app.viewport) }, scale = 1f, outputDirectory = File(harness.evidence, "checks/variant_switch_midway"), coroutineContext = OnRenderThread) {
                settle("part of the first model to arrive") {
                    val loading = app.viewport.sessions.value.singleOrNull()?.state?.value as? SessionState.Loading
                    loading?.progress?.let { it.doneBytes > 1_000_000 } == true
                }
                val first = app.viewport.session()
                check(first.model.entry == harness.store.infernus) { "The page did not start on the Infernus download" }
                capture("infernus_loading")
                press("Variants")
                advance(FrameMillis * 20)
                check(enabled("Klukai Wraith")) { "The variants menu waits for the model" }
                press("Klukai Wraith")
                advanceUntil("the first session to close") { first !in app.viewport.sessions.value }
                val asked = proxy.requests.count { "/$glb" in it }
                settle("the other model to start arriving") {
                    val loading = app.viewport.sessions.value.singleOrNull()?.takeIf { it.model.entry == harness.store.wraith }?.state?.value as? SessionState.Loading
                    loading?.progress?.let { it.doneBytes > 0 && it.doneBytes < it.totalBytes } == true
                }
                advance(FrameMillis * 4)
                val progress = checkNotNull((app.viewport.session().state.value as SessionState.Loading).progress)
                capture("wraith_loading")
                val counted = nodes().mapNotNull { it.config.getOrElseNullable(SemanticsProperties.Text) { null }?.firstOrNull { text -> BytesLine.matches(text.text) }?.text }
                log("midway: Klukai Wraith ${progress.doneBytes} of ${progress.totalBytes} bytes; shown $counted")
                check(nodes().any { it.hasLabel("Loading Silver · Klukai Wraith") } && nodes().none { it.hasLabel("Loading Silver · Infernus") }) { "The capsule does not name the new variant alone" }
                check(counted.size == 1 && abs(BytesLine.matchEntire(counted.single())!!.groupValues[2].toDouble() - progress.totalBytes / 1e6) < 0.06) { "The count $counted is not the new download's" }
                settleFrame(app.viewport)
                advance(ArrivalMillis)
                capture("wraith")
                log("midway: dropped session ${first.state.value}; liveScenes=${app.viewport.gl.liveScenes.get()}; ${proxy.requests.count { "/$glb" in it }} Infernus model requests")
                check(app.viewport.session().model.entry == harness.store.wraith) { "The page drew another model than the one picked" }
                check(first.state.value is SessionState.Loading) { "The dropped session published ${first.state.value}" }
                check(app.viewport.gl.liveScenes.get() == 1) { "The dropped model holds a scene" }
                check(!Files.exists(kept) && proxy.requests.count { "/$glb" in it } == asked) { "The dropped download was kept or asked for again" }
            }
        }
    }
}

private fun lilithVariants(harness: PreviewHarness) = mapOf(
    "lilith" to listOf(
        PreviewVariant(harness.store.infernus.fileId, harness.store.infernus.fileMd5, "infernus.zip", "Infernus", harness.store.infernus),
        PreviewVariant(harness.store.wraith.fileId, harness.store.wraith.fileMd5, "wraith.zip", "Klukai Wraith", harness.store.wraith),
    ),
)

private class RecordedFiles(val output: File) : ViewerFiles {
    var picture: ImageBitmap? = null
    var fileName: String? = null
    var saved: String? = null
    var revealed: String? = null
    var failReveal = false
    var revealAttempts = 0

    override suspend fun importBackdrop(title: String): String? = null
    override suspend fun readBackdrop(path: String): ByteArray? = null

    override suspend fun savePicture(image: ImageBitmap, title: String, fileName: String): String {
        picture = image
        this.fileName = fileName
        output.parentFile.mkdirs()
        Image.makeFromBitmap(image.asSkiaBitmap()).use { output.writeBytes(checkNotNull(it.encodeToData(EncodedImageFormat.PNG)).bytes) }
        return output.absolutePath.also { saved = it }
    }

    override suspend fun reveal(path: String) {
        revealAttempts++
        if (failReveal) throw IOException("File manager unavailable")
        revealed = path
    }
}

private class Point(val x: Float, val y: Float)

private class Composed(val bitmap: Bitmap) {
    fun colour(at: Point): Int = bitmap.getColor(at.x.toInt(), at.y.toInt())
}

private fun Script.screen(): Composed = Composed(scene.render(time * 1_000_000).use { Bitmap.makeFromImage(it) })

private fun changed(before: Composed, after: Composed, area: Rect): Int {
    var count = 0
    for (y in area.top.toInt() until area.bottom.toInt()) for (x in area.left.toInt() until area.right.toInt()) {
        if (before.bitmap.getColor(x, y) != after.bitmap.getColor(x, y)) count++
    }
    return count
}

private fun stageEdges(screen: Composed, viewport: Rect): Pair<Int, Int> {
    val y = (viewport.top + 20).toInt()
    var left = viewport.center.x.toInt()
    while (screen.bitmap.getColor(left - 1, y) != PaneColour) left--
    var right = viewport.center.x.toInt()
    while (screen.bitmap.getColor(right, y) != PaneColour) right++
    return left to right
}

private fun brightness(colour: Int) = maxOf(colour shr 16 and 0xff, colour shr 8 and 0xff, colour and 0xff)

private fun solidPng(width: Int, height: Int, colour: Int): ByteArray = Surface.makeRasterN32Premul(width, height).use { surface ->
    surface.canvas.clear(colour)
    surface.makeImageSnapshot().use { checkNotNull(it.encodeToData(EncodedImageFormat.PNG)).bytes }
}

private fun Int.hex() = "#%06X".format(this and 0xffffff)

private const val BackdropRefused = "That picture couldn’t be used as a backdrop."
private const val ImportedBlue = 0xFF2E6B8F.toInt()
private val BytesLine = Regex("([0-9]+(?:\\.[0-9])?) of ([0-9]+(?:\\.[0-9])?) MB · ([0-9]+)%")
private const val PaneColour = 0xFF141313.toInt()
private const val StageHeight = 560
private const val StageModelTop = 560 * 0.43f - 190
private const val FrameMillis = 16L
private const val ArrivalMillis = 700L
