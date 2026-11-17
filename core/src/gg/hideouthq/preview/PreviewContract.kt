package gg.hideouthq.preview

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The only preview schema this code reads. Documents with any other `schema` are rejected whole. */
const val PREVIEW_SCHEMA: Int = 1

/** A GameBanana submission, by its API model name ("Mod") and numeric id. */
@Serializable
data class SubmissionRef(val model: String, val id: Long)

/** The published list of bundles, one per exact submission file. */
@Serializable
data class PreviewIndex(
    val schema: Int,
    val generation: Long,
    val publishedAt: String,
    val entries: List<IndexEntry>,
)

/** One exact submission file's bundle. Both readiness states are always ready; any other value fails the index. */
@Serializable
data class IndexEntry(
    val submission: SubmissionRef,
    val fileId: Long,
    val fileMd5: String? = null,
    val bundleId: String,
    val manifestSha256: String,
    val build: Long,
    val picture: ReadinessState,
    val interactive: ReadinessState,
)

/** The only readiness the producer publishes. */
@Serializable
enum class ReadinessState {
    @SerialName("ready") Ready,
}

/** Which checks approved the pictures or the interactive model. */
@Serializable
data class Readiness(
    val state: ReadinessState,
    val checks: List<String> = emptyList(),
)

@Serializable
enum class Fidelity {
    @SerialName("studio_approximation") StudioApproximation,
}

/** Everything one conversion produced for one [InputIdentity]. Never rewritten under the same [bundleId]'s assets. */
@Serializable
data class BundleManifest(
    val schema: Int,
    val bundleId: String,
    val input: InputIdentity,
    val createdAt: String,
    val fidelity: Fidelity,
    val attribution: String,
    val assets: List<PreviewAsset>,
    val scene: PreviewScene? = null,
    val clips: List<PreviewClip> = emptyList(),
    val missingClips: List<String> = emptyList(),
    val abilities: List<PreviewAbility> = emptyList(),
    val pictures: List<PreviewPicture> = emptyList(),
    val picture: Readiness,
    val interactive: Readiness,
    val capabilities: SceneCapabilities = SceneCapabilities(),
)

/** The complete input of a conversion. [bundleIdOf] hashes it, so any change makes a different bundle. */
@Serializable
data class InputIdentity(
    val game: GameInput,
    val hero: String,
    val submission: SubmissionRef,
    val file: FileInput,
    val converter: ConverterInput,
    val recipeSha256: String,
    val dependencies: List<DependencyInput>,
)

@Serializable
data class DependencyInput(val submission: SubmissionRef, val file: FileInput)

/** The frozen game snapshot: Steam build, depot id to manifest id, and the hash of the frozen archive set. */
@Serializable
data class GameInput(
    val appId: Long,
    val build: Long,
    val depots: Map<String, String>,
    val snapshotSha256: String,
)

/** The exact GameBanana file and the VPK taken from it. */
@Serializable
data class FileInput(
    val id: Long,
    val md5: String? = null,
    val sha256: String,
    val bytes: Long,
    val vpk: String,
    val vpkSha256: String,
    val archiveMembers: List<String>? = null,
)

/** The pinned converter commit and the container image digests that converted and captured. */
@Serializable
data class ConverterInput(
    val vrfCommit: String,
    val convertImage: String,
    val captureImage: String,
)

/** A file in the bundle. [bytes] and [sha256] always describe the stored bytes. */
@Serializable
data class PreviewAsset(
    val path: String,
    val bytes: Long,
    val sha256: String,
    val type: String,
    val encoding: AssetEncoding? = null,
)

/** Whole-file model encoding. Decoded metadata describes the GLB, its textures packed in, before native Meshopt decoding. */
@Serializable
data class AssetEncoding(
    val codec: String,
    val decodedBytes: Long,
    val decodedSha256: String,
)

/** What the exported model needs from a renderer. [model] is the path of a listed GLB. */
@Serializable
data class PreviewScene(
    val model: String,
    val maxJointsPerSkin: Int,
    val influenceSets: Int,
    val morphTargets: Int,
    val extensionsRequired: List<String> = emptyList(),
)

/**
 * An animation exported into the scene model, by its exact glTF name. [source] is the compiled clip the converter loaded,
 * absent when the clip has no file of its own. A mod can own a source whose bytes equal the game's, so [changedByMod] is
 * the converter's comparison with the frozen game's clip at the same path: true only for different bytes, or a clip the
 * game does not have.
 */
@Serializable
data class PreviewClip(
    val id: String,
    val durationMillis: Long,
    val category: AnimationCategory? = null,
    val source: ResourceSource? = null,
    val changedByMod: Boolean = false,
)

@Serializable
enum class AnimationCategory {
    @SerialName("idle") Idle,
    @SerialName("movement") Movement,
    @SerialName("combat") Combat,
    @SerialName("abilities") Abilities,
    @SerialName("poses") Poses,
}

/** A compiled game resource as the converter's loader resolved it, by its package entry path and content hash. */
@Serializable
data class ResourceSource(val owner: ResourceOwner, val path: String, val sha256: String)

@Serializable
enum class ResourceOwner {
    @SerialName("mod") Mod,
    @SerialName("game") Game,
}

/** A listed raster image rendered from [source]. [changedByMod] is true only when the mod's bytes differ from the game's. */
@Serializable
data class PreviewIcon(val asset: String, val source: ResourceSource, val changedByMod: Boolean = false)

/**
 * One of the hero's four signature abilities. [icon] is what the game would show with the mod installed. [vanillaIcon] is the
 * game's own icon, present only when the mod's icon is changed.
 */
@Serializable
data class PreviewAbility(
    val slot: Int,
    val id: String,
    val name: String,
    val clipId: String? = null,
    val icon: PreviewIcon,
    val vanillaIcon: PreviewIcon? = null,
)

@Serializable
enum class PictureRole {
    @SerialName("featured") Featured,
    @SerialName("detail") Detail,
}

/** A captured still. [clip] and [timeMillis] give the pose; no clip means the bind pose at time zero. */
@Serializable
data class PreviewPicture(
    val role: PictureRole,
    val asset: String,
    val clip: String? = null,
    val timeMillis: Long = 0,
    val width: Int,
    val height: Int,
    val azimuth: Double,
    val lighting: String,
    val hiddenMeshSuffixes: List<String> = emptyList(),
)

/** Scene contents a viewer may offer controls for. Absent means not available. */
@Serializable
data class SceneCapabilities(
    val weapon: Boolean = false,
    val effects: Boolean = false,
    val styles: Boolean = false,
)
