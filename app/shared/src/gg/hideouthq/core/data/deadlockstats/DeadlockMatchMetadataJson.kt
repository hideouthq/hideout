package gg.hideouthq.core.data.deadlockstats

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class MatchMetadataJsonDto(@SerialName("match_info") val matchInfo: MatchDetail) {
    fun toMatchDetail(expectedMatchId: Long): MatchDetail {
        require(matchInfo.matchId == expectedMatchId) { "Metadata belongs to a different match" }
        return matchInfo.validated()
    }
}

internal fun MatchDetail.validated(): MatchDetail {
    require(matchId in 1..10_000_000_000L)
    require(players.size <= 64 && objectives.size <= 4096 && midBoss.size <= 4096)
    require(players.map { it.accountId }.distinct().size == players.size) { "Duplicate player account" }
    players.forEach { player ->
        require(SteamAccountId.ofAccountId(player.accountId) != null)
        require(player.items.size <= MatchMetadataDecoder.MaxEventsPerPlayer && player.statSnapshots.size <= MatchMetadataDecoder.MaxEventsPerPlayer)
        require((player.deathDetails?.size ?: 0) <= MatchMetadataDecoder.MaxEventsPerPlayer)
        require(player.items.all { it.itemId == null || it.itemId in 0..4_294_967_295L })
    }
    return this
}
