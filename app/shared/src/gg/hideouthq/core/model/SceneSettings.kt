package gg.hideouthq.core.model

/**
 * How the 3D viewer stages a model (the Scene panel). One choice for every mod, kept across restarts. [customBackdrop]
 * is the user's own picture, copied into Hideout's data folder; it stays when another backdrop is picked or the scene
 * is reset, so "Your image" can come back to it.
 */
data class SceneSettings(
    val backdrop: Backdrop = Backdrop.Studio,
    val lighting: SceneLighting = SceneLighting.Studio,
    val turntable: Boolean = true,
    val bloom: Boolean = false,
    val customBackdrop: String? = null,
) {
    /** Studio and Studio, the turntable on and bloom off; the user's own picture is kept. */
    fun reset(): SceneSettings = SceneSettings(customBackdrop = customBackdrop)

    /** The backdrop to draw: [Backdrop.Custom] without a picture of the user's falls back to Studio. */
    val shownBackdrop: Backdrop get() = if (backdrop == Backdrop.Custom && customBackdrop == null) Backdrop.Studio else backdrop
}

/** [photo] backdrops are pictures, drawn under a darkening gradient with the turntable a little transparent. */
enum class Backdrop(val photo: Boolean) {
    Studio(false),
    Greenech(true),
    Broadway(true),
    York(true),
    Custom(true),
}

/** Lights for the model and the spotlight cone over it: Studio is the viewer's original lighting. */
enum class SceneLighting { Soft, Studio, Dramatic }
