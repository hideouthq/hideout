@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package gg.hideouthq.tools.preview

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import gg.hideouthq.AppContainer
import gg.hideouthq.AppContent
import gg.hideouthq.HideoutAppState
import gg.hideouthq.core.data.CatalogRepository
import gg.hideouthq.core.data.ViewerFiles
import gg.hideouthq.core.data.fake.FakeConfig
import gg.hideouthq.core.data.preview.PreviewRepository
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import gg.hideouthq.core.model.Mod
import gg.hideouthq.core.model.PreviewVariant
import gg.hideouthq.core.ui.LocalReducedMotion
import gg.hideouthq.core.ui.LocalWindowChrome
import gg.hideouthq.data.CoilRemoteImages
import gg.hideouthq.data.FileCacheStorage
import gg.hideouthq.data.preview.PreviewCache
import gg.hideouthq.data.preview.PreviewPictureFetcher
import gg.hideouthq.feature.moddetail.viewer.LocalModelViewport
import gg.hideouthq.feature.moddetail.viewer.ModelViewport
import gg.hideouthq.preview.IndexEntry
import gg.hideouthq.preview.sha256Hex
import gg.hideouthq.tools.RenderedWindowChrome
import gg.hideouthq.tools.Script
import gg.hideouthq.tools.labelled
import gg.hideouthq.tools.nodes
import gg.hideouthq.tools.runUiQueue
import gg.hideouthq.viewer.FrameRequest
import gg.hideouthq.viewer.GlThread
import gg.hideouthq.viewer.NativeModelViewport
import gg.hideouthq.viewer.RenderedFrame
import gg.hideouthq.viewer.SessionState
import gg.hideouthq.viewer.ViewportSession
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.staticFiles
import io.ktor.server.netty.Netty
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import java.io.File
import java.nio.file.Path
import kotlin.coroutines.CoroutineContext
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.math.abs

internal class PreviewHarness(val evidence: File, research: File) : AutoCloseable {
    val store = HarnessPreviews.publish(evidence.toPath().resolve("fixture-store"), research.toPath())
    private val server = serveStore(store.root)
    val baseUrl = server.baseUrl

    val gl = lazy { GlThread() }

    @OptIn(ExperimentalPathApi::class)
    fun app(
        name: String,
        base: String = baseUrl,
        attached: Map<String, IndexEntry> = mapOf("lilith" to store.infernus, "werecat" to store.wraith),
        variants: Map<String, List<PreviewVariant>> = emptyMap(),
        viewerFiles: ViewerFiles = ViewerFiles.Unavailable,
    ): HarnessApp {
        val directory = evidence.toPath().resolve("run").resolve(name)
        directory.deleteRecursively()
        return HarnessApp(base, directory, attached, gl, variants, viewerFiles)
    }

    override fun close() = server.stop(0, 0)
}

internal fun serveStore(root: Path): EmbeddedServer<*, *> =
    embeddedServer(Netty, port = 0, host = "127.0.0.1") { routing { staticFiles("/v1", root.toFile()) } }.start(wait = false)

internal val EmbeddedServer<*, *>.baseUrl: String get() = "http://127.0.0.1:${runBlocking { engine.resolvedConnectors().single().port }}"

internal class HarnessApp(
    base: String,
    val directory: Path,
    attached: Map<String, IndexEntry>,
    gl: Lazy<GlThread>,
    variants: Map<String, List<PreviewVariant>> = emptyMap(),
    viewerFiles: ViewerFiles = ViewerFiles.Unavailable,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val client = HttpClient(OkHttp)
    val cache = PreviewCache(base, directory.resolve("previews"), "Hideout preview checks", key = null)
    val previews = PreviewRepository(cache, FileCacheStorage(directory.resolve("storage").toFile()), scope)
    val viewport = NativeModelViewport(cache, gl)
    val container = AppContainer.fake(
        FakeConfig(previews = attached),
        previews = previews,
        images = CoilRemoteImages(client, directory.resolve("images").toFile(), PreviewPictureFetcher(previews, cache)),
        viewerFiles = viewerFiles,
    ).let { app -> if (variants.isEmpty()) app else app.withVariants(variants) }

    override fun close() {
        scope.cancel()
        client.close()
    }
}

private fun AppContainer.withVariants(variants: Map<String, List<PreviewVariant>>) = AppContainer(
    catalog = VariantCatalog(catalog, variants),
    launcher = launcher,
    soundPlayer = soundPlayer,
    images = images,
    previews = previews,
    modLibrary = modLibrary,
    migration = migration,
    autoexec = autoexec,
    crosshairs = crosshairs,
    communityCrosshairs = communityCrosshairs,
    preferences = preferences,
    folderPicker = folderPicker,
    folderOpener = folderOpener,
    about = about,
    filePicker = filePicker,
    viewerFiles = viewerFiles,
    news = news,
)

private class VariantCatalog(private val catalog: CatalogRepository, private val variants: Map<String, List<PreviewVariant>>) : CatalogRepository by catalog {
    override val mods: Flow<List<Mod>> = catalog.mods.map { mods -> mods.map(::varied) }
    override fun cachedMod(modId: String): Mod? = catalog.cachedMod(modId)?.let(::varied)
    private fun varied(mod: Mod) = variants[mod.id]?.let { mod.copy(previewVariants = it) } ?: mod
}

@Composable
internal fun HarnessRoot(state: HideoutAppState, viewport: ModelViewport, width: MutableState<Dp?>? = null, density: MutableState<Float?>? = null) {
    val scale = density?.value?.let { Density(it) } ?: LocalDensity.current
    CompositionLocalProvider(
        LocalReducedMotion provides false,
        LocalWindowChrome provides RenderedWindowChrome,
        LocalModelViewport provides viewport,
        LocalDensity provides scale,
    ) {
        HideoutTheme {
            val size = width?.value?.let { Modifier.width(it).fillMaxHeight() } ?: Modifier.fillMaxSize()
            Box(size.onPreviewKeyEvent(state.keys::onPreviewKeyEvent).onKeyEvent(state.keys::onKeyEvent)) { AppContent(state) }
        }
    }
}

internal fun Script.advanceUntil(what: String, maxMillis: Long = 5_000, done: () -> Boolean): Long {
    var waited = 0L
    while (!done()) {
        check(waited < maxMillis) { "No $what within ${maxMillis}ms of frames" }
        advance(16)
        waited += 16
        runUiQueue(2)
    }
    return waited
}

/** Dispatches nowhere, so a scene's effects run only when the scene renders, on the render thread. */
internal object OnRenderThread : CoroutineDispatcher() {
    override fun dispatch(context: CoroutineContext, block: Runnable) = Unit
}

internal fun Script.settle(what: String, timeoutMillis: Long = 90_000, done: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMillis
    while (true) {
        scene.render(time * 1_000_000).close()
        if (done()) break
        check(System.currentTimeMillis() < deadline) { "Timed out waiting for $what" }
        runUiQueue(4)
    }
    scene.render(time * 1_000_000).close()
}

internal fun NativeModelViewport.session(): ViewportSession = sessions.value.single()

internal class Shot(val request: FrameRequest, val pixels: Pixels)

internal fun Script.settleFrame(viewport: NativeModelViewport): Shot {
    settle("a drawn frame") {
        val session = viewport.sessions.value.singleOrNull() ?: return@settle false
        val state = session.state.value
        check(state !is SessionState.Unavailable) { "The viewport cannot draw: ${state.let { it as SessionState.Unavailable }.reason}" }
        state is SessionState.Showing && session.settled && session.shown?.request == session.lastRequest && session.shown?.drawn != 0L
    }
    val frame = checkNotNull(viewport.session().shown)
    return Shot(frame.request, frame.pixels())
}

/** A frame's premultiplied RGBA pixels, bottom row first. */
internal class Pixels(val width: Int, val height: Int, private val rgba: ByteArray) {
    val covered: Int get() = (0 until width * height).count { rgba[it * 4 + 3].toInt() != 0 }

    val sha256: String get() = sha256Hex(rgba)

    fun alphaFacts() = buildJsonObject {
        var transparent = 0
        var opaque = 0
        var visible = 0
        var border = 0
        for (y in 0 until height) for (x in 0 until width) {
            val alpha = rgba[(y * width + x) * 4 + 3].toInt() and 0xff
            if (alpha == 0) transparent++
            if (alpha >= 128) opaque++
            if (alpha >= 16) visible++
            if (x == 0 || y == 0 || x == width - 1 || y == height - 1) border = maxOf(border, alpha)
        }
        put("width", width)
        put("height", height)
        put("pixels", width * height)
        put("transparentPixels", transparent)
        put("opaquePixels", opaque)
        put("visiblePixels", visible)
        put("borderMaxAlpha", border)
    }

    fun covers(x: Int, y: Int): Boolean = rgba[((height - 1 - y) * width + x) * 4 + 3].toInt() != 0

    fun png(): ByteArray {
        val rows = ByteArray(rgba.size)
        for (y in 0 until height) System.arraycopy(rgba, (height - 1 - y) * width * 4, rows, y * width * 4, width * 4)
        return Image.makeRaster(ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.PREMUL), rows, width * 4)
            .use { checkNotNull(it.encodeToData(EncodedImageFormat.PNG)).bytes }
    }

    fun changedFrom(other: Pixels): Int {
        require(width == other.width && height == other.height)
        return (0 until width * height).count { i -> (0..3).any { c -> abs((rgba[i * 4 + c].toInt() and 0xff) - (other.rgba[i * 4 + c].toInt() and 0xff)) > 2 } }
    }

    fun bounds(): List<Int> {
        var left = width; var top = height; var right = -1; var bottom = -1
        for (y in 0 until height) for (x in 0 until width) {
            if (rgba[(y * width + x) * 4 + 3].toInt() == 0) continue
            val screenY = height - 1 - y
            left = minOf(left, x); right = maxOf(right, x); top = minOf(top, screenY); bottom = maxOf(bottom, screenY)
        }
        return listOf(left, top, right, bottom)
    }
}

internal fun RenderedFrame.pixels(): Pixels {
    val bitmap = Bitmap.makeFromImage(image)
    return bitmap.use { Pixels(it.width, it.height, checkNotNull(it.readPixels())) }
}

internal fun Script.press(label: String) {
    val node = clickable(label)
    check(!node.config.contains(SemanticsProperties.Disabled)) { "'$label' is disabled" }
    checkNotNull(node.config[SemanticsActions.OnClick].action).invoke()
    absorb()
}

internal fun Script.absorb() = repeat(AbsorbRenders) {
    scene.render(time * 1_000_000).close()
    runUiQueue(AbsorbPauseMillis)
}

private const val AbsorbRenders = 8
private const val AbsorbPauseMillis = 5L

internal fun Script.enabled(label: String): Boolean = !clickable(label).config.contains(SemanticsProperties.Disabled)

private fun Script.clickable(label: String): SemanticsNode =
    labelled(label).also { check(it.size == 1) { "Expected one clickable '$label', found ${it.size}" } }.single()

/** The harness's root key modifiers only hear keys while something inside them has focus. */
@OptIn(InternalComposeUiApi::class)
internal fun Script.windowKey(page: HideoutAppState, key: Key): Boolean {
    var handled = false
    for (type in listOf(KeyEventType.KeyDown, KeyEventType.KeyUp)) {
        val event = KeyEvent(key, type)
        handled = page.keys.onPreviewKeyEvent(event) || scene.sendKeyEvent(event) || page.keys.onKeyEvent(event) || handled
    }
    absorb()
    return handled
}

internal fun Script.seek(fraction: Float) {
    val scrubber = nodes().single { it.config.contains(SemanticsActions.SetProgress) }
    checkNotNull(scrubber.config[SemanticsActions.SetProgress].action).invoke(fraction)
    absorb()
}

internal fun log(line: String) = println("[preview-checks] $line")
