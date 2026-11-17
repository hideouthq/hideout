package gg.hideouthq.core.data.deadlock

import gg.hideouthq.core.model.Hero
import gg.hideouthq.core.network.decodeLenient
import gg.hideouthq.core.network.getText
import io.ktor.client.HttpClient
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

internal class DeadlockApi(private val client: HttpClient, private val baseUrl: String = DefaultBaseUrl) {
    suspend fun heroes(): List<Hero> =
        decodeLenient(ListSerializer(HeroDto.serializer()), client.getText("$baseUrl/v1/assets/heroes"))
            .filter { it.playable }
            .map {
                Hero(
                    id = it.className,
                    name = it.name,
                    iconUrl = it.images.iconSmallWebp ?: it.images.iconSmall,
                    backgroundUrl = it.images.backgroundWebp ?: it.images.background,
                    numericId = it.id,
                    renderUrl = it.images.cardWebp ?: it.images.card,
                    colorHex = it.colors?.styleHex?.takeIf { hex -> hex.matches(HexColour) },
                    nameImageUrl = it.images.nameImage,
                    signatureAbilities = it.items.signatureAbilities(),
                )
            }

    companion object {
        const val DefaultBaseUrl = "https://api.deadlock-api.com"
    }
}

@Serializable
private class HeroDto(
    val id: Int? = null,
    @SerialName("class_name") val className: String,
    val name: String,
    val disabled: Boolean = false,
    @SerialName("development_state") val developmentState: String? = null,
    @SerialName("player_selectable") val playerSelectable: Boolean = false,
    val images: HeroImagesDto = HeroImagesDto(),
    val colors: HeroColorsDto? = null,
    val items: HeroItemsDto = HeroItemsDto(),
) {
    val playable: Boolean
        get() = !disabled && (developmentState?.let { it in PlayableStates } ?: playerSelectable)
}

private val PlayableStates = setOf("release", "pre_release")

private val HexColour = Regex("#[0-9A-Fa-f]{6}")

@Serializable
private class HeroColorsDto(@SerialName("style_hex") val styleHex: String? = null)

/** The hero's non-weapon/melee items; `signature1`..`signature4` are the four ability slots. */
@Serializable
private class HeroItemsDto(
    val signature1: String? = null,
    val signature2: String? = null,
    val signature3: String? = null,
    val signature4: String? = null,
) {
    fun signatureAbilities(): Map<Int, String> = buildMap {
        signature1?.let { put(1, it) }
        signature2?.let { put(2, it) }
        signature3?.let { put(3, it) }
        signature4?.let { put(4, it) }
    }
}

@Serializable
private class HeroImagesDto(
    @SerialName("icon_image_small") val iconSmall: String? = null,
    @SerialName("icon_image_small_webp") val iconSmallWebp: String? = null,
    @SerialName("background_image") val background: String? = null,
    @SerialName("background_image_webp") val backgroundWebp: String? = null,
    @SerialName("icon_hero_card") val card: String? = null,
    @SerialName("icon_hero_card_webp") val cardWebp: String? = null,
    @SerialName("name_image") val nameImage: String? = null,
)
