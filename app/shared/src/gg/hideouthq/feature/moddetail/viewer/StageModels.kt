package gg.hideouthq.feature.moddetail.viewer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import gg.hideouthq.core.model.Backdrop
import gg.hideouthq.core.model.SceneLighting
import gg.hideouthq.core.model.SceneSettings
import gg.hideouthq.feature.moddetail.FileSelection
import gg.hideouthq.preview.BundleManifest
import gg.hideouthq.preview.IndexEntry
import gg.hideouthq.preview.PictureRole
import gg.hideouthq.preview.PreviewClip
import gg.hideouthq.preview.PreviewPicture

@Immutable
data class StageLabels(
    val hint: String,
    /** The hint while a still stands in for a model that cannot be drawn. */
    val stillHint: String,
    val viewport: ViewportLabels,
    val toolbar: ToolbarLabels,
    val animations: AnimationLabels,
    val abilities: AbilityLabels,
    val loading: LoadingLabels,
    val scene: SceneLabels,
    /** The variants menu's header. */
    val variants: String,
)

/** The stage while a model loads, or after it failed to. */
@Immutable
class LoadingLabels(
    /** "Loading Silver · Default". */
    val title: String,
    val failed: String,
    val retry: String,
    val seeImages: String,
    /** "24.6 of 38.4 MB · 64%". */
    val progress: @Composable (doneBytes: Long, totalBytes: Long) -> String,
)

@Immutable
data class SceneLabels(
    val title: String,
    val reset: String,
    val backdrop: String,
    val lighting: String,
    val stage: String,
    val backdrops: Map<Backdrop, String>,
    val lightings: Map<SceneLighting, String>,
    val turntable: String,
    val bloom: String,
)

@Immutable
data class StageContent(
    val heroName: String,
    val styleName: String,
    /** The verified bundle to draw; null while its manifest loads, or when it has none. */
    val model: PreviewModel?,
    /** The bundle's manifest is still on its way, so the stage shows the model loading. */
    val pending: Boolean = false,
    /** The bundle's manifest could not be fetched. */
    val failed: Boolean = false,
    /** Nothing of the 3D preview is to be fetched yet, and the stage keeps to the picture. */
    val held: Boolean = false,
    /** While [held], whether there is a 3D preview to ask for. */
    val canLoad: Boolean = false,
    /** The bundle whose generated pictures the stage may show; null when the mod has none ready. */
    val pictureBundleId: String? = null,
    /** The mod's current files, offered in the variants menu when there is more than one. */
    val variants: List<StageVariant> = emptyList(),
    val selectedVariant: FileSelection? = null,
    /** The user's own backdrop, decoded, when the scene uses one. */
    val customBackdrop: ImageBitmap? = null,
)

/** One of the mod's files in the variants menu, by its own label; picking it keeps its MD5. */
@Immutable
data class StageVariant(val file: FileSelection, val label: String)

/** A bundle whose manifest was verified against its index [entry]: the scene, its real clips and its stills. */
@Immutable
data class PreviewModel(val entry: IndexEntry, val manifest: BundleManifest) {
    val clips: List<PreviewClip> get() = manifest.clips

    val catalogue: ViewerCatalogue by lazy { ViewerCatalogue(manifest.clips, manifest.abilities, stillOf(manifest.pictures)) }

    val fallbackPicture: PreviewPicture? get() = stillOf(manifest.pictures)

    val hiddenMeshSuffixes: List<String> get() = stillOf(manifest.pictures)?.hiddenMeshSuffixes.orEmpty()

    private fun stillOf(pictures: List<PreviewPicture>): PreviewPicture? =
        pictures.firstOrNull { it.role == PictureRole.Detail } ?: pictures.firstOrNull { it.role == PictureRole.Featured }
}

val PreviewClip.label: String get() = animationLabel(id)

private val AnimationPhrases: List<Pair<List<String>, String>> = listOf(
    listOf("main", "menu") to "Main Menu",
    listOf("finding", "match") to "Finding Match",
    listOf("matchmaking") to "Finding Match",
    listOf("hero", "select") to "Hero Select",
    listOf("hero", "info") to "Hero Info",
    listOf("hero", "pose") to "Hero Pose",
    listOf("ui", "pose") to "Hero Pose",
    listOf("shop", "idle") to "Shop Idle",
    listOf("out", "of", "combat", "stand", "idle") to "Relaxed Idle",
    listOf("outofcombat", "stand", "idle") to "Relaxed Idle",
    listOf("item", "stand", "idle") to "Item Idle",
    listOf("melee", "quick") to "Light Melee",
    listOf("melee", "hit") to "Heavy Melee",
    listOf("melee", "start") to "Heavy Melee",
    listOf("parry") to "Parry",
    listOf("knockdown") to "Knocked Down",
    listOf("zipline") to "Zipline",
    listOf("mantle") to "Mantle",
    listOf("slide") to "Slide",
    listOf("dash", "ground") to "Dash",
    listOf("ground", "dash") to "Dash",
    listOf("crouch", "run") to "Crouch Walk",
    listOf("crouch", "walk") to "Crouch Walk",
    listOf("crouch", "idle") to "Crouching",
    listOf("crouch") to "Crouching",
    listOf("air", "dash") to "Air Dash",
    listOf("in", "air") to "Airborne",
    listOf("airborne") to "Airborne",
    listOf("jump") to "Jump",
    listOf("landing") to "Landing",
    listOf("land") to "Landing",
    listOf("reload") to "Reload",
    listOf("run") to "Running",
    listOf("idle") to "Idle",
)

private fun animationLabel(id: String): String {
    val tokens = id.substringAfterLast('/').lowercase().split('_').filter { it.isNotEmpty() }
    val phrase = AnimationPhrases.firstOrNull { (words, _) -> tokens.containsPhrase(words) }
    return phrase?.second ?: tokens.joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
}

private fun List<String>.containsPhrase(phrase: List<String>): Boolean =
    phrase.size <= size && (0..size - phrase.size).any { start -> phrase.indices.all { this[start + it] == phrase[it] } }

interface StageActions {
    fun pickCategory(category: ViewerCategory)
    fun playClip(id: String)
    fun stepClip(by: Int)
    fun playAbility(slot: Int)
    fun closeMenu()
    fun togglePlaying()
    fun animationFinished()
    fun cycleSpeed()
    fun setLoop(loop: Boolean)
    fun setScene(settings: SceneSettings)
    /** Asks for a picture of the user's own to use as the backdrop. */
    fun chooseBackdrop() = Unit
    fun closeScene()
    /** Shows another of the mod's files; the camera stays where it is. */
    fun pickVariant(file: FileSelection)
    fun loadPreview() = Unit
    /** Loads the model again after it failed. */
    fun retryPreview()
    fun showImages()
    /** Saves a picture of the stage that Save image drew. */
    fun savePicture(picture: ImageBitmap) = Unit
}

@Immutable
class StageChoreography(val resolve: Modifier, val model: Modifier, val toolbar: Modifier) {
    companion object {
        val None = StageChoreography(Modifier, Modifier, Modifier)
    }
}
