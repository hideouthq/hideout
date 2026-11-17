@file:OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)

package gg.hideouthq.moddetail

import androidx.compose.ui.graphics.ImageBitmap
import gg.hideouthq.core.model.Backdrop
import gg.hideouthq.core.model.SceneLighting
import gg.hideouthq.core.model.SceneSettings
import gg.hideouthq.feature.moddetail.viewer.LoadingLabels
import gg.hideouthq.feature.moddetail.viewer.SceneLabels
import gg.hideouthq.feature.moddetail.viewer.ViewportCapture
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.painter.ColorPainter
import gg.hideouthq.core.designsystem.image.LocalRemoteImages
import gg.hideouthq.core.designsystem.image.NoRemoteImages
import gg.hideouthq.core.designsystem.image.RemoteImage
import gg.hideouthq.core.designsystem.image.RemoteImages
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import gg.hideouthq.core.ui.LocalPlaybackShortcut
import gg.hideouthq.core.ui.PlaybackShortcut
import gg.hideouthq.data.preview.ScriptedHost
import gg.hideouthq.data.preview.missing
import gg.hideouthq.data.preview.send
import gg.hideouthq.feature.moddetail.FileSelection
import gg.hideouthq.feature.moddetail.ViewerState
import gg.hideouthq.feature.moddetail.viewer.AbilityLabels
import gg.hideouthq.feature.moddetail.viewer.AnimationLabels
import gg.hideouthq.feature.moddetail.viewer.LocalModelViewport
import gg.hideouthq.feature.moddetail.viewer.ModelStage
import gg.hideouthq.feature.moddetail.viewer.ModelViewport
import gg.hideouthq.feature.moddetail.viewer.PreviewModel
import gg.hideouthq.feature.moddetail.viewer.StageActions
import gg.hideouthq.feature.moddetail.viewer.StageContent
import gg.hideouthq.feature.moddetail.viewer.StageLabels
import gg.hideouthq.feature.moddetail.viewer.ToolbarLabels
import gg.hideouthq.feature.moddetail.viewer.ViewerCatalogue
import gg.hideouthq.feature.moddetail.viewer.ViewerCategory
import gg.hideouthq.feature.moddetail.viewer.ViewportLabels
import gg.hideouthq.feature.moddetail.viewer.ViewportScene
import gg.hideouthq.feature.moddetail.viewer.ViewportStatus
import gg.hideouthq.preview.sha256Hex
import gg.hideouthq.viewer.GlThread
import gg.hideouthq.viewer.NativeModelViewport
import gg.hideouthq.preview.AnimationCategory
import gg.hideouthq.preview.PreviewAbility
import gg.hideouthq.preview.PreviewClip
import gg.hideouthq.preview.PreviewIcon
import gg.hideouthq.preview.ResourceOwner
import gg.hideouthq.preview.ResourceSource
import gg.hideouthq.preview.decodeBundleManifest
import gg.hideouthq.preview.decodePreviewIndex
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.Bitmap
import org.junit.Rule
import org.junit.rules.TestRule
import org.junit.runners.model.Statement
import java.awt.EventQueue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ViewerStageTest {
    /**
     * Runs each test, with its [BeforeTest] and [AfterTest], on the AWT event thread, where Compose for desktop runs
     * the layout bookkeeping it defers. Every scene here is built, rendered, used and closed inside them.
     */
    @get:Rule
    val onEventThread = TestRule { test, _ ->
        object : Statement() {
            override fun evaluate() {
                var outcome: Result<Unit>? = null
                EventQueue.invokeAndWait { outcome = runCatching { test.evaluate() } }
                checkNotNull(outcome).getOrThrow()
            }
        }
    }

    private val model = fixtureModel()
    private val stage = Stage(model.catalogue)
    private val space = PlaybackShortcut()
    private var onScreen by mutableStateOf(true)
    private var time = 0L
    private lateinit var scene: ImageComposeScene

    @BeforeTest
    fun open() {
        scene = sceneOf(stage)
        settle()
    }

    @AfterTest
    fun close() = scene.close()

    @Test
    fun anInactivePillOpensItsMenuAndEscapeClosesItAndReturnsToThePillWithoutLeavingTheKeyToTheWindow() {
        click(clickable("Combat"))
        assertEquals("fire", stage.viewer.clipId)
        assertEquals(ViewerCategory.Combat, stage.viewer.openMenu(model.catalogue))
        assertTrue(clickable("Combat").config.contains(SemanticsActions.Collapse), "the open pill offers to collapse")
        assertTrue(clickable("Idle").config.contains(SemanticsActions.Expand))
        click(clickable("Heavy Melee"))
        assertEquals("heavy_melee", stage.viewer.clipId)
        assertEquals(ViewerCategory.Combat, stage.viewer.openMenu(model.catalogue), "a row keeps its menu open")
        assertEquals(true, clickable("Heavy Melee").config.getOrNull(SemanticsProperties.Selected))

        assertTrue(key(Key.Escape), "Esc is the stage's while a menu is open")
        assertNull(stage.viewer.openMenu(model.catalogue))
        assertEquals(true, clickable("Combat").config.getOrNull(SemanticsProperties.Focused), "focus returns to the pill")
        assertFalse(key(Key.Escape), "with no menu Esc is left for the window")
    }

    @Test
    fun arrowsAndNumberKeysActOnlyWhileFocusIsInTheStageAndUnmodified() {
        assertFalse(key(Key.DirectionRight), "nothing in the stage has focus yet")
        assertNull(stage.viewer.clipId)
        click(node("3D preview").boundsInRoot.center)
        assertTrue(key(Key.DirectionLeft))
        assertEquals("ultimate", stage.viewer.clipId, "← from the first clip wraps to the last")
        assertTrue(key(Key.DirectionRight))
        assertEquals("idle", stage.viewer.clipId)
        assertFalse(key(Key.DirectionRight, shift = true))
        assertEquals("idle", stage.viewer.clipId)

        click(clickable("Combat"))
        assertFalse(key(Key.Two), "slot 2 has no clip")
        assertEquals(ViewerCategory.Combat, stage.viewer.openMenu(model.catalogue))
        assertTrue(key(Key.Four))
        assertEquals("ultimate", stage.viewer.clipId)
        assertNull(stage.viewer.openMenu(model.catalogue), "an ability closes the menu")
        assertEquals(ViewerCategory.Abilities, stage.viewer.activeCategory(model.catalogue))
    }

    @Test
    fun spacePlaysAndPausesTheDrawnModelOnlyWhileItsStageIsShown() {
        assertTrue(space.toggle())
        assertFalse(stage.viewer.playing)
        onScreen = false
        frames(2)
        assertFalse(space.toggle(), "with the Images tab in its place, Space is left for the window")
        assertFalse(stage.viewer.playing)
    }

    @Test
    fun pressingTheStageClosesTheMenuAndTheTimelineCannotSeekWhileItIsOpen() {
        assertTrue(nodes().none { it.config.contains(SemanticsActions.SetProgress) }, "the rest pose has no time to seek")
        click(clickable("Ultimate"))
        assertTrue(nodes().any { it.config.contains(SemanticsActions.SetProgress) })
        click(clickable("Combat"))
        assertEquals(ViewerCategory.Combat, stage.viewer.openMenu(model.catalogue))
        assertTrue(nodes().none { it.config.contains(SemanticsActions.SetProgress) }, "the hidden timeline does not seek")
        assertTrue(nodes().any { it.config.contains(SemanticsProperties.ProgressBarRangeInfo) }, "but it is still there")
        click(node("step through all 4"))
        assertEquals(ViewerCategory.Combat, stage.viewer.openMenu(model.catalogue), "the menu's own surface keeps it open")
        click(node("3D preview").boundsInRoot.center)
        assertNull(stage.viewer.openMenu(model.catalogue))
        assertTrue(nodes().any { it.config.contains(SemanticsActions.SetProgress) })
    }

    @Test
    fun aTileWithoutAClipKeepsItsIconAndExplainsButPlaysNothing() {
        val missing = node("Ability 2")
        assertFalse(missing.config.contains(SemanticsActions.OnClick))
        assertEquals("no clip", missing.config.getOrNull(SemanticsProperties.StateDescription))
        click(clickable("Ultimate"))
        assertEquals("ultimate", stage.viewer.clipId)
        assertEquals(true, clickable("Ultimate").config.getOrNull(SemanticsProperties.Selected))
    }

    @Test
    fun anArrowThatTakesAFocusedRowsMenuToAnotherPillLeavesTheNextKeyWithTheStage() {
        click(clickable("Combat"))
        click(clickable("Heavy Melee"))
        focus(clickable("Heavy Melee"))
        assertTrue(key(Key.DirectionRight))
        assertEquals("ultimate", stage.viewer.clipId)
        assertEquals(ViewerCategory.Abilities, stage.viewer.openMenu(model.catalogue))
        assertTrue(key(Key.DirectionRight), "the focused row is gone, but the stage still has the keys")
        assertEquals("idle", stage.viewer.clipId)
    }

    @Test
    fun aFocusedRowKeepsFocusForAnAbsentAbilityAndAnAbilityKeyLeavesTheNextKeyWithTheStage() {
        click(clickable("Combat"))
        focus(clickable("Fire"))
        assertFalse(key(Key.Two), "slot 2 has no clip")
        assertEquals(true, clickable("Fire").config.getOrNull(SemanticsProperties.Focused))
        assertTrue(key(Key.NumPad4), "the keypad's 4 plays the ultimate too")
        assertEquals("ultimate", stage.viewer.clipId)
        assertNull(stage.viewer.openMenu(model.catalogue))
        assertTrue(key(Key.DirectionLeft), "the menu is closed, but the stage still has the keys")
        assertEquals("heavy_melee", stage.viewer.clipId)
    }

    @Test
    fun tabFromTheOpenPillReachesItsMenuBeforeTheStripAndEveryControlInTurn() {
        click(clickable("Combat"))
        assertEquals(true, clickable("Combat").config.getOrNull(SemanticsProperties.Focused))
        val order = List(16) {
            key(Key.Tab)
            nodes().firstOrNull { it.config.getOrNull(SemanticsProperties.Focused) == true }?.names().orEmpty()
        }
        val row = order.indexOfFirst { "Fire" in it || "Heavy Melee" in it }
        assertTrue(row >= 0 && row < order.indexOfFirst { "Ultimate" in it }, "the menu comes before the strip: $order")
        val reached = order.flatten()
        for (control in listOf("Pause animation", "Idle", "Abilities", "Playback speed", "Loop", "Fire", "Heavy Melee", "Ability 1", "Ability 2", "Ability 3", "Ultimate")) {
            assertTrue(control in reached, "Tab reaches $control: $order")
        }
    }

    @Test
    fun aTileClickedWhileNothingHasFocusTakesItSoTheStageHasItsKeys() {
        click(clickable("Ultimate"))
        assertEquals("ultimate", stage.viewer.clipId)
        assertEquals(true, clickable("Ultimate").config.getOrNull(SemanticsProperties.Focused))
        assertTrue(key(Key.DirectionLeft))
        assertEquals("heavy_melee", stage.viewer.clipId)
    }

    @Test
    fun aPressedTileShrinksOnlyWithoutReducedMotion() {
        fun pressedWidth(reduced: Boolean): Float {
            val other = sceneOf(Stage(model.catalogue), reduced)
            try {
                settle(other)
                fun tile() = other.semanticsOwners.flatMap { all(it.rootSemanticsNode) }.single { it.named("Ultimate") && it.config.contains(SemanticsActions.OnClick) }.boundsInRoot
                val rest = tile()
                other.sendPointerEvent(PointerEventType.Move, rest.center)
                other.sendPointerEvent(PointerEventType.Press, rest.center, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
                settle(other)
                return tile().width / rest.width
            } finally {
                other.close()
            }
        }
        assertTrue(pressedWidth(reduced = false) < 1f, "the press shrinks the tile")
        assertEquals(1f, pressedWidth(reduced = true), "reduced motion keeps the tile still")
    }

    @Test
    fun aModelThatFailedKeepsItsStillWithRetryWhileOneThatCannotRetryIsNoFailure() {
        val cases = listOf(
            ViewportStatus.Unavailable(still = true) to true,
            ViewportStatus.Unavailable(still = true, canRetry = false) to false,
        )
        for ((status, failed) in cases) {
            scene.close()
            scene = sceneOf(Stage(model.catalogue), viewport = StatusViewport(mutableStateOf(status)))
            settle()
            val still = node("3D preview").boundsInRoot
            val shown = scene.render(time * 1_000_000).use { frame ->
                Bitmap.makeFromImage(frame).use { it.getColor((still.left + 20).toInt(), still.center.y.toInt()) }
            }
            assertEquals(StillColour, shown, "the still stays in view: $status")
            assertEquals(failed, nodes().any { it.named("Retry") }, "Retry: $status")
            assertEquals(failed, nodes().any { it.named("See images instead") }, "See images instead: $status")
            assertEquals(!failed, nodes().any { it.named("still") }, "the still's hint: $status")
        }
    }

    @Test
    fun whileTheModelLoadsTheBarAndTheStripTakeNoInput() {
        val status = mutableStateOf<ViewportStatus>(ViewportStatus.Loading(0.4f, 40, 100))
        val loading = Stage(model.catalogue)
        scene.close()
        scene = sceneOf(loading, viewport = StatusViewport(status))
        settle()
        val strip = node("abilities").boundsInRoot
        val play = clickable("Play animation").boundsInRoot
        val bar = nodes().filter { it.config.contains(SemanticsActions.OnClick) && abs(it.boundsInRoot.center.y - play.center.y) < 1f }
        val controls = bar + nodes().filter { it.config.contains(SemanticsActions.OnClick) && strip.contains(it.boundsInRoot.center) }
        val labels = (bar + nodes().filter { strip.contains(it.boundsInRoot.center) }).flatMap { it.names() }.toSet()
        assertTrue(controls.size >= 5 && controls.all { it.config.contains(SemanticsProperties.Disabled) }, "disabled while loading: $labels")
        controls.forEach { it.config[SemanticsActions.OnClick].action?.invoke() }
        assertFalse(space.toggle(), "nor does Space")
        settle()
        assertEquals(ViewerState(), loading.viewer, "an accessibility action reached the stage")
        val reached = (1..TabStops).map { key(Key.Tab); nodes().firstOrNull { it.config.getOrNull(SemanticsProperties.Focused) == true }?.names().orEmpty() }
        assertTrue(reached.none { names -> names.any { it in labels } }, "Tab stopped in the bar or the strip: $reached")

        status.value = ViewportStatus.Showing
        settle()
        assertFalse(clickable("Pause animation").config.contains(SemanticsProperties.Disabled))
    }

    @Test
    fun whileTheModelLoadsTheCapsuleNeverStandsWithoutTheModsPicture() {
        val capsule = "Loading Silver · Default"
        val loaded = mutableStateOf(false)
        val status = mutableStateOf<ViewportStatus>(ViewportStatus.Loading(0.4f, 40, 100))
        val pictures = RemoteImages { _, _ -> remember { RemoteImage(ColorPainter(Color.Red), loaded) } }
        scene.close()
        scene = sceneOf(stage, viewport = StatusViewport(status), pictures = pictures, picture = "bundle")
        settle()
        assertFalse(nodes().any { it.named(capsule) }, "the capsule came before the picture")

        loaded.value = true
        settle()
        assertTrue(nodes().any { it.named(capsule) }, "the capsule stayed away with the picture in")

        status.value = ViewportStatus.Showing
        settle()
        assertFalse(nodes().any { it.named(capsule) }, "the capsule outlived the load")
    }

    @Test
    fun aPictureThatCannotBeHadLeavesTheCapsuleAlone() {
        val failed = mutableStateOf(false)
        val pictures = RemoteImages { _, _ -> remember { RemoteImage(ColorPainter(Color.Red), mutableStateOf(false), failed = failed) } }
        scene.close()
        scene = sceneOf(stage, viewport = StatusViewport(mutableStateOf(ViewportStatus.Loading(0.4f, 40, 100))), pictures = pictures, picture = "bundle")
        settle()
        assertFalse(nodes().any { it.named("Loading Silver · Default") }, "the capsule came before the picture was given up on")

        failed.value = true
        settle()
        assertTrue(nodes().any { it.named("Loading Silver · Default") }, "the load went unshown with no picture to wait for")
    }

    private fun sceneOf(
        stage: Stage,
        reduced: Boolean = false,
        viewport: ModelViewport = DrawnViewport,
        shown: PreviewModel = model,
        pictures: RemoteImages = NoRemoteImages,
        picture: String? = null,
    ) = ImageComposeScene(1000, 640, Density(1f), Dispatchers.Unconfined) {
        CompositionLocalProvider(LocalModelViewport provides viewport, LocalPlaybackShortcut provides space, LocalRemoteImages provides pictures) {
            HideoutTheme(reducedMotion = reduced) {
                Box {
                    if (onScreen) ModelStage(StageContent("Silver", "Default", shown, pictureBundleId = picture), stage.viewer, stage, Labels, height = 560.dp)
                }
            }
        }
    }

    private fun frames(count: Int, millis: Long = 16, on: ImageComposeScene = scene) = repeat(count) {
        time += millis
        on.render(time * 1_000_000).close()
    }

    private fun settle(on: ImageComposeScene = scene) {
        frames(3, on = on)
        frames(6, millis = 100, on = on)
    }

    private fun all(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::all)

    private fun nodes(): List<SemanticsNode> = scene.semanticsOwners.flatMap { all(it.rootSemanticsNode) }

    private fun SemanticsNode.named(label: String) =
        config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true ||
            config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true

    private fun SemanticsNode.names(): List<String> =
        config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } + config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()

    private fun node(label: String): SemanticsNode = nodes().single { it.named(label) }

    private fun clickable(label: String): SemanticsNode = nodes().single { it.named(label) && it.config.contains(SemanticsActions.OnClick) }

    private fun click(node: SemanticsNode) = click(node.boundsInRoot.center)

    private fun focus(node: SemanticsNode) {
        assertEquals(true, node.config[SemanticsActions.RequestFocus].action?.invoke())
        frames(5)
    }

    private fun click(at: Offset) {
        scene.sendPointerEvent(PointerEventType.Move, at)
        for (type in listOf(PointerEventType.Press, PointerEventType.Release)) {
            scene.sendPointerEvent(type, at, buttons = PointerButtons(isPrimaryPressed = type == PointerEventType.Press), button = PointerButton.Primary)
            frames(2)
        }
        settle()
    }

    private fun key(key: Key, shift: Boolean = false): Boolean {
        val handled = scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyDown, isShiftPressed = shift))
        scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyUp, isShiftPressed = shift))
        settle()
        return handled
    }

    private class Stage(private val catalogue: ViewerCatalogue) : StageActions {
        var viewer by mutableStateOf(ViewerState())

        override fun pickCategory(category: ViewerCategory) { viewer = viewer.pickCategory(category, catalogue) }
        override fun playClip(id: String) { viewer = viewer.playClip(id, catalogue) }
        override fun stepClip(by: Int) { viewer = viewer.stepClip(by, catalogue) }
        override fun playAbility(slot: Int) { viewer = viewer.playAbility(slot, catalogue) }
        override fun closeMenu() { viewer = viewer.closeMenu() }
        override fun togglePlaying() { viewer = viewer.togglePlaying() }
        override fun animationFinished() { viewer = viewer.finished() }
        override fun cycleSpeed() { viewer = viewer.nextSpeed() }
        override fun setLoop(loop: Boolean) { viewer = viewer.copy(loop = loop) }
        override fun setScene(settings: SceneSettings) = Unit
        override fun chooseBackdrop() = Unit
        override fun closeScene() { viewer = viewer.copy(sceneOpen = false) }
        override fun pickVariant(file: FileSelection) = Unit
        override fun retryPreview() = Unit
        override fun showImages() = Unit
        override fun savePicture(picture: ImageBitmap) = Unit
    }

    private object DrawnViewport : ModelViewport {
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
            LaunchedEffect(Unit) { onStatus(ViewportStatus.Showing) }
            Box(modifier.semantics { contentDescription = labels.description })
        }
    }

    private class StatusViewport(private val status: State<ViewportStatus>) : ModelViewport {
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
            val current = status.value
            LaunchedEffect(current) { onStatus(current) }
            val still = (current as? ViewportStatus.Unavailable)?.still == true
            Box(modifier.then(if (still) Modifier.background(Color(StillColour)) else Modifier).semantics { contentDescription = labels.description })
        }
    }

    private companion object {
        const val StillColour = 0xFF2E6B8F.toInt()
        const val TabStops = 20

        fun fixtureModel(): PreviewModel {
            val store = Path.of(checkNotNull(System.getProperty("preview.fixture")) { "Run with ./kotlin test, which sets preview.fixture from module.yaml" })
            val entry = decodePreviewIndex(Files.readString(store.resolve("index.json"))).entries.single()
            val manifest = decodeBundleManifest(Files.readString(store.resolve("bundles/${entry.bundleId}/manifest.json")))
            fun icon(slot: Int) = PreviewIcon("icons/ability_$slot.png", ResourceSource(ResourceOwner.Game, "panorama/images/hud/abilities/a$slot.vtex_c", "c".repeat(64)))
            return PreviewModel(
                entry,
                manifest.copy(
                    clips = listOf(
                        PreviewClip("idle", 1_000, AnimationCategory.Idle),
                        PreviewClip("fire", 300, AnimationCategory.Combat),
                        PreviewClip("heavy_melee", 1_400, AnimationCategory.Combat),
                        PreviewClip("ultimate", 2_400, AnimationCategory.Abilities),
                    ),
                    abilities = (1..4).map { slot ->
                        PreviewAbility(slot, "ability_$slot", if (slot == 4) "Ultimate" else "Ability $slot", clipId = "ultimate".takeIf { slot == 4 }, icon = icon(slot))
                    },
                ),
            )
        }

        val Labels = StageLabels(
            hint = "hint",
            stillHint = "still",
            viewport = ViewportLabels("Hero model", "3D preview"),
            toolbar = ToolbarLabels("Play animation", "Pause animation", "Playback speed", "Loop"),
            animations = AnimationLabels(
                categories = ViewerCategory.entries.associateWith { it.name },
                categoryChanged = "changed pill",
                clipChanged = "changed clip",
                changedTag = "MOD",
                lengths = emptyMap(),
                stepThrough = "step through all 4",
            ),
            abilities = AbilityLabels("abilities", "changed", "icon changed", "animation changed", "unchanged", "no clip", "icon changed, no clip"),
            loading = LoadingLabels("Loading Silver · Default", "failed", "Retry", "See images instead") { done, total -> "$done of $total" },
            scene = SceneLabels(
                "Scene", "Reset", "Backdrop", "Lighting", "Stage",
                Backdrop.entries.associateWith { it.name }, SceneLighting.entries.associateWith { it.name }, "Turntable and spotlight", "Bloom glow",
            ),
            variants = "Variants",
        )
    }
}
