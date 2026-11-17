package gg.hideouthq.feature.settings

import androidx.compose.ui.graphics.vector.ImageVector
import gg.hideouthq.core.designsystem.component.ButtonVariant
import gg.hideouthq.core.designsystem.icon.HideoutIcons
import gg.hideouthq.resources.analytics_title
import gg.hideouthq.resources.analytics_description
import gg.hideouthq.resources.analytics_keywords
import gg.hideouthq.resources.Res
import gg.hideouthq.resources.setting_accent_description
import gg.hideouthq.resources.setting_accent_keywords
import gg.hideouthq.resources.setting_accent_title
import gg.hideouthq.resources.setting_ask_version_description
import gg.hideouthq.resources.setting_ask_version_keywords
import gg.hideouthq.resources.setting_ask_version_title
import gg.hideouthq.resources.setting_auto_repair_description
import gg.hideouthq.resources.setting_auto_repair_keywords
import gg.hideouthq.resources.setting_auto_repair_title
import gg.hideouthq.resources.setting_back_up_keywords
import gg.hideouthq.resources.setting_back_up_title
import gg.hideouthq.resources.setting_backups_description
import gg.hideouthq.resources.setting_backups_keywords
import gg.hideouthq.resources.setting_backups_title
import gg.hideouthq.resources.setting_check_app_updates_keywords
import gg.hideouthq.resources.setting_check_app_updates_title
import gg.hideouthq.resources.setting_custom_games_description
import gg.hideouthq.resources.setting_custom_games_keywords
import gg.hideouthq.resources.setting_custom_games_title
import gg.hideouthq.resources.setting_deadlock_keywords
import gg.hideouthq.resources.setting_deadlock_title
import gg.hideouthq.resources.setting_download_app_updates_description
import gg.hideouthq.resources.setting_download_app_updates_keywords
import gg.hideouthq.resources.setting_download_app_updates_title
import gg.hideouthq.resources.setting_download_folder_keywords
import gg.hideouthq.resources.setting_download_folder_title
import gg.hideouthq.resources.setting_bug_report_description
import gg.hideouthq.resources.setting_bug_report_keywords
import gg.hideouthq.resources.setting_bug_report_title
import gg.hideouthq.resources.setting_host_mode_folders_description
import gg.hideouthq.resources.setting_host_mode_folders_keywords
import gg.hideouthq.resources.setting_host_mode_folders_title
import gg.hideouthq.resources.setting_keep_description
import gg.hideouthq.resources.setting_keep_keywords
import gg.hideouthq.resources.setting_keep_title
import gg.hideouthq.resources.setting_licences_description
import gg.hideouthq.resources.setting_licences_keywords
import gg.hideouthq.resources.setting_licences_title
import gg.hideouthq.resources.setting_hide_mature_description
import gg.hideouthq.resources.setting_hide_mature_keywords
import gg.hideouthq.resources.setting_hide_mature_title
import gg.hideouthq.resources.setting_load_3d_previews_description
import gg.hideouthq.resources.setting_load_3d_previews_keywords
import gg.hideouthq.resources.setting_load_3d_previews_title
import gg.hideouthq.resources.setting_mod_cards_description
import gg.hideouthq.resources.setting_mod_cards_keywords
import gg.hideouthq.resources.setting_mod_cards_title
import gg.hideouthq.resources.setting_mod_loading_description
import gg.hideouthq.resources.setting_mod_loading_keywords
import gg.hideouthq.resources.setting_mod_loading_title
import gg.hideouthq.resources.setting_mods_folder_keywords
import gg.hideouthq.resources.setting_mods_folder_title
import gg.hideouthq.resources.setting_previews_keywords
import gg.hideouthq.resources.setting_previews_title
import gg.hideouthq.resources.setting_share_match_ids_description
import gg.hideouthq.resources.setting_share_match_ids_keywords
import gg.hideouthq.resources.setting_share_match_ids_title
import gg.hideouthq.resources.setting_source_description
import gg.hideouthq.resources.setting_source_keywords
import gg.hideouthq.resources.setting_source_title
import gg.hideouthq.resources.setting_space_keywords
import gg.hideouthq.resources.setting_space_title
import gg.hideouthq.resources.setting_stats_account_keywords
import gg.hideouthq.resources.setting_stats_account_title
import gg.hideouthq.resources.setting_stats_cache_description
import gg.hideouthq.resources.setting_stats_cache_keywords
import gg.hideouthq.resources.setting_stats_cache_title
import gg.hideouthq.resources.setting_stats_description
import gg.hideouthq.resources.setting_stats_keywords
import gg.hideouthq.resources.setting_stats_source_description
import gg.hideouthq.resources.setting_stats_source_keywords
import gg.hideouthq.resources.setting_stats_source_title
import gg.hideouthq.resources.setting_stats_title
import gg.hideouthq.resources.setting_version_description
import gg.hideouthq.resources.setting_version_keywords
import gg.hideouthq.resources.setting_version_title
import gg.hideouthq.resources.setting_whats_new_description
import gg.hideouthq.resources.setting_whats_new_keywords
import gg.hideouthq.resources.setting_whats_new_title
import gg.hideouthq.resources.settings_group_account
import gg.hideouthq.resources.settings_group_backups
import gg.hideouthq.resources.settings_group_data
import gg.hideouthq.resources.settings_group_browsing
import gg.hideouthq.resources.settings_group_downloads
import gg.hideouthq.resources.settings_group_installing
import gg.hideouthq.resources.settings_group_theme
import org.jetbrains.compose.resources.StringResource

internal enum class SettingId { UsageAnalytics, Deadlock, ModsFolder, ModLoading, Stats, StatsAccount, ShareMatchIds, StatsSource, StatsCache, AskWhichVersion, DownloadFolder, KeepDownloads, SpaceUsed, Load3dPreviews, Previews, HideMature, AccentColour, ModCards, AutoRepair, CustomGames, HostModeFolders, BackUp, Backups, Version, CheckAppUpdates, DownloadAppUpdates, WhatsNew, ModSource, BugReport, Licences }

internal enum class SettingGroup(val tab: SettingsTab, val heading: StringResource?) {
    Deadlock(SettingsTab.Game, null),
    ModsFolder(SettingsTab.Game, null),
    Stats(SettingsTab.Stats, Res.string.settings_group_account),
    StatsData(SettingsTab.Stats, Res.string.settings_group_data),
    Installing(SettingsTab.Mods, Res.string.settings_group_installing),
    Downloads(SettingsTab.Mods, Res.string.settings_group_downloads),
    Browsing(SettingsTab.Mods, Res.string.settings_group_browsing),
    Backups(SettingsTab.Mods, Res.string.settings_group_backups),
    Theme(SettingsTab.Appearance, Res.string.settings_group_theme),
    Experimental(SettingsTab.Experimental, null),
    Developer(SettingsTab.Developer, null),
    Hideout(SettingsTab.About, null),
}

internal enum class SettingAction { ChooseGameFolder, OpenModsFolder, RestoreOriginal, ChangeStatsAccount, ClearStats, OpenStatsSource, ChangeDownloadFolder, ClearDownloads, ClearPreviews, BackUp, ChooseBackup, OpenGameBanana, SaveBugReport, ViewLicences, CheckAppUpdates, OpenWhatsNew }

internal sealed interface SettingControl {
    data object GameCard : SettingControl

    data object Switch : SettingControl

    data class Button(val action: SettingAction, val variant: ButtonVariant) : SettingControl

    data object Accent : SettingControl

    data object CardStyle : SettingControl

    data object None : SettingControl
}

internal enum class Visibility {
    Always,

    WithGame,

    WithAnalytics,

    /** Only where this build has a Stats backend. */
    WithStats,

    /** Only where this build has 3D previews. */
    WithPreviews,

    /** Only where this build can run custom games. */
    WithCustomGames,
}

internal class Setting(
    val id: SettingId,
    val group: SettingGroup,
    val title: StringResource,
    /** The fixed description, or null where the row describes the state instead. */
    val description: StringResource?,
    val keywords: StringResource,
    /** The row's glyph, or null for Hideout's own row, which shows the logo. */
    val icon: ImageVector?,
    val control: SettingControl,
    val visibility: Visibility = Visibility.Always,
) {
    val tab: SettingsTab get() = group.tab
}

internal val SettingsRegistry: List<Setting> = listOf(
    Setting(SettingId.Deadlock, SettingGroup.Deadlock, Res.string.setting_deadlock_title, null, Res.string.setting_deadlock_keywords, HideoutIcons.Monitor, SettingControl.GameCard),
    Setting(
        SettingId.ModsFolder, SettingGroup.ModsFolder, Res.string.setting_mods_folder_title, null, Res.string.setting_mods_folder_keywords, HideoutIcons.Layers,
        SettingControl.Button(SettingAction.OpenModsFolder, ButtonVariant.Tonal), Visibility.WithGame,
    ),
    Setting(
        SettingId.ModLoading, SettingGroup.ModsFolder, Res.string.setting_mod_loading_title, Res.string.setting_mod_loading_description,
        Res.string.setting_mod_loading_keywords, HideoutIcons.FileText, SettingControl.Button(SettingAction.RestoreOriginal, ButtonVariant.Text), Visibility.WithGame,
    ),
    Setting(
        SettingId.Stats, SettingGroup.Stats, Res.string.setting_stats_title, Res.string.setting_stats_description,
        Res.string.setting_stats_keywords, HideoutIcons.NavStats, SettingControl.Switch, Visibility.WithStats,
    ),
    Setting(
        SettingId.StatsAccount, SettingGroup.Stats, Res.string.setting_stats_account_title, null, Res.string.setting_stats_account_keywords,
        HideoutIcons.Person, SettingControl.Button(SettingAction.ChangeStatsAccount, ButtonVariant.Tonal), Visibility.WithStats,
    ),
    Setting(SettingId.ShareMatchIds, SettingGroup.StatsData, Res.string.setting_share_match_ids_title, Res.string.setting_share_match_ids_description,
        Res.string.setting_share_match_ids_keywords, HideoutIcons.Share, SettingControl.Switch, Visibility.WithStats),
    Setting(SettingId.StatsSource, SettingGroup.StatsData, Res.string.setting_stats_source_title, Res.string.setting_stats_source_description,
        Res.string.setting_stats_source_keywords, HideoutIcons.Globe, SettingControl.Button(SettingAction.OpenStatsSource, ButtonVariant.Text), Visibility.WithStats),
    Setting(SettingId.StatsCache, SettingGroup.StatsData, Res.string.setting_stats_cache_title, Res.string.setting_stats_cache_description,
        Res.string.setting_stats_cache_keywords, HideoutIcons.Box, SettingControl.Button(SettingAction.ClearStats, ButtonVariant.Tonal), Visibility.WithStats),
    Setting(SettingId.UsageAnalytics, SettingGroup.Hideout, Res.string.analytics_title, Res.string.analytics_description,
        Res.string.analytics_keywords, null, SettingControl.Switch, Visibility.WithAnalytics),
    Setting(
        SettingId.Version, SettingGroup.Hideout, Res.string.setting_version_title, Res.string.setting_version_description,
        Res.string.setting_version_keywords, null, SettingControl.None,
    ),
    Setting(
        SettingId.CheckAppUpdates, SettingGroup.Hideout, Res.string.setting_check_app_updates_title, null,
        Res.string.setting_check_app_updates_keywords, HideoutIcons.Refresh, SettingControl.Button(SettingAction.CheckAppUpdates, ButtonVariant.Tonal),
    ),
    Setting(
        SettingId.DownloadAppUpdates, SettingGroup.Hideout, Res.string.setting_download_app_updates_title, Res.string.setting_download_app_updates_description,
        Res.string.setting_download_app_updates_keywords, HideoutIcons.Download, SettingControl.Switch,
    ),
    Setting(
        SettingId.WhatsNew, SettingGroup.Hideout, Res.string.setting_whats_new_title, Res.string.setting_whats_new_description,
        Res.string.setting_whats_new_keywords, HideoutIcons.FileText, SettingControl.Button(SettingAction.OpenWhatsNew, ButtonVariant.Tonal),
    ),
    Setting(
        SettingId.AskWhichVersion, SettingGroup.Installing, Res.string.setting_ask_version_title, Res.string.setting_ask_version_description,
        Res.string.setting_ask_version_keywords, HideoutIcons.SwapVersions, SettingControl.Switch,
    ),
    Setting(
        SettingId.DownloadFolder, SettingGroup.Downloads, Res.string.setting_download_folder_title, null, Res.string.setting_download_folder_keywords, HideoutIcons.Download,
        SettingControl.Button(SettingAction.ChangeDownloadFolder, ButtonVariant.Tonal),
    ),
    Setting(
        SettingId.KeepDownloads, SettingGroup.Downloads, Res.string.setting_keep_title, Res.string.setting_keep_description,
        Res.string.setting_keep_keywords, HideoutIcons.Box, SettingControl.Switch,
    ),
    Setting(
        SettingId.SpaceUsed, SettingGroup.Downloads, Res.string.setting_space_title, null, Res.string.setting_space_keywords, HideoutIcons.Folder,
        SettingControl.Button(SettingAction.ClearDownloads, ButtonVariant.Text),
    ),
    Setting(
        SettingId.Load3dPreviews, SettingGroup.Downloads, Res.string.setting_load_3d_previews_title, Res.string.setting_load_3d_previews_description,
        Res.string.setting_load_3d_previews_keywords, HideoutIcons.Cube, SettingControl.Switch, Visibility.WithPreviews,
    ),
    Setting(
        SettingId.Previews, SettingGroup.Downloads, Res.string.setting_previews_title, null, Res.string.setting_previews_keywords, HideoutIcons.Cube,
        SettingControl.Button(SettingAction.ClearPreviews, ButtonVariant.Text), Visibility.WithPreviews,
    ),
    Setting(
        SettingId.HideMature, SettingGroup.Browsing, Res.string.setting_hide_mature_title, Res.string.setting_hide_mature_description,
        Res.string.setting_hide_mature_keywords, HideoutIcons.Shield, SettingControl.Switch,
    ),
    Setting(
        SettingId.AccentColour, SettingGroup.Theme, Res.string.setting_accent_title, Res.string.setting_accent_description,
        Res.string.setting_accent_keywords, HideoutIcons.Palette, SettingControl.Accent,
    ),
    Setting(
        SettingId.ModCards, SettingGroup.Theme, Res.string.setting_mod_cards_title, Res.string.setting_mod_cards_description,
        Res.string.setting_mod_cards_keywords, HideoutIcons.Cards, SettingControl.CardStyle,
    ),
    Setting(
        SettingId.AutoRepair, SettingGroup.Experimental, Res.string.setting_auto_repair_title, Res.string.setting_auto_repair_description,
        Res.string.setting_auto_repair_keywords, HideoutIcons.Shield, SettingControl.Switch,
    ),
    Setting(
        SettingId.CustomGames, SettingGroup.Experimental, Res.string.setting_custom_games_title, Res.string.setting_custom_games_description,
        Res.string.setting_custom_games_keywords, HideoutIcons.Globe, SettingControl.Switch, Visibility.WithCustomGames,
    ),
    Setting(
        SettingId.HostModeFolders, SettingGroup.Developer, Res.string.setting_host_mode_folders_title, Res.string.setting_host_mode_folders_description,
        Res.string.setting_host_mode_folders_keywords, HideoutIcons.Folder, SettingControl.Switch, Visibility.WithCustomGames,
    ),
    Setting(
        SettingId.BackUp, SettingGroup.Backups, Res.string.setting_back_up_title, null, Res.string.setting_back_up_keywords, HideoutIcons.Shield,
        SettingControl.Button(SettingAction.BackUp, ButtonVariant.Tonal), Visibility.WithGame,
    ),
    Setting(
        SettingId.Backups, SettingGroup.Backups, Res.string.setting_backups_title, Res.string.setting_backups_description, Res.string.setting_backups_keywords, HideoutIcons.Clock,
        SettingControl.Button(SettingAction.ChooseBackup, ButtonVariant.Text), Visibility.WithGame,
    ),
    Setting(
        SettingId.ModSource, SettingGroup.Hideout, Res.string.setting_source_title, Res.string.setting_source_description,
        Res.string.setting_source_keywords, HideoutIcons.Globe, SettingControl.Button(SettingAction.OpenGameBanana, ButtonVariant.Text),
    ),
    Setting(
        SettingId.BugReport, SettingGroup.Hideout, Res.string.setting_bug_report_title, Res.string.setting_bug_report_description,
        Res.string.setting_bug_report_keywords, HideoutIcons.FileText, SettingControl.Button(SettingAction.SaveBugReport, ButtonVariant.Text),
    ),
    Setting(
        SettingId.Licences, SettingGroup.Hideout, Res.string.setting_licences_title, Res.string.setting_licences_description,
        Res.string.setting_licences_keywords, HideoutIcons.FileText, SettingControl.Button(SettingAction.ViewLicences, ButtonVariant.Tonal),
    ),
).sortedBy { it.tab.ordinal }

internal data class SettingText(val setting: Setting, val title: String, val description: String?, val keywords: List<String>)

internal fun settingText(setting: Setting, title: String, description: String?, keywords: String) =
    SettingText(setting, title, description, keywords.split(',').map { it.trim() }.filter { it.isNotEmpty() })

internal data class SettingMatch(val text: SettingText, val title: List<IntRange>, val description: List<IntRange>)

internal data class SearchSection(val tab: SettingsTab, val matches: List<SettingMatch>)

internal data class SettingsSearch(val sections: List<SearchSection>) {
    val count: Int get() = sections.sumOf { it.matches.size }
}

internal fun searchWords(query: String): List<String> = query.split(Whitespace).filter { it.isNotEmpty() }

internal fun searchSettings(texts: List<SettingText>, query: String): SettingsSearch {
    val words = searchWords(query)
    if (words.isEmpty()) return SettingsSearch(emptyList())
    val matches = texts.filter { text ->
        val searchable = listOf(text.title) + listOfNotNull(text.description) + text.keywords
        words.all { word -> searchable.any { it.contains(word, ignoreCase = true) } }
    }.map { SettingMatch(it, highlights(it.title, words), it.description?.let { description -> highlights(description, words) }.orEmpty()) }
    return SettingsSearch(SettingsTab.entries.mapNotNull { tab -> matches.filter { it.text.setting.tab == tab }.takeIf { it.isNotEmpty() }?.let { SearchSection(tab, it) } })
}

internal fun highlights(text: String, words: List<String>): List<IntRange> {
    val found = words.filter { it.isNotEmpty() }.flatMap { word ->
        (0..text.length - word.length).filter { text.regionMatches(it, word, 0, word.length, ignoreCase = true) }.map { it until it + word.length }
    }.sortedBy { it.first }
    return found.fold(mutableListOf()) { merged, range ->
        val last = merged.lastOrNull()
        if (last != null && range.first <= last.last + 1) merged[merged.lastIndex] = last.first..maxOf(last.last, range.last) else merged += range
        merged
    }
}

private val Whitespace = Regex("\\s+")
