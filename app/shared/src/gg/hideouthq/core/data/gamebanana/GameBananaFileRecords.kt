package gg.hideouthq.core.data.gamebanana

import gg.hideouthq.core.network.asBooleanOrNull
import gg.hideouthq.preview.isFileMd5
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A file container's entries, keyed by file id when GameBanana sent an object, positionless when it sent a list. Null when [container] is neither. */
internal fun fileRecords(container: JsonElement?): List<Pair<String?, JsonElement>>? = when (container) {
    is JsonArray -> container.map { null to it }
    is JsonObject -> container.map { (key, value) -> key to value }
    else -> null
}

internal fun archivedFlag(file: JsonObject): Boolean? {
    val marker = file["_bIsArchived"] ?: return false
    return marker.asBooleanOrNull()
}

internal fun idMatchesKey(key: String?, keyed: Long?, id: Long): Boolean = key == null || keyed == id

internal sealed interface Md5Field {
    data object Absent : Md5Field
    data class Value(val md5: String) : Md5Field
    data object Invalid : Md5Field
}

internal fun readMd5(file: JsonObject): Md5Field = when (val checksum = file["_sMd5Checksum"]) {
    null, JsonNull -> Md5Field.Absent
    is JsonPrimitive -> {
        if (!checksum.isString) return Md5Field.Invalid
        val value = checksum.content.takeIf { it.isNotBlank() }?.lowercase() ?: return Md5Field.Absent
        if (isFileMd5(value)) Md5Field.Value(value) else Md5Field.Invalid
    }
    else -> Md5Field.Invalid
}
