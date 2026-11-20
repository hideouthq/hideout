package gg.hideouthq.feature.settings

import gg.hideouthq.core.analytics.*

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import gg.hideouthq.core.appupdate.AppUpdateState
import gg.hideouthq.core.customgames.CustomGames
import gg.hideouthq.core.data.FolderChoice
import gg.hideouthq.core.data.FolderOpener
import gg.hideouthq.core.data.FolderPicker
import gg.hideouthq.core.data.FolderRefusal
import gg.hideouthq.core.data.InstallDefaults
import gg.hideouthq.core.data.ReportExport
import gg.hideouthq.core.data.Preferences
import gg.hideouthq.core.data.StatsPreferences
import gg.hideouthq.core.data.preview.PreviewFiles
import gg.hideouthq.core.library.CitadelPath
import gg.hideouthq.core.library.FolderCheck
import gg.hideouthq.core.library.GameStatus
import gg.hideouthq.core.library.LibraryState
import gg.hideouthq.core.library.LibraryStatus
import gg.hideouthq.core.library.ModLibrary
import gg.hideouthq.core.library.RestoreConfirmation
import gg.hideouthq.core.model.Accent
import gg.hideouthq.core.model.AppAbout
import gg.hideouthq.core.model.ModCardStyle
import gg.hideouthq.core.report.CustomGamesFacts
import gg.hideouthq.feature.moddetail.catching
import gg.hideouthq.feature.stats.data.StatsService
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val StopTimeoutMillis = 5_000L

internal class SettingsViewModel(
    private val library: ModLibrary,
    private val preferences: Preferences,
    private val folderPicker: FolderPicker,
    private val folders: FolderOpener,
    private val about: AppAbout,
    private val statsAvailable: Boolean = false,
    private val statsService: StatsService? = null,
    private val reportExport: ReportExport = ReportExport.Unavailable,
    private val previewFiles: PreviewFiles? = null,
    appUpdates: StateFlow<AppUpdateState>? = null,
    private val customGames: CustomGames? = null,
    private val analytics: UsageAnalytics? = null,
) : ViewModel(), SettingsActions {
    private data class Local(
        val rejected: FolderCheck.Invalid? = null,
        val busy: Boolean = false,
        val failed: Boolean = false,
        val query: String = "",
        val restore: RestoreConfirmation? = null,
        val licences: Boolean = false,
        val folderRefused: FolderRefusal? = null,
        val unsaved: SettingId? = null,
        val report: ReportSaved? = null,
        val backupsPane: BackupsPane? = null,
        val previews: PreviewSpace? = null,
    )

    private val local = MutableStateFlow(Local(previews = previewFiles?.let { PreviewSpace.Measuring }))

    @OptIn(ExperimentalCoroutinesApi::class)
    private val steamTracking = statsService?.state?.flatMapLatest { service ->
        service.tracked?.data?.map { SteamTrackingSettings(service.steamInstallation, it.steamTracking) }
            ?: flowOf(SteamTrackingSettings(service.steamInstallation))
    } ?: flowOf(SteamTrackingSettings())

    @OptIn(ExperimentalCoroutinesApi::class)
    private val downloadSpace = library.state
        .map { (it.library as? LibraryStatus.Ready)?.let { ready -> ready.downloads to ready.clearable } }
        .distinctUntilChanged()
        .transformLatest {
            emit(SpaceUsed.Measuring)
            emit(library.measureDownloads()?.let { SpaceUsed.Measured(it) } ?: SpaceUsed.Unknown)
        }

    internal val uiState: StateFlow<SettingsUiState> = combine(
        combine(library.state, preferences.defaults, preferences.stats, local, downloadSpace, ::uiState), steamTracking, preferences.autoRepair, preferences.downloadAppUpdates, appUpdates ?: flowOf(null),
    ) { state, steam, autoRepair, downloadAppUpdates, appUpdate -> state.copy(steamTracking = steam, autoRepair = autoRepair, downloadAppUpdates = downloadAppUpdates, appUpdate = appUpdate) }
        .combine(preferences.load3dPreviews) { state, load3dPreviews -> state.copy(load3dPreviews = load3dPreviews) }
        .combine(preferences.hideMature) { state, on -> state.copy(hideMature = on) }
        .combine(preferences.customGames) { state, customGames -> state.copy(customGames = customGames) }
        .combine(preferences.hostModeFolders) { state, on -> state.copy(hostModeFolders = on) }
        .combine(analytics?.choice ?: flowOf(null)) { state, choice -> state.copy(analyticsEnabled = choice?.let { it == AnalyticsChoice.Enabled }) }
        .combine(preferences.appearance) { state, appearance -> state.copy(appearance = appearance) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(StopTimeoutMillis), uiState(library.state.value, preferences.defaults.value, preferences.stats.value, Local(), SpaceUsed.Measuring))

    init {
        viewModelScope.launch { preferences.installDefaults() }
        viewModelScope.launch { preferences.readAutoRepair() }
        viewModelScope.launch { preferences.readDownloadAppUpdates() }
        viewModelScope.launch { preferences.readLoad3dPreviews() }
        viewModelScope.launch { preferences.readHideMature() }
        viewModelScope.launch { preferences.readCustomGames() }
        viewModelScope.launch { preferences.readHostModeFolders() }
        if (statsAvailable) viewModelScope.launch { preferences.statsPreferences() }
        measurePreviews()
    }

    override fun inspectSteamTracking() { statsService?.inspectSteamTracking() }
    override fun selectSteamInstallation(id: String) { statsService?.selectSteamInstallation(id) }
    override fun installSteamTracking() { statsService?.let { viewModelScope.launch { it.installSteamTracking() } } }
    override fun disableSteamTracking() { statsService?.disableSteamTracking() }
    override fun backfillSteamHistory() { statsService?.backfillSteamHistory() }

    override fun search(query: String) = local.update { it.copy(query = query) }

    fun chooseFolder(title: String) = act {
        val current = uiState.value
        val picked = folderPicker.pick(title, current.rejected?.path ?: current.gameRoot?.path) ?: return@act
        val check = library.useGameFolder(picked)
        local.update { it.copy(rejected = check as? FolderCheck.Invalid) }
    }

    override fun detectAgain() = act {
        local.update { it.copy(rejected = null) }
        library.detectGame()
    }

    override fun toggle(setting: SettingId, on: Boolean) = save(setting) {
        when (setting) {
            SettingId.UsageAnalytics -> analytics?.choose(on)
            SettingId.Stats -> preferences.setStatsEnabled(on)
            SettingId.ShareMatchIds -> preferences.setShareMatchIds(on)
            SettingId.AskWhichVersion -> preferences.setAskWhichVersion(on)
            SettingId.KeepDownloads -> preferences.setKeepDownloads(on)
            SettingId.AutoRepair -> preferences.setAutoRepair(on)
            SettingId.DownloadAppUpdates -> preferences.setDownloadAppUpdates(on)
            SettingId.Load3dPreviews -> preferences.setLoad3dPreviews(on)
            SettingId.HideMature -> preferences.setHideMature(on)
            SettingId.CustomGames -> preferences.setCustomGames(on)
            SettingId.HostModeFolders -> preferences.setHostModeFolders(on)
            else -> Unit
        }
    }

    override fun pickAccent(accent: Accent) = save(SettingId.AccentColour) { preferences.setAccent(accent) }

    override fun pickModCards(style: ModCardStyle) = save(SettingId.ModCards) { preferences.setModCards(style) }

    fun changeDownloadFolder(title: String) = save(SettingId.DownloadFolder) {
        val picked = folderPicker.pick(title, uiState.value.downloadFolder) ?: return@save
        when (val choice = library.checkDownloadFolder(picked)) {
            is FolderChoice.Refused -> local.update { it.copy(folderRefused = choice.reason) }
            is FolderChoice.Accepted -> {
                local.update { it.copy(folderRefused = null) }
                preferences.setDownloadFolder(choice.folder.takeIf { it != library.defaultDownloadFolder })
            }
        }
    }

    fun openModsFolder() {
        val root = uiState.value.gameRoot ?: return
        viewModelScope.launch { folders.open(root, CitadelPath.Addons) }
    }

    fun askRestore() = local.update { it.copy(restore = library.restoreConfirmation()) }

    override fun cancelRestore() = local.update { it.copy(restore = null) }

    override fun confirmRestore() {
        val confirmation = local.value.restore ?: return
        local.update { it.copy(restore = null) }
        library.restoreOriginal(confirmation)
    }

    fun changeStatsAccount() = save(SettingId.StatsAccount) { preferences.setStatsAccount(null) }

    fun clearStats() = save(SettingId.StatsCache) { statsService?.clearSaved() }

    fun openLicences() = local.update { it.copy(licences = true) }

    fun saveReport(title: String) {
        viewModelScope.launch {
            val saved = catching<ReportSaved?>({ ReportSaved.Failed }) {
                reportExport.export(title, library.state.value, customGames?.let { CustomGamesFacts(it.live.value, it.modes.value) }, preferences.snapshot())?.let { ReportSaved.Saved(it) }
            } ?: return@launch
            local.update { it.copy(report = saved) }
        }
    }

    override fun closeLicences() = local.update { it.copy(licences = false) }

    fun backUp() = library.backUp()

    override fun showBackups(pane: BackupsPane?) = local.update { it.copy(backupsPane = pane) }

    override fun confirmBackup() {
        when (val pane = local.value.backupsPane) {
            is BackupsPane.ConfirmRestore -> {
                showBackups(null)
                library.restoreBackup(pane.backup)
            }
            is BackupsPane.ConfirmDelete -> {
                showBackups(BackupsPane.List.takeIf { library.state.value.backups.size > 1 })
                library.deleteBackup(pane.backup)
            }
            BackupsPane.List, null -> Unit
        }
    }

    fun clearDownloads() {
        val confirmation = (uiState.value.space as? SpaceUsed.Measured)?.space?.clear ?: return
        library.clearDownloads(confirmation)
    }

    fun clearPreviews() {
        val files = previewFiles ?: return
        if (local.value.previews !is PreviewSpace.Measured) return
        local.update { it.copy(previews = PreviewSpace.Clearing) }
        viewModelScope.launch {
            catching({}) { files.clear() }
            measurePreviews()
        }
    }

    private fun measurePreviews() {
        val files = previewFiles ?: return
        viewModelScope.launch {
            val bytes = catching({ 0L }) { files.bytes() }
            local.update { it.copy(previews = PreviewSpace.Measured(bytes)) }
        }
    }

    private fun save(setting: SettingId, write: suspend () -> Unit) {
        viewModelScope.launch {
            val saved = catching({ false }) { write(); true }
            local.update { it.copy(unsaved = if (saved) it.unsaved.takeIf { unsaved -> unsaved != setting } else setting) }
        }
    }

    private fun act(block: suspend () -> Unit) {
        if (local.value.busy) return
        local.update { it.copy(busy = true, failed = false) }
        viewModelScope.launch {
            val failed = catching({ true }) { block(); false }
            local.update { it.copy(busy = false, failed = failed) }
        }
    }

    private fun uiState(state: LibraryState, defaults: InstallDefaults?, stats: StatsPreferences?, local: Local, space: SpaceUsed): SettingsUiState {
        val root = (state.game as? GameStatus.Ready)?.root
        val ready = state.library as? LibraryStatus.Ready
        val on = ready?.records?.filter { it.gameRoot == root && it.enabled }
        return SettingsUiState(
            game = state.game,
            separator = library.separator,
            rejected = local.rejected ?: (state.game as? GameStatus.Missing)?.rejected,
            busy = local.busy,
            failed = local.failed,
            defaults = defaults,
            defaultDownloadFolder = library.defaultDownloadFolder.path,
            modsOn = on?.takeIf { root != null }?.let { ModsOn(it.size, it.sumOf { record -> record.files.size }) },
            loading = state.loading,
            space = space,
            keptOriginals = ready?.records.orEmpty().sumOf { record -> record.sets.sumOf { set -> set.derivation?.originalFiles?.sumOf { it.sizeBytes } ?: 0L } },
            maintenance = state.maintenance,
            version = about.version,
            query = local.query,
            restore = local.restore,
            licences = local.licences,
            folderRefused = local.folderRefused,
            unsaved = local.unsaved,
            statsAvailable = statsAvailable,
            customGamesAvailable = customGames != null,
            stats = stats,
            report = local.report,
            backups = state.backups,
            backupsPane = local.backupsPane,
            previews = local.previews,
        )
    }
}
