package gg.hideouthq.feature.moddetail

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import gg.hideouthq.core.data.Loadable
import gg.hideouthq.core.designsystem.theme.HideoutDimens
import gg.hideouthq.core.designsystem.theme.HideoutShapes
import gg.hideouthq.core.model.CoverImage
import gg.hideouthq.core.ui.ModLabels
import gg.hideouthq.core.ui.catalog.SoundWaveforms
import gg.hideouthq.feature.moddetail.ModDetailTransition.stage
import gg.hideouthq.core.model.Backdrop
import gg.hideouthq.core.model.SceneLighting
import gg.hideouthq.feature.moddetail.components.ImagesStage
import gg.hideouthq.feature.moddetail.viewer.AbilityLabels
import gg.hideouthq.feature.moddetail.viewer.LoadPreviewAction
import gg.hideouthq.feature.moddetail.viewer.LoadingLabels
import gg.hideouthq.feature.moddetail.viewer.SceneLabels
import gg.hideouthq.feature.moddetail.viewer.StageVariant
import gg.hideouthq.feature.moddetail.viewer.AnimationLabels
import gg.hideouthq.feature.moddetail.viewer.ModelStage
import gg.hideouthq.feature.moddetail.viewer.SoundStage
import gg.hideouthq.feature.moddetail.viewer.SoundStageLabels
import gg.hideouthq.feature.moddetail.viewer.SoundStageState
import gg.hideouthq.feature.moddetail.viewer.StageContent
import gg.hideouthq.feature.moddetail.viewer.StageLabels
import gg.hideouthq.feature.moddetail.viewer.ToolbarLabels
import gg.hideouthq.feature.moddetail.viewer.ViewerCatalogue
import gg.hideouthq.feature.moddetail.viewer.ViewerCategory
import gg.hideouthq.feature.moddetail.viewer.ViewportLabels
import gg.hideouthq.feature.moddetail.viewer.ViewportStatus
import gg.hideouthq.preview.BundleManifest
import gg.hideouthq.resources.Res
import gg.hideouthq.resources.ability_changed
import gg.hideouthq.resources.ability_changed_animation
import gg.hideouthq.resources.ability_changed_icon
import gg.hideouthq.resources.ability_no_clip
import gg.hideouthq.resources.ability_no_clip_changed
import gg.hideouthq.resources.ability_strip
import gg.hideouthq.resources.ability_unchanged
import gg.hideouthq.resources.animation_category_abilities
import gg.hideouthq.resources.animation_category_all
import gg.hideouthq.resources.animation_category_changed
import gg.hideouthq.resources.animation_category_combat
import gg.hideouthq.resources.animation_category_idle
import gg.hideouthq.resources.animation_category_movement
import gg.hideouthq.resources.animation_category_poses
import gg.hideouthq.resources.animation_changed
import gg.hideouthq.resources.animation_changed_tag
import gg.hideouthq.resources.animation_length
import gg.hideouthq.resources.animation_step_through
import gg.hideouthq.resources.sound_stage_hint
import gg.hideouthq.resources.sound_stage_no_preview
import gg.hideouthq.resources.sound_stage_pause
import gg.hideouthq.resources.sound_stage_play
import gg.hideouthq.resources.sound_stage_word
import gg.hideouthq.resources.sound_preview_loading
import gg.hideouthq.resources.sound_preview_cancel
import gg.hideouthq.resources.stage_hint
import gg.hideouthq.resources.stage_hint_still
import gg.hideouthq.resources.toolbar_loop
import gg.hideouthq.resources.toolbar_pause
import gg.hideouthq.resources.toolbar_play
import gg.hideouthq.resources.toolbar_speed
import gg.hideouthq.resources.viewport_description
import gg.hideouthq.resources.viewport_title
import gg.hideouthq.resources.scene_backdrop
import gg.hideouthq.resources.scene_backdrop_custom
import gg.hideouthq.resources.scene_backdrop_greenech
import gg.hideouthq.resources.scene_backdrop_broadway
import gg.hideouthq.resources.scene_backdrop_york
import gg.hideouthq.resources.scene_backdrop_studio
import gg.hideouthq.resources.scene_bloom
import gg.hideouthq.resources.scene_lighting
import gg.hideouthq.resources.scene_lighting_dramatic
import gg.hideouthq.resources.scene_lighting_soft
import gg.hideouthq.resources.scene_lighting_studio
import gg.hideouthq.resources.scene_reset
import gg.hideouthq.resources.scene_stage
import gg.hideouthq.resources.scene_title
import gg.hideouthq.resources.scene_turntable
import gg.hideouthq.resources.variants_title
import gg.hideouthq.resources.viewer_failed
import gg.hideouthq.resources.viewer_loading
import gg.hideouthq.resources.viewer_loading_bytes
import gg.hideouthq.resources.viewer_loading_hero
import gg.hideouthq.resources.viewer_retry
import gg.hideouthq.resources.viewer_see_images
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

internal enum class StageKind { Model, Sound, Pictures }

internal fun stageKind(state: ModDetailUiState, header: ModHeader): StageKind = when {
    header.has3dPreviewFor(state.selectedFile) -> StageKind.Model
    state.sound != null -> StageKind.Sound
    else -> StageKind.Pictures
}

internal fun stageTabbed(state: ModDetailUiState, stage: StageKind): Boolean =
    stage == StageKind.Model || stage == StageKind.Sound && !state.detail?.images.isNullOrEmpty()

internal fun stageCredit(state: ModDetailUiState, stage: StageKind): BundleManifest? =
    state.stageModel?.manifest?.takeIf { stage == StageKind.Model }

@Composable
internal fun DetailStage(
    state: ModDetailUiState,
    header: ModHeader,
    stage: StageKind,
    actions: ModDetailActions,
    stageHeight: Dp,
    samplePosition: () -> Int,
    onOpenImage: (Int) -> Unit,
    onViewportStatus: (ViewportStatus) -> Unit,
) {
    val sound = state.sound
    when {
        stage == StageKind.Pictures || state.viewer.tab == StageTab.Images && stageTabbed(state, stage) -> Box {
            ImagesStage(
                modId = header.modId,
                images = stageImages(state, header),
                height = stageHeight,
                onOpen = onOpenImage,
                modifier = Modifier.stage(header.modId, HideoutDimens.Stage.Height),
            )
            if (stage == StageKind.Pictures && state.canLoadPreview) {
                LoadPreviewAction(actions::loadPreview, Modifier.align(Alignment.BottomCenter).padding(bottom = HideoutDimens.Toolbar.Bottom))
            }
        }
        stage == StageKind.Model -> {
            val model = state.stageModel
            val heroName = header.heroName.orEmpty()
            val names = header.previewVariantNames
            val styleName = state.selectedVersion?.let { names[it.fileId] }.orEmpty()
            ModelStage(
                content = StageContent(
                    heroName = heroName,
                    styleName = styleName,
                    model = model,
                    pending = state.preview is Loadable.Loading || state.previewLoad == PreviewLoad.Undecided,
                    held = state.previewLoad == PreviewLoad.Held,
                    canLoad = state.canLoadPreview,
                    pictureBundleId = header.previewFor(state.selectedFile)?.bundleId,
                    failed = state.preview is Loadable.Failed,
                    variants = header.previewVariants.filter { it.entry != null }.map { StageVariant(it.selection(), names.getValue(it.fileId)) },
                    selectedVariant = state.selectedVersion?.selection(),
                    customBackdrop = state.customBackdrop,
                ),
                viewer = state.viewer,
                actions = actions,
                labels = stageLabels(header.title, heroName, styleName, model?.catalogue ?: ViewerCatalogue.Empty),
                height = stageHeight,
                choreography = ModDetailTransition.stage(),
                scene = state.scene,
                modifier = Modifier.stage(header.modId, HideoutDimens.Stage.Height).clip(HideoutShapes.Stage),
                onStatus = onViewportStatus,
            )
        }
        stage == StageKind.Sound && sound != null -> SoundStage(
            word = header.kind ?: stringResource(Res.string.sound_stage_word),
            waveform = SoundWaveforms.of(header.modId, SoundWaveforms.StageBars),
            sound = sound,
            position = samplePosition,
            actions = actions,
            labels = soundLabels(sound),
            choreography = ModDetailTransition.stage(),
            modifier = Modifier.stage(header.modId, HideoutDimens.SoundStage.Height).clip(HideoutShapes.Stage),
        )
    }
}

internal fun stageImages(state: ModDetailUiState, header: ModHeader): List<CoverImage> =
    state.detail?.images?.takeIf { it.isNotEmpty() } ?: listOf(header.cover)

@Composable
private fun soundLabels(sound: SoundStageState) = SoundStageLabels(
    hint = stringResource(if (sound.available) Res.string.sound_stage_hint else Res.string.sound_stage_no_preview),
    play = stringResource(Res.string.sound_stage_play),
    pause = stringResource(Res.string.sound_stage_pause),
    loop = stringResource(Res.string.toolbar_loop),
    loading = stringResource(Res.string.sound_preview_loading),
    cancel = stringResource(Res.string.sound_preview_cancel),
)

@Composable
private fun stageLabels(title: String, heroName: String, styleName: String, catalogue: ViewerCatalogue) = StageLabels(
    hint = stringResource(Res.string.stage_hint),
    stillHint = stringResource(Res.string.stage_hint_still),
    viewport = ViewportLabels(stringResource(Res.string.viewport_title), stringResource(Res.string.viewport_description, title)),
    toolbar = ToolbarLabels(
        play = stringResource(Res.string.toolbar_play),
        pause = stringResource(Res.string.toolbar_pause),
        speed = stringResource(Res.string.toolbar_speed),
        loop = stringResource(Res.string.toolbar_loop),
    ),
    animations = AnimationLabels(
        categories = ViewerCategory.entries.associateWith { stringResource(it.label) },
        categoryChanged = stringResource(Res.string.animation_category_changed),
        clipChanged = stringResource(Res.string.animation_changed),
        changedTag = stringResource(Res.string.animation_changed_tag),
        lengths = catalogue.clips.associate { it.id to clipLength(it.durationMillis) },
        stepThrough = pluralStringResource(Res.plurals.animation_step_through, catalogue.clips.size, catalogue.clips.size),
    ),
    abilities = AbilityLabels(
        strip = stringResource(Res.string.ability_strip),
        changed = stringResource(Res.string.ability_changed),
        changedIcon = stringResource(Res.string.ability_changed_icon),
        changedAnimation = stringResource(Res.string.ability_changed_animation),
        unchanged = stringResource(Res.string.ability_unchanged),
        noClip = stringResource(Res.string.ability_no_clip),
        noClipChanged = stringResource(Res.string.ability_no_clip_changed),
    ),
    loading = LoadingLabels(
        title = if (styleName.isBlank()) stringResource(Res.string.viewer_loading_hero, heroName) else stringResource(Res.string.viewer_loading, heroName, styleName),
        failed = stringResource(Res.string.viewer_failed),
        retry = stringResource(Res.string.viewer_retry),
        seeImages = stringResource(Res.string.viewer_see_images),
        progress = { done, total ->
            stringResource(Res.string.viewer_loading_bytes, ModLabels.megabytes(done), ModLabels.megabytes(total), (done * PercentFull / total).toInt())
        },
    ),
    scene = SceneLabels(
        title = stringResource(Res.string.scene_title),
        reset = stringResource(Res.string.scene_reset),
        backdrop = stringResource(Res.string.scene_backdrop),
        lighting = stringResource(Res.string.scene_lighting),
        stage = stringResource(Res.string.scene_stage),
        backdrops = Backdrop.entries.associateWith { stringResource(it.label) },
        lightings = SceneLighting.entries.associateWith { stringResource(it.label) },
        turntable = stringResource(Res.string.scene_turntable),
        bloom = stringResource(Res.string.scene_bloom),
    ),
    variants = stringResource(Res.string.variants_title),
)

private val Backdrop.label: StringResource
    get() = when (this) {
        Backdrop.Studio -> Res.string.scene_backdrop_studio
        Backdrop.Greenech -> Res.string.scene_backdrop_greenech
        Backdrop.Broadway -> Res.string.scene_backdrop_broadway
        Backdrop.York -> Res.string.scene_backdrop_york
        Backdrop.Custom -> Res.string.scene_backdrop_custom
    }

private val SceneLighting.label: StringResource
    get() = when (this) {
        SceneLighting.Soft -> Res.string.scene_lighting_soft
        SceneLighting.Studio -> Res.string.scene_lighting_studio
        SceneLighting.Dramatic -> Res.string.scene_lighting_dramatic
    }

@Composable
private fun clipLength(millis: Long): String {
    val tenths = (millis + HalfTenthMillis) / TenthMillis
    return stringResource(Res.string.animation_length, tenths / TenthsPerSecond, tenths % TenthsPerSecond)
}

private val ViewerCategory.label: StringResource
    get() = when (this) {
        ViewerCategory.Idle -> Res.string.animation_category_idle
        ViewerCategory.Movement -> Res.string.animation_category_movement
        ViewerCategory.Combat -> Res.string.animation_category_combat
        ViewerCategory.Abilities -> Res.string.animation_category_abilities
        ViewerCategory.Poses -> Res.string.animation_category_poses
        ViewerCategory.Animations -> Res.string.animation_category_all
    }

private const val PercentFull = 100L
private const val TenthMillis = 100L
private const val HalfTenthMillis = TenthMillis / 2
private const val TenthsPerSecond = 10L
