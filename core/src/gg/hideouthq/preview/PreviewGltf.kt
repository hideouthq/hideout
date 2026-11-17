package gg.hideouthq.preview

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.abs

/**
 * Problems with the glTF JSON chunk of the listed GLB at [modelPath], as the viewer loads it once Meshopt is decoded. A
 * GLB keeps its binary data in its own chunk and every buffer view lies inside that one buffer. Its images are judged on
 * the stored GLB by [packedImageProblems].
 * When it is the scene model, its animations must be the manifest's clips with the same durations, and the scene
 * counts are checked: the largest skin's joints, the most `JOINTS_n` attributes on one primitive, the most morph
 * targets on one primitive, and `extensionsRequired`.
 */
fun gltfProblems(modelPath: String, gltfJson: String, manifest: BundleManifest): List<String> =
    parsed(modelPath, gltfJson) { gltfProblems(modelPath, it, manifest) }

/**
 * [gltfProblems] for a whole GLB whose `BIN` chunk holds [binaryChunkBytes] bytes, or null when it has none. The
 * buffer the JSON declares must fit in that chunk; a GLB that declares no buffer needs no `BIN` chunk.
 */
fun glbProblems(modelPath: String, gltfJson: String, binaryChunkBytes: Long?, manifest: BundleManifest): List<String> =
    parsed(modelPath, gltfJson) { gltf ->
        val stored = gltf.objects("buffers").singleOrNull()?.takeUnless { "uri" in it }?.get("byteLength").count()
        gltfProblems(modelPath, gltf, manifest) + when {
            stored == null -> emptyList()
            binaryChunkBytes == null -> listOf("${display(modelPath)} has no BIN chunk for its buffer")
            binaryChunkBytes < stored -> listOf("${display(modelPath)} has a BIN chunk shorter than its buffer")
            else -> emptyList()
        }
    }

/**
 * Problems with the images of the stored model at [modelPath], the GLB its zstd encoding holds, before any Meshopt
 * decoding. Every image is a [TEXTURE_MEDIA_TYPE] viewer texture packed in it: the whole of a buffer view in the `BIN`
 * chunk. The image views are the last buffer views, in the list and in the chunk, so a reader takes the meshes and then
 * the textures in one pass, and together they take at most [MAX_IMAGE_BYTES].
 */
fun packedImageProblems(modelPath: String, gltfJson: String): List<String> = parsed(modelPath, gltfJson) { gltf ->
    val name = display(modelPath)
    val views = gltf.objects("bufferViews")
    val used = mutableSetOf<Int>()
    for ((index, image) in gltf.objects("images").withIndex()) {
        val view = image["bufferView"].count()?.toInt()
        if ("uri" in image || image["mimeType"]?.jsonPrimitive?.contentOrNull != TEXTURE_MEDIA_TYPE || view == null || view >= views.size) {
            return@parsed listOf("$name image $index is not an $TEXTURE_MEDIA_TYPE texture packed in a buffer view")
        }
        used += view
    }
    val first = used.minOrNull() ?: return@parsed emptyList()
    val packed = views.drop(first)
    val meshesEnd = views.take(first).maxOfOrNull { storedEnd(it) ?: return@parsed listOf("$name has a buffer view without a byte range") } ?: 0.0
    val read = gltf.objects("accessors").mapNotNull { it["bufferView"].count()?.toInt() }.toSet()
    val offsets = packed.map { if ("byteOffset" in it) it["byteOffset"].count() else 0.0 }
    when {
        used != (first until views.size).toSet() || used.any { it in read } ||
            packed.zip(offsets).any { (view, offset) -> view["buffer"].count() != 0.0 || "byteStride" in view || "extensions" in view || offset == null || offset < meshesEnd } ->
            listOf("$name images are not packed in the buffer views after all the others")
        packed.sumOf { it["byteLength"].count() ?: Double.MAX_VALUE } > MAX_IMAGE_BYTES -> listOf("$name images take more than $MAX_IMAGE_BYTES bytes")
        else -> emptyList()
    }
}

/**
 * Where [view]'s stored bytes end in the `BIN` chunk: a Meshopt view's compressed range, which a fallback buffer stands
 * in for, or a plain view's own; 0 for a view with no bytes there, and null for one without a readable range.
 */
private fun storedEnd(view: JsonObject): Double? {
    val range = (view["extensions"] as? JsonObject)?.get("EXT_meshopt_compression") as? JsonObject ?: view
    if (range["buffer"].count() != 0.0) return 0.0
    val offset = if ("byteOffset" in range) range["byteOffset"].count() else 0.0
    val length = range["byteLength"].count()
    return if (offset == null || length == null) null else offset + length
}

private inline fun parsed(modelPath: String, gltfJson: String, problems: (JsonObject) -> List<String>): List<String> = try {
    problems(PreviewJson.parseToJsonElement(gltfJson).jsonObject)
} catch (e: IllegalArgumentException) {
    listOf("${display(modelPath)} has malformed glTF JSON")
}

private fun gltfProblems(modelPath: String, gltf: JsonObject, manifest: BundleManifest): List<String> = buildList {
    val name = display(modelPath)
    val buffers = gltf.objects("buffers")
    if (buffers.size > 1 || buffers.any { "uri" in it }) add("$name keeps binary data outside its own chunk")
    val bufferBytes = buffers.firstOrNull()?.get("byteLength").count()
    if (buffers.isNotEmpty() && (bufferBytes == null || bufferBytes < 1)) add("$name has a buffer without a byte length")
    val viewEnds = gltf.objects("bufferViews").map { view ->
        val offset = if ("byteOffset" in view) view["byteOffset"].count() else 0.0
        val length = view["byteLength"].count()
        if (view["buffer"].count() != 0.0 || offset == null || length == null) null else offset + length
    }
    if (viewEnds.any { it == null || bufferBytes == null || it > bufferBytes }) add("$name has buffer views outside its buffer")

    val scene = manifest.scene
    if (scene == null || scene.model != modelPath) return@buildList
    val accessors = gltf.objects("accessors")
    val durations = mutableMapOf<String, Double?>()
    for (animation in gltf.objects("animations")) {
        val id = animation["name"]?.jsonPrimitive?.contentOrNull
        if (id == null) {
            add("$name has an unnamed animation")
            continue
        }
        if (id in durations) add("$name has two animations named ${display(id)}")
        val ends = animation.objects("samplers").map { sampler ->
            val input = sampler["input"].count()?.let { accessors.getOrNull(it.toInt()) }
            input?.get("max")?.jsonArray?.firstOrNull().number()?.takeIf { it >= 0 }
        }
        durations[id] = if (ends.isEmpty() || null in ends) null else ends.filterNotNull().max()
    }
    val clips = manifest.clips.associate { it.id to it.durationMillis }
    if (durations.keys != clips.keys) add("$name animations are not exactly the listed clips")
    for ((id, seconds) in durations) {
        val millis = clips[id] ?: continue
        if (seconds == null) add("$name animation ${display(id)} has no input range")
        else if (abs(millis - seconds * 1000) > 1) add("$name animation ${display(id)} does not last ${millis}ms")
    }

    val joints = gltf.objects("skins").maxOfOrNull { it["joints"]?.jsonArray?.size ?: 0 } ?: 0
    val primitives = gltf.objects("meshes").flatMap { it.objects("primitives") }
    val influences = primitives.maxOfOrNull { primitive ->
        primitive["attributes"]?.jsonObject?.keys?.count { it.startsWith("JOINTS_") } ?: 0
    } ?: 0
    val morphs = primitives.maxOfOrNull { it["targets"]?.jsonArray?.size ?: 0 } ?: 0
    val required = gltf["extensionsRequired"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
    if (joints != scene.maxJointsPerSkin) add("$name has $joints joints in its largest skin, not ${scene.maxJointsPerSkin}")
    if (influences != scene.influenceSets) add("$name has $influences joint influence sets, not ${scene.influenceSets}")
    if (morphs != scene.morphTargets) add("$name has $morphs morph targets, not ${scene.morphTargets}")
    if (required.toSet() != scene.extensionsRequired.toSet()) add("$name requires different extensions")
}

private fun JsonObject.objects(key: String): List<JsonObject> = this[key]?.jsonArray?.map { it.jsonObject }.orEmpty()

private val JsonNumber = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")

/**
 * A finite JSON number. The tree parser keeps any bare token as an unquoted literal, and `doubleOrNull` reads quoted
 * text too, so `NaN`, `1.25f` and `"1.25"` are refused here rather than read as times or sizes.
 */
private fun JsonElement?.number(): Double? = (this as? JsonPrimitive)
    ?.takeIf { !it.isString && JsonNumber.matches(it.content) }?.content?.toDouble()?.takeIf { it.isFinite() }

private fun JsonElement?.count(): Double? = number()?.takeIf { it >= 0 && it % 1.0 == 0.0 }
