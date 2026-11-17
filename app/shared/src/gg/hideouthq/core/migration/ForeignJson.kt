package gg.hideouthq.core.migration

import gg.hideouthq.core.library.isSha256
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

internal fun parseJson(bytes: ByteArray): JsonElement? = parseJson(bytes.decodeToString())

internal fun parseJson(text: String): JsonElement? = try {
    Json.parseToJsonElement(text.removePrefix("﻿"))
} catch (e: SerializationException) {
    null
}

internal val JsonElement?.obj: JsonObject? get() = this as? JsonObject

internal val JsonElement?.items: List<JsonElement> get() = this as? JsonArray ?: emptyList()

internal val JsonElement?.text: String? get() = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

internal val JsonElement?.words: String? get() = text?.takeIf { it.isNotBlank() }

internal val JsonElement?.number: Double? get() = (this as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull

internal val JsonElement?.whole: Long? get() = (this as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull

internal val JsonElement?.isTrue: Boolean get() = (this as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull == true

internal val JsonElement?.isFalse: Boolean get() = (this as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull == false

internal val JsonElement?.sha256: String? get() = text?.lowercase()?.takeIf(::isSha256)
