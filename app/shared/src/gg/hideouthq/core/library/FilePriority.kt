package gg.hideouthq.core.library

enum class FilePriorityRelation { SelectedFirst, OtherFirst, NotTogether }
enum class FileIndexState { Reading, Unavailable }

data class FileIndexIssue(val installId: String, val title: String, val state: FileIndexState)

/** [kept] when the user chose to keep the two on together, from either record's [ManifestRecord.keptBeside]. */
data class FilePriorityPeer(
    val installId: String,
    val title: String,
    val paths: List<String>,
    val chosen: FilePriorityRelation,
    val applied: FilePriorityRelation,
    val kept: Boolean = false,
)

data class FilePriorityReport(
    val chosenEnabled: Boolean = false,
    val appliedEnabled: Boolean = false,
    val peers: List<FilePriorityPeer> = emptyList(),
    val unchecked: List<FileIndexIssue> = emptyList(),
)

data class FilePrioritySnapshot(val order: List<String>, val enabled: Set<String>) {
    fun relation(selected: String, peer: String): FilePriorityRelation = when {
        selected !in enabled || peer !in enabled -> FilePriorityRelation.NotTogether
        order.indexOf(selected) < order.indexOf(peer) -> FilePriorityRelation.SelectedFirst
        else -> FilePriorityRelation.OtherFirst
    }
}

/** Records worth comparing against [selected]: itself and any peer enabled alongside it in either order. */
fun candidateRecords(selected: String, records: List<ManifestRecord>, chosen: FilePrioritySnapshot, applied: FilePrioritySnapshot): List<ManifestRecord> =
    records.filter { record ->
        record.installId == selected || chosen.relation(selected, record.installId) != FilePriorityRelation.NotTogether ||
            applied.relation(selected, record.installId) != FilePriorityRelation.NotTogether
    }

/**
 * The files [selected] and each peer both change with different bytes, as [sharedFiles] finds them, and which of the
 * two loads first; not compatibility. Peers sharing a model come first. Each snapshot is compared separately.
 */
fun filePriority(
    selected: String,
    records: List<ManifestRecord>,
    chosen: FilePrioritySnapshot,
    applied: FilePrioritySnapshot,
    indexes: Map<String, ResourceIndex>,
    unavailableInstalls: Set<String> = emptySet(),
): FilePriorityReport {
    if (records.none { it.installId == selected }) return FilePriorityReport()
    val report = FilePriorityReport(selected in chosen.enabled, selected in applied.enabled)
    if (!report.chosenEnabled && !report.appliedEnabled) return report
    val candidates = candidateRecords(selected, records, chosen, applied)
    val files = mutableMapOf<String, Map<String, Long>>()
    val unchecked = mutableListOf<FileIndexIssue>()
    for (record in candidates) {
        val components = record.sets.map { indexes[it.files.first().image.sha256] }
        val issue = when {
            record.installId in unavailableInstalls || ResourceIndex.Unavailable in components -> FileIndexState.Unavailable
            components.any { it == null } -> FileIndexState.Reading
            else -> null
        }
        if (issue != null) {
            unchecked += FileIndexIssue(record.installId, record.title, issue)
        } else {
            files[record.installId] = components.filterIsInstance<ResourceIndex.Ready>().fold(LinkedHashMap()) { all, index ->
                all.apply { index.files.forEach { (path, crc) -> putIfAbsent(path, crc) } }
            }
        }
    }
    val own = files[selected].orEmpty()
    val keptBeside = records.single { it.installId == selected }.keptBeside
    val peers = candidates.filter { it.installId != selected }.mapNotNull { peer ->
        val shared = sharedFiles(listOf(own, files[peer.installId].orEmpty()))
        if (shared.isEmpty()) null else FilePriorityPeer(
            peer.installId, peer.title, shared, chosen.relation(selected, peer.installId), applied.relation(selected, peer.installId),
            kept = peer.installId in keptBeside || selected in peer.keptBeside,
        )
    }.sortedWith(compareByDescending<FilePriorityPeer> { sharesModel(it.paths) }.thenByDescending { it.paths.size }
        .thenBy { chosen.order.indexOf(it.installId) }.thenBy { it.installId })
    return report.copy(peers = peers, unchecked = unchecked)
}

/** Moves the whole installation without changing any other installation's relative order. */
fun moveAboveOrder(selected: String, peer: String, order: List<String>): List<String>? {
    val from = order.indexOf(selected)
    val to = order.indexOf(peer)
    if (to < 0 || from <= to) return null
    return order.toMutableList().apply { removeAt(from); add(to, selected) }
}
