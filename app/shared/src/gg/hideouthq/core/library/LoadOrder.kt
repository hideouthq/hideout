package gg.hideouthq.core.library

import gg.hideouthq.core.data.FolderEntry

/** A move of committed [source] to [target]: another placement, its sets in another order, or both. Every file keeps its bytes. */
data class Move(val source: ManifestRecord, val target: ManifestRecord) {
    init {
        require(target == source.placed(target.placement, target.sets) && target.sets.size == source.sets.size && target.sets.toSet() == source.sets.toSet()) {
            "a move keeps the mod, its sets and their bytes"
        }
        require(target != source) { "a move changes something" }
    }

    /** Each set whose files change path, its files paired part by part from source to target; none when only an off mod's sets change order. */
    val moving: List<List<Pair<OwnedFile, OwnedFile>>>
        get() = source.sets.map { set -> source.filesOf(set).zip(target.filesOf(set)) }.filter { pairs -> pairs.any { (from, to) -> from.relativePath(source) != to.relativePath(target) } }
}

/**
 * One mod to number, winner first: the slot each of its sets' files hold now, null when they hold none (the set is off
 * or new, or holds other bytes), how many files each set has, how many slots the mod takes, more than its sets when a
 * replacement grows it, whether it is [stuck]: its files are not known to hold their bytes, so no journal can move them,
 * and whether it is [fixed] where it is, as a mod the user edited is until they decide.
 */
internal class Block(val held: List<Slot?>, val files: List<Int>, val width: Int = held.size, val stuck: Boolean = false, val fixed: Boolean = false) {
    init {
        require(held.isNotEmpty() && held.size == files.size && width >= held.size) { "a block numbers its sets once each and takes at least as many numbers" }
        require(!fixed || held.all { it != null }) { "a fixed block is on where it is" }
    }
}

/**
 * Each of [blocks]' first numbers, in order, in the first [folders] loading folders: every block's numbers consecutive
 * in one folder, each block ending before the next one starts, none of them in [taken], and a [Block.fixed] one at the
 * numbers it holds. Of the plans that fit, the one that renames the fewest files, then the one with the highest numbers;
 * null when none fits. A set costs nothing where it lands on the number it holds, else its files, or more than every
 * other file together when its block is stuck, so a stuck block moves only when no plan leaves it. The highest numbers
 * keep the free ones ahead of the first block, where a new install goes, so it takes one of them and renames nothing.
 */
internal fun planNumbers(blocks: List<Block>, taken: Set<Slot>, folders: Int): List<Slot>? {
    if (blocks.isEmpty()) return emptyList()
    val top = folders * PakNumber.PerFolder
    val free = IntArray(top + 2)
    for (first in top downTo 1) free[first] = when {
        Slot(first) in taken -> 0
        Slot(first).pak.value == PakNumber.PerFolder -> 1
        else -> free[first + 1] + 1
    }

    fun own(block: Block, first: Int): Long? {
        if (free[first] < block.width || block.fixed && block.held.first()?.value != first) return null
        val renamed = block.held.indices.sumOf { set -> if (block.held[set]?.value == first + set) 0 else if (block.stuck) StuckRename else block.files[set] }
        return (renamed.toLong() shl 32) + top - first
    }

    val best = Array(blocks.size) { LongArray(top + 1) { Unplaced } }
    val previous = Array(blocks.size) { IntArray(top + 1) }
    for (first in 1..top) own(blocks[0], first)?.let { best[0][first] = it }
    for (index in 1 until blocks.size) {
        var cheapest = Unplaced
        var cheapestAt = 0
        for (first in 1..top) {
            val before = first - blocks[index - 1].width
            if (before >= 1 && best[index - 1][before] <= cheapest) {
                cheapest = best[index - 1][before]
                cheapestAt = before
            }
            if (cheapest == Unplaced) continue
            val cost = own(blocks[index], first) ?: continue
            best[index][first] = cheapest + cost
            previous[index][first] = cheapestAt
        }
    }
    var first = (top downTo 1).filter { best.last()[it] != Unplaced }.minByOrNull { best.last()[it] } ?: return null
    val firsts = IntArray(blocks.size)
    for (index in blocks.indices.reversed()) {
        firsts[index] = first
        first = previous[index][first]
    }
    return firsts.map(::Slot)
}

private const val Unplaced = Long.MAX_VALUE

private const val StuckRename = 1 shl 20

/** What [planPlacement] found: the records a game is to have, and the mods the profile turns on that stay off because they do not fit, by install id. */
internal data class Planned(val records: List<ManifestRecord>, val pastLimit: Set<String> = emptySet())

/**
 * One game's [records] as [profile] wants them: as many of the mods it turns on as fit, from the top of its order, since
 * the top wins, placed on in that order at the numbers [placeAround] gives against the files as [now] holds them and
 * around [taken]; every other one off. The ones turned on that do not fit, more than every loading folder holds or not
 * around [taken], or with more sets than one folder holds, are [Planned.pastLimit]. A set on now with its bytes keeps
 * its number unless that costs more renames, and a mod in [stuck], by install id, moves only when no plan can leave it
 * where it is. A mod the user edited stays exactly as it is, on or off: the rest are numbered in their order around it
 * where it is, else, when that cannot be, around it wherever it falls among them. Pure.
 */
internal fun planPlacement(records: List<ManifestRecord>, profile: Profile, taken: Set<Slot>, now: List<ManifestRecord> = records, stuck: Set<String> = emptySet()): Planned {
    val root = records.firstOrNull()?.gameRoot ?: return Planned(emptyList())
    val byId = records.associateBy { it.installId }
    val edited = records.filter { it.edited }
    val wanted = profile.onOrder(root).mapNotNull(byId::get)
    val held = now.filter { it.gameRoot == root }.flatMap { record ->
        record.sets.mapNotNull { set -> record.numberOf(set)?.let { number -> (record.installId to set.key) to (number to set.files) } }
    }.toMap()
    val (inOrder, tooWide) = wanted.filter { !it.edited || it.enabled }.partition { it.sets.size <= PakNumber.PerFolder }
    val around = edited - inOrder.toSet()
    val room = Slot.All.last - edited.sumOf { it.numbers.size }
    var width = 0
    val most = inOrder.indexOfFirst { record -> !record.edited && (width + record.sets.size).also { width = it } > room }.takeIf { it >= 0 } ?: inOrder.size
    val (kept, placed) = (most downTo 0).firstNotNullOf { fits ->
        val kept = inOrder.take(fits) + inOrder.drop(fits).filter { it.edited }
        placeAround(kept, around, taken) { record ->
            Block(record.sets.map { set -> held[record.installId to set.key]?.takeIf { it.second == set.files }?.first }, record.sets.map { it.files.size }, stuck = record.installId in stuck, fixed = record.edited)
        }?.let { kept to it.associateBy { record -> record.installId } }
    }
    return Planned(
        records.map { placed[it.installId] ?: if (it.edited) it else it.placed(Placement.Off) },
        (inOrder - kept.toSet() + tooWide).mapTo(LinkedHashSet()) { it.installId },
    )
}

/**
 * [on] placed on in their order at the numbers [planNumbers] gives each one's [block], around [taken] and the numbers
 * [around] holds: its [Block.fixed] ones where they are, else, when that cannot be, the rest around them; null when
 * neither fits in any number of loading folders. Blocks that all fit in `addons` in their order go back there, unless
 * a stuck one would have to leave a later folder. Past that it opens a folder beyond the ones the blocks already use
 * only when nothing fits there, so a library past `addons` that shrinks keeps its folders rather than renaming its sets.
 */
internal fun placeAround(on: List<ManifestRecord>, around: List<ManifestRecord>, taken: Set<Slot>, block: (ManifestRecord) -> Block): List<ManifestRecord>? {
    val blocks = on.associateWith(block)
    fun place(on: List<ManifestRecord>, around: List<ManifestRecord>, folders: Int) = planNumbers(on.map(blocks::getValue), taken + around.flatMap { it.numbers }, folders)
        ?.let { firsts -> on.zip(firsts).map { (record, first) -> record.placed(Placement.On(first)) } }
    val fixed = on.filter { blocks.getValue(it).fixed }
    val floor = blocks.values.flatMap { it.held }.filterNotNull().maxOfOrNull { it.folder.index } ?: 1
    val stuckPast = blocks.values.any { block -> block.stuck && block.held.any { it != null && it.folder != SetFolder.Addons } }
    if (floor > 1 && !stuckPast) place(on, around, 1)?.let { return it }
    return (floor..SetFolder.Loading.size).firstNotNullOfOrNull { folders -> place(on, around, folders) ?: place(on - fixed.toSet(), around + fixed, folders) }
}

/** Whether the sets of [records] that are on in [root] reach past `addons` though they are few enough to fit there again. */
internal fun List<ManifestRecord>.gathersIn(root: GameRoot): Boolean =
    overflowIn(root) > 0 && filter { it.gameRoot == root && it.enabled }.sumOf { it.sets.size } <= PakNumber.PerFolder

/** A move for each of [wanted] that its record in [now], by install id, is not. */
internal fun movesBetween(now: List<ManifestRecord>, wanted: List<ManifestRecord>): List<Move> {
    val byId = now.associateBy { it.installId }
    return wanted.mapNotNull { target -> byId.getValue(target.installId).takeIf { it != target }?.let { Move(it, target) } }
}

/** The install ids of [records] that are on, in the order their numbers load them, winner first. */
internal fun onLoadOrder(records: List<ManifestRecord>): List<String> = records.filter { it.enabled }.sortedBy { it.numbers.first() }.map { it.installId }

/** The order the files give [records]: the ones that are on as their numbers load them, then the ones that are off as listed. */
internal fun fileOrder(records: List<ManifestRecord>): List<String> = onLoadOrder(records) + records.filter { !it.enabled }.map { it.installId }

/** The mods this profile turns on in [root], in its order, winner first. */
fun Profile.onOrder(root: GameRoot): List<String> = orders[root].orEmpty().filter { it in enabled }

/**
 * The slots the entries of the loading folders take besides the files of [records] that are on, by path, and the chunks
 * beside a set of theirs the user edited, in its folder, which are that edit's, as Keep my changes and Restore original
 * take it.
 */
internal fun foreignNumbers(loading: List<OnEntry>, records: List<ManifestRecord>): Set<Slot> {
    val on = records.filter { it.enabled }
    val own = on.flatMapTo(HashSet()) { record -> record.files.map { it.relativePath(record) } }
    val edited = on.flatMap { record -> record.sets.filter { it.edits.isNotEmpty() }.mapNotNull(record::numberOf) }
    return loading.filter { entry -> entry.relativePath !in own && edited.none { it.folder == entry.folder && VpkPart.named(entry.entry.name, it.pak.stem) != null } }
        .mapNotNullTo(HashSet()) { it.number }
}

/** One entry of a folder Hideout loads sets from, known by its path: a name in one folder never stands for the same name in another. */
data class OnEntry(val folder: SetFolder.On, val entry: FolderEntry) {
    /** Where it is under the game root, as [OwnedFile.relativePath] gives a file of Hideout's. */
    val relativePath: String get() = "${folder.path}/${entry.name}"

    /** How a problem names it: by name in `addons`, by its folder and name past it. */
    val label: String get() = folder.label(entry.name)

    /** The slot its name takes, as [PakNumbers.taken] reads it; null for a name that takes none. */
    val number: Slot? get() = PakNumbers.taken(entry.name)?.let { Slot.of(folder, it) }

    /** The slot the game mounts it at as a bare `pakNN_dir.vpk`; null for any other name. */
    val mounted: Slot? get() = PakNumbers.mounted(entry.name)?.let { Slot.of(folder, it) }
}

internal sealed interface OnListing {
    data class Listed(val entries: List<OnEntry>) : OnListing

    data class Unreadable(val reason: String) : OnListing
}
