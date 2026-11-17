package gg.hideouthq.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.AnnotatedString
import gg.hideouthq.core.appupdate.whatsNew
import gg.hideouthq.core.data.FolderRefusal
import gg.hideouthq.core.data.InstallDefaults
import gg.hideouthq.core.designsystem.component.ButtonVariant
import gg.hideouthq.core.library.BackupStage
import gg.hideouthq.core.library.BackupWork
import gg.hideouthq.core.library.ClearStage
import gg.hideouthq.core.library.GameStatus
import gg.hideouthq.core.library.LoadingOwner
import gg.hideouthq.core.library.Maintenance
import gg.hideouthq.core.library.ModLoading
import gg.hideouthq.core.library.RestoreStage
import gg.hideouthq.core.library.SetFolder
import gg.hideouthq.core.library.running
import gg.hideouthq.core.model.ModCardStyle
import gg.hideouthq.core.ui.LibraryLabels
import gg.hideouthq.core.ui.ModLabels
import gg.hideouthq.feature.appupdate.checkedAgo
import gg.hideouthq.feature.appupdate.headlineLine
import gg.hideouthq.feature.settings.components.backupTime
import gg.hideouthq.feature.settings.components.folderMessage
import gg.hideouthq.feature.stats.StatsFormat
import gg.hideouthq.resources.Res
import gg.hideouthq.resources.setting_back_up_last
import gg.hideouthq.resources.setting_back_up_never
import gg.hideouthq.resources.setting_back_up_now
import gg.hideouthq.resources.setting_back_up_refused
import gg.hideouthq.resources.setting_back_up_running
import gg.hideouthq.resources.setting_backups_choose
import gg.hideouthq.resources.setting_backups_delete_refused
import gg.hideouthq.resources.setting_backups_restore_refused
import gg.hideouthq.resources.setting_check_app_updates_check
import gg.hideouthq.resources.setting_check_app_updates_checking
import gg.hideouthq.resources.setting_deadlock_missing
import gg.hideouthq.resources.setting_download_folder_inside_game
import gg.hideouthq.resources.setting_download_folder_inside_hideout
import gg.hideouthq.resources.setting_download_folder_missing
import gg.hideouthq.resources.setting_download_folder_not_a_folder
import gg.hideouthq.resources.setting_download_folder_not_writable
import gg.hideouthq.resources.setting_download_folder_unresolved
import gg.hideouthq.resources.setting_bug_report_save
import gg.hideouthq.resources.setting_bug_report_failed
import gg.hideouthq.resources.setting_bug_report_saved
import gg.hideouthq.resources.setting_licences_view
import gg.hideouthq.resources.setting_mod_loading_off
import gg.hideouthq.resources.setting_mod_loading_on
import gg.hideouthq.resources.setting_mod_loading_refused
import gg.hideouthq.resources.setting_mod_loading_restore
import gg.hideouthq.resources.setting_mod_loading_restoring
import gg.hideouthq.resources.setting_mods_folder_files
import gg.hideouthq.resources.setting_mods_folder_from
import gg.hideouthq.resources.setting_mods_folder_open
import gg.hideouthq.resources.setting_mods_folder_summary
import gg.hideouthq.resources.setting_open_stats_source
import gg.hideouthq.resources.setting_previews_kept
import gg.hideouthq.resources.setting_previews_none
import gg.hideouthq.resources.setting_source_open
import gg.hideouthq.resources.setting_space_clear
import gg.hideouthq.resources.setting_space_clear_none
import gg.hideouthq.resources.setting_space_clearable
import gg.hideouthq.resources.setting_space_clearing
import gg.hideouthq.resources.setting_space_measuring
import gg.hideouthq.resources.setting_space_none
import gg.hideouthq.resources.setting_space_nothing
import gg.hideouthq.resources.setting_space_originals
import gg.hideouthq.resources.setting_space_refused
import gg.hideouthq.resources.setting_space_unknown
import gg.hideouthq.resources.setting_stats_account_change
import gg.hideouthq.resources.setting_stats_account_none
import gg.hideouthq.resources.setting_stats_account_value
import gg.hideouthq.resources.setting_stats_clear
import gg.hideouthq.resources.setting_version_check_failed
import gg.hideouthq.resources.setting_whats_new_open
import gg.hideouthq.resources.settings_change
import gg.hideouthq.resources.settings_game_change_failed
import gg.hideouthq.resources.settings_game_choose
import gg.hideouthq.resources.settings_not_saved
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Clock

internal data class SettingRow(
    val text: SettingText,
    val mono: Boolean,
    val chip: RowChip?,
    val note: AnnotatedString?,
    val control: RowControl,
) {
    val setting: Setting get() = text.setting
}

internal data class RowChip(val label: String, val on: Boolean)

internal sealed interface RowControl {
    data class Switch(val checked: Boolean, val enabled: Boolean) : RowControl

    data class Button(val action: SettingAction, val label: String, val variant: ButtonVariant, val enabled: Boolean) : RowControl

    data class Accent(val selected: gg.hideouthq.core.model.Accent) : RowControl

    data class CardStyle(val selected: ModCardStyle) : RowControl

    data object None : RowControl
}

@Composable
internal fun settingRows(state: SettingsUiState): List<SettingRow> =
    SettingsRegistry.filter { it.isShown(state) }.map { settingRow(it, state) }

internal fun Setting.isShown(state: SettingsUiState): Boolean = when (visibility) {
    Visibility.Always -> true
    Visibility.WithAnalytics -> state.analyticsEnabled != null
    Visibility.WithGame -> state.gameRoot != null
    Visibility.WithStats -> state.statsAvailable
    Visibility.WithPreviews -> state.previews != null
    Visibility.WithCustomGames -> state.customGamesAvailable
}

@Composable
private fun settingRow(setting: Setting, state: SettingsUiState): SettingRow {
    val fixed = setting.description?.let { stringResource(it) }
    val title = if (setting.id == SettingId.Version) stringResource(setting.title, state.version) else stringResource(setting.title)
    val live = live(setting, state, fixed)
    return SettingRow(settingText(setting, title, live.description, stringResource(setting.keywords)), live.mono, live.chip, live.note, live.control)
}

private class Live(val description: String?, val control: RowControl, val chip: RowChip? = null, val note: AnnotatedString? = null, val mono: Boolean = false)

@Composable
private fun live(setting: Setting, state: SettingsUiState, fixed: String?): Live {
    val unsaved = if (state.unsaved == setting.id) AnnotatedString(stringResource(Res.string.settings_not_saved)) else null
    val busy = state.maintenance?.running == true
    return when (setting.id) {
        SettingId.Deadlock -> Live(
            description = state.gameRoot?.path ?: stringResource(Res.string.setting_deadlock_missing),
            control = setting.button(stringResource(if (state.gameRoot != null) Res.string.settings_change else Res.string.settings_game_choose), !state.busy && state.game != GameStatus.Searching),
            note = state.rejected?.let { folderMessage(it.problem, state.separator) } ?: AnnotatedString(stringResource(Res.string.settings_game_change_failed)).takeIf { state.failed },
            mono = state.gameRoot != null,
        )
        SettingId.ModsFolder -> {
            val folder = SetFolder.Addons.segments.joinToString(state.separator.toString())
            Live(
                description = state.modsOn?.takeIf { it.mods > 0 }?.let { on ->
                    stringResource(
                        Res.string.setting_mods_folder_summary,
                        folder,
                        pluralStringResource(Res.plurals.setting_mods_folder_files, on.files, on.files),
                        pluralStringResource(Res.plurals.setting_mods_folder_from, on.mods, on.mods),
                    )
                } ?: folder,
                control = setting.button(stringResource(Res.string.setting_mods_folder_open), state.gameRoot != null),
            )
        }
        SettingId.ModLoading -> {
            val restore = state.maintenance as? Maintenance.Restore
            Live(
                description = fixed,
                chip = when (state.loading) {
                    is ModLoading.Loads -> RowChip(stringResource(Res.string.setting_mod_loading_on), on = true)
                    is ModLoading.Off -> RowChip(stringResource(Res.string.setting_mod_loading_off), on = false).takeIf { (state.modsOn?.mods ?: 0) > 0 }
                    ModLoading.Unknown, is ModLoading.Unreadable -> null
                },
                control = if (state.loading != ModLoading.Loads(LoadingOwner.Hideout) && restore?.stage != RestoreStage.Running) RowControl.None else setting.button(
                    stringResource(if (restore?.stage == RestoreStage.Running) Res.string.setting_mod_loading_restoring else Res.string.setting_mod_loading_restore),
                    !busy && state.loading == ModLoading.Loads(LoadingOwner.Hideout),
                ),
                note = (restore?.stage as? RestoreStage.Refused)?.let { AnnotatedString(stringResource(Res.string.setting_mod_loading_refused, LibraryLabels.problem(it.problem))) },
            )
        }
        SettingId.StatsSource -> Live(fixed, setting.button(stringResource(Res.string.setting_open_stats_source), true))
        SettingId.StatsCache -> Live(fixed, setting.button(stringResource(Res.string.setting_stats_clear), !state.busy), note = unsaved)
        SettingId.Stats -> Live(fixed, RowControl.Switch(state.stats?.enabled ?: true, enabled = state.stats != null), note = unsaved)
        SettingId.ShareMatchIds -> Live(fixed, RowControl.Switch(state.stats?.shareMatchIds == true, enabled = state.stats?.enabled == true), note = unsaved)
        SettingId.StatsAccount -> Live(
            description = state.stats?.accountId?.let { stringResource(Res.string.setting_stats_account_value, StatsFormat.steamId(it)) } ?: stringResource(Res.string.setting_stats_account_none),
            control = setting.button(stringResource(Res.string.setting_stats_account_change), state.stats?.enabled == true && state.stats.accountId != null),
            note = unsaved,
            mono = state.stats?.accountId != null,
        )
        SettingId.AskWhichVersion -> Live(fixed, switch(state) { it.askWhichVersion }, note = unsaved)
        SettingId.KeepDownloads -> Live(fixed, switch(state) { it.keepDownloads }, note = unsaved)
        SettingId.AccentColour -> Live(fixed, RowControl.Accent(state.appearance.accent), note = unsaved)
        SettingId.ModCards -> Live(fixed, RowControl.CardStyle(state.appearance.modCards), note = unsaved)
        SettingId.AutoRepair -> Live(fixed, RowControl.Switch(state.autoRepair ?: true, enabled = state.autoRepair != null), note = unsaved)
        SettingId.DownloadFolder -> Live(
            description = state.downloadFolder,
            control = setting.button(stringResource(Res.string.settings_change), state.defaults != null),
            note = state.folderRefused?.let { AnnotatedString(stringResource(folderRefusal(it))) } ?: unsaved,
            mono = true,
        )
        SettingId.SpaceUsed -> spaceUsed(setting, state, busy)
        SettingId.Previews -> {
            val bytes = (state.previews as? PreviewSpace.Measured)?.bytes
            Live(
                description = when {
                    bytes == null -> stringResource(Res.string.setting_space_measuring)
                    bytes == 0L -> stringResource(Res.string.setting_previews_none)
                    else -> stringResource(Res.string.setting_previews_kept, ModLabels.size(bytes))
                },
                control = setting.button(
                    when {
                        state.previews == PreviewSpace.Clearing -> stringResource(Res.string.setting_space_clearing)
                        bytes != null && bytes > 0 -> stringResource(Res.string.setting_space_clear, ModLabels.size(bytes))
                        else -> stringResource(Res.string.setting_space_clear_none)
                    },
                    bytes != null && bytes > 0,
                ),
            )
        }
        SettingId.BackUp -> {
            val backup = state.maintenance as? Maintenance.Backups
            Live(
                description = state.backups.firstOrNull()?.let { stringResource(Res.string.setting_back_up_last, backupTime(it.createdAt)) } ?: stringResource(Res.string.setting_back_up_never),
                control = setting.button(stringResource(if (backup == Maintenance.Backups(BackupWork.BackUp, BackupStage.Running)) Res.string.setting_back_up_running else Res.string.setting_back_up_now), !busy),
                note = (backup?.stage as? BackupStage.Refused)?.takeIf { backup.work == BackupWork.BackUp }?.let { AnnotatedString(stringResource(Res.string.setting_back_up_refused, LibraryLabels.problem(it.problem))) },
            )
        }
        SettingId.Backups -> {
            val backup = state.maintenance as? Maintenance.Backups
            val refused = (backup?.stage as? BackupStage.Refused)?.let { LibraryLabels.problem(it.problem) }
            Live(
                description = fixed,
                control = setting.button(stringResource(Res.string.setting_backups_choose), !busy && state.backups.isNotEmpty()),
                note = when (backup?.work) {
                    is BackupWork.Revert -> refused?.let { AnnotatedString(stringResource(Res.string.setting_backups_restore_refused, it)) }
                    is BackupWork.Delete -> refused?.let { AnnotatedString(stringResource(Res.string.setting_backups_delete_refused, it)) }
                    BackupWork.BackUp, null -> null
                },
            )
        }
        SettingId.Version -> Live(fixed, RowControl.None)
        SettingId.CheckAppUpdates -> Live(
            checkedLine(state),
            setting.button(stringResource(if (state.appUpdate?.checking == true) Res.string.setting_check_app_updates_checking else Res.string.setting_check_app_updates_check), state.appUpdate?.checking != true),
        )
        SettingId.UsageAnalytics -> Live(fixed, RowControl.Switch(state.analyticsEnabled == true, enabled = state.analyticsEnabled != null), note = unsaved)
        SettingId.DownloadAppUpdates -> Live(fixed, RowControl.Switch(state.downloadAppUpdates ?: true, enabled = state.downloadAppUpdates != null), note = unsaved)
        SettingId.Load3dPreviews -> Live(fixed, RowControl.Switch(state.load3dPreviews ?: true, enabled = state.load3dPreviews != null), note = unsaved)
        SettingId.HideMature -> Live(fixed, RowControl.Switch(state.hideMature ?: false, enabled = state.hideMature != null), note = unsaved)
        SettingId.CustomGames -> Live(fixed, RowControl.Switch(state.customGames ?: false, enabled = state.customGames != null), note = unsaved)
        SettingId.HostModeFolders -> Live(fixed, RowControl.Switch(state.hostModeFolders ?: false, enabled = state.hostModeFolders != null), note = unsaved)
        SettingId.WhatsNew -> Live(
            description = state.appUpdate?.takeIf { it.phase.version != null }?.let { headlineLine(whatsNew(it).headline) } ?: fixed,
            control = setting.button(stringResource(Res.string.setting_whats_new_open), state.appUpdate?.feed != null),
        )
        SettingId.ModSource -> Live(fixed, setting.button(stringResource(Res.string.setting_source_open), true))
        SettingId.BugReport -> Live(
            description = (state.report as? ReportSaved.Saved)?.let { stringResource(Res.string.setting_bug_report_saved, it.name) } ?: fixed,
            control = setting.button(stringResource(Res.string.setting_bug_report_save), true),
            note = AnnotatedString(stringResource(Res.string.setting_bug_report_failed)).takeIf { state.report == ReportSaved.Failed },
        )
        SettingId.Licences -> Live(fixed, setting.button(stringResource(Res.string.setting_licences_view), true))
    }
}

@Composable
private fun checkedLine(state: SettingsUiState): String? {
    val update = state.appUpdate ?: return null
    return if (update.lastCheckFailed) stringResource(Res.string.setting_version_check_failed) else update.lastChecked?.let { checkedAgo(it, Clock.System.now()) }
}

@Composable
private fun spaceUsed(setting: Setting, state: SettingsUiState, busy: Boolean): Live {
    val clearing = state.maintenance as? Maintenance.Clear
    val measured = (state.space as? SpaceUsed.Measured)?.space
    val description = when (val space = state.space) {
        SpaceUsed.Measuring -> stringResource(Res.string.setting_space_measuring)
        SpaceUsed.Unknown -> stringResource(Res.string.setting_space_unknown)
        is SpaceUsed.Measured -> with(space.space) {
            listOfNotNull(
                if (matching == 0) stringResource(Res.string.setting_space_none) else ModLabels.size(matchingBytes),
                clear?.let { pluralStringResource(Res.plurals.setting_space_clearable, it.downloads.size, it.downloads.size, ModLabels.size(it.bytes)) }
                    ?: stringResource(Res.string.setting_space_nothing).takeIf { matching > 0 },
                stringResource(Res.string.setting_space_originals, ModLabels.size(state.keptOriginals)).takeIf { state.keptOriginals > 0 },
            ).joinToString(" · ")
        }
    }
    val label = if (clearing?.stage == ClearStage.Running) {
        stringResource(Res.string.setting_space_clearing)
    } else {
        measured?.clear?.let { stringResource(Res.string.setting_space_clear, ModLabels.size(it.bytes)) } ?: stringResource(Res.string.setting_space_clear_none)
    }
    return Live(
        description = description,
        control = setting.button(label, !busy && measured?.clear != null),
        note = (clearing?.stage as? ClearStage.Refused)?.let { AnnotatedString(stringResource(Res.string.setting_space_refused, LibraryLabels.problem(it.problem))) },
    )
}

private fun Setting.button(label: String, enabled: Boolean): RowControl = when (val control = control) {
    is SettingControl.Button -> RowControl.Button(control.action, label, control.variant, enabled)
    SettingControl.GameCard -> RowControl.Button(SettingAction.ChooseGameFolder, label, ButtonVariant.Tonal, enabled)
    SettingControl.Switch, SettingControl.Accent, SettingControl.CardStyle, SettingControl.None -> RowControl.None
}

private fun switch(state: SettingsUiState, checked: (InstallDefaults) -> Boolean): RowControl =
    RowControl.Switch(state.defaults?.let(checked) ?: true, enabled = state.defaults != null)

private fun folderRefusal(refusal: FolderRefusal) = when (refusal) {
    FolderRefusal.Missing -> Res.string.setting_download_folder_missing
    FolderRefusal.NotAFolder -> Res.string.setting_download_folder_not_a_folder
    FolderRefusal.NotWritable -> Res.string.setting_download_folder_not_writable
    FolderRefusal.InsideGame -> Res.string.setting_download_folder_inside_game
    FolderRefusal.InsideHideout -> Res.string.setting_download_folder_inside_hideout
    FolderRefusal.Unresolved -> Res.string.setting_download_folder_unresolved
}
