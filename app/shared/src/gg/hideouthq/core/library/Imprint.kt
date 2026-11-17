package gg.hideouthq.core.library

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

data class Imprint(val claim: ModIdentity, val original: FilePrint?) {
    companion object {
        const val EntryName = "modinfo.json"

        const val MaxBytes = 64 * 1024

        private val json = Json { ignoreUnknownKeys = true }
        private val Crc32 = Regex("[0-9a-fA-F]{8}")

        /** The record in [bytes]; null when it is another format or version, a merge's, or not one at all. */
        fun read(bytes: ByteArray): Imprint? {
            val record = try {
                json.decodeFromString(Modinfo.serializer(), bytes.decodeToString())
            } catch (e: IllegalArgumentException) {
                return null
            }
            if (record.format != "vpk-modinfo" || record.schemaVersion != 1 || record.kind != "mod") return null
            val kind = when (record.source?.section) {
                null, "Mod" -> SubmissionKind.Mod
                "Sound" -> SubmissionKind.Sound
                else -> null
            }
            val ref = record.source?.gamebananaId?.takeIf { it > 0 }?.let { id -> kind?.let { SubmissionRef(it, id) } }
            val original = record.identity.size?.let { size -> record.identity.crc32?.takeIf(Crc32::matches)?.let { FilePrint(size, it.toLong(16)) } }
            return Imprint(ModIdentity.of(ref, record.source?.gamebananaFileId?.takeIf { it > 0 }), original)
        }
    }
}

@Serializable
private class Modinfo(val format: String, val schemaVersion: Int, val kind: String, val identity: Identity, val source: Source? = null) {
    @Serializable
    class Identity(val size: Long? = null, val crc32: String? = null)

    @Serializable
    class Source(val gamebananaId: Long? = null, val gamebananaFileId: Long? = null, val section: String? = null)
}
