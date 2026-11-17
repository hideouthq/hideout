package gg.hideouthq.core.library

import kotlin.jvm.JvmInline

@JvmInline
value class StagingId(val value: String)

/** The [index]th file an extraction wrote, under a name the extractor generated, never one the archive chose. */
data class StagedRef(val staging: StagingId, val index: Int)

/** A `.vpk` grouping can judge: where it sits ([path], `/`-separated, its folder decides its set), its bytes and its own tree. */
interface VpkFile {
    val path: String
    val sizeBytes: Long
    val sha256: String
    val directory: VpkRead

    val image: FileImage get() = FileImage(sizeBytes, sha256)
}

/** A `.vpk` the archive held, extracted to staging: its archive path, its bytes, what its own directory says and the [resources] it lists. */
data class StagedVpk(
    val ref: StagedRef,
    override val path: String,
    override val sizeBytes: Long,
    override val sha256: String,
    override val directory: VpkRead,
    val resources: ResourceIndex = ResourceIndex.Unavailable,
) : VpkFile

/** Which file of a set: the part survives every rename, so `X_dir` becomes `pak05_dir` and `X_017` becomes `set-…_017`. */
sealed interface VpkPart : Comparable<VpkPart> {
    /** `dir`, or the chunk's three digits. */
    val label: String

    /** This part's file name in a set whose names start with [stem]. */
    fun fileName(stem: String): String = "${stem}_$label.vpk"

    /** The directory first, then chunks by index: the order a set keeps its files in. */
    override fun compareTo(other: VpkPart): Int = compareValuesBy(this, other) { (it as? Chunk)?.index ?: -1 }

    data object Directory : VpkPart {
        override val label: String = "dir"
    }

    data class Chunk(val index: Int) : VpkPart {
        init {
            require(index in 0..MaxChunkIndex) { "a chunk index has three digits" }
        }

        override val label: String get() = index.toString().padStart(3, '0')
    }

    companion object {
        /** The part [label] names, `dir` or three digits; null for anything else. */
        fun parse(label: String): VpkPart? = when {
            label == Directory.label -> Directory
            ChunkLabel.matches(label) -> Chunk(label.toInt())
            else -> null
        }

        /** The part the file [name] is of a set whose names start with [stem]; null for any other name. */
        fun named(name: String, stem: String): VpkPart? = parse(name.removePrefix("${stem}_").removeSuffix(".vpk"))?.takeIf { it.fileName(stem) == name }
    }
}

private val ChunkLabel = Regex("[0-9]{3}")

data class SetMember<out F : VpkFile>(val part: VpkPart, val vpk: F)

/** One complete, indivisible set: its directory (or standalone file) and every chunk of its prefix, directory first. */
data class VpkSet<out F : VpkFile>(val key: String, val members: List<SetMember<F>>) {
    val sizeBytes: Long get() = members.sumOf { it.vpk.sizeBytes }
}

sealed interface SetProblem {
    data class MissingChunks(val names: List<String>) : SetProblem
    data class ChunkTooShort(val name: String, val neededBytes: Long, val actualBytes: Long) : SetProblem
    data class ChunkIndexTooLarge(val index: Int) : SetProblem
    data class Unreadable(val reason: String) : SetProblem

    /** A file that is not part of a chunked set names chunks it does not have. */
    data object NeedsChunks : SetProblem

    /** A chunk with no directory of its prefix beside it. */
    data object Orphan : SetProblem
}

data class RejectedSet(val key: String, val problem: SetProblem)

/** Every complete set the files hold, in their order, and every candidate that is not one. */
data class SetGrouping<out F : VpkFile>(val sets: List<VpkSet<F>>, val rejected: List<RejectedSet>)

/**
 * Groups VPKs into sets by folder and case-folded prefix. A set's chunks are the ones its directory tree
 * references: each must be present and long enough for what the tree reads from it. Other chunks of the same prefix
 * belong to the set too and are kept. A `X_NNN.vpk` is a chunk only beside its `X_dir.vpk` or when it is no VPK
 * directory itself, since chunks have no header. A `.vpk` that is neither a directory nor a chunk is a set of one
 * when all its data is inside it. With [pairStrays], for files Hideout renames anyway, a directory with none of its
 * chunks takes those of the one chunk prefix in its folder that has no directory, when it is the only such directory
 * there and they fit its tree: an author who renamed one but not the other.
 */
fun <F : VpkFile> groupVpkSets(vpks: List<F>, pairStrays: Boolean = false): SetGrouping<F> {
    val named = vpks.map { it to role(it.path) }
    val directories = named.mapNotNullTo(HashSet()) { (_, role) -> (role as? Role.Directory)?.prefixKey }
    val roles = named.map { (vpk, role) -> vpk to if (role is Role.Chunk && role.prefixKey !in directories && vpk.directory is VpkRead.Parsed) Role.Standalone else role }
    val chunks = roles.mapNotNull { (vpk, role) -> (role as? Role.Chunk)?.let { Triple(vpk, it.prefixKey, it.index) } }
    val strays = if (pairStrays) strays(roles) else emptyMap()
    val claimedChunks = mutableSetOf<F>()
    val sets = mutableListOf<VpkSet<F>>()
    val rejected = mutableListOf<RejectedSet>()
    fun chunksOf(prefixKey: String?) = chunks.filter { it.second == prefixKey }.associate { (chunk, _, index) -> index to chunk }
    for ((vpk, role) in roles) {
        when (role) {
            is Role.Chunk -> Unit
            is Role.Directory -> {
                val own = chunksOf(role.prefixKey)
                val adopted = chunksOf(strays[role.prefixKey]).takeIf { it.isNotEmpty() && problemOf(vpk, role.prefix, it) == null }
                val members = adopted ?: own
                claimedChunks += own.values + members.values
                when (val problem = problemOf(vpk, role.prefix, members)) {
                    null -> sets += VpkSet(vpk.path, listOf(SetMember(VpkPart.Directory, vpk)) + members.toSortedMap().map { (index, chunk) -> SetMember(VpkPart.Chunk(index), chunk) })
                    else -> rejected += RejectedSet(vpk.path, problem)
                }
            }
            Role.Standalone -> when (val directory = vpk.directory) {
                is VpkRead.Unreadable -> rejected += RejectedSet(vpk.path, SetProblem.Unreadable(directory.reason))
                is VpkRead.Parsed -> if (directory.chunkExtents.isEmpty()) {
                    sets += VpkSet(vpk.path, listOf(SetMember(VpkPart.Directory, vpk)))
                } else {
                    rejected += RejectedSet(vpk.path, SetProblem.NeedsChunks)
                }
            }
        }
    }
    chunks.filter { it.first !in claimedChunks }.forEach { rejected += RejectedSet(it.first.path, SetProblem.Orphan) }
    return SetGrouping(sets, rejected)
}

private fun strays(roles: List<Pair<VpkFile, Role>>): Map<String, String> {
    val chunked = roles.mapNotNullTo(HashSet()) { (it.second as? Role.Chunk)?.prefixKey }
    val orphans = chunked - roles.mapNotNullTo(HashSet()) { (it.second as? Role.Directory)?.prefixKey }
    val lone = roles.mapNotNull { (vpk, role) ->
        (role as? Role.Directory)?.prefixKey?.takeIf { it !in chunked && (vpk.directory as? VpkRead.Parsed)?.chunkExtents?.isNotEmpty() == true }
    }
    return lone.groupBy(::folderOf).mapNotNull { (folder, here) ->
        val directory = here.singleOrNull() ?: return@mapNotNull null
        orphans.singleOrNull { folderOf(it) == folder }?.let { directory to it }
    }.toMap()
}

private fun folderOf(prefixKey: String) = prefixKey.substringBeforeLast('/')

private fun problemOf(directory: VpkFile, prefix: String, chunks: Map<Int, VpkFile>): SetProblem? {
    val tree = when (val read = directory.directory) {
        is VpkRead.Unreadable -> return SetProblem.Unreadable(read.reason)
        is VpkRead.Parsed -> read.chunkExtents
    }
    tree.keys.firstOrNull { it > MaxChunkIndex }?.let { return SetProblem.ChunkIndexTooLarge(it) }
    val missing = tree.keys.filter { it !in chunks }.sorted()
    if (missing.isNotEmpty()) return SetProblem.MissingChunks(missing.map { chunkName(prefix, it) })
    for ((index, extent) in tree.toSortedMap()) {
        val chunk = chunks.getValue(index)
        if (chunk.sizeBytes < extent) return SetProblem.ChunkTooShort(chunkName(prefix, index), extent, chunk.sizeBytes)
    }
    return null
}

private sealed interface Role {
    data class Directory(val prefix: String, val prefixKey: String) : Role
    data class Chunk(val prefixKey: String, val index: Int) : Role
    data object Standalone : Role
}

private val DirectoryName = Regex("""(.+)_dir\.vpk""", RegexOption.IGNORE_CASE)
private val ChunkName = Regex("""(.+)_([0-9]{3})\.vpk""", RegexOption.IGNORE_CASE)

private fun role(path: String): Role {
    val folder = path.substringBeforeLast('/', "")
    val name = path.substringAfterLast('/')
    DirectoryName.matchEntire(name)?.let { return Role.Directory(it.groupValues[1], key(folder, it.groupValues[1])) }
    ChunkName.matchEntire(name)?.let { return Role.Chunk(key(folder, it.groupValues[1]), it.groupValues[2].toInt()) }
    return Role.Standalone
}

private fun key(folder: String, prefix: String) = "${folder.lowercase()}/${prefix.lowercase()}"

private fun chunkName(prefix: String, index: Int) = "${prefix}_${index.toString().padStart(3, '0')}.vpk"

private const val MaxChunkIndex = 999
