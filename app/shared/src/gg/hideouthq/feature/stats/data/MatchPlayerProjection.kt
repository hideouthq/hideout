package gg.hideouthq.feature.stats.data

import gg.hideouthq.core.data.deadlockstats.MatchItemEvent
import gg.hideouthq.core.data.deadlockstats.MatchParticipant
import gg.hideouthq.core.data.deadlockstats.PlayerProfile
import gg.hideouthq.feature.stats.AbilityOrder
import gg.hideouthq.feature.stats.AbilityRow
import gg.hideouthq.feature.stats.AbilityStep
import gg.hideouthq.feature.stats.BuildOrder
import gg.hideouthq.feature.stats.EnemyContact
import gg.hideouthq.feature.stats.ItemCategory
import gg.hideouthq.feature.stats.ItemFate
import gg.hideouthq.feature.stats.ItemVisual
import gg.hideouthq.feature.stats.MatchPlayer
import gg.hideouthq.feature.stats.MatchStat
import gg.hideouthq.feature.stats.Purchase
import gg.hideouthq.feature.stats.RankedStat

internal fun matchPlayers(records: MatchRecords, viewer: Long, profiles: Map<Long, PlayerProfile>, reference: StatsReference): List<MatchPlayer> {
    val players = records.detail.players
    val raw = players.map { statsOf(it, records) }
    val ranks = MatchStat.entries.associateWith { stat -> competitionRanks(raw.map { it[stat] }, stat.lowestFirst) }
    val mostDamage = raw.mapNotNull { it[MatchStat.HeroDamage] }.maxOrNull()?.takeIf { it > 0 }
    return players.mapIndexed { index, player ->
        val purchases = buildOf(player, reference)
        MatchPlayer(
            player = profiles[player.accountId].toVisual(player.accountId),
            hero = reference.hero(player.heroId),
            team = teamOf(player.team),
            isYou = player.accountId == viewer,
            level = player.level,
            kills = player.kills,
            deaths = player.deaths,
            assists = player.assists,
            stats = MatchStat.entries.associateWith { stat -> RankedStat(raw[index][stat], ranks.getValue(stat)[index]) },
            damageShare = raw[index][MatchStat.HeroDamage]?.let { damage -> mostDamage?.let { damage / it } },
            items = purchases?.purchases.orEmpty().filter { it.fate == ItemFate.Kept }
                .sortedWith(compareBy<Purchase> { categoryOrder(it.item.category) }.thenBy { it.boughtAtS }).map { it.item },
            build = purchases,
            abilities = abilitiesOf(player, reference),
            enemies = enemiesOf(player, records, profiles, reference),
            souls = records.curveOf(player) { it.netWorth },
            damage = records.curveOf(player) { it.playerDamage },
        )
    }
}

private fun statsOf(player: MatchParticipant, records: MatchRecords): Map<MatchStat, Double?> {
    val last = records.latest(player)
    val minutes = records.durationS?.takeIf { it > 0 }?.let { it / 60.0 }
    val teamKills = records.detail.players.filter { it.team != null && it.team == player.team }.sumOfAll { it.kills }?.takeIf { it > 0 }
    val hit = last?.shotsHit
    val missed = last?.shotsMissed
    val bullets = last?.heroBulletsHit?.takeIf { it > 0 }
    val crits = last?.heroBulletsHitCrit
    val (timeDead, longestLife) = lifeOf(player, records.durationS)
    return mapOf(
        MatchStat.Souls to player.netWorth?.toDouble(),
        MatchStat.HeroDamage to last?.playerDamage?.toDouble(),
        MatchStat.Healing to last?.playerHealing?.toDouble(),
        MatchStat.KillShare to if (player.kills != null && player.assists != null && teamKills != null) (player.kills + player.assists).toDouble() / teamKills else null,
        MatchStat.DamagePerMinute to last?.playerDamage?.let { damage -> minutes?.let { damage / it } },
        MatchStat.DamageTaken to last?.playerDamageTaken?.toDouble(),
        MatchStat.Accuracy to if (hit != null && missed != null && hit + missed > 0) hit.toDouble() / (hit + missed) else null,
        MatchStat.Headshots to if (bullets != null && crits != null) crits.toDouble() / bullets else null,
        MatchStat.SoulsPerMinute to player.netWorth?.let { souls -> minutes?.let { souls / it } },
        MatchStat.LastHits to player.lastHits?.toDouble(),
        MatchStat.Denies to player.denies?.toDouble(),
        MatchStat.ObjectiveDamage to last?.bossDamage?.toDouble(),
        MatchStat.Level to player.level?.toDouble(),
        MatchStat.DamageMitigated to last?.damageMitigated?.toDouble(),
        MatchStat.Creeps to last?.creepKills?.toDouble(),
        MatchStat.Neutrals to last?.neutralKills?.toDouble(),
        MatchStat.TimeDead to timeDead,
        MatchStat.LongestLife to longestLife,
    )
}

internal fun lifeOf(player: MatchParticipant, durationS: Int?): Pair<Double?, Double?> {
    val events = player.deathDetails ?: return null to null
    if (durationS == null || !player.deathsRecorded() || events.any { it.deathDurationS == null }) return null to null
    var alive = 0
    var dead = 0
    var longest = 0
    events.sortedBy { it.gameTimeS }.forEach { event ->
        val died = event.gameTimeS!!.coerceIn(0, durationS)
        val length = event.deathDurationS!!.coerceAtLeast(0)
        longest = maxOf(longest, died - alive)
        dead += length
        alive = maxOf(alive, (died + length).coerceAtMost(durationS))
    }
    longest = maxOf(longest, durationS - alive)
    return dead.toDouble() to longest.toDouble()
}

internal fun competitionRanks(values: List<Double?>, lowestFirst: Boolean): List<Int?> = values.map { value ->
    value?.let { mine -> 1 + values.count { other -> other != null && if (lowestFirst) other < mine else other > mine } }
}

private fun categoryOrder(category: ItemCategory?): Int = when (category) {
    ItemCategory.Weapon -> 0
    ItemCategory.Vitality -> 1
    ItemCategory.Spirit -> 2
    null -> 3
}

/**
 * Shop purchases in order. An entry is a purchase when its upgrade id is 1; an older record without the id is one only
 * when the item list gives it a shop category, and one the loaded list does not know is counted as unresolved. A sale
 * time with flags 1 is an upgrade into another item, with flags 0 a sale; other flags say only that it was gone.
 */
internal fun buildOf(player: MatchParticipant, reference: StatsReference): BuildOrder? {
    var unresolved = 0
    val purchases = player.items.filter { it.gameTimeS != null && it.itemId != null }.mapNotNull { event ->
        val asset = reference.itemAsset(event.itemId)
        val purchase = when (event.upgradeId) {
            1 -> true
            null -> if (asset == null) false.also { if (reference.hasItems) unresolved++ } else asset.category() != null
            else -> false
        }
        if (!purchase) return@mapNotNull null
        val gone = event.soldTimeS?.takeIf { it > 0 }
        Purchase(
            item = reference.item(event.itemId) ?: ItemVisual(event.itemId!!, "", null),
            boughtAtS = event.gameTimeS!!,
            fate = when {
                gone == null -> ItemFate.Kept
                event.flags == 1L -> ItemFate.Upgraded
                event.flags == 0L -> ItemFate.Sold
                else -> ItemFate.Removed
            },
            goneAtS = gone,
        )
    }.sortedBy { it.boughtAtS }
    if (purchases.isEmpty() && unresolved == 0) return null
    return BuildOrder(purchases, unresolved)
}

/**
 * Ability unlocks and upgrades: entries whose upgrade id is not 1, or older ones naming a signature ability, with the tier
 * in the top half of `upgrade_info` (1 unlocked, 3, 7, 15 for tiers 1–3). Rows are the hero's signature slots 1–4; an
 * ability the hero data does not place gets an unnumbered row of its own rather than a guessed slot.
 */
internal fun abilitiesOf(player: MatchParticipant, reference: StatsReference): AbilityOrder? {
    val signatures = reference.signatures(player.heroId)
    fun classOf(event: MatchItemEvent): String? = reference.itemAsset(event.itemId)?.className
    val steps = player.items.mapNotNull { event ->
        val time = event.gameTimeS ?: return@mapNotNull null
        if (event.itemId == null) return@mapNotNull null
        val ability = when (event.upgradeId) {
            1 -> false
            null -> classOf(event)?.let { it in signatures.values } == true
            else -> true
        }
        val tier = tierOf(event.upgradeInfo).takeIf { ability } ?: return@mapNotNull null
        Triple(time, event, tier)
    }.sortedBy { it.first }
    if (steps.isEmpty()) return null
    val rows = mutableListOf<AbilityRow>()
    val rowOf = mutableMapOf<Any, Int>()
    signatures.toSortedMap().forEach { (slot, className) ->
        val asset = reference.itemAssetByClass(className)
        rowOf[className] = rows.size
        rows += AbilityRow(slot, asset?.name, asset?.image)
    }
    steps.forEach { (_, event, _) ->
        val key: Any = classOf(event)?.takeIf { it in rowOf } ?: event.itemId!!
        if (key !in rowOf) {
            val asset = reference.itemAsset(event.itemId)
            rowOf[key] = rows.size
            rows += AbilityRow(null, asset?.name, asset?.image)
        }
    }
    return AbilityOrder(rows, steps.map { (time, event, tier) -> AbilityStep(time, rowOf.getValue(classOf(event)?.takeIf { it in rowOf } ?: event.itemId!!), tier) })
}

private fun tierOf(upgradeInfo: Long?): Int? = when (upgradeInfo?.shr(16)) {
    1L -> 0
    3L -> 1
    7L -> 2
    15L -> 3
    else -> null
}

private fun enemiesOf(player: MatchParticipant, records: MatchRecords, profiles: Map<Long, PlayerProfile>, reference: StatsReference): List<EnemyContact> {
    val team = player.team ?: return emptyList()
    val deaths = records.deaths.orEmpty()
    return records.detail.players.filter { it.team != null && it.team != team }.map { enemy ->
        EnemyContact(
            player = profiles[enemy.accountId].toVisual(enemy.accountId),
            hero = reference.hero(enemy.heroId),
            kills = if (enemy.deathsRecorded() && records.resolvable(player)) deaths.count { it.victim == enemy.accountId && it.killer == player.accountId } else null,
            deaths = if (player.deathsRecorded() && records.resolvable(enemy)) deaths.count { it.victim == player.accountId && it.killer == enemy.accountId } else null,
        )
    }.sortedWith(compareBy<EnemyContact> { it.kills == null && it.deaths == null }.thenByDescending { (it.kills ?: 0) + (it.deaths ?: 0) })
}
