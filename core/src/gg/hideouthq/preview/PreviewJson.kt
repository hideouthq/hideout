package gg.hideouthq.preview

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.security.MessageDigest
import java.util.HexFormat

/** Unknown keys are ignored within a schema; absent optional values are omitted rather than written as null. */
val PreviewJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}

/** A preview document that must not be used. [problems] describe it without file system paths. */
class InvalidPreview(val problems: List<String>) : IllegalArgumentException(problems.joinToString("; "))

/** Decodes and validates an index, or throws [InvalidPreview]. */
fun decodePreviewIndex(text: String): PreviewIndex {
    val index = decode(PreviewIndex.serializer(), schemaOneObject(text))
    val problems = index.problems()
    if (problems.isNotEmpty()) throw InvalidPreview(problems)
    return index
}

/** Decodes and validates a manifest, or throws [InvalidPreview]. The bundle id is checked against `input` as written. */
fun decodeBundleManifest(text: String): BundleManifest {
    val document = schemaOneObject(text)
    val manifest = decode(BundleManifest.serializer(), document)
    val problems = manifestProblems(manifest, bundleIdOf(document.getValue("input")))
    if (problems.isNotEmpty()) throw InvalidPreview(problems)
    return manifest
}

/**
 * The first 32 hex digits of SHA-256 over the canonical JSON of [input]: keys sorted, no whitespace, non-ASCII
 * escaped. Python's `json.dumps(input, sort_keys=True, separators=(",", ":"))` writes the same bytes.
 */
fun bundleIdOf(input: InputIdentity): String =
    bundleIdOf(PreviewJson.encodeToJsonElement(InputIdentity.serializer(), input))

internal fun bundleIdOf(input: JsonElement): String = sha256Hex(canonicalJson(input).encodeToByteArray()).take(32)

private fun schemaOneObject(text: String): JsonObject {
    val document = try {
        PreviewJson.parseToJsonElement(text)
    } catch (e: SerializationException) {
        throw InvalidPreview(listOf("not JSON"))
    }
    if (document !is JsonObject) throw InvalidPreview(listOf("not a JSON object"))
    val schema = (document["schema"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
        ?: throw InvalidPreview(listOf("schema is missing or not an integer"))
    if (schema != PREVIEW_SCHEMA) throw InvalidPreview(listOf("unsupported schema $schema"))
    return document
}

private fun <T> decode(serializer: KSerializer<T>, document: JsonObject): T {
    if (quotesALiteral(document, serializer.descriptor)) {
        throw InvalidPreview(listOf("does not match schema $PREVIEW_SCHEMA: a number or boolean is written as text"))
    }
    return try {
        PreviewJson.decodeFromJsonElement(serializer, document)
    } catch (e: SerializationException) {
        throw InvalidPreview(listOf("does not match schema $PREVIEW_SCHEMA: ${e.message}"))
    }
}

/** The tree decoder reads `"1"` as a number and `"true"` as a boolean; Python and the Worker refuse both, so this does too. */
private fun quotesALiteral(element: JsonElement, descriptor: SerialDescriptor): Boolean = when {
    descriptor.kind is PrimitiveKind -> descriptor.kind != PrimitiveKind.STRING && element is JsonPrimitive && element.isString
    element is JsonObject && descriptor.kind == StructureKind.CLASS -> element.any { (key, value) ->
        val index = descriptor.getElementIndex(key)
        index >= 0 && quotesALiteral(value, descriptor.getElementDescriptor(index))
    }
    element is JsonObject && descriptor.kind == StructureKind.MAP -> element.values.any { quotesALiteral(it, descriptor.getElementDescriptor(1)) }
    element is JsonArray && descriptor.kind == StructureKind.LIST -> element.any { quotesALiteral(it, descriptor.getElementDescriptor(0)) }
    else -> false
}

internal fun canonicalJson(element: JsonElement): String = buildString { appendCanonical(element) }

internal fun display(value: String): String = buildString { appendCanonicalString(value.take(120)) }

private fun StringBuilder.appendCanonical(element: JsonElement) {
    when (element) {
        is JsonObject -> {
            append('{')
            element.keys.sorted().forEachIndexed { i, key ->
                if (i > 0) append(',')
                appendCanonicalString(key)
                append(':')
                appendCanonical(element.getValue(key))
            }
            append('}')
        }
        is JsonArray -> {
            append('[')
            element.forEachIndexed { i, item ->
                if (i > 0) append(',')
                appendCanonical(item)
            }
            append(']')
        }
        is JsonPrimitive -> if (element.isString) appendCanonicalString(element.content) else append(element.content)
    }
}

private fun StringBuilder.appendCanonicalString(value: String) {
    append('"')
    for (c in value) {
        when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            '\b' -> append("\\b")
            '\u000C' -> append("\\f")
            in ' '..'~' -> append(c)
            else -> append("\\u").append(c.code.toString(16).padStart(4, '0'))
        }
    }
    append('"')
}

fun sha256Hex(bytes: ByteArray): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
