package gg.hideouthq.core.data.deadlockstats

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PlayerProfile(
    @SerialName("account_id") val accountId: Long,
    @SerialName("personaname") val personaName: String? = null,
    @SerialName("profileurl") val profileUrl: String? = null,
    @SerialName("avatarfull") val avatarUrl: String? = null,
)

/** A selected account's result; absent fields survive as null across local/API reconciliation. */
@Serializable
data class MatchHistoryEntry(
    @SerialName("match_id") val matchId: Long,
    @SerialName("hero_id") val heroId: Int? = null,
    @SerialName("hero_level") val heroLevel: Int? = null,
    @SerialName("start_time") val startTime: Int? = null,
    @SerialName("game_mode") val gameMode: Int? = null,
    @SerialName("match_mode") val matchMode: Int? = null,
    @SerialName("player_team") val playerTeam: Int? = null,
    @SerialName("player_kills") val kills: Int? = null,
    @SerialName("player_deaths") val deaths: Int? = null,
    @SerialName("player_assists") val assists: Int? = null,
    val denies: Int? = null,
    @SerialName("net_worth") val netWorth: Int? = null,
    @SerialName("last_hits") val lastHits: Int? = null,
    @SerialName("match_duration_s") val matchDurationS: Int? = null,
    /** Winning team (0 or 1), NOT the metadata match_outcome enum. */
    @SerialName("match_result") val matchResult: Int? = null,
    @SerialName("player_match_outcome") val playerMatchOutcome: Int? = null,
    @SerialName("ranked_display_badge") val rankedDisplayBadge: Int? = null,
    @SerialName("abandoned_time_s") val abandonedTimeS: Int? = null,
    /** Non-zero when the match counted towards ranked calibration. */
    @SerialName("ranked_calibration_match") val rankedCalibrationMatch: Int? = null,
)

/** Latest recorded match rank, not live rank or hidden MMR. */
@Serializable
data class RankStatus(val badge: Int, val tier: Int, val subrank: Int, val lastMatchStartTime: Int? = null)

@Serializable
data class MatchItemEvent(
    @SerialName("game_time_s") val gameTimeS: Int? = null,
    @SerialName("item_id") val itemId: Long? = null,
    /** Upstream upgrade event value; not an item ID or proof of upgradedFrom. */
    @SerialName("upgrade_id") val upgradeId: Int? = null,
    @SerialName("sold_time_s") val soldTimeS: Int? = null,
    val flags: Long? = null,
    @SerialName("upgrade_info") val upgradeInfo: Long? = null,
)

@Serializable
data class MatchPlayerStatSnapshot(
    @SerialName("time_stamp_s") val timeStampS: Int? = null,
    @SerialName("net_worth") val netWorth: Int? = null,
    val kills: Int? = null,
    val deaths: Int? = null,
    val assists: Int? = null,
    @SerialName("creep_kills") val creepKills: Int? = null,
    @SerialName("neutral_kills") val neutralKills: Int? = null,
    @SerialName("player_damage") val playerDamage: Int? = null,
    val denies: Int? = null,
    @SerialName("player_healing") val playerHealing: Int? = null,
    val level: Int? = null,
    @SerialName("boss_damage") val bossDamage: Int? = null,
    @SerialName("shots_hit") val shotsHit: Int? = null,
    @SerialName("shots_missed") val shotsMissed: Int? = null,
    @SerialName("hero_bullets_hit") val heroBulletsHit: Int? = null,
    @SerialName("hero_bullets_hit_crit") val heroBulletsHitCrit: Int? = null,
    @SerialName("damage_mitigated") val damageMitigated: Int? = null,
    @SerialName("player_damage_taken") val playerDamageTaken: Int? = null,
    @SerialName("self_healing") val selfHealing: Int? = null,
)

/** Raw Valve objective codes are retained; map changes must not silently relabel events. */
@Serializable
data class MatchObjectiveEvent(
    @SerialName("legacy_objective_id") val legacyObjectiveId: Int? = null,
    @SerialName("destroyed_time_s") val destroyedTimeS: Int? = null,
    @SerialName("team_objective_id") val teamObjectiveId: Int? = null,
    val team: Int? = null,
)

@Serializable
data class MidBossEvent(
    @SerialName("team_killed") val teamKilled: Int? = null,
    @SerialName("team_claimed") val teamClaimed: Int? = null,
    @SerialName("destroyed_time_s") val destroyedTimeS: Int? = null,
)

@Serializable
data class MatchPlayerRank(@SerialName("initial_display_rank") val initialDisplayRank: Int? = null)

@Serializable
data class MatchDeathEvent(
    @SerialName("game_time_s") val gameTimeS: Int? = null,
    @SerialName("killer_player_slot") val killerPlayerSlot: Int? = null,
    @SerialName("death_duration_s") val deathDurationS: Int? = null,
)

@Serializable
data class MatchParticipant(
    @SerialName("account_id") val accountId: Long,
    @SerialName("hero_id") val heroId: Int? = null,
    @SerialName("player_slot") val playerSlot: Int? = null,
    val team: Int? = null,
    val kills: Int? = null,
    val deaths: Int? = null,
    val assists: Int? = null,
    @SerialName("net_worth") val netWorth: Int? = null,
    @SerialName("last_hits") val lastHits: Int? = null,
    val denies: Int? = null,
    val level: Int? = null,
    @SerialName("player_match_outcome") val playerMatchOutcome: Int? = null,
    @SerialName("player_rank_data") val rank: MatchPlayerRank? = null,
    val items: List<MatchItemEvent> = emptyList(),
    @SerialName("stats") val statSnapshots: List<MatchPlayerStatSnapshot> = emptyList(),
    /** Null means not yet extracted; an empty list means extraction ran and found none. */
    @SerialName("death_details") val deathDetails: List<MatchDeathEvent>? = null,
    @SerialName("abandon_match_time_s") val abandonedTimeS: Int? = null,
)

@Serializable
data class MatchDetail(
    @SerialName("match_id") val matchId: Long,
    @SerialName("duration_s") val durationS: Int? = null,
    @SerialName("match_outcome") val matchOutcome: Int? = null,
    @SerialName("winning_team") val winningTeam: Int? = null,
    @SerialName("start_time") val startTime: Int? = null,
    @SerialName("game_mode") val gameMode: Int? = null,
    @SerialName("match_mode") val matchMode: Int? = null,
    val players: List<MatchParticipant> = emptyList(),
    val objectives: List<MatchObjectiveEvent> = emptyList(),
    val fetchedAt: Long = 0,
    @SerialName("mid_boss") val midBoss: List<MidBossEvent> = emptyList(),
    @SerialName("average_badge_team0") val averageBadgeTeam0: Int? = null,
    @SerialName("average_badge_team1") val averageBadgeTeam1: Int? = null,
) {
    /** Metadata availability only; individual fields, players and timelines may still be missing. */
    val isComplete: Boolean get() = durationS != null && winningTeam != null && players.isNotEmpty()
}

@Serializable
data class PlayedWithStats(val otherAccountId: Long, val matchesPlayed: Long, val wins: Long, val matches: List<Long> = emptyList())

@Serializable
data class ItemAsset(
    val id: Long,
    @SerialName("class_name") val className: String,
    val name: String,
    @SerialName("item_slot_type") val category: String? = null,
    @SerialName("item_tier") val tier: Int? = null,
    val cost: Long? = null,
    @SerialName("shop_image_webp") val iconUrl: String? = null,
    val image: String? = null,
)

@Serializable
data class RankAsset(val tier: Int, val name: String, val color: String, val largeImageUrl: String? = null, val subrankImages: Map<Int, String> = emptyMap())

/** [lastLoginAt] is Steam's own `Timestamp` (Unix seconds), which current clients write instead of `MostRecent`. */
data class LocalSteamIdentity(val steamId64: Long, val personaName: String?, val mostRecent: Boolean, val lastLoginAt: Long? = null)

/** The flagged most recent identity, else the latest login, else the only one; null when several are equally plausible. */
fun List<LocalSteamIdentity>.mostRecentIdentity(): LocalSteamIdentity? =
    singleOrNull { it.mostRecent }
        ?: filter { it.lastLoginAt != null }.maxByOrNull { it.lastLoginAt!! }?.takeIf { latest -> count { it.lastLoginAt == latest.lastLoginAt } == 1 }
        ?: singleOrNull()

@Serializable
internal data class RankResponseDto(val badge: Int, val rank: Int, val subrank: Int, @SerialName("last_match") val lastMatch: LastRankedMatchDto? = null) {
    fun toDomain() = RankStatus(badge, rank, subrank, lastMatch?.startTime)
}

@Serializable
internal data class LastRankedMatchDto(@SerialName("start_time") val startTime: Int)

@Serializable
internal data class PlayedWithStatsDto(
    @SerialName("mate_id") val mateId: Long? = null,
    @SerialName("enemy_id") val enemyId: Long? = null,
    @SerialName("matches_played") val matchesPlayed: Long,
    val wins: Long,
    val matches: List<Long> = emptyList(),
) {
    fun toDomain() = PlayedWithStats(requireNotNull(mateId ?: enemyId), matchesPlayed, wins, matches)
}
