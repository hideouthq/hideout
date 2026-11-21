package gg.hideouthq.core.library

import java.util.Locale
import kotlin.time.Instant

/** The lines the log gains as the library moves from [before] to [after]. A state that passes before the log reads it may go unwritten. */
internal fun libraryLog(before: LibraryState, after: LibraryState): List<String> =
    libraryEvents(before, after) + (shownProblems(after) - shownProblems(before))

/** What [state] shows the user going wrong, one line each, so the log holds what a bug report describes. */
internal fun shownProblems(state: LibraryState): Set<String> = buildSet {
    (state.library as? LibraryStatus.Blocked)?.let { add("The install list is unavailable: ${it.reason}") }
    state.operations.values.forEach { operation -> (operation.stage as? OperationStage.Failed)?.let { add("Installing ${operation.modId} failed: ${it.problem}") } }
    state.changes.values.forEach { change -> (change.stage as? ChangeStage.Refused)?.let { add("${change.request::class.simpleName} of ${change.installId} was refused: ${it.problem}") } }
    when (val maintenance = state.maintenance) {
        is Maintenance.Restore -> (maintenance.stage as? RestoreStage.Refused)?.let { add("Restoring gameinfo.gi was refused: ${it.problem}") }
        is Maintenance.Clear -> (maintenance.stage as? ClearStage.Refused)?.let { add("Clearing downloads was refused: ${it.problem}") }
        is Maintenance.Backups -> (maintenance.stage as? BackupStage.Refused)?.let {
            val work = when (maintenance.work) {
                BackupWork.BackUp -> "Backing up"
                is BackupWork.Revert -> "Restoring a backup"
                is BackupWork.Delete -> "Deleting a backup"
            }
            add("$work was refused: ${it.problem}")
        }
        null -> Unit
    }
    state.choiceProblem?.let { add("An edit wasn't saved: $it") }
    (state.choiceSync as? ChoiceSync.Failed)?.let { add("The files couldn't follow the saved choices: ${it.problem}") }
}

/** What the user and Hideout did between [before] and [after], one line each. A toggle is logged once the install list shows its files moved. */
internal fun libraryEvents(before: LibraryState, after: LibraryState): List<String> = buildList {
    if (after.game != before.game) gameEvent(before.game, after.game)?.let(::add)
    if (after.deadlock != before.deadlock && after.deadlock != GameActivity.Unread) add("Deadlock: ${after.deadlock.said}")
    if (after.loading != before.loading && after.loading != ModLoading.Unknown) add("Mod loading: ${after.loading.said}")
    val was = before.library as? LibraryStatus.Ready
    val now = after.library as? LibraryStatus.Ready
    if (was == null && now != null) add("Read the install list: ${counted(now.records.size, "mod")}, ${now.records.count { it.enabled }} on")
    if (was != null && now != null) addAll(recordEvents(was, now))
    val root = (after.game as? GameStatus.Ready)?.root
    if (root != null && now != null) {
        val past = now.records.overflowIn(root)
        if (past != (was?.records?.overflowIn(root) ?: 0)) add("Mods load from ${counted(past + 1, "folder")}: ${SetFolder.Loading.take(past + 1).joinToString { it.inCitadel }}")
    }
    if (now?.interrupted != null && now.interrupted != was?.interrupted) add("A change was interrupted and waits to be finished: ${now.interrupted}")
    if (was?.interrupted != null && now != null && now.interrupted == null) add("The interrupted change was finished")
    val profilesBefore = before.profiles
    val profilesAfter = after.profiles
    if (profilesBefore != null && profilesAfter != null) addAll(profileEvents(profilesBefore, profilesAfter))
    after.operations.values.filter { it.stage !is OperationStage.Failed && before.operations[it.modId].let { prior -> prior == null || prior.stage is OperationStage.Failed } }
        .forEach { add("${if (it.installId == null) "Installing" else "Changing the files of"} ${it.title?.let { title -> "$title (${it.modId})" } ?: it.modId}") }
    if (after.maintenance != before.maintenance) after.maintenance?.let(::maintenanceEvent)?.let(::add)
    if (after.launching && !before.launching) add("Play: starting Deadlock")
    if (after.sessionHold != null && before.sessionHold == null) add("A custom game holds the game's files")
    if (after.sessionHold == null && before.sessionHold != null) add("The custom game let go of the game's files")
    val open = (after.managers as? ManagerActivity.Seen)?.open
    val wasOpen = (before.managers as? ManagerActivity.Seen)?.open.orEmpty()
    if (open != null && open != wasOpen) add(if (open.isEmpty()) "No other mod manager is open" else "Other mod managers open: ${open.joinToString()}")
    after.rewrite?.takeIf { it != before.rewrite && it != before.rewrite?.copy(seen = true) }?.let { add(it.said) }
    if (after.choiceSync is ChoiceSync.Waiting && before.choiceSync !is ChoiceSync.Waiting) add("Changes wait for Deadlock to close")
    if (after.pastLimit != before.pastLimit && after.pastLimit.isNotEmpty()) {
        add("Past what Hideout can load, so not loading: ${after.pastLimit.joinToString { id -> now?.records?.firstOrNull { it.installId == id }?.named ?: id }}")
    }
}

private fun gameEvent(before: GameStatus, after: GameStatus): String? = when (after) {
    GameStatus.Searching -> null
    is GameStatus.Ready -> "Found Deadlock at ${after.root.path}, ${after.via.said}".takeIf { (before as? GameStatus.Ready)?.root != after.root }
    is GameStatus.Missing -> if (before is GameStatus.Ready) "Lost Deadlock at ${before.root.path}" else "Deadlock not found${after.rejected?.let { ", ${it.said}" }.orEmpty()}"
}

private fun recordEvents(before: LibraryStatus.Ready, after: LibraryStatus.Ready): List<String> = buildList {
    val was = before.records.associateBy { it.installId }
    val now = after.records.associateBy { it.installId }
    after.records.filter { it.installId !in was }.forEach { record ->
        val found = record.sets.first().origin as? SetOrigin.Found
        add(if (found == null) "Installed ${record.named}: ${record.sources}" else "Added ${found.path.name} from ${found.path.folder} to the library as ${record.named}")
    }
    before.records.filter { it.installId !in now }.forEach { add("Uninstalled ${it.named}") }
    val kept = after.records.mapNotNull { record -> was[record.installId]?.let { it to record } }
    kept.filter { (old, new) -> old.provenance != new.provenance }.forEach { (old, new) -> add("Changed the files of ${new.named}: ${old.sources} to ${new.sources}") }
    kept.filter { (old, new) -> !old.enabled && new.enabled }.takeIf { it.isNotEmpty() }?.let { add("Turned on: ${it.joinToString { (_, new) -> new.named }}") }
    kept.filter { (old, new) -> old.enabled && !new.enabled }.takeIf { it.isNotEmpty() }?.let { add("Turned off: ${it.joinToString { (_, new) -> new.named }}") }
    val stayedOn = kept.filter { (old, new) -> old.enabled && new.enabled }
    val newOrder = stayedOn.sortedBy { (_, new) -> new.numbers.first() }
    if (stayedOn.sortedBy { (old, _) -> old.numbers.first() }.map { it.second.installId } != newOrder.map { it.second.installId }) {
        add("Reordered: ${newOrder.joinToString { (_, new) -> new.named }}")
    }
    kept.filter { (old, new) -> !old.edited && new.edited }.forEach { (_, new) -> add("A file of ${new.named} was changed outside Hideout") }
    kept.filter { (old, new) -> old.edited && !new.edited }.forEach { (_, new) -> add("The changed file of ${new.named} was kept or put back") }
    after.damaged.filter { (id, damage) -> before.damaged[id] != damage }.forEach { (id, damage) ->
        add("Files of ${now[id]?.named ?: id} are ${damage.name.lowercase()}")
    }
}

private fun profileEvents(before: ProfileBook, after: ProfileBook): List<String> = buildList {
    val was = before.profiles.associateBy { it.id }
    val now = after.profiles.associateBy { it.id }
    after.profiles.filter { it.id !in was }.forEach { add("Created profile ${it.name}") }
    before.profiles.filter { it.id !in now }.forEach { add("Deleted profile ${it.name}") }
    after.profiles.forEach { profile -> was[profile.id]?.takeIf { it.name != profile.name }?.let { add("Renamed profile ${it.name} to ${profile.name}") } }
    if (after.active != before.active) add("Switched to profile ${after.activeProfile.name}")
}

private fun maintenanceEvent(maintenance: Maintenance): String? = when (maintenance) {
    is Maintenance.Restore -> "Restored the original gameinfo.gi".takeIf { maintenance.stage == RestoreStage.Restored }
    is Maintenance.Clear -> (maintenance.stage as? ClearStage.Cleared)?.let {
        "Cleared downloads: ${it.removed} removed, ${sizeText(it.freedBytes)} freed, ${it.kept} kept, ${it.alreadyGone} already gone"
    }
    is Maintenance.Backups -> if (maintenance.stage != BackupStage.Done) null else when (val work = maintenance.work) {
        BackupWork.BackUp -> "Backed up the game's mods and settings"
        is BackupWork.Revert -> "Restored the backup ${work.backup.said}"
        is BackupWork.Delete -> "Deleted the backup ${work.backup.said}"
    }
}

/** A mod as the log and a report name it: its title and the mod it is of, such as `Lash Gold (mod-721908)`. */
internal val ManifestRecord.named: String get() = "$title ($modId)"

/** Where the mod's sets came from, winner first, each source once however many parts it gave. */
private val ManifestRecord.sources: String get() = sets.map { it.said }.distinct().joinToString("; ")

internal val InstalledSet.said: String
    get() = when (val source = source) {
        is InstalledSource.Remote -> "GameBanana file ${source.identity.fileId}" + version?.let { " (version ${it.index} of ${it.count})" }.orEmpty()
        is InstalledSource.Local -> "local file ${source.fileName}" + source.submission?.let { " of ${it.modId}" }.orEmpty()
    } + when (val origin = origin) {
        is SetOrigin.Adopted -> ", taken over by import ${origin.migration.value}"
        is SetOrigin.Found -> ", found in ${origin.path.folder}"
        is SetOrigin.Downloaded, is SetOrigin.Imported -> ""
    }

private val CitadelPath.folder: String get() = value.substringBeforeLast('/')

internal val FoundVia.said: String
    get() = when (this) {
        FoundVia.Steam -> "found through Steam"
        FoundVia.Chosen -> "the folder chosen in Settings"
    }

internal val FolderCheck.Invalid.said: String
    get() = "the chosen folder $path " + when (problem) {
        FolderProblem.NotDeadlock -> "doesn't hold Deadlock"
        FolderProblem.Unavailable -> "isn't there or can't be read"
    }

internal val GameActivity.said: String
    get() = when (this) {
        GameActivity.Unread -> "not looked for yet"
        GameActivity.Unknown -> "unknown, the running programs couldn't be read"
        GameActivity.Closed -> "closed"
        GameActivity.Running -> "running"
    }

internal val ModLoading.said: String
    get() = when (this) {
        ModLoading.Unknown -> "unknown, gameinfo.gi not read yet"
        is ModLoading.Loads -> "on, through ${if (by == LoadingOwner.Hideout) "Hideout's" else "another tool's"} lines in gameinfo.gi"
        is ModLoading.Off -> "off" + (if (vanilla) ", as asked for to play without mods" else "") + (if (notLoading > 0) ", so $notLoading of Hideout's mods that are on don't load" else "")
        is ModLoading.Unreadable -> "unknown, gameinfo.gi can't be used: ${problem.name}"
    }

internal val GameInfoRewrite.said: String
    get() {
        val who = when (by) {
            RewrittenBy.GameUpdate -> "a Deadlock update"
            is RewrittenBy.AnotherProgram -> by.manager?.name ?: "something outside Hideout"
        }
        return when (outcome) {
            RewriteOutcome.WaitingForGame -> "$who took Hideout's lines out of gameinfo.gi; they go back when Deadlock closes"
            RewriteOutcome.PutBack -> "Put Hideout's lines back in gameinfo.gi after $who took them out"
        }
    }

private val BackupInfo.said: String get() = "${id.value} of ${Instant.fromEpochMilliseconds(createdAt)} (${reason.name}, ${counted(mods, "mod")})"

internal fun counted(count: Int, thing: String): String = if (count == 1) "1 $thing" else "$count ${thing}s"

/** [bytes] in the largest unit that keeps it at 1 or more, to one decimal place. */
internal fun sizeText(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes / 1024.0
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    return String.format(Locale.ROOT, "%.1f %s", value, units[unit])
}
