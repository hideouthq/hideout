package gg.hideouthq.core.data.deadlockstats

/** All combines ranked + unranked normal games. Bots, private/custom, brawl and unknown modes are excluded; raw state retains them. */
enum class MatchModeFilter { All, Ranked, Normal }
enum class MatchResultFilter { All, Win, Loss }

/** Half-open Unix-second bounds. Undated matches are included only in an unbounded window. */
data class DateWindow(val fromInclusive: Int? = null, val toExclusive: Int? = null) {
    init {
        require(fromInclusive == null || toExclusive == null || fromInclusive < toExclusive) {
            "fromInclusive must precede toExclusive"
        }
    }

    val lengthSeconds: Long? get() = if (fromInclusive != null && toExclusive != null) toExclusive.toLong() - fromInclusive else null

    /** Null for open bounds or when the preceding window cannot be represented by Unix Int seconds. */
    fun previous(): DateWindow? {
        val length = lengthSeconds ?: return null
        val start = fromInclusive!!.toLong() - length
        return if (start < Int.MIN_VALUE) null else DateWindow(start.toInt(), fromInclusive)
    }

    fun contains(startTime: Int?): Boolean = if (startTime == null) {
        fromInclusive == null && toExclusive == null
    } else {
        (fromInclusive == null || startTime >= fromInclusive) && (toExclusive == null || startTime < toExclusive)
    }
}

data class MatchListFilter(
    val mode: MatchModeFilter = MatchModeFilter.All,
    val window: DateWindow = DateWindow(),
    val heroId: Int? = null,
    val result: MatchResultFilter = MatchResultFilter.All,
)

/** Descending (startTime, matchId), with unknown times last. Reuse only with the same filter. */
data class MatchCursor(val afterStartTime: Int?, val afterMatchId: Long)

data class MatchRow(
    val matchId: Long,
    val startTime: Int?,
    val durationS: Int?,
    val gameMode: Int?,
    val matchMode: Int?,
    val heroId: Int?,
    val heroLevel: Int?,
    val kills: Int?,
    val deaths: Int?,
    val assists: Int?,
    val netWorth: Int?,
    val lastHits: Int?,
    val denies: Int?,
    val result: PlayerMatchOutcome,
    val heroDamage: Int?,
    val healing: Int?,
    val soulsPerMin: Double?,
    val heroDamagePerMin: Double?,
    val kda: Double?,
    val accuracy: Double?,
    val critRate: Double?,
    /** Metadata's initial rank, else the badge reported after the previous tracked ranked match. */
    val rankBefore: Int?,
    /** A reported badge only; never inferred from flat-progress arithmetic. */
    val rankAfter: Int?,
    /** The match counted towards ranked placement. */
    val placement: Boolean,
    val abandonedTimeS: Int?,
    val hasDetail: Boolean,
)

data class MatchPage(
    val rows: List<MatchRow>,
    val nextCursor: MatchCursor?,
    val filteredTotal: Int,
    val filteredWins: Int,
    val filteredLosses: Int,
)

fun DeadlockStatsState.matches(filter: MatchListFilter = MatchListFilter(), after: MatchCursor? = null, limit: Int = 20): MatchPage {
    require(limit in 1..200) { "limit must be between 1 and 200, was $limit" }
    val filtered = filteredHistory(filter)
    val remaining = if (after == null) filtered else filtered.filter {
        val timeOrder = compareValues(it.startTime, after.afterStartTime)
        timeOrder < 0 || (timeOrder == 0 && it.matchId < after.afterMatchId)
    }
    val page = remaining.take(limit)
    val before = previousRankedBadges()
    return MatchPage(
        rows = page.map { toRow(it, before) },
        nextCursor = page.lastOrNull()?.takeIf { remaining.size > page.size }?.let { MatchCursor(it.startTime, it.matchId) },
        filteredTotal = filtered.size,
        filteredWins = filtered.count { it.result() == PlayerMatchOutcome.Win },
        filteredLosses = filtered.count { it.result() == PlayerMatchOutcome.Loss },
    )
}

/** The filter's tracked match ids, newest first. */
fun DeadlockStatsState.matchIds(filter: MatchListFilter = MatchListFilter()): List<Long> = filteredHistory(filter).map { it.matchId }

private fun DeadlockStatsState.filteredHistory(filter: MatchListFilter): List<MatchHistoryEntry> = matchHistory
    .filter { filter.includes(it) }
    .sortedWith(compareByDescending<MatchHistoryEntry> { it.startTime }.thenByDescending { it.matchId })

private fun MatchListFilter.includes(entry: MatchHistoryEntry): Boolean =
    mode.includes(entry) && window.contains(entry.startTime) && (heroId == null || heroId == entry.heroId) && when (result) {
        MatchResultFilter.All -> true
        MatchResultFilter.Win -> entry.result() == PlayerMatchOutcome.Win
        MatchResultFilter.Loss -> entry.result() == PlayerMatchOutcome.Loss
    }

private const val NormalGameMode = 1

private fun MatchModeFilter.includes(entry: MatchHistoryEntry): Boolean = entry.gameMode == NormalGameMode && when (this) {
    MatchModeFilter.All -> MatchMode.ofCode(entry.matchMode) in setOf(MatchMode.Ranked, MatchMode.Unranked)
    MatchModeFilter.Ranked -> MatchMode.ofCode(entry.matchMode) == MatchMode.Ranked
    MatchModeFilter.Normal -> MatchMode.ofCode(entry.matchMode) == MatchMode.Unranked
}

/** History's matchResult is the winning team (0/1), NOT EMatchOutcome. */
private fun MatchHistoryEntry.result(): PlayerMatchOutcome = if (matchResult in 0..1 && playerTeam in 0..1) {
    if (matchResult == playerTeam) PlayerMatchOutcome.Win else PlayerMatchOutcome.Loss
} else {
    PlayerMatchOutcome.ofCode(playerMatchOutcome)
}

private fun DeadlockStatsState.participant(matchId: Long): MatchParticipant? =
    matchDetails[matchId]?.players?.firstOrNull { it.accountId == accountId }

private fun DeadlockStatsState.statsFor(matchId: Long): MatchPlayerStatSnapshot? =
    participant(matchId)?.latestStats() ?: matchSummaries[matchId]?.takeIf { it.accountId == accountId }?.toSnapshot()

internal fun DeadlockStatsState.previousRankedBadges(): Map<Long, Int> {
    val ranked = matchHistory.filter { MatchMode.ofCode(it.matchMode) == MatchMode.Ranked && it.startTime != null }
        .sortedWith(compareBy<MatchHistoryEntry> { it.startTime }.thenBy { it.matchId })
    return ranked.zipWithNext().mapNotNull { (previous, next) -> previous.rankedDisplayBadge?.takeIf { it > 0 }?.let { next.matchId to it } }.toMap()
}

internal fun MatchParticipant.latestStats(): MatchPlayerStatSnapshot? =
    statSnapshots.sortedBy { it.timeStampS }.lastOrNull()

internal fun DeadlockStatsState.matchRow(matchId: Long): MatchRow? =
    matchHistory.firstOrNull { it.matchId == matchId }?.let { toRow(it, previousRankedBadges()) }

private fun DeadlockStatsState.toRow(entry: MatchHistoryEntry, previousRanked: Map<Long, Int>): MatchRow {
    val participant = participant(entry.matchId)
    val stats = statsFor(entry.matchId)
    val metrics = totals(listOf(entry))
    return MatchRow(
        matchId = entry.matchId, startTime = entry.startTime, durationS = entry.matchDurationS,
        gameMode = entry.gameMode, matchMode = entry.matchMode, heroId = entry.heroId, heroLevel = entry.heroLevel,
        kills = entry.kills, deaths = entry.deaths, assists = entry.assists, netWorth = entry.netWorth,
        lastHits = entry.lastHits, denies = entry.denies, result = entry.result(),
        heroDamage = stats?.playerDamage, healing = stats?.playerHealing,
        soulsPerMin = metrics.soulsPerMin.value, heroDamagePerMin = metrics.heroDamagePerMin.value,
        kda = metrics.kda.value, accuracy = metrics.accuracy.value, critRate = metrics.critRate.value,
        rankBefore = participant?.rank?.initialDisplayRank?.takeIf { it > 0 } ?: previousRanked[entry.matchId],
        rankAfter = entry.rankedDisplayBadge?.takeIf { it > 0 }, placement = (entry.rankedCalibrationMatch ?: 0) != 0, abandonedTimeS = entry.abandonedTimeS,
        hasDetail = matchDetails[entry.matchId]?.isComplete == true,
    )
}

/** A total over known values, not a zero-filled estimate over all tracked matches. */
data class StatTotal(val value: Long?, val matches: Int) {
    val average: Double? get() = value?.let { it.toDouble() / matches }
}

/** Ratio of sums over matches with ALL required inputs, not a mean of per-match ratios. */
data class StatRatio(val value: Double?, val matches: Int)

data class PeriodTotals(
    val matches: Int,
    val wins: Int,
    val losses: Int,
    val kills: StatTotal,
    val deaths: StatTotal,
    val assists: StatTotal,
    val souls: StatTotal,
    val playtimeSeconds: StatTotal,
    val heroDamage: StatTotal,
    val healing: StatTotal,
    val bossDamage: StatTotal,
    val damageMitigated: StatTotal,
    val playerDamageTaken: StatTotal,
    val selfHealing: StatTotal,
    val soulsPerMin: StatRatio,
    val heroDamagePerMin: StatRatio,
    /** Boss and structure damage per minute, over matches with both the damage and the duration. */
    val objectiveDamagePerMin: StatRatio = StatRatio(null, 0),
    /** (Kills + assists) / max(deaths, 1), using only complete K/D/A triples. */
    val kda: StatRatio,
    /** shotsHit / (shotsHit + shotsMissed), in [0, 1], not a percentage. */
    val accuracy: StatRatio,
    /** heroBulletsHitCrit / heroBulletsHit, in [0, 1]. */
    val critRate: StatRatio,
) {
    val winRate: Double? get() = (wins + losses).takeIf { it > 0 }?.let { wins.toDouble() / it }
    val avgKills: Double? get() = kills.average
    val avgDeaths: Double? get() = deaths.average
    val avgAssists: Double? get() = assists.average
    val avgSouls: Double? get() = souls.average
}

private fun List<Int?>.statTotal(): StatTotal {
    val known = filterNotNull()
    return StatTotal(known.takeIf { it.isNotEmpty() }?.sumOf { it.toLong() }, known.size)
}

private fun ratio(pairs: List<Pair<Long, Long>>, scale: Double = 1.0, deathsFloor: Boolean = false): StatRatio {
    if (pairs.isEmpty()) return StatRatio(null, 0)
    val numerator = pairs.sumOf { it.first }
    val denominator = pairs.sumOf { it.second }.let { if (deathsFloor) it.coerceAtLeast(1) else it }
    return StatRatio(denominator.takeIf { it > 0 }?.let { numerator.toDouble() * scale / it }, pairs.size)
}

private fun perMinutePair(value: Int?, duration: Int?): Pair<Long, Long>? =
    if (value != null && duration != null && duration > 0) value.toLong() to duration.toLong() else null

private fun DeadlockStatsState.totals(entries: List<MatchHistoryEntry>): PeriodTotals {
    val snapshots = entries.map { statsFor(it.matchId) }
    return PeriodTotals(
        matches = entries.size,
        wins = entries.count { it.result() == PlayerMatchOutcome.Win },
        losses = entries.count { it.result() == PlayerMatchOutcome.Loss },
        kills = entries.map { it.kills }.statTotal(), deaths = entries.map { it.deaths }.statTotal(),
        assists = entries.map { it.assists }.statTotal(), souls = entries.map { it.netWorth }.statTotal(),
        playtimeSeconds = entries.map { it.matchDurationS }.statTotal(),
        heroDamage = snapshots.map { it?.playerDamage }.statTotal(), healing = snapshots.map { it?.playerHealing }.statTotal(),
        bossDamage = snapshots.map { it?.bossDamage }.statTotal(), damageMitigated = snapshots.map { it?.damageMitigated }.statTotal(),
        playerDamageTaken = snapshots.map { it?.playerDamageTaken }.statTotal(), selfHealing = snapshots.map { it?.selfHealing }.statTotal(),
        soulsPerMin = ratio(entries.mapNotNull { perMinutePair(it.netWorth, it.matchDurationS) }, scale = 60.0),
        heroDamagePerMin = ratio(entries.zip(snapshots).mapNotNull { (entry, stats) -> perMinutePair(stats?.playerDamage, entry.matchDurationS) }, scale = 60.0),
        objectiveDamagePerMin = ratio(entries.zip(snapshots).mapNotNull { (entry, stats) -> perMinutePair(stats?.bossDamage, entry.matchDurationS) }, scale = 60.0),
        kda = ratio(entries.mapNotNull {
            val kills = it.kills ?: return@mapNotNull null
            val deaths = it.deaths ?: return@mapNotNull null
            val assists = it.assists ?: return@mapNotNull null
            (kills.toLong() + assists) to deaths.toLong()
        }, deathsFloor = true),
        accuracy = ratio(snapshots.mapNotNull {
            val hit = it?.shotsHit ?: return@mapNotNull null
            val missed = it.shotsMissed ?: return@mapNotNull null
            if (hit < 0 || missed < 0) null else hit.toLong() to (hit.toLong() + missed)
        }),
        critRate = ratio(snapshots.mapNotNull {
            val hit = it?.heroBulletsHit ?: return@mapNotNull null
            val crit = it.heroBulletsHitCrit ?: return@mapNotNull null
            if (hit < 0 || crit < 0 || crit > hit) null else crit.toLong() to hit.toLong()
        }),
    )
}

data class Streak(val outcome: PlayerMatchOutcome, val length: Int)

data class ProfileSummary(
    val accountId: Long,
    val name: String?,
    val avatarUrl: String?,
    val firstMatchAt: Int?,
    val lastMatchAt: Int?,
    val totals: PeriodTotals,
    val heroesPlayedCount: Int,
    val mostPlayedHeroId: Int?,
    val currentStreak: Streak?,
) {
    val matchesTracked: Int get() = totals.matches
    val wins: Int get() = totals.wins
    val losses: Int get() = totals.losses
    val playtimeSeconds: Long? get() = totals.playtimeSeconds.value
}

fun DeadlockStatsState.profileSummary(filter: MatchListFilter = MatchListFilter()): ProfileSummary? {
    val account = accountId ?: return null
    val entries = filteredHistory(filter)
    val outcomes = entries.map { it.result() }
    val streak = outcomes.firstOrNull()?.takeIf {
        entries.all { it.startTime != null } && (it == PlayerMatchOutcome.Win || it == PlayerMatchOutcome.Loss)
    }?.let { first -> Streak(first, outcomes.takeWhile { it == first }.size) }
    val heroes = entries.mapNotNull { it.heroId }
    val mostPlayed = heroes.groupingBy { it }.eachCount().entries.sortedWith(
        compareByDescending<Map.Entry<Int, Int>> { it.value }.thenBy { it.key },
    ).firstOrNull()?.key
    return ProfileSummary(
        accountId = account, name = profile?.personaName, avatarUrl = profile?.avatarUrl,
        firstMatchAt = entries.mapNotNull { it.startTime }.minOrNull(), lastMatchAt = entries.mapNotNull { it.startTime }.maxOrNull(),
        totals = totals(entries), heroesPlayedCount = heroes.distinct().size, mostPlayedHeroId = mostPlayed, currentStreak = streak,
    )
}

data class PeriodSummary(
    val totals: PeriodTotals,
    val previousWindow: PeriodTotals?,
    /** Per-match chart rows, newest first, restricted to the SAME filter/window as totals. */
    val last20: List<MatchRow>,
)

fun DeadlockStatsState.periodSummary(window: DateWindow, mode: MatchModeFilter = MatchModeFilter.All): PeriodSummary =
    periodSummary(MatchListFilter(mode = mode, window = window))

fun DeadlockStatsState.periodSummary(filter: MatchListFilter): PeriodSummary {
    val entries = filteredHistory(filter)
    val before = previousRankedBadges()
    return PeriodSummary(
        totals = totals(entries),
        previousWindow = filter.window.previous()?.let { totals(filteredHistory(filter.copy(window = it))) },
        last20 = entries.take(20).map { toRow(it, before) },
    )
}

data class RankHistoryPoint(val matchId: Long, val startTime: Int?, val badgeBefore: Int?, val badgeAfter: Int?)

fun DeadlockStatsState.rankHistory(filter: MatchListFilter = MatchListFilter()): List<RankHistoryPoint> =
    matchHistory.filter { filter.includes(it) }.mapNotNull { entry ->
        val local = participant(entry.matchId)?.rank
        if (entry.rankedDisplayBadge == null && local == null) return@mapNotNull null
        RankHistoryPoint(entry.matchId, entry.startTime, local?.initialDisplayRank?.takeIf { it > 0 }, entry.rankedDisplayBadge?.takeIf { it > 0 })
    }.sortedWith(compareBy<RankHistoryPoint> { it.startTime == null }.thenBy { it.startTime }.thenBy { it.matchId })

/** Tracked history only, per hero. */
data class HeroPeriodAggregateView(val heroId: Int, val totals: PeriodTotals)

fun DeadlockStatsState.heroPeriodAggregates(filter: MatchListFilter = MatchListFilter()): List<HeroPeriodAggregateView> =
    filteredHistory(filter).filter { it.heroId != null }.groupBy { it.heroId!! }.map { (heroId, entries) -> HeroPeriodAggregateView(heroId, totals(entries)) }
        .sortedWith(compareByDescending<HeroPeriodAggregateView> { it.totals.matches }.thenBy { it.heroId })

/** API shared history is not evidence of a party. Filtered rows intersect reported IDs with tracked history. */
data class PlayedWithView(
    val otherAccountId: Long,
    val matchesPlayed: Long,
    val wins: Long,
    val losses: Long?,
    /** Null for an unfiltered API aggregate, whose decisive-result coverage is not reported. */
    val knownResultMatches: Int?,
    /** Latest start time among tracked history rows whose IDs the API reported as shared. */
    val lastSharedMatchAt: Int? = null,
    /** This account's (kills + assists) / deaths over those tracked rows. */
    val yourKda: StatRatio = StatRatio(null, 0),
)

/** Null filter preserves the API aggregate; even an empty non-null filter requests the tracked intersection. */
fun DeadlockStatsState.mates(filter: MatchListFilter? = null): List<PlayedWithView> = mateStats.mapNotNull { playedWithView(it, filter) }

fun DeadlockStatsState.enemies(filter: MatchListFilter? = null): List<PlayedWithView> = enemyStats.mapNotNull { playedWithView(it, filter) }

private fun DeadlockStatsState.playedWithView(stats: PlayedWithStats, filter: MatchListFilter?): PlayedWithView? {
    val ids = stats.matches.toSet()
    val entries = filter?.let { filteredHistory(it).filter { entry -> entry.matchId in ids } }
    if (entries != null && entries.isEmpty()) return null
    val wins = entries?.count { it.result() == PlayerMatchOutcome.Win }
    val losses = entries?.count { it.result() == PlayerMatchOutcome.Loss }
    val shared = entries ?: matchHistory.filter { it.matchId in ids }
    return PlayedWithView(
        otherAccountId = stats.otherAccountId, matchesPlayed = entries?.size?.toLong() ?: stats.matchesPlayed,
        wins = wins?.toLong() ?: stats.wins, losses = losses?.toLong(), knownResultMatches = wins?.let { it + losses!! },
        lastSharedMatchAt = shared.mapNotNull { it.startTime }.maxOrNull(), yourKda = totals(shared).kda,
    )
}
