package gg.hideouthq.core.crosshair

import gg.hideouthq.core.crosshair.community.dmmConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.util.Base64

data class ImportedCrosshair(val name: String?, val crosshair: Crosshair)

/**
 * Share codes and the Import box. A code is `HX1.` and the unpadded base64url of a JSON object of the twenty settings,
 * keyed by [CrosshairSetting.suffix] and written as the game saves them, and its `name`. Keyed rather than positional,
 * so a code made before a setting was known reads as one with the game's value of it.
 */
object CrosshairCodes {
    private const val Prefix = "HX1."

    fun encode(crosshair: Crosshair, name: String): String {
        val fields = listOf("name" to name) + crosshair.settings().toList()
        val json = JsonObject(fields.associate { (key, value) -> key to JsonPrimitive(value) }).toString()
        return Prefix + Base64.getUrlEncoder().withoutPadding().encodeToString(json.encodeToByteArray())
    }

    fun parse(text: String): ImportedCrosshair? = code(text.trim()) ?: json(text.trim()) ?: lines(text)

    private fun code(text: String): ImportedCrosshair? {
        val payload = text.takeIf { it.startsWith(Prefix) }?.removePrefix(Prefix) ?: return null
        val fields = decoded(Base64.getUrlDecoder(), payload)?.let(::jsonObject) ?: return null
        val read = crosshairOf(fields.mapNotNull { (key, value) -> (value as? JsonPrimitive)?.contentOrNull?.let { key to it } }.toMap())
        return read.takeIf { it.set.isNotEmpty() }?.let { ImportedCrosshair((fields["name"] as? JsonPrimitive)?.contentOrNull, it.crosshair) }
    }

    private fun json(text: String): ImportedCrosshair? {
        val fields = jsonObject(text) ?: decoded(Base64.getDecoder(), text)?.let(::jsonObject) ?: return null
        val row = fields["config"] as? JsonObject
        val crosshair = dmmConfig(row ?: fields) ?: return null
        return ImportedCrosshair(if (row != null) (fields["name"] as? JsonPrimitive)?.contentOrNull else null, crosshair)
    }

    private fun lines(text: String): ImportedCrosshair? {
        val statements = text.lines().flatMap { line -> statements(line).orEmpty().map { it.joinToString(" ") } }
        return CrosshairLines.read(statements).takeIf { it.set.isNotEmpty() }?.let { ImportedCrosshair(null, it.crosshair) }
    }

    private fun decoded(decoder: Base64.Decoder, text: String): String? = try {
        decoder.decode(text).decodeToString()
    } catch (e: IllegalArgumentException) {
        null
    }

    private fun jsonObject(text: String): JsonObject? = if (!text.startsWith("{")) null else try {
        Json.parseToJsonElement(text) as? JsonObject
    } catch (e: IllegalArgumentException) {
        null
    }
}
