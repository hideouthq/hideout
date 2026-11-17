package gg.hideouthq.core.data.deadlockstats

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StatsEnrichmentQueriesTest {
    private fun entry(matchId: Long, startTime: Int, matchMode: Int = 1, won: Boolean = true, badge: Int? = null, k: Int = 6, d: Int = 2, a: Int = 4) = MatchHistoryEntry(
        matchId = matchId, heroId = 2, heroLevel = 30, startTime = startTime, gameMode = 1, matchMode = matchMode, playerTeam = 0,
        kills = k, deaths = d, assists = a, denies = 3, netWorth = 30_000, lastHits = 150, matchDurationS = 1800,
        matchResult = if (won) 0 else 1, rankedDisplayBadge = badge,
    )

    private fun summary(matchId: Long, damage: Int, hit: Int = 40, missed: Int = 60, boss: Int? = null) =
        MatchPlayerSummary(matchId, 1, playerDamage = damage, shotsHit = hit, shotsMissed = missed, bossDamage = boss)

    @Test
    fun summariesFillDamageAndAccuracyButAreNotLoadedDetails() {
        val state = DeadlockStatsState(
            accountId = 1,
            matchHistory = listOf(entry(1, 100), entry(2, 200), entry(3, 300)),
            matchSummaries = mapOf(1L to summary(1, 30_000, boss = 1_800), 2L to summary(2, 60_000, hit = 10, missed = 10)),
        )
        val rows = state.matches().rows.associateBy { it.matchId }
        assertEquals(30_000, rows.getValue(1).heroDamage)
        assertNull(rows.getValue(3).heroDamage, "no summary is unavailable, not zero")
        assertEquals(false, rows.getValue(1).hasDetail, "a summary is not a detail")
        val totals = state.periodSummary(MatchListFilter()).totals
        assertEquals(2, totals.heroDamage.matches)
        assertEquals(45_000.0, totals.heroDamage.average)
        assertEquals(90_000.0 * 60 / 3600, totals.heroDamagePerMin.value, "ratio of sums over matches with both numbers")
        assertEquals(50.0 / 120, totals.accuracy.value)
        assertEquals(1_800.0 * 60 / 1800, totals.objectiveDamagePerMin.value)
        assertEquals(1, totals.objectiveDamagePerMin.matches)
    }

    @Test
    fun aLoadedDetailWinsOverASummaryAndAnotherAccountsSummaryIsIgnored() {
        val detail = MatchDetail(1, durationS = 1800, winningTeam = 0, players = listOf(MatchParticipant(1, statSnapshots = listOf(MatchPlayerStatSnapshot(1800, playerDamage = 12_345)))))
        val state = DeadlockStatsState(
            accountId = 1, matchHistory = listOf(entry(1, 100), entry(2, 200)), matchDetails = mapOf(1L to detail),
            matchSummaries = mapOf(1L to summary(1, 1), 2L to summary(2, 99).copy(accountId = 2)),
        )
        val rows = state.matches().rows.associateBy { it.matchId }
        assertEquals(12_345, rows.getValue(1).heroDamage)
        assertNull(rows.getValue(2).heroDamage)
    }

    @Test
    fun rankBeforeIsTheBadgeAfterThePreviousTrackedRankedMatch() {
        val state = DeadlockStatsState(
            accountId = 1,
            matchHistory = listOf(
                entry(1, 100, matchMode = 4, badge = 72), entry(2, 200, matchMode = 1), entry(3, 300, matchMode = 4, badge = 73),
                entry(4, 400, matchMode = 4, badge = 73), entry(5, 500, matchMode = 4),
            ),
        )
        val rows = state.matches().rows.associateBy { it.matchId }
        assertNull(rows.getValue(1).rankBefore, "nothing earlier is tracked")
        assertNull(rows.getValue(2).rankBefore, "normal games have no rank")
        assertEquals(72, rows.getValue(3).rankBefore)
        assertEquals(73, rows.getValue(4).rankBefore)
        assertEquals(73, rows.getValue(5).rankBefore)
        assertNull(rows.getValue(5).rankAfter, "a missing reported badge stays missing")
    }

    @Test
    fun sharedMatchesGiveRecencyAndYourKdaWithoutAssumingAParty() {
        val state = DeadlockStatsState(
            accountId = 1,
            matchHistory = listOf(entry(1, 100, k = 10, d = 2, a = 10), entry(2, 200, matchMode = 4, k = 2, d = 4, a = 2), entry(3, 300)),
            mateStats = listOf(PlayedWithStats(otherAccountId = 9, matchesPlayed = 2, wins = 2, matches = listOf(1, 2))),
        )
        val all = state.mates(filter = MatchListFilter()).single()
        assertEquals(200, all.lastSharedMatchAt)
        assertEquals((12.0 + 12) / 6, all.yourKda.value)
        val ranked = state.mates(filter = MatchListFilter(mode = MatchModeFilter.Ranked)).single()
        assertEquals(1L, ranked.matchesPlayed, "the mode filter applies to shared matches")
        assertEquals(200, ranked.lastSharedMatchAt)
        assertEquals(listOf(3L, 2L, 1L), state.matchIds())
        assertEquals(listOf(2L), state.matchIds(MatchListFilter(mode = MatchModeFilter.Ranked)))
    }

    @Test
    fun theSuggestedLocalAccountIsFlaggedOrLatestNeverAGuess() {
        val a = LocalSteamIdentity(76561198000000001, "A", mostRecent = false, lastLoginAt = 100)
        val b = LocalSteamIdentity(76561198000000002, "B", mostRecent = false, lastLoginAt = 200)
        val flagged = LocalSteamIdentity(76561198000000003, "C", mostRecent = true)
        val untimed = LocalSteamIdentity(76561198000000004, "D", mostRecent = false)
        assertEquals(flagged, listOf(a, b, flagged).mostRecentIdentity())
        assertEquals(b, listOf(a, b).mostRecentIdentity())
        assertEquals(untimed, listOf(untimed).mostRecentIdentity())
        assertNull(listOf(untimed, untimed.copy(steamId64 = 76561198000000005)).mostRecentIdentity(), "two equally likely accounts are not chosen between")
        assertNull(emptyList<LocalSteamIdentity>().mostRecentIdentity())
    }
}
