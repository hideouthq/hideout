package gg.hideouthq.feature.settings

import gg.hideouthq.core.analytics.*

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalUriHandler
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import gg.hideouthq.core.appupdate.AppUpdates
import gg.hideouthq.core.customgames.CustomGames
import gg.hideouthq.core.data.FolderOpener
import gg.hideouthq.core.data.FolderPicker
import gg.hideouthq.core.data.Preferences
import gg.hideouthq.core.data.ReportExport
import gg.hideouthq.core.data.gamebanana.GameBananaApi
import gg.hideouthq.core.data.preview.PreviewFiles
import gg.hideouthq.core.library.ModLibrary
import gg.hideouthq.core.model.AppAbout
import gg.hideouthq.core.ui.WindowModal
import gg.hideouthq.feature.stats.data.StatsService
import gg.hideouthq.resources.Res
import gg.hideouthq.resources.setting_bug_report_title
import gg.hideouthq.resources.setting_download_folder_picker_title
import gg.hideouthq.resources.settings_game_picker_title
import org.jetbrains.compose.resources.stringResource

@Composable
fun SettingsRoute(
    library: ModLibrary,
    preferences: Preferences,
    folderPicker: FolderPicker,
    folders: FolderOpener,
    about: AppAbout,
    modal: WindowModal,
    tab: SettingsTab,
    onTab: (SettingsTab) -> Unit,
    statsAvailable: Boolean = false,
    customGames: CustomGames? = null,
    onStatsAccount: () -> Unit = {},
    statsService: StatsService? = null,
    reportExport: ReportExport = ReportExport.Unavailable,
    previewFiles: PreviewFiles? = null,
    appUpdates: AppUpdates,
    onWhatsNew: () -> Unit,
    analytics: UsageAnalytics? = null,
) {
    val viewModel = viewModel { SettingsViewModel(library, preferences, folderPicker, folders, about, statsAvailable, statsService, reportExport, previewFiles, appUpdates.state, customGames, analytics) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val uris = LocalUriHandler.current
    val gamePicker = stringResource(Res.string.settings_game_picker_title)
    val downloadPicker = stringResource(Res.string.setting_download_folder_picker_title)
    val reportSaver = stringResource(Res.string.setting_bug_report_title)
    SettingsScreen(state, tab, onTab, viewModel, { action ->
        when (action) {
            SettingAction.ChooseGameFolder -> viewModel.chooseFolder(gamePicker)
            SettingAction.OpenModsFolder -> viewModel.openModsFolder()
            SettingAction.RestoreOriginal -> viewModel.askRestore()
            SettingAction.ChangeStatsAccount -> {
                viewModel.changeStatsAccount()
                onStatsAccount()
            }
            SettingAction.ChangeDownloadFolder -> viewModel.changeDownloadFolder(downloadPicker)
            SettingAction.ClearDownloads -> viewModel.clearDownloads()
            SettingAction.ClearPreviews -> viewModel.clearPreviews()
            SettingAction.ClearStats -> viewModel.clearStats()
            SettingAction.BackUp -> viewModel.backUp()
            SettingAction.ChooseBackup -> viewModel.showBackups(BackupsPane.List)
            SettingAction.OpenStatsSource -> uris.openUri("https://deadlock-api.com")
            SettingAction.OpenGameBanana -> uris.openUri(GameBananaApi.GamePage)
            SettingAction.SaveBugReport -> viewModel.saveReport(reportSaver)
            SettingAction.ViewLicences -> viewModel.openLicences()
            SettingAction.CheckAppUpdates -> appUpdates.checkNow()
            SettingAction.OpenWhatsNew -> onWhatsNew()
        }
    }, modal)
}
