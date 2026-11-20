package gg.hideouthq.core.library

import gg.hideouthq.core.autoexec.SectionMarkers
import gg.hideouthq.core.data.LocalFile

/** Where the engine loads a set today: its folder's place among the mounts in SearchPaths, then its pak number. Lower loads first and wins. */
data class LoadPosition(val mount: Int, val number: Int) : Comparable<LoadPosition> {
    override fun compareTo(other: LoadPosition): Int = compareValuesBy(this, other, { it.mount }, { it.number })
}

/**
 * Everything one import hands the library to change in [root], settled and blind to which manager it came from:
 * [ModLibrary.takeOver] carries it out and nothing else writes the game while it does.
 */
data class Takeover(
    val migration: MigrationId,
    val root: GameRoot,
    /** The manager whose closing the import waits for besides Deadlock's; null for a bundle, which waits only for the game. */
    val manager: Manager?,
    /** Where `addons` is among the mounts today, which ranks Hideout's own mods against [incoming]; null when it does not load. */
    val addonsMount: Int?,
    /** Mods already in the game: those that load today in engine order, then those that do not in the source's order. */
    val incoming: List<Incoming>,
    /** A bundle's mods with no copy in the game, copied in the way a file picked from the computer is. */
    val copies: List<BundleCopy>,
    /** The source's profiles besides the live one, each to become a new profile. */
    val profiles: List<IncomingProfile>,
    /** The name the live profile went by, which an empty Main takes along with its mods; null to keep Main's. */
    val liveProfileName: String? = null,
    /** The other manager's folders, whose mounts leave `gameinfo.gi`, after which Hideout's block follows the loading intent; none leaves it as it is. */
    val unmount: Set<ForeignRoot>,
    val autoexec: AutoexecTakeover?,
    /** Other names of bytes a record holds, which go once the mods are committed, each only while it still holds them and no record has that name. */
    val duplicates: List<KeptFile>,
    /** The other manager's own files in the game, which go last, each only while it still holds the bytes its copy has. */
    val manifests: List<KeptFile>,
)

/** One mod already in the game, as it comes: its sets winner first, its title and identity settled. */
data class Incoming(
    val title: String,
    /** Who its source says it is: every set's exact file brings it in as GameBanana's, anything less as a local mod. */
    val matched: Matched,
    /** The name its winning file had, which a local mod keeps. */
    val fileName: String,
    /** Where it loads today; null when it does not, and it comes in off. */
    val position: LoadPosition?,
    val sets: List<IncomingSet>,
) {
    val enabled: Boolean get() = position != null
}

/** One complete set, its directory file first, under the key that tells it apart from the mod's other sets. */
data class IncomingSet(val componentKey: String, val files: List<IncomingFile>)

data class IncomingFile(val path: CitadelPath, val part: VpkPart, val image: FileImage)

/** A bundle's mod to copy in from [files], VPKs outside the game. */
data class BundleCopy(val title: String, val matched: Matched, val fileName: String, val enabled: Boolean, val files: List<LocalFile>)

/** A source profile to become a new, inactive Hideout profile named [name], with [members] in its order, winner first. */
data class IncomingProfile(val name: String, val members: List<IncomingMember>)

sealed interface IncomingMember {
    /** The mod at [index] of [Takeover.incoming]. */
    data class Adopted(val index: Int) : IncomingMember

    /** The mod at [index] of [Takeover.copies]. */
    data class Copied(val index: Int) : IncomingMember

    data class Held(val installId: String) : IncomingMember
}

/** What becomes of `autoexec.cfg`: the source's console commands become Hideout's, the user's own lines. */
sealed interface AutoexecTakeover {
    /** The other manager's commands [sections] become plain lines, with whatever they hold when the file is written. */
    data class Sections(val sections: List<SectionMarkers>) : AutoexecTakeover

    /** [commands], from a bundle, go in. */
    data class Lines(val commands: List<String>) : AutoexecTakeover
}

/** A file at [path] under `game/citadel` holding [image], whose bytes are kept elsewhere: in the import's record, or a record's own file. */
data class KeptFile(val path: CitadelPath, val image: FileImage)

/**
 * [manager], imported from, wrote its own mod lines into `gameinfo.gi` again, and Hideout put back the ones the import
 * left. Each time is its own object, so the same one is never told twice.
 */
class LinesPutBack(val manager: Manager)

/** Why an import waits: until Deadlock closes, a custom game ends, or the manager it imports from closes. */
sealed interface ImportWait {
    data object GameOpen : ImportWait

    data object CustomGame : ImportWait

    data class ManagerOpen(val manager: Manager) : ImportWait
}

/**
 * Why an import cannot run now: Deadlock open or maybe open, as for every change to the game's files, or [manager], the
 * one it imports from, seen open, as it would rewrite the files being taken over. A manager Hideout cannot tell about
 * holds nothing up, and a bundle waits only for the game.
 */
internal fun gateOf(state: LibraryState, manager: Manager?): ImportWait? = when {
    state.sessionHold != null -> ImportWait.CustomGame
    state.deadlock != GameActivity.Closed -> ImportWait.GameOpen
    manager != null && (state.managers as? ManagerActivity.Seen)?.open?.contains(manager) == true -> ImportWait.ManagerOpen(manager)
    else -> null
}

/** Why an import cannot be previewed or run, or stopped. One type for the preview, the gate and the run. */
sealed interface ImportProblem {
    data object GameMissing : ImportProblem

    /** The library cannot take a change: it is being read, cannot be trusted, or an interrupted change waits. */
    data class LibraryBusy(val reason: String?) : ImportProblem

    /** Nothing of the source was found where it keeps its data, or its files could not be read. */
    data class SourceUnreadable(val reason: String) : ImportProblem

    /** The picked folder holds no deadlock-mod-interchange v1 bundle Hideout reads. */
    data class NotABundle(val reason: String) : ImportProblem

    /** A folder of the game could not be read, so what it holds is not known. */
    data class GameUnreadable(val reason: String) : ImportProblem

    /** The import's record could not be written; nothing changed. */
    data class BackupFailed(val reason: String) : ImportProblem

    /** A change to the library or the game failed; whatever committed before it stays committed. */
    data class Library(val problem: LibraryProblem) : ImportProblem
}

data class ImportSummary(
    /** Mods that came in on and off, adopted or copied. */
    val on: Int,
    val off: Int,
    /** Profiles made from the source's. */
    val profiles: List<String>,
    /** The source's crosshair joined Yours. */
    val crosshair: Boolean,
    /** Command lines the import brought into `autoexec.cfg`. */
    val commands: Int,
)

sealed interface TakeoverOutcome {
    /** What the takeover brought, as [ImportSummary] counts it, on or off as the active profile has it; the crosshair joins Yours apart from it. */
    data class Done(val on: Int, val off: Int, val profiles: List<String>, val commands: Int) : TakeoverOutcome

    data class Waiting(val wait: ImportWait) : TakeoverOutcome

    data class Refused(val problem: ImportProblem) : TakeoverOutcome
}

/**
 * What [placeTakeover] decided: [plan] commits the mods that come, and [profiles] names each incoming profile as
 * Hideout has it, made now or by an earlier run of the same import.
 */
data class TakeoverPlacement(val plan: AdoptionPlan, val profiles: List<String>)

/**
 * Places [takeover]'s incoming mods against [manifest] and [addons], the names in `addons` now; [copied] are the install
 * ids [Takeover.copies] got, in order, which a preview has not installed yet and orders last. Pure. An import never
 * picks a number: a mod that loads today from its own bare consecutive `pakNN` names in `addons`, which no other entry
 * and no mod Hideout has on uses, is claimed there, on, and every other incoming mod comes in off. The saved book turns
 * on every mod that loads today, and the choices worker places those that came in off. Its active order keeps
 * Hideout's mods in the order already saved for them, puts each mod that loads today before the first of Hideout's mods
 * that are on that loads after it, then the mods that do not load today in the source's order. Every other profile keeps
 * its own mods' order, with the adopted ones where the active order has them, and an incoming profile has its members
 * in its source's order there. An incoming profile already there under its name with exactly its members, as after an
 * earlier run of the same import, is not made again; one with no mods on is made all the same. When the files already give the saved book, the saved choices
 * settle in the commit. A mod that comes as a local one is [ManifestRecord.unasked], for GameBanana to be asked later.
 */
fun placeTakeover(takeover: Takeover, manifest: LibraryManifest, addons: List<String>, copied: List<String>, ids: () -> String): TakeoverPlacement {
    val root = takeover.root
    val held = manifest.records.filter { it.gameRoot == root }
    val mounted = held.flatMap { it.numbers }.toSet()
    val incoming = takeover.incoming
    val made = mutableListOf<ManifestRecord>()
    val adopted = incoming.map { mod ->
        val (sources, title) = sourcesAndTitle(mod.matched, mod.sets.size, mod.title, mod.fileName, held + made, ids)
        val first = if (mod.enabled) claimed(mod, addons, mounted) else null
        val sets = mod.sets.mapIndexed { offset, set -> InstalledSet(sources[offset], null, null, SetOrigin.Adopted(takeover.migration), null, set.componentKey, set.files.map { SetFile(it.part, it.image) }) }
        val record = ManifestRecord(ids(), root, title, first?.let(Placement::On) ?: Placement.Off, sets, unasked = sources.first() is InstalledSource.Local)
        Adoption(record.also { made += it }, mod.sets.flatMap { set -> set.files.map { it.path } })
    }
    val adoptedIds = adopted.map { it.record.installId }
    val loads = incoming.indices.mapNotNull { index -> incoming[index].position?.let { adoptedIds[index] to it } }
    val loading = loads.sortedBy { it.second }.map { it.first }
    val position = held.filter { it.enabled }.associate { it.installId to LoadPosition(takeover.addonsMount ?: Int.MAX_VALUE, it.numbers.first().value) } + loads
    val resting = incoming.indices.filter { !incoming[it].enabled }.map { adoptedIds[it] }

    fun merged(order: List<String>, entering: List<String>, after: List<String>) = order.toMutableList().apply {
        for (id in entering) {
            val next = indexOfFirst { other -> position[other]?.let { it > position.getValue(id) } == true }
            add(if (next >= 0) next else indexOfLast { it in position } + 1, id)
        }
        addAll(after)
    }

    val claimedIds = adopted.filter { it.record.enabled }.map { it.record.installId }
    val copies = copied.filter { id -> held.none { it.installId == id } }
    val saved = manifest.chosen()
    val active = merged(saved.activeProfile.orders[root].orEmpty(), loading, resting + copies)

    fun joined(book: ProfileBook, activeOrder: List<String>, on: List<String>) = book.copy(profiles = book.profiles.map { profile ->
        val order = if (profile.id == book.active) activeOrder else refill(activeOrder, profile.orders[root].orEmpty())
        profile.copy(enabled = if (profile.id == book.active) profile.enabled + on else profile.enabled, orders = if (order.isEmpty()) profile.orders else profile.orders + (root to order))
    })
    var chosen = joined(saved, active, loading)
    val committed = held + adopted.map { it.record }
    val byNumber = claimedIds.sortedBy { position.getValue(it) }
    var applied = if (chosen.follows(committed)) chosen else joined(manifest.profiles, merged(manifest.profiles.activeProfile.orders[root].orEmpty(), byNumber, adoptedIds - claimedIds.toSet() + copies), claimedIds)
    val installed = (manifest.records.map { it.installId } + adoptedIds).toSet()
    val names = mutableListOf<String>()
    takeover.liveProfileName?.let(::cleaned)?.takeIf { it.isNotEmpty() }?.let { name ->
        val before = manifest.profiles.activeProfile
        if (before.name == ProfileBook.MainName && before.enabled.isEmpty() && manifest.profiles.profiles.none { it.name.equals(name, ignoreCase = true) }) {
            fun named(book: ProfileBook) = book.copy(profiles = book.profiles.map { if (it.id == before.id) it.copy(name = name) else it })
            applied = named(applied)
            chosen = named(chosen)
        }
    }
    takeover.profiles.forEach { profile ->
        val members = profile.members.mapNotNull { member ->
            when (member) {
                is IncomingMember.Adopted -> adoptedIds[member.index]
                is IncomingMember.Copied -> copied[member.index]
                is IncomingMember.Held -> member.installId.takeIf { it in installed }
            }
        }.distinct()
        val there = (applied.profiles + chosen.profiles).firstOrNull { it.name.equals(cleaned(profile.name), ignoreCase = true) && it.enabled == members.toSet() }
        if (there != null) {
            names += there.name
            return@forEach
        }
        val name = freeProfileName(cleaned(profile.name), applied.profiles + chosen.profiles)
        val made = Profile(ProfileId(ids()), name, ProfileColour.entries[applied.profiles.size % ProfileColour.entries.size], members.toSet(), chosen.activeProfile.orders + (root to refill(active, members)))
        applied = applied.copy(profiles = applied.profiles + made)
        chosen = chosen.copy(profiles = chosen.profiles + made)
        names += name
    }
    return TakeoverPlacement(AdoptionPlan(root, takeover.migration, adopted, applied, chosen), names)
}

/**
 * How an incoming mod of [sets] sets is held beside [held]: each set Remote with its own exact file unless one of them
 * is already that mod, else every set one Local with whatever submission is known; and [title] with [fileName] after it
 * when one of them already has that title.
 */
internal fun sourcesAndTitle(matched: Matched, sets: Int, title: String, fileName: String, held: List<ManifestRecord>, ids: () -> String): Pair<List<InstalledSource>, String> {
    val pinned = (matched as? Matched.Pinned)?.takeIf { pinned -> held.none { it.modId == pinned.submission.modId } }
    val sources = pinned?.files?.map(InstalledSource::Remote) ?: InstalledSource.Local(ids(), fileName, matched.submission).let { local -> List(sets) { local } }
    return sources to if (held.any { it.title == title }) withFileName(title, fileName) else title
}

/** [title] with the [fileName] a mod came from after it, as an import tells it from a held mod of that title. */
internal fun withFileName(title: String, fileName: String) = "$title ($fileName)"

internal fun withoutFileName(title: String, fileName: String) = title.removeSuffix(withFileName("", fileName))

private fun claimed(mod: Incoming, addons: List<String>, mounted: Set<Slot>): Slot? {
    val first = mod.sets.first().files.first().path.takeIf { it.parent == CitadelPath.Addons }?.name?.let(PakNumbers::mounted) ?: return null
    if (first.value + mod.sets.size - 1 > PakNumber.PerFolder) return null
    val numbers = mod.sets.indices.map { Slot.of(SetFolder.Addons, first) + it }
    val own = mod.sets.flatMapIndexed { offset, set -> set.files.map { file -> file.part.fileName(numbers[offset].pak.stem).takeIf { file.path == CitadelPath.Addons.resolve(it) } ?: return null } }.toSet()
    if (numbers.any { it in mounted }) return null
    return numbers.first().takeIf { addons.none { name -> name !in own && PakNumbers.taken(name)?.let { Slot.of(SetFolder.Addons, it) } in numbers } }
}

private fun refill(list: List<String>, order: List<String>): List<String> {
    val ranked = order.filter { it in list }
    val slots = list.indices.filter { list[it] in ranked }
    return list.toMutableList().apply { slots.zip(ranked).forEach { (slot, id) -> this[slot] = id } }
}

private fun cleaned(name: String): String = name.filterNot { it.isISOControl() }.trim().ifEmpty { "Imported" }
