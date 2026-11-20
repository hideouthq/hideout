package gg.hideouthq.core.report

import gg.hideouthq.core.customgames.redactInvites
import gg.hideouthq.core.customgames.FolderRead
import gg.hideouthq.core.customgames.LiveSession
import gg.hideouthq.core.customgames.ModeList
import gg.hideouthq.core.customgames.phase
import gg.hideouthq.core.customgames.said
import gg.hideouthq.core.data.FileRead
import gg.hideouthq.core.data.FolderListing
import gg.hideouthq.core.data.SettingsSnapshot
import gg.hideouthq.core.library.ChangeRequest
import gg.hideouthq.core.library.ChangeStage
import gg.hideouthq.core.library.ChoiceSync
import gg.hideouthq.core.library.ClearStage
import gg.hideouthq.core.library.EntryKind
import gg.hideouthq.core.library.FilesDamage
import gg.hideouthq.core.library.GameInfoPatch
import gg.hideouthq.core.library.GameStatus
import gg.hideouthq.core.library.LibraryProblem
import gg.hideouthq.core.library.LibraryState
import gg.hideouthq.core.library.LibraryStatus
import gg.hideouthq.core.library.Maintenance
import gg.hideouthq.core.library.ManagerActivity
import gg.hideouthq.core.library.ManifestRecord
import gg.hideouthq.core.library.OperationStage
import gg.hideouthq.core.library.SearchOrderRead
import gg.hideouthq.core.library.SetFolder
import gg.hideouthq.core.library.admitsTemporary
import gg.hideouthq.core.library.counted
import gg.hideouthq.core.library.named
import gg.hideouthq.core.library.reason
import gg.hideouthq.core.library.said
import gg.hideouthq.core.library.shownProblems
import gg.hideouthq.core.library.sizeText
import kotlin.time.Instant

/**
 * What a bug report says of the computer and of this copy of Hideout, read once at start: [build] is how it was packaged
 * and whether it can update itself, and [desktop] the Linux desktop and session, null elsewhere.
 */
data class Machine(val version: String, val build: String, val system: String, val desktop: String?, val locale: String, val runtime: String) {
    /** Everything but the version on one line, as the log's start line has it. */
    val line: String get() = listOfNotNull(build, system, desktop?.let { "desktop $it" }, "locale $locale", runtime).joinToString(", ")
}

/**
 * Everything a bug report shows, read as it is saved at [savedAt]: [customGames] is null where this build has none,
 * [addons] and [gameInfo] are the found game's `addons` folder and `gameinfo.gi` as read then, null without a game, and
 * [log] Hideout's log, oldest first.
 */
data class ReportFacts(
    val savedAt: Instant,
    val machine: Machine,
    val library: LibraryState,
    val settings: SettingsSnapshot,
    val customGames: CustomGamesFacts?,
    val addons: FolderListing?,
    val gameInfo: FileRead?,
    val gameFreeBytes: Long?,
    val dataFreeBytes: Long?,
    val log: String,
)

/** What a report says of custom games: the session as published, and the modes and list this computer has. */
data class CustomGamesFacts(val live: LiveSession, val modes: ModeList)

private data class ReportSection(val title: String, val entries: List<ReportEntry>)

private sealed interface ReportEntry {
    data class Fact(val name: String, val value: String) : ReportEntry
    data class Item(val text: String, val details: List<String> = emptyList()) : ReportEntry
    data class Text(val text: String) : ReportEntry
}

/**
 * The report on [facts] as plain text that reads the same in a text editor and pasted into a GitHub issue, in the order
 * a maintainer reads one: the computer, the game, its files, the library and what it does, custom games, the settings, then the log.
 */
fun bugReport(facts: ReportFacts): String = render(
    listOf(
        hideout(facts.savedAt, facts.machine),
        game(facts),
        addons(facts),
        library(facts.library),
        profiles(facts.library),
        work(facts.library),
        backups(facts.library),
        customGames(facts.customGames, facts.settings),
        settings(facts.settings),
        ReportSection("Log", listOf(ReportEntry.Text(facts.log.ifEmpty { "(empty)\n" }))),
    ),
)

private fun render(sections: List<ReportSection>): String = buildString {
    appendLine("Hideout bug report")
    sections.forEach { section ->
        appendLine()
        appendLine("## ${section.title}")
        if (section.entries.isEmpty()) appendLine("None")
        section.entries.forEach { entry ->
            when (entry) {
                is ReportEntry.Fact -> appendLine("${entry.name}: ${entry.value}")
                is ReportEntry.Item -> {
                    appendLine("- ${entry.text}")
                    entry.details.forEach { appendLine("    $it") }
                }
                is ReportEntry.Text -> append(entry.text)
            }
        }
    }
}

private typealias Fact = ReportEntry.Fact
private typealias Item = ReportEntry.Item

private fun hideout(savedAt: Instant, machine: Machine) = ReportSection(
    "Hideout",
    listOfNotNull(
        Fact("Saved", "$savedAt"),
        Fact("Version", machine.version),
        Fact("Build", machine.build),
        Fact("System", machine.system),
        machine.desktop?.let { Fact("Desktop", it) },
        Fact("Locale", machine.locale),
        Fact("Runtime", machine.runtime),
    ),
)

private fun game(facts: ReportFacts): ReportSection {
    val state = facts.library
    val open = (state.managers as? ManagerActivity.Seen)?.open
    return ReportSection(
        "Game",
        listOfNotNull(
            Fact(
                "Folder",
                when (val game = state.game) {
                    GameStatus.Searching -> "still looking"
                    is GameStatus.Ready -> "${game.root.path}, ${game.via.said}"
                    is GameStatus.Missing -> "not found" + game.rejected?.let { ", ${it.said}" }.orEmpty()
                },
            ),
            Fact("Deadlock", state.deadlock.said),
            Fact("Mod loading", state.loading.said),
            facts.gameInfo?.let { Fact("Search paths", searchPaths(it)) },
            state.sessionHold?.let { Fact("Custom game", "holds the game's files (${it.step})") },
            Fact("Other mod managers open", open?.joinToString()?.ifEmpty { "none" } ?: "unknown"),
            Fact("Free space", "game folder ${facts.gameFreeBytes?.let(::sizeText) ?: "unknown"}, Hideout's data ${facts.dataFreeBytes?.let(::sizeText) ?: "unknown"}"),
        ),
    )
}

/** The `Game` entries of `gameinfo.gi` in the order Deadlock reads them, so a folder loaded ahead of Hideout's lines shows. */
private fun searchPaths(file: FileRead): String = when (file) {
    FileRead.Absent -> "gameinfo.gi is missing"
    is FileRead.Unreadable -> "gameinfo.gi couldn't be read: ${file.reason}"
    is FileRead.Present -> when (val order = GameInfoPatch.searchOrder(file.bytes)) {
        is SearchOrderRead.Problem -> LibraryProblem.GameInfo(order.problem).reason()
        is SearchOrderRead.Read -> order.entries.joinToString { if (it.hideouts) "${it.path} (Hideout's)" else it.path }
    }
}

/**
 * Every entry of `addons` and whose it is: a file of a mod Hideout has on there, a temporary of one of its changes, or
 * not Hideout's. Files Hideout did not put there load all the same, so they are what a mod that "does nothing" most often
 * meets. A file a mod that is on should have and that is not there is listed too.
 */
private fun addons(facts: ReportFacts): ReportSection {
    val title = "Files in ${SetFolder.Addons.segments.joinToString("/")}"
    val root = (facts.library.game as? GameStatus.Ready)?.root
    val listing = facts.addons
    if (root == null || listing == null) return ReportSection(title, listOf(Fact("Listing", "no game found")))
    if (listing is FolderListing.Unreadable) return ReportSection(title, listOf(Fact("Listing", "couldn't be read: ${listing.reason}")))
    val entries = (listing as FolderListing.Listed).entries.sortedBy { it.name.lowercase() }
    val records = (facts.library.library as? LibraryStatus.Ready)?.records.orEmpty().filter { it.gameRoot == root && it.enabled }
    val owned = records.flatMap { record -> record.files.map { file -> file.name to (record to file) } }.toMap()
    val unknown = entries.count { it.name !in owned && !SetFolder.Addons.admitsTemporary(it.name) }
    val items = entries.map { entry ->
        val size = when (entry.kind) {
            EntryKind.File -> entry.stamp?.let { sizeText(it.sizeBytes) } ?: "file"
            EntryKind.Directory -> "folder"
            EntryKind.Link -> "link"
            EntryKind.Other -> "not a plain file"
        }
        val whose = owned[entry.name]?.let { (record, file) ->
            "Hideout's, ${record.named}" + entry.stamp?.takeIf { it.sizeBytes != file.sizeBytes }?.let { ", but Hideout wrote ${sizeText(file.sizeBytes)}" }.orEmpty()
        } ?: when {
            SetFolder.Addons.admitsTemporary(entry.name) -> "a temporary of a change Hideout is making"
            entry.name.endsWith(".vpk", ignoreCase = true) -> "NOT Hideout's, it loads all the same"
            else -> "not Hideout's"
        }
        Item("${entry.name}, $size: $whose")
    }
    val missing = owned.filterKeys { it !in listing.names }.map { (name, owner) -> Item("$name: missing, of ${owner.first.named}") }
    return ReportSection(title, listOf(Fact("Summary", "${entries.size} entries, ${entries.size - unknown} Hideout's, $unknown not, ${missing.size} missing")) + items + missing)
}

private fun library(state: LibraryState): ReportSection {
    val ready = when (val status = state.library) {
        LibraryStatus.Loading -> return ReportSection("Library", listOf(Fact("Status", "still reading the install list")))
        is LibraryStatus.Blocked -> return ReportSection("Library", listOf(Fact("Status", "blocked: ${status.reason}")))
        is LibraryStatus.Ready -> status
    }
    val root = (state.game as? GameStatus.Ready)?.root
    val order = root?.let { state.profiles?.activeProfile?.orders?.get(it) }.orEmpty()
    val records = ready.records.sortedWith(compareBy<ManifestRecord> { it.gameRoot != root }.thenBy { order.indexOf(it.installId).let { at -> if (at < 0) Int.MAX_VALUE else at } })
    return ReportSection(
        "Library",
        listOfNotNull(
            Fact("Status", "ready, ${counted(ready.records.size, "mod")}, ${ready.records.count { it.enabled }} on"),
            ready.interrupted?.let { Fact("Interrupted change", it) },
            Fact("Downloads", "${ready.downloads.size} kept, ${ready.clearable.size} Clear may delete"),
        ) + records.mapIndexed { index, record ->
            val flags = listOfNotNull(
                ready.damaged[record.installId]?.let { if (it == FilesDamage.Missing) "files missing" else "files changed" },
                "files unreadable".takeIf { record.installId in ready.unchecked },
                "changed outside Hideout, held as it is".takeIf { record.edited },
                "past Deadlock's 99-file limit, not loading".takeIf { record.installId in state.pastLimit },
                "uninstalled, files not gone yet".takeIf { record.installId in state.leaving },
                "not yet found on GameBanana".takeIf { record.unasked },
                "in ${record.gameRoot.path}, not the found game".takeIf { record.gameRoot != root },
            )
            val placed = record.numbers.takeIf { it.isNotEmpty() }?.let { "on, ${it.first().pak.stem}" + if (it.size > 1) "-${it.last().pak.stem}" else "" } ?: "off"
            Item(
                "${index + 1}. ${record.named} [${record.installId}]: $placed" + flags.joinToString("") { ", $it" },
                record.sets.map { set ->
                    set.said + ", part ${set.componentKey}, ${counted(set.files.size, "file")}, ${sizeText(set.files.sumOf { it.image.sizeBytes })}" +
                        set.variant?.let { ", variant $it" }.orEmpty() + set.derivation?.let { ", repaired by ${it.rules.joinToString()}" }.orEmpty() +
                        set.edits.keys.takeIf { it.isNotEmpty() }?.let { parts -> ", edited: ${parts.joinToString { it.label }}" }.orEmpty()
                },
            )
        },
    )
}

private fun profiles(state: LibraryState): ReportSection {
    val book = state.profiles ?: return ReportSection("Profiles", listOf(Fact("Profiles", "not read yet")))
    val records = (state.library as? LibraryStatus.Ready)?.records.orEmpty()
    fun title(id: String) = records.firstOrNull { it.installId == id }?.title ?: id
    val unsaved = Fact("Unsaved edits", "yes, the shown profiles differ from the saved ones").takeIf { state.shownProfiles != book }
    return ReportSection(
        "Profiles",
        listOfNotNull(unsaved) + book.profiles.map { profile ->
            val mods = profile.orders.values.flatten()
            Item(
                "${profile.name}${if (profile.id == book.active) " (active)" else ""}: ${mods.count { it in profile.enabled }} of ${mods.size} on",
                profile.orders.map { (root, ids) -> "order in ${root.path}: " + ids.joinToString { id -> title(id) + if (id in profile.enabled) " (on)" else "" } },
            )
        },
    )
}

private fun work(state: LibraryState): ReportSection = ReportSection(
    "Under way",
    listOfNotNull(
        Fact("Files against the saved choices", when (val sync = state.choiceSync) {
            ChoiceSync.Settled -> "settled"
            is ChoiceSync.Waiting -> "waiting for Deadlock to close (it is ${sync.activity.said})"
            ChoiceSync.Applying -> "applying"
            is ChoiceSync.Failed -> "failed: ${sync.problem.reason()}"
        }),
        Fact("Saving an edit", "yes").takeIf { state.saving },
        Fact("Play", "starting Deadlock").takeIf { state.launching },
        state.gameBananaBusy?.let { Fact("GameBanana busy until", "$it") },
        state.linesPutBack?.let { Fact("Lines last put back in gameinfo.gi after", "${it.manager}") },
        state.maintenance?.let { Fact("Settings work", maintenanceText(it)) },
    ) + state.operations.values.map { operation ->
        val stage = operation.stage
        Item("Install of ${operation.title ?: operation.modId} (${operation.modId}): ${if (stage is OperationStage.Failed) "failed, ${stage.problem.reason()}" else stage::class.simpleName}")
    } + state.changes.values.map { change ->
        val stage = change.stage
        Item("${if (change.request is ChangeRequest.Uninstall) "Uninstall" else "Change"} of ${change.title} [${change.installId}]: ${if (stage is ChangeStage.Refused) "refused, ${stage.problem.reason()}" else "running"}")
    } + shownProblems(state).map { Item("Shown: $it") },
)

private fun maintenanceText(maintenance: Maintenance): String = when (maintenance) {
    is Maintenance.Restore -> "restore gameinfo.gi, ${maintenance.stage}"
    is Maintenance.Clear -> "clear downloads, " + ((maintenance.stage as? ClearStage.Cleared)?.let { "${it.removed} removed, ${it.kept} kept" } ?: "${maintenance.stage}")
    is Maintenance.Backups -> "backups, ${maintenance.work::class.simpleName}, ${maintenance.stage}"
}

private fun backups(state: LibraryState) = ReportSection(
    "Backups",
    state.backups.map { Item("${it.id.value}, ${Instant.fromEpochMilliseconds(it.createdAt)}, ${it.reason.name}, ${counted(it.mods, "mod")}") },
)

private fun customGames(games: CustomGamesFacts?, settings: SettingsSnapshot): ReportSection {
    if (games == null) return ReportSection("Custom games", listOf(Fact("Available", "no, this build has no custom games helper")))
    val modes = games.modes
    val list = when {
        modes.noFeed -> "none, this build has no list to read"
        modes.feed != null -> modes.feed.said
        modes.refreshing -> "being read"
        else -> "not read yet"
    } + modes.problem?.let { "; the last read failed: ${it.said}" }.orEmpty()
    return ReportSection(
        "Custom games",
        listOfNotNull(
            Fact("Available", "yes, turned ${if (settings.customGames) "on" else "off"}"),
            Fact("Session", redactInvites(games.live.session.phase)),
            games.live.game?.let { Fact("Game", it.said) },
            Fact("List", redactInvites(list)),
            modes.updating?.let { (id, transfer) -> Fact("Updating", id.value + transfer?.let { ", ${sizeText(it.done)} of ${sizeText(it.total)}" }.orEmpty()) },
        ) + modes.installed.values.sortedBy { it.mode.id.value }.map { record ->
            val update = modes.updateOf(record)?.let { ", version ${it.version} available" }.orEmpty()
            val failed = modes.failed[record.mode.id]?.let { ", the last update failed, ${it.said}" }.orEmpty()
            Item("Installed: ${record.mode.said}, ${sizeText(record.bytes)}, last played ${record.lastPlayed ?: "never"}$update$failed")
        } + modes.folders.orEmpty().map { folder ->
            Item(
                "Folder ${folder.path}: " + when (val read = folder.read) {
                    is FolderRead.Read -> read.mode.said
                    FolderRead.NotThere -> "not there"
                    FolderRead.NotAMode -> "no mod.json with a name and version"
                    FolderRead.BadListing -> "hideout.json can't be read"
                },
            )
        },
    )
}

private fun settings(settings: SettingsSnapshot): ReportSection {
    fun on(value: Boolean) = if (value) "on" else "off"
    return ReportSection(
        "Settings",
        listOf(
            Fact("Game folder", settings.gameFolder?.let { "chosen, $it" } ?: "none chosen, Steam says where"),
            Fact("Ask which files to install", on(settings.install.askWhichVersion)),
            Fact("Keep downloaded files", on(settings.install.keepDownloads)),
            Fact("Download folder", settings.install.downloadFolder?.path ?: "Hideout's own"),
            Fact("Repair skins automatically", on(settings.autoRepair)),
            Fact("Download updates automatically", on(settings.downloadAppUpdates)),
            Fact("Load 3D previews by default", on(settings.load3dPreviews)),
            Fact("Hide mature mods", on(settings.hideMature)),
            Fact("Custom games", on(settings.customGames)),
            Fact("Host mode folders", on(settings.hostModeFolders)),
            Fact("Stats", "${on(settings.stats)}, Steam tracking ${on(settings.steamTracking)}, sharing match IDs ${on(settings.shareMatchIds)}"),
            Fact("Unfinished downloads", settings.unfinishedInstalls.joinToString().ifEmpty { "none" }),
        ),
    )
}
