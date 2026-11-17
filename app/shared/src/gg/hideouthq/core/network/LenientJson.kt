package gg.hideouthq.core.network

import gg.hideouthq.core.data.DataError
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

internal val LenientJson: Json = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    isLenient = true
    explicitNulls = false
}

/**
 * Decodes [text] with [LenientJson], first dropping anything printed before the JSON itself (PHP
 * warnings, for one). A body that still is not the expected JSON is [DataError.Unavailable].
 */
internal fun <T> decodeLenient(deserializer: DeserializationStrategy<T>, text: String): T = try {
    LenientJson.decodeFromString(deserializer, jsonPart(text))
} catch (e: IllegalArgumentException) {
    throw RemoteException(DataError.Unavailable, "Unexpected response: ${e.message}", e)
}

internal fun jsonPart(text: String): String {
    var from = 0
    while (true) {
        val start = (from..text.lastIndex).firstOrNull { !text[it].isWhitespace() } ?: return text
        if (text[start] == '{' || text[start] == '[') return if (start == 0) text else text.substring(start)
        val lineEnd = text.indexOf('\n', start)
        if (lineEnd < 0) return text
        from = lineEnd + 1
    }
}

/**
 * The elements of a JSON list that PHP may also send as an object keyed by id (or as `[]` when
 * empty): an array gives its elements, an object its values, anything else nothing.
 */
internal fun JsonElement?.asList(): List<JsonElement> = when (this) {
    is JsonArray -> this
    is JsonObject -> values.toList()
    else -> emptyList()
}

internal fun JsonElement?.asObjectOrNull(): JsonObject? = this as? JsonObject

internal fun JsonElement?.asStringOrNull(): String? = (this as? JsonPrimitive)?.contentOrNull

internal fun JsonElement?.asLongOrNull(): Long? = (this as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.toLongOrNull() }

internal fun JsonElement?.asBooleanOrNull(): Boolean? = (this as? JsonPrimitive)?.let { it.booleanOrNull ?: it.contentOrNull?.toBooleanStrictOrNull() }
