package gg.hideouthq.feature.stats

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import gg.hideouthq.core.data.Loadable

/** Which matches every tab counts: normal-game ranked and unranked matches, or one of them. */
enum class StatsMode { All, Ranked, Normal }

enum class StatsPeriod { Week, Month, Season, AllTime }

data class StatsFilter(val mode: StatsMode = StatsMode.All, val period: StatsPeriod = StatsPeriod.Month)

enum class StatsTab { Overview, Matches, Heroes, Teammates }

enum class ResultFilter { All, Wins, Losses }

enum class HeroSort { MostPlayed, WinRate, Kda, SoulsPerMin, DamagePerMin, Accuracy, Name }

@Immutable
data class HeroVisual(
    val id: Int,
    val name: String,
    val initials: String,
    val portraitUrl: String? = null,
    val renderUrl: String? = null,
    val artUrl: String? = null,
    val tint: Tint? = null,
    /** The catalogue's hero id, for Browse. */
    val catalogId: String? = null,
)

@Immutable
data class Tint(val container: Color, val content: Color)

@Immutable
data class PlayerVisual(val accountId: Long, val name: String, val avatarUrl: String? = null, val tint: Tint? = null) {
    val initial: String get() = (name.firstOrNull { it.isLetter() } ?: name.firstOrNull { it.isDigit() })?.uppercase() ?: "?"
}

/** A badge: [tier] and [subrank] (1–6) as Valve reports them, named from the API's rank list. */
@Immutable
data class RankVisual(val badge: Int, val tierName: String?, val imageUrl: String? = null) {
    val tier: Int get() = badge / 10
    val subrank: Int get() = badge % 10
    val label: String get() = listOfNotNull(tierName ?: "Rank $tier", subrank.takeIf { it in 1..6 }?.toString()).joinToString(" ")
}

sealed interface Moment {
    data object JustNow : Moment
    data class MinutesAgo(val minutes: Int) : Moment
    data class HoursAgo(val hours: Int) : Moment
    /** "21:40", for a match under its day's heading. */
    data class ClockTime(val hour: Int, val minute: Int) : Moment
    data object Today : Moment
    data object Yesterday : Moment
    /** 1 is Monday, as in ISO-8601. */
    data class Weekday(val dayOfWeek: Int) : Moment
    data class Date(val day: Int, val month: Int) : Moment
    data class WeekdayDate(val dayOfWeek: Int, val day: Int, val month: Int) : Moment
    data class MonthYear(val month: Int, val year: Int) : Moment
    /** "Today, 14:32", "Yesterday, 21:40" or "11 Oct, 22:18". */
    data class At(val day: Moment, val hour: Int, val minute: Int) : Moment
}

enum class Outcome { Win, Loss, Other }

enum class MatchKind { Ranked, Normal, Other }

/** What a ranked match did to the badge; [Unknown] when a ranked match's badge before or after was not reported. */
sealed interface RankChange {
    data object Unranked : RankChange
    data object NoChange : RankChange
    data object Unknown : RankChange
    /** A placement match that reported no badge yet. */
    data object Calibrating : RankChange
    /** The placement match that reported the account's first badge. */
    data class Placed(val to: RankVisual) : RankChange
    data class Up(val steps: Int, val to: RankVisual) : RankChange
    data class Down(val steps: Int, val to: RankVisual) : RankChange
}

@Immutable
data class MatchRowUi(
    val matchId: Long,
    val hero: HeroVisual?,
    val outcome: Outcome,
    val kind: MatchKind,
    val durationS: Int?,
    val level: Int?,
    val kills: Int?,
    val deaths: Int?,
    val assists: Int?,
    val kda: Double?,
    val souls: Int?,
    val heroDamage: Int?,
    val lastHits: Int?,
    val denies: Int?,
    val rankChange: RankChange,
    val playedAt: Moment?,
    val abandoned: Boolean = false,
    /** A just-finished match whose full metadata is still on its way. */
    val detailsPending: Boolean = false,
)

/** All-time numbers over the matches Hideout tracks for the account, which may not be its whole career. */
@Immutable
data class TrackedTotals(
    val matches: Int,
    val wins: Int,
    val losses: Int,
    val winRate: Double?,
    val kda: Double?,
    val playedSeconds: Long?,
    val heroes: Int,
)

@Immutable
data class Calibration(val played: Int, val of: Int?)

@Immutable
data class HeaderRank(
    val rank: RankVisual?,
    val calibration: Calibration? = null,
    /** Sub-rank steps between the first and last ranked match of the period; null when it cannot be told. */
    val change: Int? = null,
    /** "in 30 days", "this season", "all time". */
    val changePeriod: StatsPeriod,
    /** Share of ranked players at or above this badge, in percent. */
    val topPercent: Int? = null,
    /** The day of the ranked match that reported [rank], which may be long ago. */
    val lastRanked: Moment? = null,
)

@Immutable
data class StatsHeader(
    val player: PlayerVisual,
    val isYou: Boolean,
    val lastMatch: Moment?,
    val updated: Moment?,
    val mostPlayed: HeroVisual?,
    val winStreak: Int?,
    val lossStreak: Int?,
    val trackedSince: Moment?,
    val rank: HeaderRank?,
    val totals: TrackedTotals,
)

/** A change against the previous period of the same length, in the metric's own terms. */
@Immutable
data class Change(val amount: Double, val unit: ChangeUnit)

enum class ChangeUnit { Points, Percent, Plain }

enum class MetricKind { Kda, SoulsPerMin, DamagePerMin, Accuracy }

@Immutable
data class WinRateTile(
    val winRate: Double?,
    val wins: Int,
    val losses: Int,
    val change: Change?,
    /** The last twenty decided results, oldest first; true is a win. */
    val form: List<Boolean>,
)

@Immutable
data class MetricTile(
    val kind: MetricKind,
    val value: Double?,
    /** KDA: average kills, deaths, assists. Souls and damage: per match. Accuracy: crit share of hero hits. */
    val sub: List<Double?>,
    val change: Change?,
    /** The last twenty matches' values, oldest first; null where a match lacks the number. */
    val series: List<Double?>,
    /** Matches the value covers when fewer than the period's matches carry it. */
    val coverage: Coverage? = null,
)

/**
 * Who rank averages describe: players of [tierName], whose lobbies have a rank only in ranked matches. [rankedOnly]
 * says so when the filters also count normal matches. There is none for normal matches alone or an account without a
 * rank, so nothing is compared there.
 */
@Immutable
data class RankCohort(val tierName: String, val rankedOnly: Boolean)

@Immutable
data class Coverage(val covered: Int, val of: Int)

@Immutable
data class FeaturedHero(val hero: HeroVisual, val matches: Int, val playedSeconds: Long?, val winRate: Double?, val kda: Double?, val soulsPerMin: Double?)

@Immutable
data class HeroLine(val hero: HeroVisual, val matches: Int, val kda: Double?, val winRate: Double?)

/** A rank after a match; [position] is a continuous rank on the sub-rank scale when a source has one. */
@Immutable
data class RankPoint(val epochSeconds: Long, val badge: Int, val position: Double? = null)

/** A label at [fraction] of a chart's width. */
@Immutable
data class AxisLabel(val fraction: Float, val moment: Moment)

@Immutable
data class RankChart(
    val points: List<RankPoint>,
    val from: RankVisual?,
    val to: RankVisual?,
    val peak: RankVisual?,
    val peakAt: RankPoint?,
    val peakDay: Moment?,
    val rankedMatches: Int,
    val startEpoch: Long,
    val endEpoch: Long,
    /** Dates along the bottom at a regular step from the start; the last, at the end, reads "Today" when that is now. */
    val axisLabels: List<AxisLabel>,
    /** Names for badges on the grid, keyed by badge. */
    val names: Map<Int, String>,
)

/** The badge the account's latest ranked match reported, and its day. */
@Immutable
data class RankedAt(val rank: RankVisual, val day: Moment)

@Immutable
data class TeammateRow(
    val player: PlayerVisual,
    val party: Boolean,
    val lastPlayed: Moment?,
    val games: Int,
    val wins: Int,
    val winRate: Double?,
    val kda: Double?,
)

@Immutable
data class OpponentRow(val player: PlayerVisual, val usualHero: HeroVisual?, val met: Int, val winRate: Double?)

/**
 * The parts fetched beside the saved stats are [Loadable]: loading, failed (with a retry), or ready, and only a ready
 * answer is ever shown as empty.
 */
@Immutable
data class OverviewContent(
    val winRate: WinRateTile,
    val metrics: List<MetricTile>,
    val cohort: RankCohort?,
    /** The cohort's averages by metric; unused without a cohort. */
    val averages: Loadable<Map<MetricKind, Double>>,
    val recent: List<MatchRowUi>,
    val featured: FeaturedHero?,
    val heroes: List<HeroLine>,
    val rank: RankChart?,
    /** The account's rank and when it was last reported, for a view without ranked matches. */
    val lastRanked: RankedAt? = null,
    val teammates: Loadable<List<TeammateRow>>,
    /** The teammates' names and avatars, which arrive after the rows. */
    val names: Loadable<Unit>,
)

@Immutable
data class MatchDay(val day: Moment?, val wins: Int, val losses: Int, val rows: List<MatchRowUi>)

@Immutable
data class MatchesContent(
    val heroOptions: List<HeroVisual>,
    /** The hero filter's hero, named even when it has no match in this period. */
    val selectedHero: HeroVisual?,
    val total: Int,
    val wins: Int,
    val losses: Int,
    val days: List<MatchDay>,
    val shown: Int,
)

@Immutable
data class HeroStatRow(
    val hero: HeroVisual,
    val matches: Int,
    val wins: Int,
    val playedSeconds: Long?,
    val winRate: Double?,
    val kda: Double?,
    val soulsPerMin: Double?,
    val damagePerMin: Double?,
    val accuracy: Double?,
)

@Immutable
data class HeroVsRank(val hero: HeroVisual, val yours: Double, val rankAverage: Double)

enum class BenchmarkKind { SoulsPerMin, HeroDamagePerMin, Accuracy, CritRate, ObjectiveDamagePerMin }

@Immutable
data class BenchmarkRow(val kind: BenchmarkKind, val yours: Double?)

enum class ItemCategory { Weapon, Vitality, Spirit }

/** [code] replaces the generated two letters where two items would otherwise read the same. */
@Immutable
data class ItemVisual(val id: Long, val name: String, val category: ItemCategory?, val iconUrl: String? = null, val cost: Long? = null, val code: String? = null) {
    val initials: String get() = code ?: itemInitials(name)
}

@Immutable
data class BuildItem(val item: ItemVisual, val boughtIn: Int)

@Immutable
data class Matchup(val enemy: HeroVisual, val wins: Int, val losses: Int)

/** The enemy hero with the best winning record and the one with the worst losing record; either can be missing. */
@Immutable
data class Matchups(val best: Matchup?, val hardest: Matchup?)

@Immutable
data class HeroPanel(
    val hero: HeroVisual,
    val matches: Int,
    val winRate: Double?,
    val playedSeconds: Long?,
    val benchmarks: List<BenchmarkRow>,
    /** The cohort's averages for this hero; unused without a cohort. */
    val averages: Loadable<Map<BenchmarkKind, Double>>,
    val build: Loadable<List<BuildItem>>,
    val buildOf: Int,
    val matchups: Loadable<Matchups>,
)

@Immutable
data class HeroesContent(
    val rows: List<HeroStatRow>,
    val cohort: RankCohort?,
    /** Win rate against the cohort's for each hero; unused without a cohort. */
    val vsRank: Loadable<List<HeroVsRank>>,
    val panel: HeroPanel?,
)

@Immutable
data class SocialContent(
    /** Known once the API's party list has answered, since a party member with one game is listed too. */
    val teammates: Loadable<List<TeammateRow>>,
    val opponents: List<OpponentRow>,
    /** Everyone's names and avatars, which arrive after the rows. */
    val names: Loadable<Unit>,
    /** The opponents' most played heroes. */
    val usualHeroes: Loadable<Unit>,
)

sealed interface TabContent {
    data class Overview(val content: OverviewContent) : TabContent
    data class Matches(val content: MatchesContent) : TabContent
    data class Heroes(val content: HeroesContent) : TabContent
    data class Teammates(val content: SocialContent) : TabContent
}

@Immutable
data class SyncStatus(
    val refreshing: Boolean = false,
    /** Matches whose numbers are on their way, for "Fetching your matches · 120 of 248". */
    val progress: Coverage? = null,
    /** The last update failed; what is shown is the last saved copy. */
    val failed: Boolean = false,
)

@Immutable
data class LocalAccount(val accountId: Long, val steamId64: Long, val name: String?)

sealed interface AccountLookup {
    data object Idle : AccountLookup
    data object Searching : AccountLookup
    data class Found(val players: List<PlayerVisual>) : AccountLookup
    data object NotFound : AccountLookup
    data class Failed(val reason: LookupFailure) : AccountLookup
}

enum class LookupFailure { Offline, Unavailable, NameSearchUnavailable }

sealed interface StatsContent {
    /** Turned off in Settings. */
    data object Off : StatsContent

    /** No account chosen: the one Steam last signed in with on this PC, once its login file has been read. */
    data class Setup(val suggestion: Loadable<LocalAccount?>) : StatsContent

    data class Active(
        val accountId: Long,
        val header: StatsHeader?,
        val status: SyncStatus,
        /** The filters' matches and heroes; null while the period's dates are unknown. */
        val matchCount: Int?,
        val heroCount: Int?,
        /** Null while the tab's numbers load, or while the period's dates are unknown. */
        val tab: TabContent?,
        /** Nothing to show yet: the first fetch is under way or failed without a saved copy. */
        val empty: Boolean = false,
        /** Whether a ranked season is running, once the seasons' dates have arrived. */
        val season: Loadable<Boolean> = Loadable.Ready(true),
        /** The account is the one chosen as yours, however it was opened. */
        val isYou: Boolean = true,
    ) : StatsContent
}

const val MatchesPageSize = 20

sealed interface SearchResult {
    data class Match(val matchId: Long) : SearchResult
    data class Player(val player: PlayerVisual) : SearchResult
}

@Immutable
data class SearchState(
    val query: String = "",
    val results: List<SearchResult> = emptyList(),
    val searching: Boolean = false,
    val failed: LookupFailure? = null,
    /** Stats is turned off, so nothing is searched. */
    val off: Boolean = false,
)

/** Two letters for an item without its icon: "EC" for Extra Charge, "Sp" for a one-word name. */
fun itemInitials(name: String): String {
    val words = name.split(' ', '-').map { word -> word.filter { it.isLetterOrDigit() } }.filter { it.isNotEmpty() }
    return when {
        words.size >= 2 -> "${words[0].first()}${words[1].first()}".uppercase()
        words.size == 1 -> heroInitials(words[0])
        else -> "?"
    }
}

/** Two letters for a hero without its portrait: "Se" for Seven, "Mo" for Mo & Krill. */
fun heroInitials(name: String): String {
    val letters = name.filter { it.isLetter() }
    return when {
        letters.isEmpty() -> "?"
        letters.length == 1 -> letters.uppercase()
        else -> letters.take(1).uppercase() + letters.substring(1, 2).lowercase()
    }
}
