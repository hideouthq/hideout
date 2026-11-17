package gg.hideouthq.core.data.deadlockstats

import gg.hideouthq.core.data.deadlock.proto.*
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal class SteamGcInvalidMatch(matchId: Long) : IllegalStateException("Steam match $matchId is invalid")

object SteamGcCodec {
    const val MaxFrameBytes = 4 * 1024 * 1024
    data class Message(val type: Int, val job: Long?, val body: ByteArray)

    fun decode(frame: ByteArray): Message {
        require(frame.size in 8..MaxFrameBytes) { "Invalid GC frame size" }
        val buffer = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN)
        val type = buffer.int
        require(type and Int.MIN_VALUE != 0) { "Expected protobuf GC message" }
        val length = buffer.int
        require(length in 0..minOf(buffer.remaining(), 64 * 1024)) { "Invalid GC header size" }
        val header = SteamGcHeader.ADAPTER.decode(ByteArray(length).also(buffer::get))
        return Message(type and Int.MAX_VALUE, header.job_id_target,
            ByteArray(buffer.remaining()).also(buffer::get))
    }

    fun history(body: ByteArray): SteamHistoryPage {
        val response = CMsgClientToGCGetMatchHistoryResponse.ADAPTER.decode(body)
        result(response.result?.value, 5, "history")
        require(response.matches.size <= 1000) { "Oversized history page" }
        val matches = response.matches.map { m ->
            MatchHistoryEntry(matchId = requireNotNull(m.match_id).also { require(it > 0) },
                heroId = m.hero_id, heroLevel = m.hero_level, startTime = m.start_time,
                gameMode = m.game_mode, matchMode = m.match_mode, playerTeam = m.player_team,
                kills = m.player_kills, deaths = m.player_deaths, assists = m.player_assists,
                lastHits = m.last_hits, denies = m.denies, netWorth = m.net_worth,
                matchDurationS = m.match_duration_s, matchResult = m.match_result, playerMatchOutcome = m.player_match_outcome,
                rankedDisplayBadge = m.ranked_display_badge, abandonedTimeS = m.abandoned_time_s, rankedCalibrationMatch = m.ranked_calibration_match)
        }
        return SteamHistoryPage(matches, response.continue_cursor ?: 0)
    }

    fun metadata(body: ByteArray, matchId: Long): ReplayMetadataRef {
        val response = CMsgClientToGCGetMatchMetaDataResponse.ADAPTER.decode(body)
        if (response.result == CMsgClientToGCGetMatchMetaDataResponse.EResult.k_eResult_InvalidMatch) throw SteamGcInvalidMatch(matchId)
        result(response.result?.value, 5, "metadata")
        val salt = requireNotNull(response.metadata_salt).toLong() and 0xffffffffL
        val group = requireNotNull(response.replay_group_id).toLong() and 0xffffffffL
        return ReplayMetadataRef(matchId, group, salt, "http://replay$group.valve.net/1422450/${matchId}_$salt.meta.bz2")
    }

    private fun result(code: Int?, limited: Int, name: String) {
        if (code == limited) throw SteamGcRateLimited(name)
        check(code == 1) { "Steam $name result ${code ?: 0}" }
    }
}
