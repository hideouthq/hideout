package gg.hideouthq.core.model

import kotlinx.serialization.Serializable

/**
 * A Deadlock hero, as the Deadlock API describes it and the catalogue keeps it between runs. [id] is the game's class
 * name (`hero_inferno`), stable across renames; [iconUrl] is a small square portrait, if known;
 * [backgroundUrl] is the hero's key art, if known, for the Discover hero's background. [numericId] is the id match
 * data uses, [renderUrl] a transparent card render and [colorHex] the game's style colour (`#RRGGBB`), when known.
 * [signatureAbilities] maps ability slot 1..4 to the hero API's `items.signature1`..`signature4` class name.
 */
@Serializable
data class Hero(
    val id: String,
    val name: String,
    val iconUrl: String? = null,
    val backgroundUrl: String? = null,
    val numericId: Int? = null,
    val renderUrl: String? = null,
    val colorHex: String? = null,
    val nameImageUrl: String? = null,
    val signatureAbilities: Map<Int, String> = emptyMap(),
)
