package gg.hideouthq.feature.moddetail

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import gg.hideouthq.core.data.DataError
import gg.hideouthq.core.data.Loadable
import gg.hideouthq.core.model.CalendarDate
import gg.hideouthq.core.model.CoverImage
import gg.hideouthq.core.model.ModCategory
import gg.hideouthq.core.library.VersionSource
import gg.hideouthq.core.library.versionNames
import gg.hideouthq.core.model.ModDetail
import gg.hideouthq.core.model.PreviewVariant
import gg.hideouthq.core.model.offeredFirst
import gg.hideouthq.core.model.SceneSettings
import gg.hideouthq.feature.moddetail.viewer.PreviewModel
import gg.hideouthq.feature.moddetail.viewer.SoundStageState
import gg.hideouthq.feature.moddetail.viewer.ViewerCatalogue
import gg.hideouthq.feature.moddetail.viewer.ViewerCategory
import gg.hideouthq.preview.IndexEntry

/** Everything the mod page shows. [header] is null until the mod is known (or if it does not exist). */
@Immutable
data class ModDetailUiState(
    val loading: Boolean = true,
    val header: ModHeader? = null,
    val detail: ModDetail? = null,
    /** Why [detail] is missing, once loading it has failed. */
    val detailError: DataError? = null,
    val installation: PageInstallation = PageInstallation(),
    /** What the mod page says the mod needs; empty when it says nothing or has not loaded. */
    val needs: List<Need> = emptyList(),
    val viewer: ViewerState = ViewerState(),
    /** The preview and player, for a sound mod; null for anything else. */
    val sound: SoundStageState? = null,
    /** The generated preview, its manifest verified against its own index entry; null when the mod has no preview. */
    val preview: Loadable<PreviewModel>? = null,
    val previewLoad: PreviewLoad = PreviewLoad.Wanted,
    /** The viewer's file selection; null uses the default display preview. Installation chooses its file separately. */
    val selectedFile: FileSelection? = null,
    /** How the 3D viewer stages the model: one choice for every mod page. */
    val scene: SceneSettings = SceneSettings(),
    /** The user's own backdrop, decoded, while the scene has one that can be read. */
    val customBackdrop: ImageBitmap? = null,
    /** What became of the last Save image, shown for a few seconds. */
    val picture: PictureNotice? = null,
) {
    /** The selected file's catalogue facts, with or without a preview; with nothing selected, the only file. */
    val selectedVersion: PreviewVariant?
        get() = if (selectedFile == null) header?.previewVariants?.singleOrNull() else header?.variantFor(selectedFile)

    /**
     * What the Size fact says: the installed files' sizes added up, or, before an install, the size of the file Install
     * offers first; null when GameBanana did not list it.
     */
    val installSizeBytes: Long?
        get() {
            val variants = header?.previewVariants.orEmpty()
            val installed = installation.installedFileIds
            if (installed.isEmpty()) return variants.offeredFirst()?.sizeBytes
            return variants.filter { it.fileId in installed }.mapNotNull { it.sizeBytes }.takeIf { it.isNotEmpty() }?.sum()
        }

    /** Whether the stage is holding back a 3D preview that the user can ask for. */
    val canLoadPreview: Boolean
        get() = previewLoad == PreviewLoad.Held && header?.has3dPreviewFor(selectedFile) == true

    /** The bundle the 3D viewer draws: a verified preview of exactly the entry this page's header and selection name. */
    val stageModel: PreviewModel?
        get() = (preview as? Loadable.Ready)?.value?.takeIf { it.entry == header?.previewFor(selectedFile) }
}

/** Whether a mod page's 3D preview may load. Nothing of it is fetched, and no viewport exists, until it is [Wanted]. */
enum class PreviewLoad {
    /** The saved setting has not been read yet. */
    Undecided,

    /** Loading by default is off and nobody has asked for this one. */
    Held,

    /** The setting is on, or the user asked. A page stays here until it goes away. */
    Wanted,
}

/** One GameBanana file picked to preview, and its MD5 when the picker knew one, so a different upload under the same id is never shown. */
@Immutable
data class FileSelection(val fileId: Long, val fileMd5: String? = null) {
    /** Every known MD5 must agree: [md5] or this selection's own may be unknown, never different. */
    fun agrees(md5: String?): Boolean = fileMd5 == null || md5 == null || md5 == fileMd5
}

internal fun PreviewVariant.selection() = FileSelection(fileId, fileMd5)

@Immutable
data class ModHeader(
    val modId: String,
    val title: String,
    val category: ModCategory,
    val heroName: String?,
    val kind: String?,
    val has3dPreview: Boolean,
    val worksOnCurrentPatch: Boolean,
    val published: CalendarDate,
    val updated: CalendarDate,
    val downloads: Int?,
    val likes: Int,
    val cover: CoverImage,
    val heroIconUrl: String? = null,
    val audioPreviewUrl: String? = null,
    /** The catalogue's default display preview, matched to one current file. */
    val preview: IndexEntry? = null,
    /** Every current GameBanana file, each matched against the preview host on its own; offered only when there is more than one. */
    val previewVariants: List<PreviewVariant> = emptyList(),
) {
    val previewVariantNames = versionNames(title, previewVariants.map { VersionSource(it.fileId, it.description, it.filename ?: it.fileId.toString()) })

    /** The current file [selection] names; its facts need no preview. */
    fun variantFor(selection: FileSelection): PreviewVariant? =
        previewVariants.firstOrNull { it.fileId == selection.fileId }?.takeIf { selection.agrees(it.fileMd5) }

    /** The catalogue's name for exactly [file]; null once it is delisted or re-uploaded, so an installed file keeps the manifest's label. */
    fun nameOf(file: FileSelection): String? = variantFor(file)?.let { previewVariantNames[it.fileId] }

    fun has3dPreviewFor(selection: FileSelection?): Boolean =
        previewFor(selection) != null || selection == null && has3dPreview

    /** An explicit [selection] matches exactly or shows nothing. No selection uses the catalogue's display default. */
    fun previewFor(selection: FileSelection?): IndexEntry? {
        if (selection == null) return preview
        val entry = if (previewVariants.isEmpty()) preview?.takeIf { it.fileId == selection.fileId } else variantFor(selection)?.entry
        return entry?.takeIf { selection.agrees(it.fileMd5) }
    }
}

/**
 * The viewer's settings. Playback position is animation progress and lives in the stage itself. The playing clip
 * and the open menu are ids into a [ViewerCatalogue], which resolves them: they outlive a refreshed bundle, so one
 * the new bundle lacks falls back to its opening pose and closes the menu.
 */
@Immutable
data class ViewerState(
    /** The chosen clip's id; null opens on the catalogue's opening pose. */
    val clipId: String? = null,
    /** The pill whose menu was opened, shown only while it is still the playing clip's pill. */
    val menuCategory: ViewerCategory? = null,
    val playing: Boolean = true,
    val speedIndex: Int = DefaultSpeedIndex,
    val loop: Boolean = true,
    /** The turntable turns the model by itself until this is switched off. */
    val autoSpin: Boolean = true,
    val sceneOpen: Boolean = false,
    val fullscreen: Boolean = false,
    /** The stage's tab: the mod's preview, its 3D viewer or its sound, or the mod page's images. */
    val tab: StageTab = StageTab.Preview,
    /** Counts "Reset camera" presses, so the viewport can react to each one. */
    val cameraResets: Int = 0,
    /** Counts "Save image" presses, so the stage answers each one once. */
    val pictureRequests: Int = 0,
    /** Counts Retry presses after the model failed to load, so the viewport loads it again. */
    val loadAttempts: Int = 0,
) {
    val speed: Float get() = Speeds[speedIndex]

    fun activeCategory(catalogue: ViewerCatalogue): ViewerCategory? = catalogue.resolve(clipId)?.let(catalogue::categoryOf)

    fun openMenu(catalogue: ViewerCatalogue): ViewerCategory? = menuCategory?.takeIf { it == activeCategory(catalogue) }

    fun pickCategory(category: ViewerCategory, catalogue: ViewerCatalogue): ViewerState =
        if (category == activeCategory(catalogue)) {
            copy(menuCategory = category.takeIf { openMenu(catalogue) == null })
        } else {
            catalogue.group(category)?.clips?.firstOrNull()?.let { copy(clipId = it.id, playing = true, menuCategory = category) } ?: this
        }

    fun playClip(id: String, catalogue: ViewerCatalogue): ViewerState {
        val clip = catalogue.clip(id) ?: return this
        return copy(clipId = id, playing = true, menuCategory = openMenu(catalogue)?.let { catalogue.categoryOf(clip) })
    }

    fun stepClip(by: Int, catalogue: ViewerCatalogue): ViewerState {
        val next = catalogue.step(catalogue.resolve(clipId), by) ?: return this
        return copy(clipId = next.id, playing = true, menuCategory = openMenu(catalogue)?.let { catalogue.categoryOf(next) })
    }

    fun playAbility(slot: Int, catalogue: ViewerCatalogue): ViewerState =
        catalogue.abilityClip(slot)?.let { copy(clipId = it.id, playing = true, menuCategory = null) } ?: this

    fun closeMenu(): ViewerState = copy(menuCategory = null)

    fun togglePlaying(): ViewerState = copy(playing = !playing)

    fun finished(): ViewerState = copy(playing = false)

    fun nextSpeed(): ViewerState = copy(speedIndex = (speedIndex + 1) % Speeds.size)

    companion object {
        val Speeds = listOf(0.5f, 1f, 1.5f, 2f)
        const val DefaultSpeedIndex = 1
    }
}

enum class StageTab { Preview, Images }

/** Saving and revealing are separate: a file-manager failure never means the picture was not saved. */
sealed interface PictureNotice {
    data class Saved(val path: String, val reveal: PictureReveal = PictureReveal.Ready) : PictureNotice
    data object Failed : PictureNotice
    data object BackdropFailed : PictureNotice
}

enum class PictureReveal { Ready, Opening, Failed }

/** The 3D tab's toolbar, in order. */
enum class ViewerTool { AutoSpin, Scene, SaveImage, ResetCamera, Fullscreen }
