package gg.hideouthq.feature.library

import gg.hideouthq.core.library.CompatStatus
import gg.hideouthq.core.library.FilesDamage
import gg.hideouthq.core.library.ImportWait
import gg.hideouthq.core.library.InstallCompatibility
import gg.hideouthq.core.library.Leftover
import gg.hideouthq.core.library.LibraryProblem
import gg.hideouthq.core.library.OrderView
import gg.hideouthq.core.library.ProfileBook
import gg.hideouthq.core.library.ProfileId
import gg.hideouthq.core.library.ReinstallFrom
import gg.hideouthq.core.library.Slot
import gg.hideouthq.core.library.UpdatePlan
import gg.hideouthq.core.library.inCitadel
import gg.hideouthq.core.library.status
import gg.hideouthq.core.model.ModCategory
import gg.hideouthq.feature.imports.SourceOption
import kotlin.time.Instant

enum class LibraryLayout { List, Cards }

/** Which installed mods Library lists before any filter: every one, or those the active profile turns on. */
enum class LibraryScope { All, Profile }

/** How a click picks rows: this one only, this one added or taken away (Ctrl), or the run from the last one picked (Shift). */
enum class SelectGesture { Only, Toggle, Range }

/**
 * Two or more mods selected together, what the details pane shows instead of one mod. [on] counts those the active
 * profile turns on; [canUninstall] unless one of them is changing already.
 */
data class SelectedMods(val count: Int, val on: Int, val sizeBytes: Long, val canToggle: Boolean, val canUninstall: Boolean, val coverage: Map<ProfileId, Coverage>)

/** How many of the picked mods a profile turns on. */
enum class Coverage { None, Some, All }

data class LibraryUiState(
    val loading: Boolean = true,
    val profiles: ProfileBook? = null,
    /** Mods may be added, from a file too, whatever Deadlock is doing. */
    val canInstall: Boolean = false,
    /** The note above the list about Deadlock and the files, if any. */
    val gameNote: GameNote? = null,
    /** Set when the manifest cannot be trusted; the list below is then always empty. */
    val blocked: String? = null,
    val enabledCount: Int = 0,
    /** How many mods that are on come after the most Deadlock loads, so they stay out of the game. */
    val pastLimit: Int = 0,
    /** The size of the sets that are on, which is what the game loads. */
    val enabledSizeBytes: Long = 0,
    val query: String = "",
    val layout: LibraryLayout = LibraryLayout.List,
    /** In claim order; a mod leaves this list once its record exists in the root. */
    val downloads: List<DownloadRow> = emptyList(),
    val scope: LibraryScope = LibraryScope.All,
    /** How many rows [scope] lists before the search and filter narrow them. */
    val scopedCount: Int = 0,
    /** All, Favourites, then one pill per category present among the rows [scope] lists and the search finds, with mods of none under Other, then Updates. */
    val filters: List<FilterPill> = emptyList(),
    val filter: LibraryFilter = LibraryFilter.All,
    /** Every installed set, by load order; rows the scope, search or filter hides have [LibraryRow.visible] false. */
    val rows: List<LibraryRow> = emptyList(),
    /** The rows picked, every one visible; one picked row is also [selected], two or more are [several]. */
    val selectedIds: Set<String> = emptySet(),
    val selected: LibraryDetail? = null,
    val several: SelectedMods? = null,
    /** Why an earlier change still waits to be finished; nothing else changes until it is. */
    val interrupted: String? = null,
    /** The order [rows] shows, as the library issued it: what a drag starts from and its drop is asked from. */
    val orderView: OrderView? = null,
    /** What the last check says of the current game's GameBanana mods. */
    val updates: UpdatesUi = UpdatesUi(),
    /** What checking this game's mods against the game found, grouped, and the compatibility dialog open, if any. */
    val compat: CompatUi = CompatUi(),
    /** An import that waits or runs, which the library shows until its mods are rows. */
    val importing: ImportBanner? = null,
    val leftovers: LeftoversUi = LeftoversUi(),
) {
    val isEmpty: Boolean get() = !loading && blocked == null && rows.isEmpty() && downloads.isEmpty()
    val totalCount: Int get() = rows.size
    val visibleCount: Int get() = rows.count { it.visible }

    /** From [FilterPillsFrom] mods, while a filter is chosen, or while an installed mod is a favourite, so its pill is there to pick. */
    val showFilters: Boolean get() = totalCount >= FilterPillsFrom || filter != LibraryFilter.All || filters.any { it.filter == LibraryFilter.Favourites }

    /** The scope switch has a choice to offer: a mod the active profile leaves off, or the profile's own view to leave. */
    val showScope: Boolean get() = scope == LibraryScope.Profile || rows.any { !it.enabled }

    /**
     * When several apply the most pressing wins: an import's mods are missing from the list until it runs, and its wait
     * names what to close; mods past the limit stay out of the game until the user acts, though their rows say so too;
     * saved changes apply by themselves once Deadlock closes; and files left in the game load until the user acts.
     */
    val note: LibraryNote?
        get() = importing?.let(LibraryNote::Importing)
            ?: pastLimit.takeIf { it > 0 }?.let(LibraryNote::PastLimit)
            ?: gameNote?.let(LibraryNote::Game)
            ?: leftovers.sets.takeIf { it.isNotEmpty() }?.let { sets -> LibraryNote.Leftovers(sets.size, sets.map { it.folder }.distinct().singleOrNull()?.inCitadel) }
}

/** What the summary line under Library's title adds after the counts. */
sealed interface LibraryNote {
    data class Importing(val banner: ImportBanner) : LibraryNote
    data class PastLimit(val count: Int) : LibraryNote
    data class Game(val note: GameNote) : LibraryNote

    /** [count] sets load that are not Hideout's, all in [folder] or, when null, in several loading folders. */
    data class Leftovers(val count: Int, val folder: String?) : LibraryNote
}

/**
 * The sets in the game's loading folders that load and are not Hideout's, and the dialog that reviews them: [open]
 * while it is on screen, [working] while a change it asked for runs, and [last], how the last one ended, until it
 * closes. [canChange] while files the game loads may change. [copies] has, for each set at a slot that is a copy of a
 * library mod, that mod's title: such a set can only be moved out.
 */
data class LeftoversUi(
    val sets: List<Leftover> = emptyList(),
    val copies: Map<Slot, String> = emptyMap(),
    val open: Boolean = false,
    val canChange: Boolean = false,
    val working: LeftoverAction? = null,
    val last: LeftoverResult? = null,
)

enum class LeftoverAction { Adding, Moving }

sealed interface LeftoverResult {
    data object Added : LeftoverResult

    /** Out of the game in [folder], below the game's `citadel` folder, as the system writes paths. */
    data class Moved(val folder: String) : LeftoverResult

    data class Failed(val problem: LibraryProblem) : LeftoverResult
}

/** An import under way: from [from], bringing [mods] mods when its preview counted them, and waiting for [wait] when it waits. */
data class ImportBanner(val from: SourceOption, val mods: Int?, val wait: ImportWait?)

/** A mod downloading, waiting for a slot, or being placed, shown in Library's "Downloading" group. */
data class DownloadRow(
    val modId: String,
    val title: String?,
    val category: ModCategory?,
    val heroOrKind: String?,
    val author: String?,
    val phase: DownloadPhase,
    val canCancel: Boolean,
    val hasModPage: Boolean = true,
    /** From the catalogue's cached mod, for the small list thumbnail; null when there is no artwork. */
    val coverUrl: String? = null,
    /** The catalogue's full-size art, for the card layout; null when there is no artwork. */
    val coverFullUrl: String? = null,
)

/** What Library notes about Deadlock: saved choices waiting while it runs or may run, that edits save while it runs, or that it may run. */
enum class GameNote { SavedRunning, SavedUnknown, SavedCustomGame, Running, Unknown }

sealed interface DownloadPhase {
    data object Preparing : DownloadPhase

    /** A file the user handed Hideout is read before it installs. */
    data object Reading : DownloadPhase

    /** GameBanana asked Hideout to wait; it starts, or goes on, by itself at [until]. */
    data class Busy(val until: Instant, val doneBytes: Long = 0) : DownloadPhase
    data object Choosing : DownloadPhase

    /** Waits for another download to finish. */
    data class Waiting(val totalBytes: Long?) : DownloadPhase

    /** [stalled] while no bytes come and it tries again by itself from what it has. */
    data class Transferring(val doneBytes: Long, val totalBytes: Long, val stalled: Boolean = false) : DownloadPhase {
        val percent: Int get() = if (totalBytes <= 0) 0 else ((doneBytes * 100) / totalBytes).toInt().coerceIn(0, 100)
    }
    data object Finishing : DownloadPhase
    /** Downloaded; it replaces files the game loads, so it waits for Deadlock to close. */
    data object WaitingForGame : DownloadPhase
    /** Downloaded; it replaces files the game loads, so it waits for the custom game holding them to end. */
    data object WaitingForCustomGame : DownloadPhase
    data object Cancelling : DownloadPhase
}

sealed interface LibraryFilter {
    data object All : LibraryFilter

    /** Mods of [category]; [ModCategory.Other] also holds the mods the catalogue gives no category. */
    data class Category(val category: ModCategory) : LibraryFilter

    /** This game's mods Update all would update; another game's mods stay under [All]. */
    data object Updates : LibraryFilter

    /** This game's mods whose files to review against what their GameBanana pages offer now. */
    data object Review : LibraryFilter

    data object Favourites : LibraryFilter
}

data class FilterPill(val filter: LibraryFilter, val count: Int)

/** The library size from which the filter pills show. */
const val FilterPillsFrom = 8

data class LibraryRow(
    val installId: String,
    /** Source key, distinct from [installId]. */
    val modId: String,
    /** Two of its parts change one file with different bytes, so their order decides what shows. */
    val partsOverlap: Boolean = false,
    /** 1 for the mod that wins when two change the same files: its position in load order, shown as "#". */
    val rank: Int,
    val title: String,
    /** What the mod's files are called: one file's version name, or every file's, joined. */
    val variant: String?,
    /** From the catalogue, when this mod is still in it; filter pills group by this. */
    val category: ModCategory?,
    /** The hero a skin replaces, or a HUD/sound's finer kind, from the catalogue. */
    val heroOrKind: String?,
    val author: String?,
    val sizeBytes: Long,
    /** Whether the active profile includes this set, as asked: a toggle or switch still settling shows already. One [held] is on while its files are in the game. */
    val enabled: Boolean,
    /** It is on and comes after the most Deadlock loads, so its files stay out of the game until mods above it go off or it moves up. */
    val pastLimit: Boolean = false,
    val visible: Boolean,
    /** Whether an owned file is missing or no longer matches what Hideout wrote, and which; null when neither. */
    val damage: FilesDamage? = null,
    /** Its files were changed outside Hideout: it stays as it is until the user keeps the change or restores the original, so nothing that would move or replace them is offered. */
    val held: Boolean = false,
    /** Where Reinstall gets the mod's original files; null when it has nowhere to get them from. */
    val reinstall: ReinstallFrom? = null,
    /** A change of this set is running, so Uninstall and its memberships wait. */
    val busy: Boolean = false,
    /** Whether this row's handle can start a drag right now; false while search/filter/busy/unknown disable reordering. */
    val draggable: Boolean = false,
    /** From the catalogue's cached mod, for the small list thumbnail; null when there is no artwork. */
    val coverUrl: String? = null,
    /** The catalogue's full-size art, for the card and Details layouts; null when there is no artwork. */
    val coverFullUrl: String? = null,
    /** The mod id of the GameBanana page the mod has, its own or its submission's when it is local; null when it has none. */
    val page: String?,
    /** An import brought it as a local mod and GameBanana is still to say who it is. */
    val finding: Boolean = false,
    /** The user starred it, here or on its page. */
    val favourite: Boolean = false,
    /** Mirrors [LibraryUiState.canInstall]: a different file may be installed over this set whatever Deadlock is doing. */
    val canInstall: Boolean = true,
    /** Its switch works now, even while this or another set is still being turned. */
    val canToggle: Boolean = true,
    /** Library work is still settling, so controls that cannot act yet wait without looking unavailable. */
    val applying: Boolean = false,
    val installedFiles: Int = 1,
    /** The VPKs it holds, which one download can have several of. */
    val installedVpks: Int = installedFiles,
    val availableFiles: Int? = null,
    /** What the last check says of its GameBanana files; null for the user's own files and another game's mods. */
    val update: UpdateState? = null,
    /** The update Update would confirm for it, when there is one it may carry out. */
    val updatePlan: UpdatePlan? = null,
    /** An update or change of its files under way, or one that failed. */
    val operation: RowOperation? = null,
    /** What checking it against the game found and whether its files are changing to a repair choice; null before any check. */
    val compat: InstallCompatibility? = null,
    /** How many models its files lose to files that load before them in the active profile's order. */
    val hiddenModels: Int = 0,
    /** What shows those models instead, first loaded first. */
    val hiddenBy: List<HiddenBy> = emptyList(),
) {
    /** What favourites know it by, the same id on its page and across an uninstall: its page's, or its own without one. */
    val favouriteId: String get() = page ?: modId

    /** Its switch can be turned: one [held] as it is shows where its files are and waits for the user's choice in Details. */
    val canSwitch: Boolean get() = canToggle && !held

    /** Whether all that shows its models instead is what the user meant: its own parts, and mods kept on beside it. */
    val hiddenMeant: Boolean get() = hiddenBy.all { it is HiddenBy.Part || it is HiddenBy.Mod && it.kept }

    /** Its chip, the same as every other screen shows for it; null when there is nothing confirmed or changing to say. */
    val compatStatus: CompatStatus? get() = compat?.status(enabled)

    /** Whether its chip has a place: kept while a repair in use says nothing because the mod is off, so turning it off, or the repair ending, moves nothing. */
    val compatPlace: Boolean get() = compat?.status(enabled = true) != null
}

/** What shows a row's models instead of it, by [name]: another mod, one of its own parts, or a file Hideout does not own. */
sealed interface HiddenBy {
    val name: String

    /** Mod [installId]; [kept] when the user chose, at the swap question or in File priority, to keep the two on together. */
    data class Mod(val installId: String, override val name: String, val kept: Boolean) : HiddenBy

    data class Part(override val name: String) : HiddenBy

    data class Foreign(override val name: String) : HiddenBy
}

/**
 * This game's mods by what checking them against the game found and what their files are doing, each count read from
 * the mods' current statuses. [repairable] Repair all may repair now: not those whose original files the user chose,
 * nor any already changing or whose repair didn't finish, and none while Hideout repairs them on its own ([automatic]).
 * [retry] are those whose repair didn't finish, which Repair all asks for again. [repairing], [restoring] and [waiting] count only
 * the changes this screen asked for: queued, changing, or placed and being checked until each one's own check lands,
 * or waiting for Deadlock to close. [unfinished] did not finish, whoever asked. [finished] tells how the changes this
 * screen asked for ended, once all of them did, until dismissed. [open] is the compatibility dialog on screen. A
 * problem only its author can fix is said on its mod's chip alone: nothing here can be done about it.
 */
data class CompatUi(
    val repairable: List<String> = emptyList(),
    val retry: List<String> = emptyList(),
    val repairing: Int = 0,
    val restoring: Int = 0,
    val waiting: Int = 0,
    val unfinished: Int = 0,
    val finished: CompatFinished? = null,
    val checking: Boolean = false,
    val unavailable: String? = null,
    /** Repair choices may be saved now; files change once Deadlock is known closed. */
    val canChange: Boolean = false,
    /** Hideout repairs what it can without being asked, so Repair all only tries again what didn't finish. */
    val automatic: Boolean = false,
    val open: CompatOpen? = null,
) {
    /** What Repair all asks for, as one decision. */
    val offered: List<String> get() = repairable + retry

    /** Whether the summary has anything to say: it stays from the first request until its outcome is dismissed. */
    val shown: Boolean get() = repairable.isNotEmpty() || repairing > 0 || restoring > 0 || waiting > 0 || unfinished > 0 || finished != null

    val working: Boolean get() = repairing > 0 || restoring > 0
}

/** Of the mods whose files this screen asked to change, how many now use repaired files, and how many their original ones. */
data class CompatFinished(val repaired: Int, val restored: Int)

sealed interface CompatOpen {
    /** One mod's details. */
    data class Mod(val installId: String, val title: String, val install: InstallCompatibility?, val enabled: Boolean) : CompatOpen

    /**
     * Every mod with a problem or a change under way, and those whose change this screen asked for, the same ones from
     * when it opened until it closes. Any other repair in use is neither.
     */
    data class Review(val items: List<CompatReviewItem>) : CompatOpen
}

data class CompatReviewItem(val installId: String, val title: String, val compat: InstallCompatibility?, val enabled: Boolean) {
    val status: CompatStatus? get() = compat?.status(enabled)
}

/** What the last check says of an installed mod's GameBanana files. */
enum class UpdateState {
    /** Not checked yet, or a file was installed since the last check asked. */
    NotChecked,

    /** The last check could not tell. Never shown as up to date. */
    Unknown,

    /** Nothing to do: still offered, its MD5 the same or not there to compare, or kept as it is over a review. */
    Current,

    /** Update can replace its changed files, each pinned to an exact file. */
    Available,

    /** A file changed or is gone in a way only choosing by hand can settle. */
    Review,
}

/** An update or change of an installed mod's files, shown on its own row. */
data class RowOperation(val phase: DownloadPhase, val canCancel: Boolean)

/**
 * The current game's GameBanana mods by what the last check says: [available] Update all may update, [review] need
 * files chosen by hand, [unchecked] could not be told or were not checked yet. [checking] while a check runs, and
 * [busyUntil] while it waits for GameBanana, which asked to wait until then; [checkable] when there is any GameBanana
 * mod to check.
 */
data class UpdatesUi(
    val available: Int = 0,
    val review: Int = 0,
    val unchecked: Int = 0,
    val checking: Boolean = false,
    val busyUntil: Instant? = null,
    val checkable: Boolean = false,
)

data class LibraryDetail(
    val row: LibraryRow,
    /** Where the mod's files currently sit, relative to the game folder: `addons`, or `hideout_off/<modId>` while off. */
    val folderPath: String,
    val files: FilesPlace,
    /** Every file the mod was installed from, winner first. */
    val downloads: List<DownloadDetail>,
    /** While the mod is [LibraryRow.held]: the files the user edited, and where it is now. */
    val held: HeldFiles? = null,
)

/** A mod held as it is: the names of the [edited] files, and the [numbers] it is in the game at, none while it is out of it. */
data class HeldFiles(val edited: List<String>, val numbers: List<String>)

/** Where a mod's files are, and where a saved choice moves them once Deadlock closes. */
enum class FilesPlace {
    InGame,
    Out,

    /** Turned on while Deadlock may run: its files go into the game when it closes. */
    GoingIn,

    /** Turned off while Deadlock may run: its files stay in the game until it closes. */
    GoingOut;

    val inGame: Boolean get() = this == InGame || this == GoingOut
}

/** One file a mod was installed from: what it is, the archive it came from, and the files Hideout placed for it, under [number] while the mod is on. */
data class DownloadDetail(
    /** Its version name, when it has one. */
    val name: String?,
    val archiveName: String,
    /** The pak number its files hold while the mod is on; null while it is off. */
    val number: Int?,
    val files: List<OwnedFileSummary>,
    val componentPath: String? = null,
)

data class OwnedFileSummary(val name: String, val sizeBytes: Long)
