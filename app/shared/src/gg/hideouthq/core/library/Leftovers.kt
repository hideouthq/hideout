package gg.hideouthq.core.library

/**
 * A set Hideout does not own that the game mounts from one of its loading folders at [slot]: a bare `pakNN_dir.vpk`
 * and every `pakNN_NNN.vpk` chunk of its number beside it, directory first. Another manager, a copy made by hand or an
 * uninstall that stopped halfway left it there.
 */
data class Leftover(val slot: Slot, val files: List<LeftoverFile>) {
    init {
        require(files.firstOrNull()?.part == VpkPart.Directory && files.map { it.part } == files.map { it.part }.distinct().sorted()) { "a leftover is its directory, then each chunk once in order" }
    }

    val folder: SetFolder.On get() = slot.folder

    /** Its directory's name, as the folder lists it. */
    val name: String get() = files.first().name

    val sizeBytes: Long get() = files.sumOf { it.sizeBytes }

    /** Where each file is below `game/citadel`, directory first. */
    val paths: List<CitadelPath> get() = files.map { CitadelPath("${folder.inCitadel}/${it.name}") }
}

/** One file of a [Leftover]: which part of its set it is, its name and the size its folder listed. */
data class LeftoverFile(val part: VpkPart, val name: String, val sizeBytes: Long)

/** How taking [Leftover]s into the library or out of the game ended. */
sealed interface LeftoverOutcome {
    data object Added : LeftoverOutcome

    /** Out of the game in [folder], below `game/citadel`, each file at its own path there. */
    data class Moved(val folder: CitadelPath) : LeftoverOutcome

    data class Refused(val problem: LibraryProblem) : LeftoverOutcome
}

/**
 * The [Leftover]s among [loading], in load order, besides [owned], the paths below the game root of every file Hideout's
 * records name. Only plain files count, as Hideout never acts through a link. A chunk with no directory of its number
 * beside it loads nothing, and the game mounts no other name, such as `631628_pak07_dir.vpk`, so neither is one.
 */
internal fun leftovers(loading: List<OnEntry>, owned: Set<String>): List<Leftover> {
    val foreign = loading.filter { it.entry.kind == EntryKind.File && it.relativePath !in owned }
    return foreign.mapNotNull { entry -> entry.mounted?.let { it to entry } }.distinctBy { it.first }.sortedBy { it.first }.map { (slot, directory) ->
        val chunks = foreign.filter { it.folder == directory.folder }.mapNotNull { entry ->
            ChunkName.matchEntire(entry.entry.name)?.takeIf { it.groupValues[1].toInt() == slot.pak.value }?.let { VpkPart.Chunk(it.groupValues[2].toInt()) to entry }
        }.distinctBy { it.first }.sortedBy { it.first }
        Leftover(slot, (listOf(VpkPart.Directory to directory) + chunks).map { (part, on) -> LeftoverFile(part, on.entry.name, on.entry.stamp?.sizeBytes ?: 0) })
    }
}

private val ChunkName = Regex("""pak([0-9]{2})_([0-9]{3})\.vpk""", RegexOption.IGNORE_CASE)
