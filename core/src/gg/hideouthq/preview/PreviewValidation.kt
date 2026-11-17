package gg.hideouthq.preview

/** The manifest's name inside a bundle directory, which no asset may take. */
const val MANIFEST_FILE: String = "manifest.json"

internal const val MAX_ASSET_BYTES: Long = 1L shl 30
const val MAX_EXPANDED_MODEL_BYTES: Long = 96L shl 20

/** The most bytes a model's packed viewer textures take together. */
const val MAX_IMAGE_BYTES: Long = 128L shl 20

/** The largest stored GLB a model's encoding may hold: its meshes and its packed textures. */
const val MAX_DECODED_MODEL_BYTES: Long = MAX_EXPANDED_MODEL_BYTES + MAX_IMAGE_BYTES
internal const val MAX_ASSETS: Int = 4096
internal const val MAX_ASSET_PATH_LENGTH: Int = 240
internal const val MAX_PICTURE_SIDE: Int = 16384
internal const val MAX_ICON_BYTES: Long = 1L shl 20
internal const val MAX_RESOURCE_PATH_LENGTH: Int = 255

private val PreviewMediaTypes: Map<String, Set<String>> = mapOf(
    "model/gltf-binary" to setOf("glb.zst"),
    "image/png" to setOf("png"),
    "image/webp" to setOf("webp"),
)

const val MODEL_MEDIA_TYPE: String = "model/gltf-binary"

const val TEXTURE_MEDIA_TYPE: String = "image/ktx2"

private const val PictureMediaType = "image/webp"
private const val IconMediaType = "image/png"
private val AbilitySlots = listOf(1, 2, 3, 4)

private val Sha256Hex = Regex("[0-9a-f]{64}")
private val BundleIdHex = Regex("[0-9a-f]{32}")
private val Md5Hex = Regex("[0-9a-f]{32}")
private val ModelName = Regex("[A-Z][A-Za-z]{0,31}")
private val Key = Regex("[a-z0-9][a-z0-9_]{0,63}")
private val Timestamp = Regex("[0-9]{4}-(0[1-9]|1[0-2])-(0[1-9]|[12][0-9]|3[01])T([01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9]Z")
private val PathSegment = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")
private val ResourcePath = Regex("(?:[a-z0-9_][a-z0-9_-]*(?:\\.[a-z0-9_-]+)*/)*[a-z0-9_][a-z0-9_-]*(?:\\.[a-z0-9_-]+)*\\.[a-z0-9_-]*_c")
private val WindowsDeviceNames = setOf("CON", "PRN", "AUX", "NUL") + (1..9).flatMap { listOf("COM$it", "LPT$it") }

fun isBundleId(value: String): Boolean = BundleIdHex.matches(value)

fun isFileMd5(value: String): Boolean = Md5Hex.matches(value)

/**
 * Why [path] cannot name a file in a bundle, or null when it can. Paths are relative, `/`-separated ASCII segments
 * that start with a letter or digit and do not end with a dot, so they never need escaping, never climb out of the
 * bundle, and stay valid file names on Windows.
 */
fun assetPathProblem(path: String): String? {
    if (path.isEmpty() || path.length > MAX_ASSET_PATH_LENGTH) {
        return "asset path ${display(path)} must be 1..$MAX_ASSET_PATH_LENGTH characters"
    }
    if (path.equals(MANIFEST_FILE, ignoreCase = true)) return "asset path ${display(path)} is reserved"
    for (segment in path.split('/')) {
        if (!PathSegment.matches(segment) || segment.endsWith('.')) {
            return "asset path ${display(path)} has an unsafe segment"
        }
        if (segment.substringBefore('.').uppercase() in WindowsDeviceNames) {
            return "asset path ${display(path)} uses a reserved device name"
        }
    }
    return null
}

fun PreviewIndex.problems(): List<String> = buildList {
    if (generation <= 0) add("generation must be positive")
    if (!Timestamp.matches(publishedAt)) add("publishedAt must be a UTC time like 2026-10-07T16:32:39Z")
    for (entry in entries) addAll(entry.problems())
    for ((submission, fileId) in duplicates(entries.map { it.submission to it.fileId })) {
        add("index repeats ${submission.model} ${submission.id} file $fileId")
    }
    for (bundleId in duplicates(entries.map { it.bundleId })) add("index repeats bundle ${display(bundleId)}")
}

private fun IndexEntry.problems(): List<String> = buildList {
    val name = "entry ${display(submission.model)} ${submission.id} file $fileId"
    submission(name, submission)
    if (fileId <= 0) add("$name file id must be positive")
    fileMd5?.let { if (!Md5Hex.matches(it)) add("$name fileMd5 must be 32 lowercase hex digits") }
    if (!BundleIdHex.matches(bundleId)) add("$name bundleId must be 32 lowercase hex digits")
    if (!Sha256Hex.matches(manifestSha256)) add("$name manifestSha256 must be 64 lowercase hex digits")
    if (build <= 0) add("$name build must be positive")
}

/** How this entry disagrees with the manifest it names. The caller checks [IndexEntry.manifestSha256] on the bytes. */
fun IndexEntry.problemsAgainst(manifest: BundleManifest): List<String> = buildList {
    val input = manifest.input
    if (bundleId != manifest.bundleId) add("entry bundleId differs from the manifest")
    if (submission != input.submission || fileId != input.file.id || fileMd5 != input.file.md5) {
        add("entry file differs from the manifest input")
    }
    if (build != input.game.build) add("entry build differs from the manifest input")
}

fun BundleManifest.problems(): List<String> = manifestProblems(this, bundleIdOf(input))

internal fun manifestProblems(manifest: BundleManifest, identityBundleId: String): List<String> = buildList {
    if (!BundleIdHex.matches(manifest.bundleId)) add("bundleId must be 32 lowercase hex digits")
    else if (manifest.bundleId != identityBundleId) add("bundleId does not match the input identity")
    if (!Timestamp.matches(manifest.createdAt)) add("createdAt must be a UTC time like 2026-10-07T16:32:39Z")
    text("attribution", manifest.attribution, 2000)
    addAll(assetProblems(manifest.assets))
    addAll(sceneProblems(manifest))
    addAll(abilityProblems(manifest))
    addAll(pictureProblems(manifest))
    readiness("picture", manifest.picture)
    readiness("interactive", manifest.interactive)
    if (manifest.pictures.isEmpty()) add("picture is ready without pictures")
    if (manifest.scene == null) add("interactive is ready without a scene")
    val capabilities = manifest.capabilities
    if ((capabilities.weapon || capabilities.effects || capabilities.styles) && manifest.scene == null) {
        add("capabilities are claimed without a scene")
    }
}

private fun assetProblems(assets: List<PreviewAsset>): List<String> = buildList {
    if (assets.size > MAX_ASSETS) add("a bundle holds at most $MAX_ASSETS assets")
    for (asset in assets) {
        val name = display(asset.path)
        assetPathProblem(asset.path)?.let { add(it) }
        if (asset.bytes !in 1..MAX_ASSET_BYTES) add("asset $name must be 1..$MAX_ASSET_BYTES bytes")
        if (!Sha256Hex.matches(asset.sha256)) add("asset $name sha256 must be 64 lowercase hex digits")
        val encoding = asset.encoding
        if ((asset.type == MODEL_MEDIA_TYPE) != (encoding?.codec == "zstd")) {
            add("asset $name must be zstd-encoded exactly when it is a $MODEL_MEDIA_TYPE model")
        }
        if (encoding != null) {
            if (encoding.decodedBytes !in 1..MAX_DECODED_MODEL_BYTES) add("asset $name decodedBytes must be 1..$MAX_DECODED_MODEL_BYTES")
            if (!Sha256Hex.matches(encoding.decodedSha256)) add("asset $name decodedSha256 must be 64 lowercase hex digits")
        }
        val extensions = PreviewMediaTypes[asset.type]
        if (extensions == null) add("asset $name has unsupported type ${display(asset.type)}")
        else if (extensions.none { asset.path.endsWith(".$it") }) add("asset $name extension does not match its type")
    }
    val folded = assets.map { it.path.lowercase() }
    for (path in duplicates(folded)) add("asset paths repeat ignoring case: ${display(path)}")
    val files = folded.toSet()
    for (path in folded) {
        val parents = path.split('/').dropLast(1).runningReduce { parent, segment -> "$parent/$segment" }
        parents.firstOrNull { it in files }?.let { add("asset ${display(path)} is inside listed file ${display(it)}") }
    }
}

private fun sceneProblems(manifest: BundleManifest): List<String> = buildList {
    val scene = manifest.scene
    if (scene != null) {
        val model = manifest.assets.firstOrNull { it.path == scene.model }
        if (model == null) add("scene model ${display(scene.model)} is not a listed asset")
        else if (model.type != MODEL_MEDIA_TYPE) add("scene model ${display(scene.model)} is not $MODEL_MEDIA_TYPE")
        if (scene.maxJointsPerSkin < 0 || scene.influenceSets < 0 || scene.morphTargets < 0) {
            add("scene counts must not be negative")
        }
        scene.extensionsRequired.forEach { text("scene required extension", it, 100) }
        if (duplicates(scene.extensionsRequired).isNotEmpty()) add("scene required extensions repeat")
    } else if (manifest.clips.isNotEmpty()) {
        add("clips are listed without a scene")
    }
    for (clip in manifest.clips) {
        text("clip id", clip.id, 200)
        if (clip.durationMillis <= 0) add("clip ${display(clip.id)} must last longer than zero")
        clip.source?.let { source("clip ${display(clip.id)} source", it) }
        if (clip.changedByMod && clip.source?.owner != ResourceOwner.Mod) {
            add("clip ${display(clip.id)} is changed by the mod without a mod source")
        }
    }
    for (id in duplicates(manifest.clips.map { it.id })) add("clip ${display(id)} is listed twice")
    manifest.missingClips.forEach { text("missing clip", it, 200) }
    if (duplicates(manifest.missingClips).isNotEmpty()) add("missing clips repeat")
    for (id in manifest.missingClips.intersect(manifest.clips.map { it.id }.toSet())) {
        add("clip ${display(id)} is both exported and missing")
    }
}

private fun abilityProblems(manifest: BundleManifest): List<String> = buildList {
    val abilities = manifest.abilities
    if (abilities.isNotEmpty() && abilities.map { it.slot } != AbilitySlots) {
        add("abilities must be the four signature slots 1..4 in order")
    }
    val assets = manifest.assets.associateBy { it.path }
    val clips = manifest.clips.map { it.id }.toSet()
    for (ability in abilities) {
        val name = "ability ${ability.slot}"
        if (!Key.matches(ability.id)) add("$name id must be a lowercase key")
        text("$name name", ability.name, 200)
        ability.clipId?.let { if (it !in clips) add("$name clip ${display(it)} is not an exported clip") }
        icon("$name icon", ability.icon, assets)
        val vanilla = ability.vanillaIcon ?: continue
        icon("$name vanilla icon", vanilla, assets)
        val mod = ability.icon.source
        if (!ability.icon.changedByMod || vanilla.changedByMod || mod.owner != ResourceOwner.Mod ||
            vanilla.source.owner != ResourceOwner.Game || mod.path != vanilla.source.path || mod.sha256 == vanilla.source.sha256
        ) {
            add("$name has a vanilla icon, so its icon must be the mod's changed bytes at the game icon's path")
        }
    }
    for (id in duplicates(abilities.map { it.id })) add("ability ${display(id)} is listed twice")
}

private fun MutableList<String>.icon(name: String, icon: PreviewIcon, assets: Map<String, PreviewAsset>) {
    val asset = assets[icon.asset]
    if (asset == null) add("$name asset ${display(icon.asset)} is not listed")
    else if (asset.type != IconMediaType || asset.bytes > MAX_ICON_BYTES) {
        add("$name asset must be a PNG image of at most $MAX_ICON_BYTES bytes")
    }
    source("$name source", icon.source)
    if (icon.changedByMod && icon.source.owner != ResourceOwner.Mod) add("$name is changed by the mod without a mod source")
}

private fun MutableList<String>.source(name: String, source: ResourceSource) {
    if (source.path.length > MAX_RESOURCE_PATH_LENGTH || !ResourcePath.matches(source.path)) {
        add("$name path must be a lowercase compiled resource path of at most $MAX_RESOURCE_PATH_LENGTH characters")
    }
    if (!Sha256Hex.matches(source.sha256)) add("$name sha256 must be 64 lowercase hex digits")
}

private fun pictureProblems(manifest: BundleManifest): List<String> = buildList {
    val assets = manifest.assets.associateBy { it.path }
    val clips = manifest.clips.associateBy { it.id }
    for (role in duplicates(manifest.pictures.map { it.role })) add("picture role ${role.name.lowercase()} is listed twice")
    for (picture in manifest.pictures) {
        val name = "picture ${picture.role.name.lowercase()}"
        val asset = assets[picture.asset]
        if (asset == null) add("$name asset ${display(picture.asset)} is not listed")
        else if (asset.type != PictureMediaType) add("$name asset ${display(picture.asset)} is not a WebP image")
        if (picture.width !in 1..MAX_PICTURE_SIDE || picture.height !in 1..MAX_PICTURE_SIDE) {
            add("$name dimensions must be 1..$MAX_PICTURE_SIDE")
        }
        val clip = picture.clip
        if (clip == null) {
            if (picture.timeMillis != 0L) add("$name without a clip must be at time zero")
        } else {
            val duration = clips[clip]?.durationMillis
            if (duration == null) add("$name clip ${display(clip)} is not an exported clip")
            else if (picture.timeMillis !in 0..duration) add("$name time is outside clip ${display(clip)}")
        }
        if (!picture.azimuth.isFinite() || picture.azimuth !in -360.0..360.0) add("$name azimuth must be -360..360 degrees")
        if (!Key.matches(picture.lighting)) add("$name lighting must be a lowercase key")
        picture.hiddenMeshSuffixes.forEach { text("$name hidden mesh suffix", it, 200) }
        if (duplicates(picture.hiddenMeshSuffixes).isNotEmpty()) add("$name hidden mesh suffixes repeat")
    }
}

private fun MutableList<String>.readiness(name: String, readiness: Readiness) {
    if (readiness.checks.isEmpty()) add("$name is ready without checks")
    readiness.checks.forEach { text("$name check", it, 200) }
    if (duplicates(readiness.checks).isNotEmpty()) add("$name checks repeat")
}

private fun MutableList<String>.submission(name: String, submission: SubmissionRef) {
    if (!ModelName.matches(submission.model) || submission.id <= 0) {
        add("$name submission must be a model name and a positive id")
    }
}

private fun MutableList<String>.text(name: String, value: String, max: Int) {
    if (value.isBlank() || value.length > max || value.any { it < ' ' || it == '\u007f' }) {
        add("$name must be 1..$max printable characters")
    }
}

private fun <T> duplicates(values: List<T>): Set<T> = values.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
