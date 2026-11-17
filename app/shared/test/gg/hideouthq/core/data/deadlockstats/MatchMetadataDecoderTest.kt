package gg.hideouthq.core.data.deadlockstats

import gg.hideouthq.core.data.deadlock.proto.CMsgMatchMetaData
import gg.hideouthq.core.data.deadlock.proto.CMsgMatchMetaDataContents
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MatchMetadataDecoderTest {
    @Test
    fun decodesARealisticMatchIntoDomainTypes() {
        val contents = CMsgMatchMetaDataContents(
            match_info = CMsgMatchMetaDataContents.MatchInfo(
                duration_s = 1800,
                match_outcome = 0,
                winning_team = 1,
                start_time = 1_700_000_000,
                match_id = 123456789L,
                game_mode = 1,
                match_mode = 4,
                players = listOf(
                    CMsgMatchMetaDataContents.Players(
                        account_id = 123456,
                        team = 0,
                        kills = 10,
                        deaths = 2,
                        assists = 5,
                        net_worth = 20000,
                        hero_id = 1,
                        last_hits = 120,
                        denies = 8,
                        level = 20,
                        player_match_outcome = 1,
                        player_rank_data = CMsgMatchMetaDataContents.PlayerRankData(
                            initial_display_rank = 55,
                        ),
                        items = listOf(
                            CMsgMatchMetaDataContents.Items(game_time_s = 120, item_id = 1000, sold_time_s = null,
                                flags = 1, upgrade_info = 458752),
                        ),
                        stats = listOf(
                            CMsgMatchMetaDataContents.PlayerStats(time_stamp_s = 60, net_worth = 500, kills = 1),
                        ),
                    ),
                ),
                objectives = listOf(
                    CMsgMatchMetaDataContents.Objective(legacy_objective_id = 9, destroyed_time_s = 1500, team = 1),
                ),
            ),
        )
        val envelope = CMsgMatchMetaData(version = 1, match_details = contents.encodeByteString(), match_id = 123456789L)
        val compressed = bzip2(envelope.encode())

        val detail = MatchMetadataDecoder.decode(compressed)

        assertEquals(123456789L, detail.matchId)
        assertEquals(1800, detail.durationS)
        assertEquals(0, detail.matchOutcome)
        assertEquals(1, detail.winningTeam)
        val player = detail.players.single()
        assertEquals(123456L, player.accountId)
        assertEquals(10, player.kills)
        assertEquals(55, player.rank?.initialDisplayRank)
        assertEquals(1000, player.items.single().itemId)
        assertEquals(1L, player.items.single().flags)
        assertEquals(458752L, player.items.single().upgradeInfo)
        assertEquals(500, player.statSnapshots.single().netWorth)
        assertEquals(9, detail.objectives.single().legacyObjectiveId)
    }

    @Test
    fun decodesAZstdCompressedPayloadDespiteTheBz2Extension() {
        val contents = CMsgMatchMetaDataContents(
            match_info = CMsgMatchMetaDataContents.MatchInfo(duration_s = 600, match_id = 42L),
        )
        val envelope = CMsgMatchMetaData(match_details = contents.encodeByteString())
        val compressed = zstd(envelope.encode())

        val detail = MatchMetadataDecoder.decode(compressed)

        assertEquals(42L, detail.matchId)
        assertEquals(600, detail.durationS)
    }

    @Test
    fun accountIdsAboveIntMaxValueSurviveAsUnsignedLongsAndMissingFieldsStayNull() {
        val aboveIntMax = -1
        val contents = CMsgMatchMetaDataContents(
            match_info = CMsgMatchMetaDataContents.MatchInfo(
                match_id = 1L,
                players = listOf(CMsgMatchMetaDataContents.Players(account_id = aboveIntMax)),
            ),
        )
        val envelope = CMsgMatchMetaData(match_details = contents.encodeByteString())

        val detail = MatchMetadataDecoder.decode(bzip2(envelope.encode()))

        assertEquals(4_294_967_295L, detail.players.single().accountId)
        assertNull(detail.durationS)
        assertNull(detail.winningTeam)
        assertNull(detail.players.single().kills)
        assertFalse(detail.isComplete)
    }

    @Test
    fun rejectsAPayloadThatIsNeitherBzip2NorZstd() {
        val failure = assertFailsWith<MatchMetadataDecodeException> { MatchMetadataDecoder.decode(byteArrayOf(1, 2, 3, 4)) }
        assertTrue(failure.message!!.contains("neither bzip2 nor zstd"))
        assertFailsWith<MatchMetadataDecodeException> { MatchMetadataDecoder.decode(ByteArray(0)) }
    }

    @Test
    fun rejectsATruncatedBzip2Payload() {
        val envelope = CMsgMatchMetaData(match_details = CMsgMatchMetaDataContents().encodeByteString())
        val compressed = bzip2(envelope.encode())
        val truncated = compressed.copyOf(compressed.size / 2)
        assertFailsWith<MatchMetadataDecodeException> { MatchMetadataDecoder.decode(truncated) }
    }

    @Test
    fun rejectsACompressedPayloadOverTheSizeLimit() {
        val tooBig = ByteArray(MatchMetadataDecoder.MaxCompressedBytes + 1) { 'B'.code.toByte() }
        val failure = assertFailsWith<MatchMetadataDecodeException> { MatchMetadataDecoder.decode(tooBig) }
        assertTrue(failure.message!!.contains("limit"))
    }

    @Test
    fun missingMatchDetailsFailsClearly() {
        val envelope = CMsgMatchMetaData(version = 1)
        val failure = assertFailsWith<MatchMetadataDecodeException> { MatchMetadataDecoder.decode(bzip2(envelope.encode())) }
        assertTrue(failure.message!!.contains("match_details"))
    }

    @Test
    fun decodesDeathDetailsAtTheirRealWireTags() {
        val contents = CMsgMatchMetaDataContents(
            match_info = CMsgMatchMetaDataContents.MatchInfo(
                match_id = 118933077L,
                players = listOf(
                    CMsgMatchMetaDataContents.Players(
                        account_id = 111, player_slot = 10,
                        death_details = listOf(
                            CMsgMatchMetaDataContents.Deaths(game_time_s = 227, killer_player_slot = 5, death_duration_s = 8),
                        ),
                    ),
                ),
            ),
        )
        val envelope = CMsgMatchMetaData(match_details = contents.encodeByteString())

        val detail = MatchMetadataDecoder.decode(bzip2(envelope.encode()))

        val player = detail.players.single()
        assertEquals(10, player.playerSlot)
        assertEquals(listOf(MatchDeathEvent(227, 5, 8)), player.deathDetails)
    }

    @Test
    fun aPlayerWithNoEncodedDeathsGetsAnEmptyListNotNull() {
        val contents = CMsgMatchMetaDataContents(
            match_info = CMsgMatchMetaDataContents.MatchInfo(
                match_id = 1L,
                players = listOf(CMsgMatchMetaDataContents.Players(account_id = 7, player_slot = 1)),
            ),
        )
        val envelope = CMsgMatchMetaData(match_details = contents.encodeByteString())

        val detail = MatchMetadataDecoder.decode(bzip2(envelope.encode()))

        assertEquals(emptyList(), detail.players.single().deathDetails, "protobuf always decodes the full list: no events means none happened")
    }

    private fun zstd(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        com.github.luben.zstd.ZstdOutputStream(out).use { it.write(bytes) }
        return out.toByteArray()
    }
}
