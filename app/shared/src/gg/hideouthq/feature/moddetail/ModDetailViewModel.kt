package gg.hideouthq.feature.moddetail

import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import gg.hideouthq.core.analytics.*
import gg.hideouthq.core.data.CatalogRepository
import gg.hideouthq.core.data.DataError
import gg.hideouthq.core.data.Loadable
import gg.hideouthq.core.data.Preferences
import gg.hideouthq.core.data.ViewerFiles
import gg.hideouthq.core.library.ModLibrary
import gg.hideouthq.core.library.ProfileId
import gg.hideouthq.core.library.InstallInput
import gg.hideouthq.core.library.InstallRequest
import gg.hideouthq.core.library.SubmissionRef
import gg.hideouthq.core.library.installStatus
import gg.hideouthq.core.data.PlayingSample
import gg.hideouthq.core.data.SamplePreview
import gg.hideouthq.core.data.SoundSamplePlayer
import gg.hideouthq.core.data.preview.PreviewRepository
import gg.hideouthq.core.model.Hero
import gg.hideouthq.core.model.Mod
import gg.hideouthq.core.model.ModCategory
import gg.hideouthq.core.model.ModDetail
import gg.hideouthq.core.model.Backdrop
import gg.hideouthq.core.model.SceneSettings
import gg.hideouthq.core.navigation.PreviewRequest
import gg.hideouthq.feature.moddetail.viewer.PreviewModel
import gg.hideouthq.feature.moddetail.viewer.SoundStageState
import gg.hideouthq.feature.moddetail.viewer.ViewerCatalogue
import gg.hideouthq.feature.moddetail.viewer.ViewerCategory
import gg.hideouthq.preview.IndexEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.decodeToImageBitmap

private const val StopTimeoutMillis = 5_000L
private const val NoticeMillis = 6_000L

@OptIn(ExperimentalCoroutinesApi::class)
class ModDetailViewModel(
    private val modId: String,
    private val catalog: CatalogRepository,
    private val installations: ModLibrary,
    private val player: SoundSamplePlayer,
    private val previews: PreviewRepository,
    /** The exact file this page was opened to show, so the viewer is on it from the first frame. */
    opened: PreviewRequest? = null,
    /** Where the viewer's scene is kept for every mod page; without it the scene lasts as long as the page. */
    private val preferences: Preferences? = null,
    private val files: ViewerFiles = ViewerFiles.Unavailable,
    private val analytics: UsageAnalytics? = null,
) : ViewModel(), ModDetailActions {
    private val viewer = MutableStateFlow(ViewerState())
    private val detailAttempts = MutableStateFlow(0)
    private val previewAttempts = MutableStateFlow(0)
    private val previewLoad = MutableStateFlow(
        when {
            opened != null || preferences == null -> PreviewLoad.Wanted
            else -> preferences.load3dPreviews.value?.let(::previewLoadFor) ?: PreviewLoad.Undecided
        },
    )
    private val scene = MutableStateFlow(preferences?.scene?.value ?: SceneSettings())
    private val picture = MutableStateFlow<PictureNotice?>(null)
    private var pictureTimeout: Job? = null

    private val staged: Flow<Staged> = combine(scene, scene.map { it.customBackdrop }.distinctUntilChanged().map(::decodeBackdrop), picture, ::Staged)
    private val looping = MutableStateFlow(false)
    private val selectedFile = MutableStateFlow(opened?.selection())
    private var lastRequest = opened?.serial ?: 0
    private val installation = installations.state.map { it.pageInstallation(modId) }

    private val sample = player.nowPlaying.map { it?.takeIf { sample -> sample.modId == modId } }

    /** How far into the preview, in milliseconds; kept apart from [uiState] because it changes many times a second. */
    val samplePosition: StateFlow<Int> = sample.map { it?.positionMillis ?: 0 }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(StopTimeoutMillis), 0)

    private val playback = combine(sample.map { it?.let(::Playback) }.distinctUntilChanged(), looping) { playing, loop -> playing to loop }

    private val content = combine(catalog.mods, catalog.heroes, detailAttempts.flatMapLatest { catalog.detail(modId) }) { mods, heroes, detail ->
        val byId = mods.associateBy { it.id }
        val needed = (detail as? Loadable.Ready)?.value?.requirements.orEmpty().mapNotNull { it.modId }
        Content(byId[modId]?.toHeader(heroes.associateBy { it.id }), detail, needed.mapNotNull(byId::get).associate { it.id to it.title })
    }.shareIn(viewModelScope, SharingStarted.WhileSubscribed(replayExpirationMillis = 0), replay = 1)

    private val selected: Flow<Selected> = combine(content, selectedFile, installation) { content, requested, page ->
        val file = resolveFile(content.header, requested, page.installedFileIds.firstOrNull())
        Selected(content, file, content.header?.previewFor(file))
    }

    private val previewed: Flow<Selected> = combine(selected.map { it.entry }.distinctUntilChanged(), previewAttempts, previewLoad) { entry, _, load -> entry to load }
        .flatMapLatest { (entry, load) ->
            val preview = if (load == PreviewLoad.Wanted) previewOf(entry) else flowOf(null)
            combine(selected.filter { it.entry == entry }, preview) { sel, loaded -> Selected(sel.content, sel.file, sel.entry, loaded, load) }
        }

    val uiState: StateFlow<ModDetailUiState> = combine(previewed, installations.state, viewer, playback, staged) { sel, library, viewer, (playing, loop), staged ->
        val content = sel.content
        val header = content.header
        val detail = (content.detail as? Loadable.Ready)?.value
        ModDetailUiState(
            loading = false,
            header = header,
            detail = detail,
            detailError = (content.detail as? Loadable.Failed)?.error,
            installation = library.pageInstallation(modId),
            needs = needsOf(detail?.requirements.orEmpty(), modId, content.listed, library::installStatus),
            viewer = viewer,
            sound = header?.takeIf { it.category == ModCategory.Sounds }?.let {
                SoundStageState(
                    available = it.audioPreviewUrl != null,
                    playing = playing?.playing == true,
                    loading = playing?.loading == true,
                    lengthMillis = playing?.lengthMillis,
                    looping = loop,
                )
            },
            preview = sel.preview,
            previewLoad = sel.load,
            selectedFile = sel.file,
            scene = staged.scene,
            customBackdrop = staged.customBackdrop,
            picture = staged.picture,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(StopTimeoutMillis), cachedState())

    init {
        preferences?.let { saved ->
            viewModelScope.launch {
                saved.readLoad3dPreviews()
                saved.load3dPreviews.filterNotNull().collect { on ->
                    previewLoad.update { current -> if (on || current == PreviewLoad.Undecided) previewLoadFor(on) else current }
                }
            }
            viewModelScope.launch {
                saved.sceneSettings()
                saved.scene.filterNotNull().collect { scene.value = it }
            }
        }
    }

    private suspend fun decodeBackdrop(path: String?): ImageBitmap? {
        path ?: return null
        return catching({ null }) { files.readBackdrop(path)?.let { bytes -> withContext(Dispatchers.Default) { bytes.decodeToImageBitmap() } } }
    }

    private fun previewOf(entry: IndexEntry?): Flow<Loadable<PreviewModel>?> = if (entry == null) flowOf(null) else flow {
        emit(Loadable.Loading)
        val usage = analytics?.begin(UsageOperation.PreviewLoad)
        val preview = try {
            Loadable.Ready(PreviewModel(entry, previews.manifest(entry)))
        } catch (e: CancellationException) {
            usage?.finish(UsageOutcome.Cancelled, UsageStage.Manifest)
            throw e
        } catch (e: Exception) {
            usage?.finish(UsageOutcome.Failed, UsageStage.Manifest, UsageFailure.Unavailable)
            Loadable.Failed(DataError.Unavailable)
        }
        emit(preview)
    }

    private fun cachedState(): ModDetailUiState {
        val mod = catalog.cachedMod(modId) ?: return ModDetailUiState()
        val heroes = listOfNotNull(mod.heroId?.let(catalog::cachedHero)).associateBy { it.id }
        return ModDetailUiState(loading = true, header = mod.toHeader(heroes))
    }

    /** Asks for the mod page's details again after they failed to load. */
    override fun retryDetail() = detailAttempts.update { it + 1 }

    override fun pickCategory(category: ViewerCategory) = viewer.update { it.pickCategory(category, catalogue()) }
    override fun playClip(id: String) = viewer.update { it.playClip(id, catalogue()) }
    override fun stepClip(by: Int) = viewer.update { it.stepClip(by, catalogue()) }
    override fun playAbility(slot: Int) = viewer.update { it.playAbility(slot, catalogue()) }
    override fun closeMenu() = viewer.update { it.closeMenu() }
    override fun togglePlaying() = viewer.update { it.togglePlaying() }
    override fun animationFinished() = viewer.update { it.finished() }
    override fun cycleSpeed() = viewer.update { it.nextSpeed() }
    override fun setLoop(loop: Boolean) = viewer.update { it.copy(loop = loop) }

    override fun pickVariant(file: FileSelection) {
        selectedFile.value = file
    }

    /**
     * Shows the file [request] names, when it is newer than the last request this page applied: coming back to a page
     * keeps whatever was picked on it since, and asking again for the same file still shows it.
     */
    fun show(request: PreviewRequest) {
        if (request.serial <= lastRequest) return
        lastRequest = request.serial
        request.selection()?.let { selectedFile.value = it }
        previewLoad.value = PreviewLoad.Wanted
    }

    override fun useTool(tool: ViewerTool) = viewer.update {
        when (tool) {
            ViewerTool.AutoSpin -> it.copy(autoSpin = !it.autoSpin)
            ViewerTool.Scene -> it.copy(sceneOpen = !it.sceneOpen)
            ViewerTool.SaveImage -> it.copy(pictureRequests = it.pictureRequests + 1)
            ViewerTool.ResetCamera -> it.copy(cameraResets = it.cameraResets + 1)
            ViewerTool.Fullscreen -> it.copy(fullscreen = !it.fullscreen)
        }
    }

    override fun closeScene() = viewer.update { it.copy(sceneOpen = false) }

    /** Shows the preview or the images; the images never fill the window and close the Scene panel. */
    override fun showTab(tab: StageTab) = viewer.update { it.copy(tab = tab, sceneOpen = false, fullscreen = it.fullscreen && tab == StageTab.Preview) }

    override fun showImages() = showTab(StageTab.Images)

    /** Changes the scene for every mod page and keeps it across restarts. */
    override fun setScene(settings: SceneSettings) {
        scene.value = settings
        preferences?.let { saved -> viewModelScope.launch { saved.setScene(settings) } }
    }

    /** Uses the picture just copied in at [path]; with none, goes back to the user's earlier picture if there is one. */
    fun useBackdrop(path: String?) {
        val current = scene.value
        when {
            path != null -> setScene(current.copy(backdrop = Backdrop.Custom, customBackdrop = path))
            current.customBackdrop != null -> setScene(current.copy(backdrop = Backdrop.Custom))
        }
    }

    /** Shows how Save image, or picking Your image, went for a few seconds. */
    fun showNotice(notice: PictureNotice) {
        picture.value = notice
        pictureTimeout?.cancel()
        pictureTimeout = viewModelScope.launch {
            delay(NoticeMillis)
            picture.value = null
        }
    }

    override fun revealPicture() {
        val saved = picture.value as? PictureNotice.Saved ?: return
        if (saved.reveal == PictureReveal.Opening) return
        pictureTimeout?.cancel()
        val opening = saved.copy(reveal = PictureReveal.Opening)
        picture.value = opening
        viewModelScope.launch {
            val result = catching({ PictureReveal.Failed }) {
                files.reveal(saved.path)
                PictureReveal.Ready
            }
            if (picture.value === opening) showNotice(saved.copy(reveal = result))
        }
    }

    override fun dismissPicture() {
        pictureTimeout?.cancel()
        picture.value = null
    }

    /** Loads the model again: its manifest, if that is what failed, and the viewport either way. */
    override fun loadPreview() {
        previewLoad.value = PreviewLoad.Wanted
        showTab(StageTab.Preview)
    }

    override fun retryPreview() {
        if (uiState.value.preview is Loadable.Failed) previewAttempts.update { it + 1 }
        viewer.update { it.copy(loadAttempts = it.loadAttempts + 1) }
    }

    /** Plays the preview, or pauses or resumes it if it is already loaded. */
    override fun toggleSample() {
        val url = uiState.value.header?.audioPreviewUrl ?: return
        val current = loadedSample()
        when {
            current == null -> player.play(SamplePreview(modId, url, looping = looping.value))
            current.loading -> player.stop()
            current.paused -> player.resume()
            else -> player.pause()
        }
    }

    override fun seekSample(fraction: Float) {
        if (loadedSample() != null) player.seekTo(fraction)
    }

    override fun setSampleLooping(looping: Boolean) {
        this.looping.value = looping
        if (loadedSample() != null) player.setLooping(looping)
    }

    /** Stops the preview if it is this mod's, when the page goes away. */
    fun stopSample() {
        if (loadedSample() != null) player.stop()
    }

    override fun install() {
        val state = installations.state.value
        if (!state.canInstall) return
        val page = state.pageInstallation(modId)
        if (page.record != null) return
        val source = SubmissionRef.parse(modId)
        if (source == null) {
            installations.install(modId)
            return
        }
        installations.install(InstallRequest(InstallInput.Remote(source), page.selectedProfiles.toSet(), chooseVersion = true))
    }

    /** Asks which files the installed mod is to have, starting from the ones it has. */
    override fun changeFiles() {
        val state = installations.state.value
        if (!state.canInstall) return
        val record = state.pageInstallation(modId).record ?: return
        installations.changeFiles(record.installId)
    }

    /** Turns the installed mod on or off in [profile]; a new install's profiles are chosen in the install dialog. */
    override fun setProfile(profile: ProfileId, selected: Boolean) {
        val state = installations.state.value
        val page = state.pageInstallation(modId)
        val record = page.record ?: return
        if (page.profiles.none { it.id == profile } || !state.canEditChoices) return
        installations.setEnabled(record.installId, selected, profile)
    }

    override fun cancelInstall() { installations.cancel(modId) }

    override fun installNeed(modId: String) {
        if (installations.state.value.canInstall) installations.install(modId)
    }

    /** Saves that the installed mod is to use repaired files; they are placed once Deadlock is known closed. */
    fun applyRepair() {
        val state = installations.state.value
        val record = state.pageInstallation(modId).record ?: return
        if (state.canEditChoices) viewModelScope.launch { installations.useRepairedFiles(setOf(record.installId)) }
    }

    /** Saves that the installed mod is to use its original files. */
    fun useOriginal() {
        val state = installations.state.value
        val record = state.pageInstallation(modId).record ?: return
        if (state.canEditChoices) viewModelScope.launch { installations.useOriginalFiles(record.installId) }
    }

    fun checkCompatibility() = installations.checkCompatibility()

    private fun loadedSample(): PlayingSample? = player.nowPlaying.value?.takeIf { it.modId == modId }

    private fun catalogue(): ViewerCatalogue = uiState.value.stageModel?.catalogue ?: ViewerCatalogue.Empty

    private class Staged(val scene: SceneSettings, val customBackdrop: ImageBitmap?, val picture: PictureNotice?)
    /** [listed] holds the catalogue's title of each mod the page says this one needs, when the catalogue lists it. */
    private class Content(val header: ModHeader?, val detail: Loadable<ModDetail>, val listed: Map<String, String>)
    private class Selected(val content: Content, val file: FileSelection?, val entry: IndexEntry?, val preview: Loadable<PreviewModel>? = null, val load: PreviewLoad = PreviewLoad.Wanted)

    private data class Playback(val playing: Boolean, val loading: Boolean, val lengthMillis: Int?) {
        constructor(sample: PlayingSample) : this(!sample.paused && !sample.loading, sample.loading, sample.lengthMillis)
    }
}

private fun previewLoadFor(on: Boolean) = if (on) PreviewLoad.Wanted else PreviewLoad.Held

private fun PreviewRequest.selection() = fileId?.let { FileSelection(it, fileMd5) }

internal inline fun <T> catching(onError: () -> T, block: () -> T): T = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    onError()
}

internal fun pictureFileName(title: String, variant: String?): String {
    val name = listOfNotNull(title, variant?.takeIf { it.isNotBlank() }).joinToString("-")
        .lowercase()
        .replace(NotNameCharacters, "-")
        .trim('-')
        .take(MaxPictureName)
        .trimEnd('-')
    return "${name.ifEmpty { "hideout" }}.png"
}

private val NotNameCharacters = Regex("[^\\p{L}\\p{N}]+")
private const val MaxPictureName = 96

private fun resolveFile(header: ModHeader?, requested: FileSelection?, installed: Long?): FileSelection? {
    if (requested != null) return requested
    val variants = header?.previewVariants.orEmpty()
    val file = variants.firstOrNull { it.fileId == installed }
        ?: variants.firstOrNull { it.entry != null && it.entry == header?.preview }
        ?: variants.firstOrNull()
    return file?.selection()
}

private fun Mod.toHeader(heroes: Map<String, Hero>): ModHeader {
    val hero = heroId?.let { heroes[it] }
    return ModHeader(
        modId = id,
        title = title,
        category = category,
        heroName = hero?.name,
        kind = kind,
        has3dPreview = has3dPreview,
        worksOnCurrentPatch = worksOnCurrentPatch,
        published = published,
        updated = updated,
        downloads = downloads,
        likes = likes,
        cover = cover,
        heroIconUrl = hero?.iconUrl,
        audioPreviewUrl = audioPreviewUrl,
        preview = preview,
        previewVariants = previewVariants,
    )
}
