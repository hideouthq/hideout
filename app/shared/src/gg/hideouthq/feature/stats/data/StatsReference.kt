package gg.hideouthq.feature.stats.data

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import gg.hideouthq.core.data.deadlockstats.ItemAsset
import gg.hideouthq.core.data.deadlockstats.PlayerProfile
import gg.hideouthq.core.data.deadlockstats.RankAsset
import gg.hideouthq.core.model.Hero
import gg.hideouthq.feature.stats.HeroVisual
import gg.hideouthq.feature.stats.ItemCategory
import gg.hideouthq.feature.stats.ItemVisual
import gg.hideouthq.feature.stats.PlayerVisual
import gg.hideouthq.feature.stats.RankVisual
import gg.hideouthq.feature.stats.Tint
import gg.hideouthq.feature.stats.heroInitials

internal class StatsReference(heroes: List<Hero>, items: List<ItemAsset>, ranks: List<RankAsset>) {
    private val heroes: Map<Int, HeroVisual> = heroes.mapNotNull { hero -> hero.numericId?.let { it to hero.toVisual(it) } }.toMap()
    private val signatures: Map<Int, Map<Int, String>> = heroes.mapNotNull { hero -> hero.numericId?.let { it to hero.signatureAbilities } }.toMap()
    private val items: Map<Long, ItemAsset> = items.associateBy { it.id }
    private val itemsByClass: Map<String, ItemAsset> = items.associateBy { it.className }
    private val ranks: Map<Int, RankAsset> = ranks.associateBy { it.tier }

    fun hero(id: Int?): HeroVisual? = id?.takeIf { it > 0 }?.let { heroes[it] ?: HeroVisual(it, "", "?") }

    fun item(id: Long?): ItemVisual? {
        val asset = id?.let(items::get) ?: return null
        return ItemVisual(asset.id, asset.name, asset.category(), asset.iconUrl, asset.cost)
    }

    val hasItems: Boolean get() = items.isNotEmpty()

    fun itemAsset(id: Long?): ItemAsset? = id?.let(items::get)

    fun itemAssetByClass(className: String): ItemAsset? = itemsByClass[className]

    fun signatures(heroId: Int?): Map<Int, String> = heroId?.let(signatures::get).orEmpty()

    fun rank(badge: Int?): RankVisual? {
        val value = badge?.takeIf { it > 0 } ?: return null
        val asset = ranks[value / 10]
        return RankVisual(value, asset?.name, asset?.subrankImages?.get(value % 10) ?: asset?.largeImageUrl)
    }
}

internal fun ItemAsset.category(): ItemCategory? = when (category?.lowercase()) {
    "weapon" -> ItemCategory.Weapon
    "vitality", "armor" -> ItemCategory.Vitality
    "spirit", "tech" -> ItemCategory.Spirit
    else -> null
}

internal fun Hero.toVisual(numericId: Int) = HeroVisual(
    id = numericId,
    name = name,
    initials = heroInitials(name),
    portraitUrl = iconUrl,
    renderUrl = renderUrl,
    artUrl = backgroundUrl,
    tint = colorHex?.let(::tintOf),
    catalogId = id,
)

private val TintBase = Color(0xFF1D1C1D)

internal fun tintOf(hex: String): Tint? {
    val value = hex.removePrefix("#").toLongOrNull(16)?.takeIf { hex.length == 7 } ?: return null
    val colour = Color(0xFF000000 or value)
    return Tint(container = lerp(TintBase, colour, 0.24f), content = lerp(colour, Color.White, 0.62f))
}

internal fun PlayerProfile?.toVisual(accountId: Long): PlayerVisual =
    PlayerVisual(accountId, this?.personaName?.trim().orEmpty(), this?.avatarUrl)
