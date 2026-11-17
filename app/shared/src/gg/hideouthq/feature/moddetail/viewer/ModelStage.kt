package gg.hideouthq.feature.moddetail.viewer

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.min
import androidx.compose.ui.zIndex
import gg.hideouthq.core.data.preview.PreviewImageAddress
import gg.hideouthq.core.designsystem.component.ButtonVariant
import gg.hideouthq.core.designsystem.component.HideoutButton
import gg.hideouthq.core.designsystem.component.HideoutIcon
import gg.hideouthq.core.designsystem.icon.HideoutIcons
import gg.hideouthq.core.designsystem.image.LocalRemoteImages
import gg.hideouthq.core.designsystem.image.RemoteImage
import gg.hideouthq.core.designsystem.interaction.focusRing
import gg.hideouthq.core.designsystem.theme.HideoutDimens
import gg.hideouthq.core.designsystem.theme.HideoutMotion
import gg.hideouthq.core.designsystem.theme.HideoutShapes
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import gg.hideouthq.core.model.Backdrop
import gg.hideouthq.core.model.SceneSettings
import gg.hideouthq.core.ui.LocalFocusVisibility
import gg.hideouthq.core.ui.PlaybackShortcutEffect
import gg.hideouthq.feature.moddetail.ViewerState
import gg.hideouthq.preview.PictureRole
import gg.hideouthq.preview.PreviewClip
import gg.hideouthq.resources.Res
import gg.hideouthq.resources.stage_preview_3d
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * The timeline is the only animation clock; playback controls work only while the viewport really draws the model,
 * which [onStatus] also reports so tools outside the stage can follow it. While [StageContent.held] no viewport exists:
 * the stage shows the mod's picture with an action to load the model.
 */
@Composable
fun ModelStage(
    content: StageContent,
    viewer: ViewerState,
    actions: StageActions,
    labels: StageLabels,
    modifier: Modifier = Modifier,
    height: Dp = HideoutDimens.Stage.Height,
    choreography: StageChoreography = StageChoreography.None,
    scene: SceneSettings = SceneSettings(),
    onStatus: (ViewportStatus) -> Unit = {},
) {
    val colors = HideoutTheme.colors
    val scheme = MaterialTheme.colorScheme
    val motion = HideoutTheme.motion
    val catalogue = content.model?.catalogue ?: ViewerCatalogue.Empty
    val clip = catalogue.resolve(viewer.clipId)
    var status by remember(content.model, viewer.loadAttempts) { mutableStateOf<ViewportStatus>(ViewportStatus.NoModel) }
    val showing = status == ViewportStatus.Showing
    val phase = stagePhase(content, status)
    val held = phase == StagePhase.Held
    val loading = phase == StagePhase.Loading
    val failed = phase == StagePhase.Failed
    val hidden = loading || failed && (status as? ViewportStatus.Unavailable)?.still != true
    val picture = content.pictureBundleId?.let { rememberStagePicture(it) }
    val awaitedPicture = picture != null && !picture.failed
    val pictureShown = !failed && (held || loading || status == ViewportStatus.NoModel)
    val pictureAlpha by animateFloatAsState(if (pictureShown && picture?.loaded == true) 1f else 0f, tween(RevealMillis), label = "stagePicture")
    val capsuleUp by remember(awaitedPicture) { derivedStateOf { !awaitedPicture || pictureAlpha > 0f } }
    val menu = viewer.openMenu(catalogue).takeIf { showing }
    PlaybackShortcutEffect(enabled = showing, onToggle = actions::togglePlaying)
    val clock = rememberPlaybackClock(
        clip = clip,
        playing = viewer.playing,
        speed = viewer.speed,
        loop = viewer.loop,
        running = showing,
        onFinished = actions::animationFinished,
    )
    val stageFocus = remember { FocusRequester() }
    val pillFocus = remember { ViewerCategory.entries.associateWith { FocusRequester() } }
    val anchors = remember { PillAnchors() }
    var focused by remember { mutableStateOf(false) }
    var menuFocused by remember { mutableStateOf(false) }
    var dragged by remember { mutableStateOf(false) }
    val keyboard = LocalFocusVisibility.current.keyboardInUse
    val press = rememberUpdatedState {
        stageFocus.requestFocus()
        if (menu != null) actions.closeMenu()
    }
    val photo = if (scene.shownBackdrop == Backdrop.Custom) content.customBackdrop else backdropPhoto(scene.shownBackdrop)
    val scenery = Scenery(scene, photo, colors, scheme.primary, scheme.secondary, scheme.surfaceContainerHighest, scheme.outlineVariant)
    val spin = rememberSpin(viewer.autoSpin && showing && motion.continuousMotion, viewer.cameraResets)
    val arrival = remember(content.model, viewer.loadAttempts) { Animatable(0f) }
    val shown = remember(content.model, viewer.loadAttempts) { Animatable(1f) }
    LaunchedEffect(shown, hidden, showing) {
        when {
            hidden -> shown.snapTo(0f)
            showing && shown.value < 1f -> {
                if (!motion.reduced) launch {
                    arrival.snapTo(-HideoutDimens.Turntable.ArrivalSpinDegrees)
                    arrival.animateTo(0f, tween(ArrivalMillis, easing = HideoutMotion.EmphasizedDecelerate))
                }
                shown.animateTo(1f, if (motion.reduced) snap() else tween(RevealMillis))
            }
            else -> shown.snapTo(1f)
        }
    }
    val spinDegrees = { spin.floatValue + arrival.value }
    val progress = (status as? ViewportStatus.Loading)?.fraction ?: 0f
    val capture = remember { ViewportCapture() }
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(height)
            .drawBehind {
                drawBackdrop(scenery)
                if (scene.turntable) drawCone(scene.lighting, scenery.primary)
            }
            .onGloballyPositioned { anchors.stage = it.positionInRoot() }
            .focusRing(focused && keyboard, HideoutShapes.Stage)
            .focusRequester(stageFocus)
            .onFocusChanged { focused = it.isFocused }
            .onKeyEvent { event -> stageKey(event, catalogue, clip, menu, showing, actions, pillFocus, stageFocus.takeIf { menuFocused }) }
            .focusTarget(),
    ) {
        val stageWidth = maxWidth
        val stageHeight = maxHeight
        SavePictureEffect(viewer.pictureRequests, DpSize(stageWidth, stageHeight), scenery, spinDegrees, capture, actions::savePicture)
        Box(
            Modifier
                .matchParentSize()
                .pointerInput(showing) {
                    awaitPointerEventScope {
                        var down: Offset? = null
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val position = event.changes.firstOrNull()?.position ?: continue
                            when (event.type) {
                                PointerEventType.Press -> {
                                    press.value()
                                    down = position
                                }
                                PointerEventType.Move -> if (showing && down?.let { (position - it).getDistance() > viewConfiguration.touchSlop } == true) {
                                    dragged = true
                                    down = null
                                }
                                PointerEventType.Release -> down = null
                            }
                        }
                    }
                },
        ) {
            val area = min(HideoutDimens.Stage.ModelArea, stageWidth - HideoutDimens.Stage.ModelAreaMinInset)
            val areaTop = stageHeight * HideoutDimens.Stage.ModelCenterFraction - HideoutDimens.Stage.ModelArea / 2
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = areaTop)
                    .size(area, HideoutDimens.Stage.ModelArea)
                    .then(choreography.model)
                    .drawBehind { drawTurntable(scenery, Rect(Offset.Zero, size), TurntablePose(spinDegrees(), loading, progress)) },
            ) {
                if (!held) LocalModelViewport.current.Content(
                    scene = ViewportScene(
                        heroName = content.heroName,
                        styleName = content.styleName,
                        model = content.model,
                        clip = clip,
                        lighting = scene.lighting,
                        bloom = scene.bloom,
                        cameraResets = viewer.cameraResets,
                        loadAttempts = viewer.loadAttempts,
                    ),
                    timeMillis = { clock.elapsedMillis },
                    spinDegrees = spinDegrees,
                    labels = labels.viewport,
                    onStatus = {
                        status = it
                        onStatus(it)
                    },
                    capture = capture,
                    modifier = Modifier.matchParentSize().graphicsLayer { alpha = shown.value },
                )
                picture?.let {
                    StagePicture(
                        image = it,
                        alpha = { pictureAlpha },
                        onUnavailable = {
                            if (held) {
                                val unavailable = ViewportStatus.Unavailable(canRetry = false)
                                status = unavailable
                                onStatus(unavailable)
                            }
                        },
                        modifier = Modifier.matchParentSize(),
                    )
                }
                if (loading && capsuleUp) {
                    val loaded = status as? ViewportStatus.Loading
                    val bytes = loaded?.takeIf { it.totalBytes > 0 }?.let { labels.loading.progress(it.doneBytes, it.totalBytes) }
                    LoadingCapsule(
                        labels.loading.title,
                        bytes,
                        loaded?.fraction,
                        Modifier.matchParentSize().graphicsLayer { alpha = if (awaitedPicture) pictureAlpha else 1f },
                        overPicture = awaitedPicture,
                    )
                }
                if (failed) {
                    Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(HideoutDimens.Stage.CapsuleHeight), contentAlignment = Alignment.Center) {
                        LoadFailure(labels.loading, actions::retryPreview, actions::showImages, Modifier.blockStagePointerHit(), overPicture = (status as? ViewportStatus.Unavailable)?.still == true)
                    }
                }
            }
            Box(Modifier.matchParentSize().drawBehind { drawVignette() })
            val hint = when (val current = status) {
                is ViewportStatus.Unavailable -> if (current.still) labels.stillHint to HideoutIcons.Image else null
                else -> if (held) labels.stillHint to HideoutIcons.Image else labels.hint to HideoutIcons.Reset
            }
            val hintAlpha by animateFloatAsState(
                when {
                    dragged -> 0f
                    loading -> HideoutDimens.Stage.LoadingAlpha
                    else -> 1f
                },
                motion.effectsDefault(),
                label = "hint",
            )
            if (hint != null && !failed) {
                Hint(
                    hint.first,
                    hint.second,
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(end = HideoutDimens.Stage.HintEnd, top = HideoutDimens.Stage.HintTop)
                        .graphicsLayer { alpha = hintAlpha }
                        .then(choreography.resolve),
                )
            }
            val timed = (clip?.durationMillis ?: 0) > 0
            val timeline by animateFloatAsState(if (timed && menu == null && !held && !loading && !failed) 1f else 0f, motion.effectsFast(), label = "timeline")
            WavyScrubber(
                clock = clock,
                playing = viewer.playing && showing,
                name = clip?.let(catalogue::label),
                onSeek = if (timed && showing && menu == null) clock::seekTo else null,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = HideoutDimens.Scrubber.Bottom)
                    .widthIn(max = min(HideoutDimens.Scrubber.MaxWidth, stageWidth - HideoutDimens.Scrubber.SideInset))
                    .fillMaxWidth()
                    .graphicsLayer { alpha = timeline },
            )
        }
        if (content.variants.size > 1) {
            VariantsMenu(
                variants = content.variants,
                selected = content.selectedVariant,
                title = labels.variants,
                onPick = actions::pickVariant,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(HideoutDimens.Variants.Inset)
                    .then(choreography.toolbar)
                    .blockStagePointerHit(),
            )
        }
        val strip = catalogue.abilities.isNotEmpty()
        val menuEnd = if (strip) stageWidth - HideoutDimens.AbilityStrip.End - StripWidth else stageWidth - HideoutDimens.AnimationMenu.StageInset
        val pill = menu?.let { anchors.pills[it] }
        val anchor = pill?.let { with(LocalDensity.current) { menuAnchor((it.left - anchors.stage.x).toDp(), (it.right - anchors.stage.x).toDp(), HideoutDimens.AnimationMenu.StageInset, menuEnd) } }
        if (held) {
            if (content.canLoad) LoadPreviewAction(actions::loadPreview, Modifier.align(Alignment.BottomCenter).padding(bottom = HideoutDimens.Toolbar.Bottom).then(choreography.toolbar))
        } else AnimationBar(
            groups = catalogue.groups,
            active = clip?.let(catalogue::categoryOf),
            open = menu,
            onPick = actions::pickCategory,
            playing = viewer.playing && showing,
            onTogglePlaying = actions::togglePlaying,
            speedLabel = speedLabel(viewer.speed),
            onCycleSpeed = actions::cycleSpeed,
            loop = viewer.loop,
            onLoopChange = actions::setLoop,
            labels = labels.toolbar,
            animationLabels = labels.animations,
            pillFocus = pillFocus,
            onPillPlaced = { category, coordinates -> anchors.pills[category] = coordinates.boundsInRoot() },
            enabled = showing,
            dimmed = loading,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = HideoutDimens.Toolbar.Bottom)
                .widthIn(max = stageWidth - HideoutDimens.Toolbar.SideInset)
                .then(choreography.toolbar)
                .blockStagePointerHit()
                // Composed before the menu so Tab reaches the menu after the bar, but drawn and hit over it as before.
                .zIndex(1f),
        )
        AnimationMenu(
            content = menu?.let(catalogue::group)?.let { group ->
                AnimationMenuContent(
                    category = group.category,
                    title = labels.animations.categories.getValue(group.category),
                    rows = group.menu.map { AnimationMenuRow(it.id, catalogue.label(it), labels.animations.lengths[it.id].orEmpty(), it.changedByMod) },
                )
            },
            anchor = anchor,
            playingId = clip?.id,
            changedTag = labels.animations.changedTag,
            changedDescription = labels.animations.clipChanged,
            footer = labels.animations.stepThrough,
            onPlay = actions::playClip,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(bottom = HideoutDimens.Toolbar.Bottom + HideoutDimens.Toolbar.Height + HideoutDimens.AnimationMenu.Gap)
                .onFocusChanged { menuFocused = it.hasFocus }
                .blockStagePointerHit(),
        )
        if (strip) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .fillMaxHeight(HideoutDimens.Stage.ModelCenterFraction * 2)
                    .padding(end = HideoutDimens.AbilityStrip.End),
                contentAlignment = Alignment.Center,
            ) {
                AbilityStrip(
                    tiles = abilityTiles(content.model?.entry?.bundleId.orEmpty(), catalogue, clip, labels.abilities),
                    label = labels.abilities.strip,
                    onPlay = actions::playAbility,
                    enabled = showing,
                    dimmed = loading,
                    modifier = choreography.toolbar.blockStagePointerHit(),
                )
            }
        }
        if (viewer.sceneOpen) ScenePanel(scene, labels.scene, content.customBackdrop, actions::setScene, actions::chooseBackdrop, actions::closeScene)
    }
}

internal enum class StagePhase { Held, Loading, Failed, Settled }

internal fun stagePhase(content: StageContent, status: ViewportStatus): StagePhase = when {
    content.held -> StagePhase.Held
    content.pending || status is ViewportStatus.Loading || content.model != null && status == ViewportStatus.NoModel -> StagePhase.Loading
    content.failed || (status as? ViewportStatus.Unavailable)?.canRetry == true -> StagePhase.Failed
    else -> StagePhase.Settled
}

@Composable
private fun rememberStagePicture(bundleId: String): RemoteImage? {
    val images = LocalRemoteImages.current
    val detail = images.rememberImage(PreviewImageAddress.Picture(bundleId, PictureRole.Detail).url, ContentScale.Fit) ?: return null
    return if (detail.failed) images.rememberImage(PreviewImageAddress.Picture(bundleId, PictureRole.Featured).url, ContentScale.Fit) else detail
}

@Composable
private fun StagePicture(image: RemoteImage, alpha: () -> Float, onUnavailable: () -> Unit, modifier: Modifier = Modifier) {
    val failed = image.failed
    LaunchedEffect(failed) { if (failed) onUnavailable() }
    Image(image.painter, null, modifier.then(image.layoutModifier).graphicsLayer { this.alpha = alpha() }, contentScale = ContentScale.Fit)
}

@Composable
internal fun LoadPreviewAction(onClick: () -> Unit, modifier: Modifier = Modifier) {
    HideoutButton(
        text = stringResource(Res.string.stage_preview_3d),
        onClick = onClick,
        modifier = modifier.blockStagePointerHit(),
        variant = ButtonVariant.Primary,
        leadingIcon = HideoutIcons.Cube,
    )
}

@Composable
private fun rememberSpin(running: Boolean, cameraResets: Int): MutableFloatState {
    val spin = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(cameraResets) { spin.floatValue = 0f }
    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            spin.floatValue = (spin.floatValue + (now - last) / NanosPerSecond * HideoutDimens.Turntable.SpinDegreesPerSecond) % FullTurn
            last = now
        }
    }
    return spin
}

@Composable
private fun SavePictureEffect(requests: Int, stage: DpSize, scenery: Scenery, spinDegrees: () -> Float, capture: ViewportCapture, onPicture: (ImageBitmap) -> Unit) {
    val answered = remember { mutableIntStateOf(requests) }
    val current by rememberUpdatedState(scenery)
    val size by rememberUpdatedState(stage)
    LaunchedEffect(requests) {
        if (requests == answered.intValue) return@LaunchedEffect
        answered.intValue = requests
        drawStagePicture(size, current, TurntablePose(spinDegrees(), loading = false, progress = 0f), capture)?.let(onPicture)
    }
}

private fun Modifier.blockStagePointerHit(): Modifier = pointerInput(Unit) {}

@Stable
private class PillAnchors {
    var stage by mutableStateOf(Offset.Zero)
    val pills = mutableStateMapOf<ViewerCategory, Rect>()
}

private fun stageKey(
    event: KeyEvent,
    catalogue: ViewerCatalogue,
    clip: PreviewClip?,
    menu: ViewerCategory?,
    showing: Boolean,
    actions: StageActions,
    pillFocus: Map<ViewerCategory, FocusRequester>,
    focusBeforeMenuRemoval: FocusRequester?,
): Boolean {
    if (event.type != KeyEventType.KeyDown || event.isAltPressed || event.isCtrlPressed || event.isMetaPressed || event.isShiftPressed) return false
    val step = StepKeys[event.key]
    val slot = AbilityKeys[event.key]
    return when {
        event.key == Key.Escape && menu != null -> {
            actions.closeMenu()
            pillFocus.getValue(menu).requestFocus()
            true
        }
        !showing -> false
        step != null && catalogue.clips.isNotEmpty() -> {
            val next = checkNotNull(catalogue.step(clip, step))
            if (catalogue.categoryOf(next) != menu) focusBeforeMenuRemoval?.requestFocus()
            actions.stepClip(step)
            true
        }
        slot != null && catalogue.abilityClip(slot) != null -> {
            focusBeforeMenuRemoval?.requestFocus()
            actions.playAbility(slot)
            true
        }
        else -> false
    }
}

private val StepKeys = mapOf(Key.DirectionLeft to -1, Key.DirectionRight to 1)
private val AbilityKeys = mapOf(
    Key.One to 1, Key.Two to 2, Key.Three to 3, Key.Four to 4,
    Key.NumPad1 to 1, Key.NumPad2 to 2, Key.NumPad3 to 3, Key.NumPad4 to 4,
)

internal fun abilityTiles(bundleId: String, catalogue: ViewerCatalogue, playing: PreviewClip?, labels: AbilityLabels): List<AbilityTile> =
    catalogue.abilities.map { ability ->
        val clip = catalogue.abilityClip(ability.slot)
        val icon = ability.icon.changedByMod
        val animation = clip?.changedByMod == true
        AbilityTile(
            slot = ability.slot,
            name = ability.name,
            image = PreviewImageAddress.Icon(bundleId, ability.icon.asset).url,
            detail = when {
                clip == null -> if (icon) labels.noClipChanged else labels.noClip
                icon && animation -> labels.changed
                icon -> labels.changedIcon
                animation -> labels.changedAnimation
                else -> labels.unchanged
            },
            played = clip != null,
            selected = clip != null && clip == playing,
            changed = icon || animation,
        )
    }

@Composable
internal fun Hint(text: String, icon: ImageVector, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(HideoutDimens.Stage.HintGap), verticalAlignment = Alignment.CenterVertically) {
        HideoutIcon(icon, contentDescription = null, size = HideoutDimens.Icon.Small, tint = scheme.outline)
        Text(text, style = HideoutTheme.typography.caption, color = scheme.outline)
    }
}

private fun speedLabel(speed: Float): String {
    val whole = speed.toInt()
    return if (speed == whole.toFloat()) "$whole×" else "$speed×"
}

private const val RevealMillis = 300
private const val ArrivalMillis = 450
private const val NanosPerSecond = 1_000_000_000f
private const val FullTurn = 360f

private val StripWidth = HideoutDimens.AbilityStrip.Tile + HideoutDimens.AbilityStrip.Padding * 2

