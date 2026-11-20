package gg.hideouthq.core.library

/**
 * A set Hideout does not own that the game mounts from one of its loading folders at [slot]: a bare `pakNN_dir.vpk`
 * and every `pakNN_NNN.vpk` chunk of its number beside it, directory first. Another manager, a copy made by hand or an
 * uninstall that stopped halfway left it there. [copyOf] is the install id of a library mod with a set of these very
 * bytes, which adding it would hold twice; null when none has.
 */
data class Leftover(val slot: Slot, val files: List<LeftoverFile>, val copyOf: String? = null) {
    init {
        require(files.firstOrNull()?.part == VpkPart.Directory && files.map { it.part } == files.map { it.part }.distinct().sorted()) { "a leftover is its directory, then each chunk once in order" }
    }

    val folder: SetFolder.On get() = slot.folder

    val name: String get() = files.first().name

    val sizeBytes: Long get() = files.sumOf { it.sizeBytes }

    /** Where each file is below `game/citadel`, directory first. */
    val paths: List<CitadelPath> get() = files.map { CitadelPath("${folder.inCitadel}/${it.name}") }
}

data class LeftoverFile(val part: VpkPart, val name: String, val sizeBytes: Long)

sealed interface LeftoverOutcome {
    data object Added : LeftoverOutcome

    /** Out of the game in [folder], below `game/citadel`, each file at its own path there. */
    data class Moved(val folder: CitadelPath) : LeftoverOutcome

    data class Refused(val problem: LibraryProblem) : LeftoverOutcome
}

/** A chunk with no directory of its number beside it loads nothing, and the game mounts no name like `631628_pak07_dir.vpk`. */
internal fun leftovers(loading: List<OnEntry>, owned: Set<String>): List<Leftover> {
    val foreign = loading.filter { it.entry.kind == EntryKind.File && it.relativePath !in owned }
    return foreign.mapNotNull { entry -> entry.mounted?.let { it to entry } }.distinctBy { it.first }.sortedBy { it.first }.map { (slot, directory) ->
        val chunks = foreign.filter { it.folder == directory.folder }.mapNotNull { entry ->
            ChunkName.matchEntire(entry.entry.name)?.takeIf { it.groupValues[1].toInt() == slot.pak.value }?.let { VpkPart.Chunk(it.groupValues[2].toInt()) to entry }
        }.distinctBy { it.first }.sortedBy { it.first }
        Leftover(slot, (listOf(VpkPart.Directory to directory) + chunks).map { (part, on) -> LeftoverFile(part, on.entry.name, on.entry.stamp?.sizeBytes ?: 0) })
    }
}

internal suspend fun copyOf(set: Leftover, records: List<ManifestRecord>, image: suspend (CitadelPath) -> FileImage?): ManifestRecord? {
    val sizes = set.files.mapTo(HashSet()) { it.part to it.sizeBytes }
    val alike = records.filter { record -> record.sets.any { held -> held.files.mapTo(HashSet()) { it.part to it.image.sizeBytes } == sizes } }
    if (alike.isEmpty()) return null
    return holding(set.files.zip(set.paths) { file, path -> file.part to (image(path) ?: return null) }.toSet(), alike)
}

internal fun holding(files: Set<Pair<VpkPart, FileImage>>, records: List<ManifestRecord>): ManifestRecord? =
    records.firstOrNull { record -> record.sets.any { held -> held.files.mapTo(HashSet()) { it.part to it.image } == files } }

private val ChunkName = Regex("""pak([0-9]{2})_([0-9]{3})\.vpk""", RegexOption.IGNORE_CASE)
