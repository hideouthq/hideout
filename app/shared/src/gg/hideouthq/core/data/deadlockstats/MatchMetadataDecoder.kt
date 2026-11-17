package gg.hideouthq.core.data.deadlockstats

import gg.hideouthq.core.data.deadlock.proto.CMsgMatchMetaData
import gg.hideouthq.core.data.deadlock.proto.CMsgMatchMetaDataContents
import com.github.luben.zstd.ZstdInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/** The payload this backend was asked to decode is not a match-metadata file it understands. */
class MatchMetadataDecodeException(message: String) : Exception(message)

/**
 * Decodes the `.meta.bz2` (sometimes actually zstd, see below) payload Valve's replay CDN serves
 * for a match into this backend's [MatchDetail]. Two steps, both load-bearing:
 *
 * 1. Decompress. Valve kept the `.meta.bz2` file name but, per deadlock-api's own
 *    `decompress_metadata` comment, switched some matches' compression to zstd without changing
 *    the extension — so the real container is sniffed from magic bytes, not trusted from the name.
 * 2. Decode the generated [CMsgMatchMetaData] wrapper, then its `match_details` bytes a second time
 *    as [CMsgMatchMetaDataContents] — the same two-pass decode deadlock-api's own
 *    `parse_match_metadata_raw` performs.
 *
 * Every size here is bounded: this runs on bytes from an untrusted CDN response, so neither a
 * truncated/partial file nor a decompression bomb can grow unbounded memory.
 */
object MatchMetadataDecoder {
    const val MaxCompressedBytes = 8 * 1024 * 1024

    /** Decompressed output bigger than this aborts the read — a real match's metadata is a few MB. */
    const val MaxDecompressedBytes = 64 * 1024 * 1024

    /** A player with more items, stat snapshots or deaths than this is refused; a match has at most a few hundred of each. */
    const val MaxEventsPerPlayer = 4_096

    private val Bzip2Magic = byteArrayOf('B'.code.toByte(), 'Z'.code.toByte(), 'h'.code.toByte())
    private val ZstdMagic = byteArrayOf(0x28, 0xB5.toByte(), 0x2F, 0xFD.toByte())

    /** Decodes a compressed match-metadata payload straight into a [MatchDetail]. */
    fun decode(compressed: ByteArray): MatchDetail {
        val (envelope, contents) = try {
            val envelope = CMsgMatchMetaData.ADAPTER.decode(decompress(compressed))
            val details = envelope.match_details ?: throw MatchMetadataDecodeException("CMsgMatchMetaData has no match_details")
            envelope to CMsgMatchMetaDataContents.ADAPTER.decode(details)
        } catch (e: IOException) {
            throw MatchMetadataDecodeException("could not decode the metadata: ${e.message}")
        } catch (e: IllegalStateException) {
            // Wire throws this, not IOException, for some malformed input such as a varint truncated at the end.
            throw MatchMetadataDecodeException("could not decode the metadata: ${e.message}")
        }
        return try {
            val detail = contents.toMatchDetail(preferredMatchId = envelope.match_id)
            require(envelope.match_id == null || contents.match_info?.match_id == null || envelope.match_id == contents.match_info?.match_id) { "Envelope and content match IDs differ" }
            detail.validated()
        } catch (e: IllegalArgumentException) {
            throw MatchMetadataDecodeException(e.message ?: "Invalid metadata")
        }
    }

    private fun decompress(compressed: ByteArray): ByteArray {
        if (compressed.size > MaxCompressedBytes) {
            throw MatchMetadataDecodeException("compressed payload is ${compressed.size} bytes, over the $MaxCompressedBytes limit")
        }
        return try {
            when {
                startsWith(compressed, ZstdMagic) -> ZstdInputStream(compressed.inputStream()).setLongMax(26)
                startsWith(compressed, Bzip2Magic) -> BZip2CompressorInputStream(compressed.inputStream())
                else -> throw MatchMetadataDecodeException("payload is neither bzip2 nor zstd (unrecognised magic bytes)")
            }.use(::readBounded)
        } catch (e: IOException) {
            throw MatchMetadataDecodeException("decompression failed: ${e.message}")
        }
    }

    private fun startsWith(bytes: ByteArray, magic: ByteArray): Boolean =
        bytes.size >= magic.size && magic.indices.all { bytes[it] == magic[it] }

    private fun readBounded(input: InputStream): ByteArray {
        val buffer = ByteArrayOutputStream()
        val chunk = ByteArray(64 * 1024)
        var total = 0
        while (true) {
            val read = input.read(chunk)
            if (read < 0) break
            total += read
            if (total > MaxDecompressedBytes) {
                throw MatchMetadataDecodeException("decompressed payload exceeds the $MaxDecompressedBytes byte limit")
            }
            buffer.write(chunk, 0, read)
        }
        return buffer.toByteArray()
    }
}

private fun Int.toUnsignedLong(): Long = toUInt().toLong()

private fun CMsgMatchMetaDataContents.toMatchDetail(preferredMatchId: Long?): MatchDetail {
    val info = requireNotNull(match_info) { "Missing match_info" }
    return MatchDetail(
        matchId = preferredMatchId ?: info.match_id ?: 0,
        durationS = info.duration_s,
        matchOutcome = info.match_outcome,
        winningTeam = info.winning_team,
        startTime = info.start_time,
        gameMode = info.game_mode,
        matchMode = info.match_mode,
        players = info.players.filter { it.account_id != null && it.account_id != 0 }.map { it.toParticipant() },
        objectives = info.objectives.map { it.toObjectiveEvent() },
        midBoss = info.mid_boss.map { MidBossEvent(it.team_killed, it.team_claimed, it.destroyed_time_s) },
        averageBadgeTeam0 = info.average_badge_team0,
        averageBadgeTeam1 = info.average_badge_team1,
    )
}

private fun CMsgMatchMetaDataContents.Players.toParticipant() = MatchParticipant(
    accountId = (account_id ?: 0).toUnsignedLong(),
    heroId = hero_id,
    playerSlot = player_slot,
    team = team,
    kills = kills,
    deaths = deaths,
    assists = assists,
    netWorth = net_worth,
    lastHits = last_hits,
    denies = denies,
    level = level,
    playerMatchOutcome = player_match_outcome,
    rank = player_rank_data?.let { MatchPlayerRank(it.initial_display_rank) },
    items = items.map { it.toItemEvent() },
    statSnapshots = stats.map { it.toStatSnapshot() }.sortedBy { it.timeStampS },
    deathDetails = death_details.map { MatchDeathEvent(it.game_time_s, it.killer_player_slot, it.death_duration_s) },
    abandonedTimeS = abandon_match_time_s,
)

private fun CMsgMatchMetaDataContents.Items.toItemEvent() = MatchItemEvent(
    gameTimeS = game_time_s,
    itemId = item_id?.toUnsignedLong(),
    upgradeId = upgrade_id,
    soldTimeS = sold_time_s,
    flags = flags?.toUnsignedLong(),
    upgradeInfo = upgrade_info?.toUnsignedLong(),
)

private fun CMsgMatchMetaDataContents.PlayerStats.toStatSnapshot() = MatchPlayerStatSnapshot(
    timeStampS = time_stamp_s,
    netWorth = net_worth,
    kills = kills,
    deaths = deaths,
    assists = assists,
    creepKills = creep_kills,
    neutralKills = neutral_kills,
    playerDamage = player_damage,
    denies = denies,
    playerHealing = player_healing,
    level = level,
    bossDamage = boss_damage,
    shotsHit = shots_hit,
    shotsMissed = shots_missed,
    heroBulletsHit = hero_bullets_hit,
    heroBulletsHitCrit = hero_bullets_hit_crit,
    damageMitigated = damage_mitigated,
    playerDamageTaken = player_damage_taken,
    selfHealing = self_healing,
)

private fun CMsgMatchMetaDataContents.Objective.toObjectiveEvent() = MatchObjectiveEvent(
    legacyObjectiveId = legacy_objective_id,
    destroyedTimeS = destroyed_time_s,
    teamObjectiveId = team_objective_id,
    team = team,
)
