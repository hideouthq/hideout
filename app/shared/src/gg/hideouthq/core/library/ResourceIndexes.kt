package gg.hideouthq.core.library

import gg.hideouthq.core.data.CacheStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

sealed interface ResourceIndex {
    /** Each resource path the tree lists, `/`-separated and lowercase, with the CRC-32 the tree records for its bytes. */
    data class Ready(val files: Map<String, Long>) : ResourceIndex
    data object Unavailable : ResourceIndex
}

/** Reads only managed directory resources, checking that the bytes still match the manifest's image. */
fun interface ResourceIndexSource {
    suspend fun read(root: GameRoot, relativePath: String, image: FileImage): ResourceIndex

    /** The tree of `addons/[name]`, a file Hideout does not own, read for what it shows and never for ownership. */
    suspend fun readForeign(root: GameRoot, name: String): ResourceIndex = ResourceIndex.Unavailable
}

class ResourceIndexCache(private val storage: CacheStorage, internal val source: ResourceIndexSource) {
    suspend fun read(record: ManifestRecord, directory: OwnedFile): ResourceIndex {
        val name = "vpk-resources-v2-${directory.sha256}.json"
        val cached = optional { storage.read(name)?.let { Json.decodeFromString(CachedFiles, it) } }
        if (cached != null) return ResourceIndex.Ready(cached)
        val result = optional { source.read(record.gameRoot, directory.relativePath(record), FileImage(directory.sizeBytes, directory.sha256)) }
            ?: ResourceIndex.Unavailable
        if (result is ResourceIndex.Ready) optional { storage.write(name, Json.encodeToString(CachedFiles, result.files)) }
        return result
    }
}

private val CachedFiles = MapSerializer(String.serializer(), Long.serializer())

/** Index work never takes the library's mutation lock and never changes install/reorder availability. */
class FilePriorities(private val library: ModLibrary, private val cache: ResourceIndexCache) {
    private val indexes = MutableStateFlow<Map<String, ResourceIndex>>(emptyMap())

    private val foreign = MutableStateFlow<Map<Mounted.Foreign, ResourceIndex>>(emptyMap())

    /** Reads only start once this flow is collected, and only for [installId] and its peers in the current game root. */
    fun inspect(installId: String): Flow<FilePriorityReport> = flow {
        coroutineScope {
            launch { trackIndexes(installId) }
            combine(library.state, indexes) { current, known -> report(current, known, installId) }.collect { emit(it) }
        }
    }

    /**
     * Which models each mod of the current game loses, as the active profile's order places its mods: at the numbers
     * the choices worker will give them, so it follows a drag before Deadlock closes. Reads start once collected.
     */
    fun conflicts(): Flow<ConflictReport> = flow {
        coroutineScope {
            launch { trackMounts() }
            combine(library.state.map(::placedFor).distinctUntilChanged(), indexes, foreign) { placed, known, read ->
                if (placed == null) return@combine ConflictReport()
                val overlapping = placed.parted().filter { record ->
                    val trees = record.sets.map { set -> known[set.files.first().image.sha256] as? ResourceIndex.Ready ?: return@filter false }
                    sharedFiles(trees.map { it.files }).isNotEmpty()
                }
                conflictReport(placed.mounts, placed.mounts.mapNotNull { mount -> placed.indexOf(mount.by, known, read)?.let { mount.by to it } }.toMap())
                    .copy(overlapping = overlapping.mapTo(HashSet()) { it.installId })
            }.distinctUntilChanged().collect { emit(it) }
        }
    }

    fun moveAbove(selected: String, peer: String) {
        val view = library.state.value.orderView ?: return
        moveAboveOrder(selected, peer, view.sets)?.let { library.reorder(view, it) }
    }

    private suspend fun trackIndexes(installId: String) {
        library.state.map { current ->
            scopeFor(current)?.let { Triple(candidateRecords(installId, it.records, it.chosen, it.applied), it.repair, it.unchecked) }
        }.distinctUntilChanged().collectLatest { snapshot ->
            if (snapshot == null) return@collectLatest
            val (candidates, repair, unchecked) = snapshot
            for (record in candidates) {
                if (record.installId in repair || record.installId in unchecked) continue
                for (set in record.sets) index(record, set)
            }
        }
    }

    private suspend fun trackMounts() {
        library.state.map(::placedFor).distinctUntilChanged().collectLatest { placed ->
            if (placed == null) return@collectLatest
            for (mount in placed.mounts) {
                when (val by = mount.by) {
                    is Mounted.Managed -> {
                        val record = placed.records.first { it.installId == by.installId }
                        if (record.installId !in placed.unreadable) index(record, record.set(by.set))
                    }
                    is Mounted.Foreign -> {
                        if (by in foreign.value) continue
                        val result = if (by.stamp == null) ResourceIndex.Unavailable else optional { cache.source.readForeign(placed.root, by.name) } ?: ResourceIndex.Unavailable
                        foreign.update { it + (by to result) }
                    }
                }
            }
            for (record in placed.parted()) record.sets.forEach { index(record, it) }
        }
    }

    private suspend fun index(record: ManifestRecord, set: InstalledSet) {
        val directory = record.filesOf(set).first()
        if (indexes.value[directory.sha256] is ResourceIndex.Ready) return
        val result = cache.read(record, directory)
        indexes.update { it + (directory.sha256 to result) }
    }

    private fun report(current: LibraryState, indexes: Map<String, ResourceIndex>, installId: String): FilePriorityReport {
        val scope = scopeFor(current) ?: return FilePriorityReport()
        return filePriority(installId, scope.records, scope.chosen, scope.applied, indexes, scope.repair + scope.unchecked)
    }
}

private class LibraryScope(
    val records: List<ManifestRecord>,
    val repair: Set<String>,
    val unchecked: Set<String>,
    val chosen: FilePrioritySnapshot,
    val applied: FilePrioritySnapshot,
)

private data class Placed(val root: GameRoot, val records: List<ManifestRecord>, val unreadable: Set<String>, val mounts: List<Mount>) {
    fun parted(): List<ManifestRecord> = records.filter { it.sets.size > 1 && it.installId !in unreadable }

    fun indexOf(by: Mounted, known: Map<String, ResourceIndex>, foreign: Map<Mounted.Foreign, ResourceIndex>): ResourceIndex? = when (by) {
        is Mounted.Managed -> records.first { it.installId == by.installId }.let { record -> known[record.set(by.set).files.first().image.sha256] }
        is Mounted.Foreign -> foreign[by]
    }
}

private fun placedFor(current: LibraryState): Placed? {
    val ready = current.library as? LibraryStatus.Ready ?: return null
    val root = (current.game as? GameStatus.Ready)?.root ?: return null
    val loading = ready.loading ?: return null
    val profile = (current.shownProfiles ?: current.profiles)?.activeProfile ?: return null
    val records = ready.records.filter { it.gameRoot == root }
    val stuck = ready.damaged.keys + ready.unchecked
    return Placed(root, records, stuck, profileMounts(records, profile, loading, stuck))
}

private fun scopeFor(current: LibraryState): LibraryScope? {
    val ready = current.library as? LibraryStatus.Ready ?: return null
    val root = (current.game as? GameStatus.Ready)?.root ?: return null
    val records = ready.records.filter { it.gameRoot == root }
    val applied = FilePrioritySnapshot(fileOrder(records), records.filter { it.enabled }.mapTo(mutableSetOf()) { it.installId })
    val chosen = FilePrioritySnapshot(current.orderView?.sets ?: applied.order,
        (current.shownProfiles ?: current.profiles)?.activeProfile?.enabled ?: applied.enabled)
    return LibraryScope(records, ready.damaged.keys, ready.unchecked, chosen, applied)
}

private suspend fun <T> optional(block: suspend () -> T): T? = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    null
}
