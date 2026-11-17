package gg.hideouthq.core.data.deadlockstats

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeadlockStatsQueriesTest {
    private fun historyEntry(
        matchId: Long,
        startTime: Int? = null,
        heroId: Int? = 1,
        matchMode: Int? = 1,
        outcome: Int? = 1,
        kills: Int? = 5,
        deaths: Int? = 2,
        assists: Int? = 3,
        netWorth: Int? = 10_000,
        durationS: Int? = 1800,
        rankedDisplayBadge: Int? = null,
    ) = MatchHistoryEntry(
        matchId = matchId, heroId = heroId, heroLevel = 20, startTime = startTime, gameMode = 1, matchMode = matchMode,
        playerTeam = 0, kills = kills, deaths = deaths, assists = assists, denies = 4, netWorth = netWorth, lastHits = 50,
        matchDurationS = durationS, matchResult = when (outcome) { 1 -> 0; 2 -> 1; else -> null },
        playerMatchOutcome = outcome, rankedDisplayBadge = rankedDisplayBadge,
    )

    private fun detail(id: Long, stats: MatchPlayerStatSnapshot, rank: MatchPlayerRank? = null) = MatchDetail(
        matchId = id, durationS = 1800, winningTeam = 0,
        players = listOf(MatchParticipant(accountId = 1, statSnapshots = listOf(stats), rank = rank)),
    )

    @Test
    fun normalMeansUnrankedNormalGameNotEveryNonRankedMode() {
        val state = DeadlockStatsState(accountId = 1, matchHistory = listOf(
            historyEntry(1, 100), historyEntry(2, 200, matchMode = 4), historyEntry(3, 300, matchMode = 3),
            historyEntry(4, 400, matchMode = 7), historyEntry(5, 500).copy(gameMode = 4), MatchHistoryEntry(6),
            historyEntry(7, 600, matchMode = 4).copy(gameMode = 4), historyEntry(8, 700).copy(gameMode = null),
            historyEntry(9, 800, matchMode = null),
        ))
        assertEquals(listOf(1L), state.matches(MatchListFilter(mode = MatchModeFilter.Normal)).rows.map { it.matchId })
        assertEquals(listOf(2L), state.matches(MatchListFilter(mode = MatchModeFilter.Ranked)).rows.map { it.matchId })
        assertEquals(2, state.matches().filteredTotal)
    }

    @Test
    fun windowsAreValidatedHalfOpenAndDoNotInventUnknownDates() {
        val state = DeadlockStatsState(accountId = 1, matchHistory = listOf(
            historyEntry(1, 100), historyEntry(2, 200), historyEntry(3, 300), historyEntry(4),
        ))
        assertEquals(listOf(2L, 1L), state.matches(MatchListFilter(window = DateWindow(100, 300))).rows.map { it.matchId })
        assertEquals(listOf(3L, 2L, 1L, 4L), state.matches().rows.map { it.matchId })
        assertEquals(DateWindow(0, 100), DateWindow(100, 200).previous())
        assertNull(DateWindow(fromInclusive = 100).previous())
        assertNull(DateWindow(Int.MIN_VALUE, Int.MAX_VALUE).previous())
    }

    @Test
    fun winningTeamIsNotMatchOutcomeAndUnknownResultsDoNotMatchWinLossFilters() {
        val state = DeadlockStatsState(accountId = 1, matchHistory = listOf(
            historyEntry(1, 100).copy(playerTeam = 1, matchResult = 1, playerMatchOutcome = null),
            historyEntry(2, 200).copy(playerTeam = 0, matchResult = 1, playerMatchOutcome = null),
            historyEntry(3, 300, outcome = null),
            historyEntry(4, 400, outcome = 2).copy(playerTeam = null, matchResult = null),
            historyEntry(5, 500).copy(playerTeam = 16, matchResult = 0, playerMatchOutcome = null),
        ))
        val all = state.matches()
        assertEquals(5, all.filteredTotal)
        assertEquals(1, all.filteredWins)
        assertEquals(2, all.filteredLosses)
        assertEquals(listOf(1L), state.matches(MatchListFilter(result = MatchResultFilter.Win)).rows.map { it.matchId })
        assertEquals(listOf(4L, 2L), state.matches(MatchListFilter(result = MatchResultFilter.Loss)).rows.map { it.matchId })
        assertEquals(1.0 / 3, state.periodSummary(DateWindow()).totals.winRate)
    }

    @Test
    fun keysetPaginationHandlesTiesUnknownDatesAndCountsTheWholeFilter() {
        val state = DeadlockStatsState(accountId = 1, matchHistory = listOf(
            historyEntry(1, 100), historyEntry(2, 100), historyEntry(3, 100, outcome = 2),
            historyEntry(4, 200, heroId = 9), historyEntry(6, heroId = null, outcome = null), historyEntry(5, heroId = null, outcome = null),
        ))
        val first = state.matches(limit = 2)
        assertEquals(listOf(4L, 3L), first.rows.map { it.matchId })
        val second = state.matches(after = first.nextCursor, limit = 2)
        assertEquals(listOf(2L, 1L), second.rows.map { it.matchId })
        val third = state.matches(after = second.nextCursor, limit = 1)
        assertEquals(listOf(6L), third.rows.map { it.matchId })
        val fourth = state.matches(after = third.nextCursor, limit = 1)
        assertEquals(listOf(5L), fourth.rows.map { it.matchId })
        assertNull(fourth.nextCursor)
        assertEquals(6, fourth.filteredTotal)
        assertEquals(first.filteredWins, fourth.filteredWins)
        assertEquals(3, state.matches(MatchListFilter(heroId = 1)).filteredTotal)
        assertTrue(state.matches(after = MatchCursor(null, 0)).rows.isEmpty())
        assertEquals(listOf(3L, 2L), state.matches(after = MatchCursor(150, 99), limit = 2).rows.map { it.matchId }, "a deleted row's cursor resumes at its place")
    }

    @Test
    fun noDataAndPartialRowsNeverInventZeroStatisticsOrFreshness() {
        val state = DeadlockStatsState(accountId = 1, matchHistory = listOf(MatchHistoryEntry(1, gameMode = 1, matchMode = 1)))
        val row = state.matches().rows.single()
        assertNull(row.kills)
        assertNull(row.durationS)
        assertNull(row.soulsPerMin)
        assertEquals(PlayerMatchOutcome.Unknown, row.result)
        val summary = state.profileSummary()!!
        assertNull(summary.totals.kills.value)
        assertNull(summary.mostPlayedHeroId)
        assertNull(summary.currentStreak)
        val empty = DeadlockStatsState().periodSummary(DateWindow())
        assertEquals(0, empty.totals.matches)
        assertNull(empty.totals.avgKills)
        assertNull(empty.totals.winRate)
        assertNull(empty.totals.accuracy.value)
        assertTrue(empty.last20.isEmpty())
        assertNull(DeadlockStatsState().profileSummary())
    }

    @Test
    fun profileCountsHeroesAndStreaksFromTrackedHistory() {
        val state = DeadlockStatsState(accountId = 1, matchHistory = listOf(
            historyEntry(1, 100, heroId = 1), historyEntry(2, 200, heroId = 1), historyEntry(3, 300, heroId = 2, outcome = 2),
        ))
        val summary = state.profileSummary()!!
        assertEquals(3, summary.matchesTracked)
        assertEquals(1, summary.mostPlayedHeroId)
        assertEquals(2, summary.wins)
        assertEquals(Streak(PlayerMatchOutcome.Loss, 1), summary.currentStreak)
        val filtered = state.profileSummary(MatchListFilter(heroId = 1))!!
        assertEquals(2, filtered.matchesTracked)
        assertEquals(200, filtered.lastMatchAt)
        assertEquals(Streak(PlayerMatchOutcome.Win, 2), filtered.currentStreak)
    }

    @Test
    fun unknownOutcomeBreaksStreakAndUndatedMatchPreventsCurrentStreakClaim() {
        val entries = listOf(historyEntry(1, 100), historyEntry(2, 200, outcome = null), historyEntry(3, 300))
        assertEquals(Streak(PlayerMatchOutcome.Win, 1), DeadlockStatsState(accountId = 1, matchHistory = entries).profileSummary()!!.currentStreak)
        assertNull(DeadlockStatsState(accountId = 1, matchHistory = entries.dropLast(1)).profileSummary()!!.currentStreak)
        assertNull(DeadlockStatsState(accountId = 1, matchHistory = entries + historyEntry(4)).profileSummary()!!.currentStreak)
    }

    @Test
    fun periodPreviousAndLast20ShareModeHeroResultAndHalfOpenWindow() {
        val state = DeadlockStatsState(accountId = 1, matchHistory = listOf(
            historyEntry(1, 50), historyEntry(2, 150), historyEntry(3, 160, outcome = 2),
            historyEntry(4, 170, heroId = 2), historyEntry(5, 180, matchMode = 4), historyEntry(6, 200),
        ), matchDetails = mapOf(1L to detail(1, MatchPlayerStatSnapshot(playerDamage = 30))))
        val summary = state.periodSummary(MatchListFilter(
            mode = MatchModeFilter.Normal, heroId = 1, result = MatchResultFilter.Win, window = DateWindow(100, 200),
        ))
        assertEquals(1, summary.totals.matches)
        val previous = summary.previousWindow!!
        assertEquals(1, previous.matches)
        assertEquals(30.0, previous.heroDamage.average)
        assertEquals(listOf(2L), summary.last20.map { it.matchId })
        assertEquals(5, summary.last20.single().kills)
    }

    @Test
    fun last20AreIndividualRowsNotTotalsAndNeverIncludeOutsideWindow() {
        val state = DeadlockStatsState(accountId = 1, matchHistory = (1..30).map { historyEntry(it.toLong(), it * 10, kills = it) })
        val summary = state.periodSummary(DateWindow(10, 260))
        assertEquals(25, summary.totals.matches)
        assertEquals((25 downTo 6).toList(), summary.last20.map { it.kills })
    }

    @Test
    fun weightedMetricsUseOnlyKnownPairedInputsAndExposeTheirDenominators() {
        val state = DeadlockStatsState(accountId = 1, matchHistory = listOf(
            historyEntry(1, 100, kills = 4, deaths = 1, assists = 2, netWorth = 100, durationS = 60),
            historyEntry(2, 200, kills = 8, deaths = 3, assists = 2, netWorth = 900, durationS = 180),
            historyEntry(3, 300, kills = null, deaths = null, assists = null, netWorth = null, durationS = 600),
            historyEntry(4, 400, kills = null, deaths = null, assists = null, netWorth = 500, durationS = null),
        ), matchDetails = mapOf(
            1L to detail(1, MatchPlayerStatSnapshot(playerDamage = 60, shotsHit = 1, shotsMissed = 1, heroBulletsHit = 2, heroBulletsHitCrit = 1)),
            2L to detail(2, MatchPlayerStatSnapshot(playerDamage = 540, shotsHit = 9, shotsMissed = 1, heroBulletsHit = 8, heroBulletsHitCrit = 1)),
            3L to detail(3, MatchPlayerStatSnapshot(shotsHit = 100, shotsMissed = null)),
        ))
        val totals = state.periodSummary(DateWindow()).totals
        assertEquals(6.0, totals.avgKills)
        assertEquals(StatTotal(12, 2), totals.kills)
        assertEquals(StatTotal(1500, 3), totals.souls)
        assertEquals(StatRatio(250.0, 2), totals.soulsPerMin)
        assertEquals(StatRatio(150.0, 2), totals.heroDamagePerMin)
        assertEquals(StatRatio(4.0, 2), totals.kda)
        assertEquals(StatRatio(10.0 / 12, 2), totals.accuracy)
        assertEquals(StatRatio(0.2, 2), totals.critRate)
        assertEquals(300.0, totals.heroDamage.average)
        assertNull(totals.healing.value)
    }

    @Test
    fun zeroKnownValuesDifferFromMissingAndZeroDenominatorsAreNotInfinity() {
        val state = DeadlockStatsState(accountId = 1, matchHistory = listOf(historyEntry(
            1, 100, kills = 0, deaths = 0, assists = 0, netWorth = 0, durationS = 0,
        )), matchDetails = mapOf(1L to detail(1, MatchPlayerStatSnapshot(shotsHit = 0, shotsMissed = 0, heroBulletsHit = 0, heroBulletsHitCrit = 0))))
        val totals = state.periodSummary(DateWindow()).totals
        assertEquals(StatTotal(0, 1), totals.kills)
        assertEquals(0.0, totals.avgKills)
        assertEquals(StatRatio(null, 0), totals.soulsPerMin)
        assertEquals(StatRatio(0.0, 1), totals.kda)
        assertEquals(StatRatio(null, 1), totals.accuracy)
        assertEquals(StatRatio(null, 1), totals.critRate)
    }

    @Test
    fun rowUsesLatestSnapshotWithoutBorrowingMissingFieldsFromEarlierTimes() {
        val match = detail(1, MatchPlayerStatSnapshot()).copy(players = listOf(MatchParticipant(accountId = 1,
            statSnapshots = listOf(MatchPlayerStatSnapshot(timeStampS = 20, playerDamage = 50), MatchPlayerStatSnapshot(timeStampS = 10, playerDamage = 10, playerHealing = 5)),
            rank = MatchPlayerRank(initialDisplayRank = 40),
        )))
        val row = DeadlockStatsState(accountId = 1, matchHistory = listOf(historyEntry(1, 100, rankedDisplayBadge = 45)), matchDetails = mapOf(1L to match)).matches().rows.single()
        assertEquals(50, row.heroDamage)
        assertNull(row.healing)
        assertEquals(40, row.rankBefore)
        assertEquals(45, row.rankAfter)
    }

    @Test
    fun rankHistoryKeepsTheBadgesBeforeAndAfterApartAndLeavesAnUnreportedOneUnknown() {
        val state = DeadlockStatsState(accountId = 1,
            matchHistory = listOf(
                historyEntry(1, 100, matchMode = 4, rankedDisplayBadge = 50), historyEntry(2, 200, matchMode = 4),
                historyEntry(3, 300, matchMode = 4, rankedDisplayBadge = 0), historyEntry(4, 400, matchMode = 4),
            ),
            matchDetails = mapOf(
                1L to detail(1, MatchPlayerStatSnapshot(), MatchPlayerRank(initialDisplayRank = 40)),
                2L to detail(2, MatchPlayerStatSnapshot(), MatchPlayerRank(initialDisplayRank = 50)),
            ),
        )
        assertEquals(listOf(RankHistoryPoint(1, 100, 40, 50), RankHistoryPoint(2, 200, 50, null), RankHistoryPoint(3, 300, null, null)),
            state.rankHistory(), "a match with neither badge is no point")
        assertEquals(listOf(2L, 3L), state.rankHistory(MatchListFilter(window = DateWindow(200, 400))).map { it.matchId })
        assertTrue(state.rankHistory(MatchListFilter(mode = MatchModeFilter.Normal)).isEmpty())
        assertTrue(state.rankHistory(MatchListFilter(heroId = 2)).isEmpty())
    }

    @Test
    fun socialFiltersIntersectReportedIdsWithoutMixingApiCounts() {
        val stats = PlayedWithStats(otherAccountId = 2, matchesPlayed = 50, wins = 40, matches = listOf(10, 11, 11, 12))
        val state = DeadlockStatsState(accountId = 1, mateStats = listOf(stats), enemyStats = listOf(stats),
            matchHistory = listOf(historyEntry(10, 100, outcome = 2), historyEntry(11, 200, matchMode = 4), historyEntry(13, 300)),
        )
        val api = state.mates().single()
        assertEquals(50L, api.matchesPlayed)
        assertEquals(40L, api.wins)
        assertNull(api.losses)
        assertNull(api.knownResultMatches)
        val tracked = state.mates(filter = MatchListFilter(mode = MatchModeFilter.Normal)).single()
        assertEquals(1L, tracked.matchesPlayed)
        assertEquals(0L, tracked.wins)
        assertEquals(1L, tracked.losses)
        assertEquals(1, tracked.knownResultMatches)
        assertEquals(tracked, state.enemies(filter = MatchListFilter(mode = MatchModeFilter.Normal)).single())
        assertTrue(state.mates(filter = MatchListFilter(heroId = 99)).isEmpty())
    }
}
