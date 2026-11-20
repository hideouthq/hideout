package gg.hideouthq.core.library

import gg.hideouthq.core.data.FileStamp

/**
 * The game files two or more of [parts] hold with different bytes, by the CRC-32 their trees record, packer files
 * aside: where the one that loads first hides the others. Models come first, as a clash there shows the most.
 */
fun sharedFiles(parts: List<Map<String, Long>>): List<String> {
    val first = HashMap<String, Long>()
    val shared = HashSet<String>()
    for (part in parts) for ((path, crc) in part) {
        if (isPackerFile(path)) continue
        val seen = first.putIfAbsent(path, crc)
        if (seen != null && seen != crc) shared += path
    }
    return shared.sortedWith(compareBy<String> { !it.endsWith(ModelExtension) }.thenBy { it })
}

/** Whether [a] and [b] hold any one file with different bytes, as [sharedFiles] counts them, without listing them. */
fun overlap(a: Map<String, Long>, b: Map<String, Long>): Boolean {
    val (small, large) = if (a.size <= b.size) a to b else b to a
    return small.any { (path, crc) -> large[path].let { it != null && it != crc } && !isPackerFile(path) }
}

/**
 * Whether [a] and [b] are near-alternatives, of which keeping both makes little sense: they change some file with
 * different bytes, and either the same models or mostly the same files, by the larger one's count. An add-on that
 * changes a few of a base's files is not one.
 */
fun alternatives(a: Map<String, Long>, b: Map<String, Long>): Boolean {
    if (!overlap(a, b)) return false
    fun models(files: Map<String, Long>) = files.keys.filterTo(HashSet()) { it.endsWith(ModelExtension) && !isPackerFile(it) }
    if (models(a).let { it.isNotEmpty() && it == models(b) }) return true
    val larger = maxOf(a.keys.count { !isPackerFile(it) }, b.keys.count { !isPackerFile(it) })
    return 2 * sharedFiles(listOf(a, b)).size > larger
}

/** Whether [paths], as [sharedFiles] gives them, include a model. */
fun sharesModel(paths: List<String>): Boolean = paths.firstOrNull()?.endsWith(ModelExtension) == true

data class Mount(val number: Slot, val by: Mounted)

sealed interface Mounted {
    data class Managed(val installId: String, val set: SetKey) : Mounted

    data class Foreign(val name: String, val stamp: FileStamp?) : Mounted
}

/**
 * What the loading folders mount with [placed] at their slots, lowest first: every set of a mod that is on, and every
 * bare `pakNN_dir.vpk` in `addons`, of [loading], that is not a file of the mods [now] has on, which may sit elsewhere
 * than [placed]. A foreign file past `addons` loads all the same, unseen here: its resources are read from `addons` only.
 */
internal fun mounts(placed: List<ManifestRecord>, loading: List<OnEntry>, now: List<ManifestRecord>): List<Mount> {
    val own = now.filter { it.enabled }.flatMapTo(HashSet()) { record -> record.files.map { it.relativePath(record) } }
    val managed = placed.flatMap { record -> record.sets.mapNotNull { set -> record.numberOf(set)?.let { Mount(it, Mounted.Managed(record.installId, set.key)) } } }
    val foreign = loading.filter { it.folder == SetFolder.Addons && it.relativePath !in own }.mapNotNull { on -> on.mounted?.let { Mount(it, Mounted.Foreign(on.entry.name, on.entry.stamp)) } }
    return (managed + foreign).sortedBy { it.number }
}

/**
 * What the loading folders mount once [profile] places [records], one game's, around [loading]'s other entries, the
 * [stuck] mods and the ones the user edited, which stay where they are; as they are when they do not fit.
 */
internal fun profileMounts(records: List<ManifestRecord>, profile: Profile, loading: List<OnEntry>, stuck: Set<String>): List<Mount> =
    mounts(planPlacement(records, profile, foreignNumbers(loading, records), stuck = stuck).records, loading, records)

/** A model [loser] holds at [path] that the game shows from [winner] instead, which mounts first with other bytes. */
data class ModelLoss(val path: String, val loser: Mount, val winner: Mount)

/** What shows a model of a mod instead of it: another of Hideout's mods, one of its own parts, or a file Hideout does not own. */
sealed interface Winner {
    data class Mod(val record: ManifestRecord) : Winner

    data class Part(val set: InstalledSet) : Winner

    data class Foreign(val name: String) : Winner
}

/** How many models a mod's sets lose, and what shows them instead, first mounted first. */
data class Hidden(val models: Int, val by: List<Winner>)

/**
 * The models each mount loses to one that mounts before it, and the mods, on or off, two of whose parts change one file
 * with different bytes, so the order of their parts decides what shows.
 */
data class ConflictReport(val losses: List<ModelLoss> = emptyList(), val overlapping: Set<String> = emptySet()) {
    /** How many models [installId]'s sets lose: to other mods, to files Hideout does not own, or to its own earlier parts. */
    fun hiddenModels(installId: String): Int = lost(installId).map { it.path }.distinct().size

    /** What shows those models instead, in the order the game mounts them. */
    fun winnersOf(installId: String): List<Mounted> = lost(installId).map { it.winner }.sortedBy { it.number }.map { it.by }.distinct()

    /**
     * What [record]'s sets lose, with each mod and part of the report paired by key with [records], the library now.
     * Null when they lose nothing, and while a loss names a set no longer there: the report reads trees from disk after
     * the library changes, so it catches up later.
     */
    fun hiddenIn(record: ManifestRecord, records: Map<String, ManifestRecord>): Hidden? {
        val lost = lost(record.installId).ifEmpty { return null }
        if (lost.any { loss -> record.sets.none { it.key == (loss.loser.by as Mounted.Managed).set } }) return null
        val by = winnersOf(record.installId).map { winner ->
            when (winner) {
                is Mounted.Foreign -> Winner.Foreign(winner.name)
                is Mounted.Managed -> {
                    val owner = records[winner.installId] ?: return null
                    val set = owner.sets.firstOrNull { it.key == winner.set } ?: return null
                    if (owner.installId == record.installId) Winner.Part(set) else Winner.Mod(owner)
                }
            }
        }
        return Hidden(hiddenModels(record.installId), by)
    }

    private fun lost(installId: String) = losses.filter { (it.loser.by as? Mounted.Managed)?.installId == installId }
}

/** Walks [mounts] lowest number first: a model an earlier mount holds with other bytes is a loss for the later one. Equal bytes never count. */
fun conflictReport(mounts: List<Mount>, files: Map<Mounted, ResourceIndex>): ConflictReport {
    val first = HashMap<String, Pair<Mount, Long>>()
    val losses = mutableListOf<ModelLoss>()
    for (mount in mounts.sortedBy { it.number }) {
        val index = files[mount.by] as? ResourceIndex.Ready ?: continue
        for ((path, crc) in index.files) {
            if (!path.endsWith(ModelExtension) || isPackerFile(path)) continue
            val shown = first.putIfAbsent(path, mount to crc) ?: continue
            if (shown.second != crc) losses += ModelLoss(path, mount, shown.first)
        }
    }
    return ConflictReport(losses)
}

/** The mods among [mounts] that hold any of [incoming]'s models with other bytes, in load order: what a new skin would swap out. */
fun modelRivals(incoming: List<ResourceIndex>, mounts: List<Mount>, files: Map<Mounted, ResourceIndex>): Set<String> {
    val models = HashMap<String, Long>()
    for (index in incoming.filterIsInstance<ResourceIndex.Ready>()) for ((path, crc) in index.files) if (path.endsWith(ModelExtension) && !isPackerFile(path)) models.putIfAbsent(path, crc)
    return mounts.sortedBy { it.number }.mapNotNullTo(LinkedHashSet()) { mount ->
        val by = mount.by as? Mounted.Managed ?: return@mapNotNullTo null
        by.installId.takeIf { (files[by] as? ResourceIndex.Ready)?.files?.any { (path, crc) -> models[path].let { it != null && it != crc } } == true }
    }
}

private fun isPackerFile(path: String): Boolean {
    val name = path.substringAfterLast('/')
    return name in PackerNames || name.substringBeforeLast('.') in DocStems && name.substringAfterLast('.', "") in DocExtensions ||
        path == "panorama/image_compiler.vdata_c" || path.startsWith("materials/default/default_")
}

private const val ModelExtension = ".vmdl_c"
private val PackerNames = setOf("addoninfo.txt", "modinfo.json", "grimoire_meta.json")
private val DocStems = setOf("readme", "license", "credits", "changelog", "info")
private val DocExtensions = setOf("txt", "md")
