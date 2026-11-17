package gg.hideouthq.core.data.deadlockstats

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One per-match metric across a filtered population (`/v1/analytics/player-stats/metrics`). [avg] is the mean of
 * per-match values, not a ratio of sums, so it is a benchmark rather than a figure comparable to the last decimal.
 */
@Serializable
data class MetricDistribution(val avg: Double? = null)

object PlayerMetric {
    const val Kills = "kills"
    const val Deaths = "deaths"
    const val Assists = "assists"
    const val SoulsPerMin = "net_worth_per_min"
    const val HeroDamagePerMin = "player_damage_per_min"
    const val ObjectiveDamagePerMin = "boss_damage_per_min"
    /** A fraction in 0..1. */
    const val Accuracy = "accuracy"
    /** A fraction in 0..1. */
    const val CritRate = "crit_shot_rate"
}

/** Hero-versus-enemy-hero record for the filtered matches of one account (`/v1/analytics/hero-counter-stats`). */
@Serializable
data class HeroCounter(
    @SerialName("hero_id") val heroId: Int,
    @SerialName("enemy_hero_id") val enemyHeroId: Int,
    val wins: Long,
    @SerialName("matches_played") val matchesPlayed: Long,
)

/** Matches in which one account bought [itemId] at least once (`/v1/analytics/item-stats`); not purchase events. */
@Serializable
data class ItemUsageStat(@SerialName("item_id") val itemId: Long, val matches: Long, val wins: Long = 0, val losses: Long = 0)

/** Per-account hero totals from a batch `/v1/players/hero-stats` request, for "usually plays". */
@Serializable
data class AccountHeroCount(
    @SerialName("account_id") val accountId: Long,
    @SerialName("hero_id") val heroId: Int,
    @SerialName("matches_played") val matchesPlayed: Long,
)

@Serializable
data class SeasonInterval(@SerialName("start_timestamp") val start: Long, @SerialName("end_timestamp") val end: Long)

@Serializable
data class RankedSeason(
    val name: String,
    @SerialName("calibration_matches") val calibrationMatches: Int? = null,
    val intervals: List<SeasonInterval> = emptyList(),
) {
    /** The season's first start, provided one of its intervals contains [epochSeconds]. */
    fun startContaining(epochSeconds: Long): Long? =
        intervals.takeIf { list -> list.any { epochSeconds >= it.start && epochSeconds < it.end } }?.minOf { it.start }
}

/** Players whose latest ranked match in the window ended at [badge] (`/v1/players/rank/distribution`). */
@Serializable
data class RankDistributionEntry(val badge: Int, val players: Long)

/**
 * The selected account's final per-match numbers, without a timeline, items or roster.
 * A match with only a summary is not a loaded detail.
 */
@Serializable
data class MatchPlayerSummary(
    @SerialName("match_id") val matchId: Long,
    @SerialName("account_id") val accountId: Long,
    @SerialName("hero_id") val heroId: Int? = null,
    @SerialName("time_stamp_s") val timeStampS: Int? = null,
    @SerialName("player_damage") val playerDamage: Int? = null,
    @SerialName("player_healing") val playerHealing: Int? = null,
    @SerialName("boss_damage") val bossDamage: Int? = null,
    @SerialName("shots_hit") val shotsHit: Int? = null,
    @SerialName("shots_missed") val shotsMissed: Int? = null,
    @SerialName("hero_bullets_hit") val heroBulletsHit: Int? = null,
    @SerialName("hero_bullets_hit_crit") val heroBulletsHitCrit: Int? = null,
    @SerialName("damage_mitigated") val damageMitigated: Int? = null,
    @SerialName("player_damage_taken") val playerDamageTaken: Int? = null,
    @SerialName("self_healing") val selfHealing: Int? = null,
) {
    internal fun toSnapshot() = MatchPlayerStatSnapshot(
        timeStampS = timeStampS, playerDamage = playerDamage, playerHealing = playerHealing, bossDamage = bossDamage,
        shotsHit = shotsHit, shotsMissed = shotsMissed, heroBulletsHit = heroBulletsHit, heroBulletsHitCrit = heroBulletsHitCrit,
        damageMitigated = damageMitigated, playerDamageTaken = playerDamageTaken, selfHealing = selfHealing,
    )
}

internal fun MatchDetail.summaryFor(accountId: Long): MatchPlayerSummary? {
    if (!isComplete) return null
    val player = players.firstOrNull { it.accountId == accountId } ?: return null
    return player.latestStats()?.summary(matchId, accountId, player.heroId)
}

@Serializable
internal data class BulkMatchDto(@SerialName("match_id") val matchId: Long, val players: List<BulkPlayerDto> = emptyList()) {
    fun summaryFor(accountId: Long): MatchPlayerSummary? =
        players.firstOrNull { it.accountId == accountId }?.let { player -> player.finalStats?.summary(matchId, accountId, player.heroId) }
}

@Serializable
internal data class BulkPlayerDto(
    @SerialName("account_id") val accountId: Long,
    @SerialName("hero_id") val heroId: Int? = null,
    @SerialName("final_stats") val finalStats: MatchPlayerStatSnapshot? = null,
)

private fun MatchPlayerStatSnapshot.summary(matchId: Long, accountId: Long, heroId: Int?) = MatchPlayerSummary(
    matchId, accountId, heroId, timeStampS, playerDamage, playerHealing, bossDamage, shotsHit, shotsMissed, heroBulletsHit,
    heroBulletsHitCrit, damageMitigated, playerDamageTaken, selfHealing,
)
