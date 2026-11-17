package gg.hideouthq.feature.customgames

import gg.hideouthq.core.customgames.Choices
import gg.hideouthq.core.customgames.FetchProblem
import gg.hideouthq.core.customgames.FolderRead
import gg.hideouthq.core.customgames.Install
import gg.hideouthq.core.customgames.InstalledMode
import gg.hideouthq.core.customgames.LibraryEntry
import gg.hideouthq.core.customgames.Mode
import gg.hideouthq.core.customgames.ModeId
import gg.hideouthq.core.customgames.ModeKind
import gg.hideouthq.core.customgames.ModeList
import gg.hideouthq.core.customgames.ModeListing
import gg.hideouthq.core.customgames.ModeSetting
import gg.hideouthq.core.customgames.Session
import gg.hideouthq.core.customgames.SettingValue
import gg.hideouthq.core.customgames.Transfer
import gg.hideouthq.core.customgames.UpcomingMode
import gg.hideouthq.core.customgames.capacity
import gg.hideouthq.core.customgames.library
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.time.Instant

enum class ModeTab { Browse, Servers, Installed }

enum class ModeSort { Featured, Newest }

/** [kind] null is All. */
data class ModeFilter(val kind: ModeKind? = null, val sort: ModeSort = ModeSort.Featured, val query: String = "")

/** [key] is the library entry's, so a folder and a feed mode of one id stay apart. */
sealed interface ModeCard {
    val key: String
    val id: ModeId
    val listing: ModeListing

    data class Hostable(override val key: String, val mode: Mode, val install: Install, val new: Boolean, val folder: Boolean) : ModeCard {
        override val id get() = mode.id
        override val listing get() = mode.listing
    }

    data class ComingSoon(override val key: String, val mode: UpcomingMode) : ModeCard {
        override val id get() = mode.id
        override val listing get() = mode.listing
    }
}

data class KindCount(val kind: ModeKind?, val count: Int)

/** [filtersShown] is false with one mode, when chips, sort and search would only be noise. */
data class LibraryView(val kinds: List<KindCount>, val filtersShown: Boolean, val shown: List<ModeCard>, val browseCount: Int, val installed: InstalledView)

/** [folders] is null while hosting mode folders is off. */
data class InstalledView(val rows: List<InstalledRow>, val folders: List<LibraryEntry.Folder>?, val bytes: Long, val updates: Int) {
    val count: Int get() = rows.size + folders.orEmpty().size
}

data class InstalledRow(val key: String, val mode: Mode, val record: InstalledMode, val install: Install, val inFeed: Boolean, val failed: FetchProblem?)

fun libraryView(modes: ModeList, filter: ModeFilter, today: LocalDate): LibraryView {
    val entries = library(modes)
    val cards = entries.mapNotNull { cardOf(it, today) }
    val kinds = listOf(KindCount(null, cards.size)) +
        ModeKind.entries.mapNotNull { kind -> cards.count { it.listing.kind == kind }.takeIf { it > 0 }?.let { KindCount(kind, it) } }
    val query = filter.query.trim()
    val matching = cards.filter { card ->
        (filter.kind == null || card.listing.kind == filter.kind) && (query.isEmpty() || card.listing.matches(query))
    }
    val shown = when (filter.sort) {
        ModeSort.Featured -> matching
        ModeSort.Newest -> matching.sortedWith(compareByDescending(nullsFirst()) { it.listing.released })
    }
    val rows = entries.filterIsInstance<LibraryEntry.Listed>().mapNotNull { entry ->
        val record = modes.installed[entry.mode.id] ?: return@mapNotNull null
        InstalledRow(entry.key, entry.mode, record, entry.install, entry.inFeed, modes.failed[entry.mode.id])
    }
    val installed = InstalledView(rows, modes.folders, rows.sumOf { it.record.bytes }, rows.count { (it.install as? Install.Installed)?.update != null })
    return LibraryView(kinds, cards.size > 1, shown, cards.size, installed)
}

/** Discover's cards, and how many modes there are in all. */
fun shelf(modes: ModeList, today: LocalDate): Pair<List<ModeCard>, Int> {
    val cards = library(modes).mapNotNull { cardOf(it, today) }
    return cards.take(ShelfSize) to cards.size
}

/** The card whose page [key] names. An upcoming mode has no page. */
fun modeCard(modes: ModeList, key: String, today: LocalDate): ModeCard.Hostable? =
    library(modes).firstOrNull { it.key == key }?.let { cardOf(it, today) } as? ModeCard.Hostable

private fun cardOf(entry: LibraryEntry, today: LocalDate): ModeCard? = when (entry) {
    is LibraryEntry.Listed -> ModeCard.Hostable(entry.key, entry.mode, entry.install, isNew(entry.mode.listing, today), folder = false)
    is LibraryEntry.Folder -> (entry.read as? FolderRead.Read)?.let { ModeCard.Hostable(entry.key, it.mode, Install.NotInstalled, new = false, folder = true) }
    is LibraryEntry.Upcoming -> ModeCard.ComingSoon(entry.key, entry.mode)
}

private fun ModeListing.matches(query: String): Boolean =
    (listOf(name, tagline, description) + creators).any { it.contains(query, ignoreCase = true) }

internal fun isNew(listing: ModeListing, today: LocalDate): Boolean {
    val released = listing.released ?: return false
    return !released.isAfter(today) && released.plusDays(NewForDays).isAfter(today)
}

private const val NewForDays = 14L
private const val ShelfSize = 4

/** Why Host is off right now, as the button's tooltip and the dialog's hint. Null hosts. */
enum class HostBlock { CleaningUp, GameRunning }

fun hostBlock(session: Session): HostBlock? = when {
    session == Session.Recovering -> HostBlock.CleaningUp
    session != Session.Idle && session !is Session.Ended -> HostBlock.GameRunning
    else -> null
}

/** Why Update, Update all and Remove are off, as their tooltip. The owner ignores them while a session holds the files or an update runs. */
enum class UpkeepBlock { CleaningUp, GameRunning, Updating }

fun upkeepBlock(host: HostBlock?, modes: ModeList): UpkeepBlock? = when {
    host == HostBlock.CleaningUp -> UpkeepBlock.CleaningUp
    host == HostBlock.GameRunning -> UpkeepBlock.GameRunning
    modes.updating != null -> UpkeepBlock.Updating
    else -> null
}

data class SettingPick(val setting: ModeSetting, val picked: SettingValue?)

data class HostDialogView(val settings: List<SettingPick>, val capacity: Int, val download: Long?)

fun hostDialog(mode: Mode, download: Long?, choices: Choices): HostDialogView = HostDialogView(
    settings = mode.listing.settings.map { SettingPick(it, choices[it.key]) },
    capacity = mode.listing.capacity(choices),
    download = download,
)

enum class ModeBadge { New, Installed, Update, Folder, ComingSoon }

/** The one thing a card says about its mode, the most pressing: a waiting update before being installed, and New only for a mode not here yet. */
val ModeCard.badge: ModeBadge?
    get() = when (this) {
        is ModeCard.ComingSoon -> ModeBadge.ComingSoon
        is ModeCard.Hostable -> when {
            folder -> ModeBadge.Folder
            (install as? Install.Installed)?.update != null -> ModeBadge.Update
            install !is Install.NotInstalled -> ModeBadge.Installed
            new -> ModeBadge.New
            else -> null
        }
    }

/** Where an installed mode stands, most pressing first: a running update, then a failed one, then a dropped listing. */
sealed interface InstalledStatus {
    data object UpToDate : InstalledStatus

    data class UpdateTo(val version: String) : InstalledStatus

    data class Updating(val transfer: Transfer?) : InstalledStatus

    data object UpdateFailed : InstalledStatus

    data object NotListed : InstalledStatus
}

val InstalledRow.update: Mode? get() = (install as? Install.Installed)?.update

val InstalledRow.status: InstalledStatus
    get() = when {
        install is Install.Updating -> InstalledStatus.Updating(install.transfer)
        failed != null -> InstalledStatus.UpdateFailed
        !inFeed -> InstalledStatus.NotListed
        else -> update?.let { InstalledStatus.UpdateTo(it.version) } ?: InstalledStatus.UpToDate
    }

fun playedDaysAgo(lastPlayed: Instant?, today: LocalDate, zone: ZoneId): Int? =
    lastPlayed?.let { ChronoUnit.DAYS.between(java.time.Instant.ofEpochMilli(it.toEpochMilliseconds()).atZone(zone).toLocalDate(), today).toInt().coerceAtLeast(0) }

enum class BrowseBody { Loading, NoModes, NoMatch, Cards }

fun browseBody(view: LibraryView, modes: ModeList): BrowseBody = when {
    view.shown.isNotEmpty() -> BrowseBody.Cards
    view.browseCount > 0 -> BrowseBody.NoMatch
    modes.refreshing || (modes.feed == null && modes.problem == null && !modes.noFeed) -> BrowseBody.Loading
    else -> BrowseBody.NoModes
}
