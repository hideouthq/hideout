package gg.hideouthq.core.data

import gg.hideouthq.core.library.CitadelPath
import gg.hideouthq.core.library.MigrationId

/**
 * The records imports keep in Hideout's data folder, one folder per import, `migrations/<id>/`: the record itself and
 * a byte-exact copy of every game file the import may change. VPK bytes are never copied: an adopted file is never
 * modified, so its old path, size and hash in the record find it. With the copies, an import can be undone by hand.
 */
interface MigrationRecords {
    /** Writes [copies], by their path under `game/citadel`, then [record], before any game file changes. Refused over an existing record. */
    suspend fun begin(id: MigrationId, record: ByteArray, copies: Map<CitadelPath, ByteArray>): RecordWrite

    /** Replaces [id]'s record with [record]. */
    suspend fun update(id: MigrationId, record: ByteArray): RecordWrite

    /** Every record kept, by id. */
    suspend fun all(): Map<MigrationId, ByteArray>
}

sealed interface RecordWrite {
    data object Written : RecordWrite

    data class Failed(val reason: String) : RecordWrite
}
