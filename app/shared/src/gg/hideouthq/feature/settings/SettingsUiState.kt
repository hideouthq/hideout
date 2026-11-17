package gg.hideouthq.feature.settings

import gg.hideouthq.core.appupdate.AppUpdateState
import gg.hideouthq.core.data.FolderRefusal
import gg.hideouthq.core.data.InstallDefaults
import gg.hideouthq.core.data.StatsPreferences
import gg.hideouthq.core.data.deadlockstats.SteamTrackingInstallation
import gg.hideouthq.core.data.deadlockstats.SteamTrackingStatus
import gg.hideouthq.core.library.BackupInfo
import gg.hideouthq.core.library.DownloadSpace
import gg.hideouthq.core.library.FolderCheck
import gg.hideouthq.core.library.GameRoot
import gg.hideouthq.core.library.GameStatus
import gg.hideouthq.core.library.Maintenance
import gg.hideouthq.core.library.ModLoading
import gg.hideouthq.core.library.RestoreConfirmation
import gg.hideouthq.core.model.Appearance

internal data class SettingsUiState(
    val analyticsEnabled: Boolean? = null,
    val game: GameStatus = GameStatus.Searching,
    /** How this platform separates folders, for relative paths in messages. */
    val separator: Char = '/',
    /** The folder last found not to be Deadlock's: one just chosen, or a remembered choice that stopped working. */
    val rejected: FolderCheck.Invalid? = null,
    /** A search or a choice is under way. */
    val busy: Boolean = false,
    /** The last search or choice failed before it could take effect. */
    val failed: Boolean = false,
    val defaults: InstallDefaults? = null,
    val defaultDownloadFolder: String = "",
    val modsOn: ModsOn? = null,
    val loading: ModLoading = ModLoading.Unknown,
    val space: SpaceUsed = SpaceUsed.Measuring,
    /** What the original files kept for repaired mods in every game take, as the install list records them; Clear leaves them. */
    val keptOriginals: Long = 0,
    val maintenance: Maintenance? = null,
    val version: String = "",
    val query: String = "",
    val restore: RestoreConfirmation? = null,
    val licences: Boolean = false,
    val folderRefused: FolderRefusal? = null,
    val unsaved: SettingId? = null,
    /** Whether this build has a Stats backend; without one the Stats rows are hidden. */
    val statsAvailable: Boolean = false,
    /** Null until read. */
    val stats: StatsPreferences? = null,
    val steamTracking: SteamTrackingSettings = SteamTrackingSettings(),
    /** How saving the last bug report went; null until one finished. */
    val report: ReportSaved? = null,
    /** The found game's backups, newest first; none while they cannot be listed. */
    val backups: List<BackupInfo> = emptyList(),
    /** What the backups dialog shows, or null while it is closed. */
    val backupsPane: BackupsPane? = null,
    /** The kept 3D preview files; null where this build has no previews. */
    val previews: PreviewSpace? = null,
    /** Whether skins are repaired without asking; null until read. */
    val autoRepair: Boolean? = null,
    /** Whether Hideout's own updates download by themselves; null until read, and on by default. */
    val downloadAppUpdates: Boolean? = null,
    val load3dPreviews: Boolean? = null,
    /** Whether mature mods are left out of Browse; null until read, and off by default. */
    val hideMature: Boolean? = null,
    val appearance: Appearance = Appearance(),
    /** Whether this build can run custom games; without that the switch is hidden. */
    val customGamesAvailable: Boolean = false,
    /** Whether Custom games shows in the sidebar; null until read, and off by default. */
    val customGames: Boolean? = null,
    /** Whether the custom games library offers mode folders to host; null until read, and off by default. */
    val hostModeFolders: Boolean? = null,
    /** Where updating Hideout stands; null in a view model that was given none. */
    val appUpdate: AppUpdateState? = null,
) {
    val gameRoot: GameRoot? get() = (game as? GameStatus.Ready)?.root

    val downloadFolder: String get() = defaults?.downloadFolder?.path ?: defaultDownloadFolder
}

internal data class SteamTrackingSettings(
    val installation: SteamTrackingInstallation = SteamTrackingInstallation(),
    val status: SteamTrackingStatus = SteamTrackingStatus(),
)

internal data class ModsOn(val mods: Int, val files: Int)

internal sealed interface ReportSaved {
    data class Saved(val name: String) : ReportSaved

    data object Failed : ReportSaved
}

internal sealed interface BackupsPane {
    data object List : BackupsPane

    data class ConfirmRestore(val backup: BackupInfo) : BackupsPane

    data class ConfirmDelete(val backup: BackupInfo) : BackupsPane
}

internal sealed interface PreviewSpace {
    data object Measuring : PreviewSpace

    data object Clearing : PreviewSpace

    data class Measured(val bytes: Long) : PreviewSpace
}

internal sealed interface SpaceUsed {
    data object Measuring : SpaceUsed

    data object Unknown : SpaceUsed

    data class Measured(val space: DownloadSpace) : SpaceUsed
}
