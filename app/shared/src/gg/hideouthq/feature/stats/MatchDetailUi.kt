package gg.hideouthq.feature.stats

import androidx.compose.runtime.Immutable
import gg.hideouthq.core.data.Loadable

/** A side by its colour: amber is the Hidden King's, sapphire the Archmother's, as the game names them. */
enum class Team { Amber, Sapphire }

enum class GraphView { SoulsLead, YourSouls, YourDamage }

enum class ScoreColumns { Overview, Combat, Economy }

sealed interface MatchDetailState {
    data object Loading : MatchDetailState
    data class Ready(val content: MatchDetailContent, val refreshing: Boolean = false) : MatchDetailState
    /** A just-finished match whose full metadata has not arrived yet: what the match history row already knows. */
    data class Pending(val content: MatchPendingContent) : MatchDetailState
    data class Failed(val notFound: Boolean) : MatchDetailState
    /** Stats is turned off in Settings. */
    data object Off : MatchDetailState
    /** No account is chosen yet, so there is no one to show the match for. */
    data object NoAccount : MatchDetailState
}

enum class MatchPendingPhase { OnTheWay, Slow, Unavailable }

/**
 * A just-finished match's page before [MatchDetailContent] exists for it: the hero card from the match history row
 * alone, which part of the wait it is in, and whether a check (automatic or manual) is running right now.
 */
@Immutable
data class MatchPendingContent(
    val matchId: Long,
    val header: MatchHeader,
    val phase: MatchPendingPhase,
    val endedOn: Moment?,
    val checking: Boolean,
)

@Immutable
data class MatchDetailContent(
    val matchId: Long,
    val started: Moment?,
    val header: MatchHeader,
    /** The side the lead is counted for: yours, or the Hidden King's in a match you weren't in. */
    val perspective: Team,
    /** Null when the metadata has no stat snapshots to draw. */
    val timeline: MatchTimeline?,
    /** Both teams, [perspective]'s first, each sorted by souls. */
    val teams: List<MatchTeam>,
    /** The players' names and avatars, which arrive after the match. */
    val names: Loadable<Unit> = Loadable.Ready(Unit),
    /** No detail for this account: someone else's match, opened by its id. */
    val spectator: Boolean = false,
    /** Item and ability ids the item list does not know, as after a patch. */
    val unknownItems: Int = 0,
) {
    val players: List<MatchPlayer> get() = teams.flatMap { it.players }

    val you: MatchPlayer? get() = players.firstOrNull { it.isYou }
}

@Immutable
data class MatchHeader(
    val hero: HeroVisual?,
    val outcome: Outcome,
    val kind: MatchKind,
    val durationS: Int?,
    val team: Team?,
    val level: Int?,
    val lobbyRank: RankVisual?,
    val rankChange: RankChange,
    /** No one else in the match had as many assists. */
    val mostAssists: Boolean,
    val kills: Int?,
    val deaths: Int?,
    val assists: Int?,
    val kda: Double?,
    val souls: Int?,
    val heroDamage: Int?,
    /** The viewer's place by hero damage, when it is in the top three. */
    val heroDamagePlace: Int?,
    val lastHits: Int?,
    val denies: Int?,
    val objectiveDamage: Int?,
    val healing: Int?,
    /** When a just-finished match ended, instead of the lobby average: relative while pending, a clock time once unavailable. */
    val finished: Moment? = null,
)

@Immutable
data class CurvePoint(val timeS: Int, val value: Double)

/**
 * A line through measured points that is smooth without overshooting them (monotone cubic, Fritsch–Carlson). [runs] are
 * stretches of consecutive known points; between runs a measurement is missing and the curve has no value, so a gap is
 * never filled with an invented one.
 */
@Immutable
data class MatchCurve(val runs: List<List<CurvePoint>>) {
    private val tangents: List<DoubleArray> by lazy {
        runs.map { run -> monotoneTangents(DoubleArray(run.size) { run[it].timeS.toDouble() }, DoubleArray(run.size) { run[it].value }) }
    }

    val points: List<CurvePoint> get() = runs.flatten()

    /** The curve's value at [timeS], or null outside every run. */
    fun valueAt(timeS: Double): Double? {
        runs.forEachIndexed { r, run ->
            if (run.isEmpty() || timeS < run.first().timeS || timeS > run.last().timeS) return@forEachIndexed
            if (run.size == 1) return run.single().value
            val i = run.indexOfLast { it.timeS <= timeS }.coerceIn(0, run.size - 2)
            val (t0, v0) = run[i]
            val (t1, v1) = run[i + 1]
            val h = (t1 - t0).toDouble()
            val s = (timeS - t0) / h
            val m = tangents[r]
            return hermite(v0, v1, m[i] * h, m[i + 1] * h, s)
        }
        return null
    }

    /**
     * Each run as its first point and cubic Bézier segments in data coordinates, which an affine mapping to the screen
     * keeps exact.
     */
    fun segments(): List<CurveRun> = runs.mapIndexed { r, run ->
        val m = tangents[r]
        CurveRun(
            start = run.first(),
            segments = (0 until run.size - 1).map { i ->
                val (t0, v0) = run[i]
                val (t1, v1) = run[i + 1]
                val third = (t1 - t0) / 3.0
                CurveSegment(t0 + third, v0 + m[i] * third, t1 - third, v1 - m[i + 1] * third, t1.toDouble(), v1)
            },
        )
    }

    companion object {
        /** A curve through [points] in time order; a null value breaks it. */
        fun of(points: List<Pair<Int, Double?>>): MatchCurve {
            val runs = mutableListOf<MutableList<CurvePoint>>()
            var open = false
            points.sortedBy { it.first }.forEach { (time, value) ->
                if (value == null) {
                    open = false
                } else {
                    if (!open) runs += mutableListOf<CurvePoint>()
                    runs.last() += CurvePoint(time, value)
                    open = true
                }
            }
            return MatchCurve(runs)
        }
    }
}

@Immutable
data class CurveRun(val start: CurvePoint, val segments: List<CurveSegment>)

/** A cubic Bézier from the previous point through two control points to ([t], [v]). */
@Immutable
data class CurveSegment(val c1t: Double, val c1v: Double, val c2t: Double, val c2v: Double, val t: Double, val v: Double)

private fun hermite(p0: Double, p1: Double, m0: Double, m1: Double, s: Double): Double {
    val s2 = s * s
    val s3 = s2 * s
    return (2 * s3 - 3 * s2 + 1) * p0 + (s3 - 2 * s2 + s) * m0 + (-2 * s3 + 3 * s2) * p1 + (s3 - s2) * m1
}

internal fun monotoneTangents(xs: DoubleArray, ys: DoubleArray): DoubleArray {
    val n = xs.size
    if (n < 2) return DoubleArray(n)
    val slopes = DoubleArray(n - 1) { i ->
        val dx = xs[i + 1] - xs[i]
        if (dx == 0.0) 0.0 else (ys[i + 1] - ys[i]) / dx
    }
    val tangents = DoubleArray(n) { i ->
        when (i) {
            0 -> slopes[0]
            n - 1 -> slopes[n - 2]
            else -> if (slopes[i - 1] * slopes[i] <= 0) 0.0 else (slopes[i - 1] + slopes[i]) / 2
        }
    }
    for (i in 0 until n - 1) {
        if (slopes[i] == 0.0) {
            tangents[i] = 0.0
            tangents[i + 1] = 0.0
        } else {
            val a = tangents[i] / slopes[i]
            val b = tangents[i + 1] / slopes[i]
            val h = a * a + b * b
            if (h > 9) {
                val t = 3 / kotlin.math.sqrt(h)
                tangents[i] = t * a * slopes[i]
                tangents[i + 1] = t * b * slopes[i]
            }
        }
    }
    return tangents
}

enum class ObjectiveKind { Guardian, Walker, BaseGuardian, Shrine, PatronWeakened, Patron, MidBoss }

enum class Lane { Yellow, Green, Blue, Purple }

/**
 * A destroyed structure, taken by [takenBy], or a mid-boss killed by [killedBy] whose Rejuvenator [takenBy] claimed.
 * [lane] is known for guardians, walkers and base guardians.
 */
@Immutable
data class ObjectiveEvent(val timeS: Int, val kind: ObjectiveKind, val lane: Lane?, val takenBy: Team?, val killedBy: Team? = null) {
    val guide: Boolean get() = kind == ObjectiveKind.MidBoss || kind == ObjectiveKind.PatronWeakened
}

/** A recorded death of [victim], by [killer] when its player slot names exactly one player. */
@Immutable
data class MatchDeath(val timeS: Int, val victim: Long, val killer: Long?, val durationS: Int?)

@Immutable
data class MatchTimeline(
    val durationS: Int,
    /** The perspective side's souls minus the other side's, at each snapshot. */
    val lead: MatchCurve,
    /** The mean of all players' souls and hero damage where every player's is known. */
    val averageSouls: MatchCurve,
    val averageDamage: MatchCurve,
    val objectives: List<ObjectiveEvent>,
    /** Every recorded death; null when no player's deaths are in the metadata. */
    val deaths: List<MatchDeath>?,
    /** Every player's deaths are recorded with their times, so counts per minute are complete. */
    val deathsComplete: Boolean,
    val summary: TimelineSummary,
)

@Immutable
data class TimelineSummary(
    /** Share of the match the perspective side was ahead on souls; null unless the lead is known throughout. */
    val aheadShare: Double?,
    val kills: Int?,
    val killsAgainst: Int?,
    val objectives: Int,
    val objectivesAgainst: Int,
    /** The lowest measured lead and when, if the perspective side was ever behind. */
    val furthestBehind: CurvePoint?,
)

@Immutable
data class MatchTeam(val team: Team, val won: Boolean?, val kills: Int?, val souls: Int?, val players: List<MatchPlayer>)

enum class MatchStat(
    /** Ranked lowest first, so first is least. */
    val lowestFirst: Boolean = false,
    /** A first place is praised; damage taken is ranked but not a merit. */
    val praised: Boolean = true,
) {
    Souls, HeroDamage, Healing, KillShare, DamagePerMinute, DamageTaken(praised = false), Accuracy, Headshots, SoulsPerMinute,
    LastHits, Denies, ObjectiveDamage, Level, DamageMitigated, Creeps, Neutrals, TimeDead(lowestFirst = true), LongestLife,
}

/** A value and its competition rank among the players who have one; both null when it is unknown. */
@Immutable
data class RankedStat(val value: Double?, val rank: Int?) {
    val best: Boolean get() = rank == 1
}

enum class ItemFate { Kept, Upgraded, Sold, Removed }

@Immutable
data class Purchase(val item: ItemVisual, val boughtAtS: Int, val fate: ItemFate, val goneAtS: Int? = null)

/** Every purchase in order. [unresolved] counts entries the item list cannot tell from abilities. */
@Immutable
data class BuildOrder(val purchases: List<Purchase>, val unresolved: Int = 0) {
    val upgraded: Int get() = purchases.count { it.fate == ItemFate.Upgraded }
    val sold: Int get() = purchases.count { it.fate == ItemFate.Sold }
    val removed: Int get() = purchases.count { it.fate == ItemFate.Removed }
    val kept: Int get() = purchases.count { it.fate == ItemFate.Kept }
}

/** An ability row: its slot from the hero's signatures, or null for an ability the hero data does not place. */
@Immutable
data class AbilityRow(val slot: Int?, val name: String?, val iconUrl: String?)

/** One unlock ([tier] 0) or upgrade (1–3) of [row]. */
@Immutable
data class AbilityStep(val timeS: Int, val row: Int, val tier: Int)

@Immutable
data class AbilityOrder(val rows: List<AbilityRow>, val steps: List<AbilityStep>)

/** Kills on [player] and deaths to them; null where the deaths that would tell are not recorded. */
@Immutable
data class EnemyContact(val player: PlayerVisual, val hero: HeroVisual?, val kills: Int?, val deaths: Int?) {
    val none: Boolean get() = kills == 0 && deaths == 0
}

@Immutable
data class MatchPlayer(
    val player: PlayerVisual,
    val hero: HeroVisual?,
    val team: Team?,
    val isYou: Boolean,
    val level: Int?,
    val kills: Int?,
    val deaths: Int?,
    val assists: Int?,
    val stats: Map<MatchStat, RankedStat>,
    /** Hero damage against the most anyone did, for the scoreboard's bar. */
    val damageShare: Double?,
    /** What they held at the end: weapon, vitality, then spirit, each by when it was bought. */
    val items: List<ItemVisual>,
    /** Null when the metadata has no purchases. */
    val build: BuildOrder?,
    val abilities: AbilityOrder?,
    /** The other team, most contact first. */
    val enemies: List<EnemyContact>,
    val souls: MatchCurve?,
    val damage: MatchCurve?,
) {
    val accountId: Long get() = player.accountId

    fun stat(stat: MatchStat): RankedStat = stats[stat] ?: RankedStat(null, null)
}
