package gg.hideouthq.feature.stats.components

import androidx.compose.runtime.Composable
import gg.hideouthq.core.data.Loadable
import gg.hideouthq.core.ui.ModLabels
import gg.hideouthq.feature.stats.HeroVisual
import gg.hideouthq.feature.stats.Lane
import gg.hideouthq.feature.stats.MatchKind
import gg.hideouthq.feature.stats.MatchPlayer
import gg.hideouthq.feature.stats.Moment
import gg.hideouthq.feature.stats.ObjectiveEvent
import gg.hideouthq.feature.stats.ObjectiveKind
import gg.hideouthq.feature.stats.Outcome
import gg.hideouthq.feature.stats.PlayerVisual
import gg.hideouthq.feature.stats.StatsFormat
import gg.hideouthq.feature.stats.StatsPeriod
import gg.hideouthq.feature.stats.Team
import gg.hideouthq.resources.Res
import gg.hideouthq.resources.date_day_month
import gg.hideouthq.resources.months_short
import gg.hideouthq.resources.stats_clock
import gg.hideouthq.resources.stats_day_at
import gg.hideouthq.resources.stats_hours
import gg.hideouthq.resources.stats_hours_ago
import gg.hideouthq.resources.stats_in_all_time
import gg.hideouthq.resources.stats_in_month
import gg.hideouthq.resources.stats_in_season
import gg.hideouthq.resources.stats_in_week
import gg.hideouthq.resources.stats_just_now
import gg.hideouthq.resources.stats_lane_base_guardian
import gg.hideouthq.resources.stats_lane_blue
import gg.hideouthq.resources.stats_lane_green
import gg.hideouthq.resources.stats_lane_guardian
import gg.hideouthq.resources.stats_lane_purple
import gg.hideouthq.resources.stats_lane_walker
import gg.hideouthq.resources.stats_lane_yellow
import gg.hideouthq.resources.stats_last_month
import gg.hideouthq.resources.stats_last_week
import gg.hideouthq.resources.stats_loss
import gg.hideouthq.resources.stats_mark_died
import gg.hideouthq.resources.stats_mark_killed
import gg.hideouthq.resources.stats_mark_killed_you
import gg.hideouthq.resources.stats_mark_you_died
import gg.hideouthq.resources.stats_mark_you_killed
import gg.hideouthq.resources.stats_minutes
import gg.hideouthq.resources.stats_minutes_ago
import gg.hideouthq.resources.stats_mode_normal
import gg.hideouthq.resources.stats_mode_ranked
import gg.hideouthq.resources.stats_month_year
import gg.hideouthq.resources.stats_no_value
import gg.hideouthq.resources.stats_not_scored
import gg.hideouthq.resources.stats_objective_base_guardian_lower
import gg.hideouthq.resources.stats_objective_guardian_lower
import gg.hideouthq.resources.stats_objective_killed_by
import gg.hideouthq.resources.stats_objective_lost
import gg.hideouthq.resources.stats_objective_mid_boss_lower
import gg.hideouthq.resources.stats_objective_patron_lower
import gg.hideouthq.resources.stats_objective_patron_weakened_lower
import gg.hideouthq.resources.stats_objective_shrine_lower
import gg.hideouthq.resources.stats_objective_team_took
import gg.hideouthq.resources.stats_objective_took
import gg.hideouthq.resources.stats_objective_walker_lower
import gg.hideouthq.resources.stats_period_all
import gg.hideouthq.resources.stats_period_month
import gg.hideouthq.resources.stats_period_season
import gg.hideouthq.resources.stats_period_week
import gg.hideouthq.resources.stats_team_archmother
import gg.hideouthq.resources.stats_team_hidden_king
import gg.hideouthq.resources.stats_today
import gg.hideouthq.resources.stats_today_lower
import gg.hideouthq.resources.stats_unknown_hero
import gg.hideouthq.resources.stats_unknown_player
import gg.hideouthq.resources.stats_weekday_date
import gg.hideouthq.resources.stats_weekdays_short
import gg.hideouthq.resources.stats_win
import gg.hideouthq.resources.stats_yesterday
import gg.hideouthq.resources.stats_yesterday_lower
import org.jetbrains.compose.resources.stringArrayResource
import org.jetbrains.compose.resources.stringResource

private fun twoDigits(value: Int) = value.toString().padStart(2, '0')

internal object StatsLabels {
    const val Separator = ModLabels.Separator

    @Composable
    fun moment(moment: Moment, capitalized: Boolean = false): String {
        val months = stringArrayResource(Res.array.months_short)
        val weekdays = stringArrayResource(Res.array.stats_weekdays_short)
        fun month(number: Int) = months.getOrElse(number - 1) { "$number" }
        fun weekday(number: Int) = weekdays.getOrElse(number - 1) { "$number" }
        val text = when (moment) {
            Moment.JustNow -> stringResource(Res.string.stats_just_now)
            is Moment.MinutesAgo -> stringResource(Res.string.stats_minutes_ago, moment.minutes)
            is Moment.HoursAgo -> stringResource(Res.string.stats_hours_ago, moment.hours)
            is Moment.ClockTime -> stringResource(Res.string.stats_clock, twoDigits(moment.hour), twoDigits(moment.minute))
            Moment.Today -> stringResource(if (capitalized) Res.string.stats_today else Res.string.stats_today_lower)
            Moment.Yesterday -> stringResource(if (capitalized) Res.string.stats_yesterday else Res.string.stats_yesterday_lower)
            is Moment.Weekday -> weekday(moment.dayOfWeek)
            is Moment.Date -> stringResource(Res.string.date_day_month, moment.day, month(moment.month))
            is Moment.WeekdayDate -> stringResource(Res.string.stats_weekday_date, weekday(moment.dayOfWeek), moment.day, month(moment.month))
            is Moment.MonthYear -> stringResource(Res.string.stats_month_year, month(moment.month), moment.year)
            is Moment.At -> stringResource(Res.string.stats_day_at, moment(moment.day, capitalized), twoDigits(moment.hour), twoDigits(moment.minute))
        }
        return if (capitalized) text.replaceFirstChar { it.uppercase() } else text
    }

    @Composable
    fun heroName(hero: HeroVisual?): String = hero?.name?.takeIf { it.isNotEmpty() } ?: stringResource(Res.string.stats_unknown_hero)

    @Composable
    fun playerName(player: PlayerVisual): String = player.name.takeIf { it.isNotEmpty() } ?: stringResource(Res.string.stats_unknown_player)

    @Composable
    fun playerName(player: PlayerVisual, names: Loadable<Unit>): String =
        if (player.name.isEmpty() && names is Loadable.Failed) none() else playerName(player)

    @Composable
    fun playerOrHero(player: MatchPlayer): String = player.player.name.takeIf { it.isNotEmpty() } ?: heroName(player.hero)

    @Composable
    fun objectiveName(event: ObjectiveEvent): String {
        val lane = event.lane?.let { lane ->
            stringResource(
                when (lane) {
                    Lane.Yellow -> Res.string.stats_lane_yellow
                    Lane.Green -> Res.string.stats_lane_green
                    Lane.Blue -> Res.string.stats_lane_blue
                    Lane.Purple -> Res.string.stats_lane_purple
                },
            )
        }
        return when (event.kind) {
            ObjectiveKind.Guardian -> lane?.let { stringResource(Res.string.stats_lane_guardian, it) } ?: stringResource(Res.string.stats_objective_guardian_lower)
            ObjectiveKind.Walker -> lane?.let { stringResource(Res.string.stats_lane_walker, it) } ?: stringResource(Res.string.stats_objective_walker_lower)
            ObjectiveKind.BaseGuardian -> lane?.let { stringResource(Res.string.stats_lane_base_guardian, it) } ?: stringResource(Res.string.stats_objective_base_guardian_lower)
            ObjectiveKind.Shrine -> stringResource(Res.string.stats_objective_shrine_lower)
            ObjectiveKind.PatronWeakened -> stringResource(Res.string.stats_objective_patron_weakened_lower)
            ObjectiveKind.Patron -> stringResource(Res.string.stats_objective_patron_lower)
            ObjectiveKind.MidBoss -> stringResource(Res.string.stats_objective_mid_boss_lower)
        }
    }

    @Composable
    fun objectiveLine(event: ObjectiveEvent, perspective: Team, spectator: Boolean): String {
        val name = objectiveName(event)
        val taker = event.takenBy
        val line = when {
            taker == null -> name.replaceFirstChar { it.uppercase() }
            spectator -> stringResource(Res.string.stats_objective_team_took, team(taker), name)
            taker == perspective -> stringResource(Res.string.stats_objective_took, name)
            else -> stringResource(Res.string.stats_objective_lost, name)
        }
        val killer = event.killedBy?.takeIf { event.kind == ObjectiveKind.MidBoss && it != taker }
        return if (killer != null) stringResource(Res.string.stats_objective_killed_by, line, team(killer)) else line
    }

    @Composable
    fun markLine(mark: TimelineMark, players: Map<Long, MatchPlayer>): String {
        val victim = players[mark.death.victim]
        val killer = mark.death.killer?.let(players::get)
        return when {
            killer == null -> victim?.let { if (it.isYou) stringResource(Res.string.stats_mark_you_died) else stringResource(Res.string.stats_mark_died, playerOrHero(it)) }.orEmpty()
            killer.isYou -> stringResource(Res.string.stats_mark_you_killed, victim?.let { playerOrHero(it) }.orEmpty())
            victim?.isYou == true -> stringResource(Res.string.stats_mark_killed_you, playerOrHero(killer))
            else -> stringResource(Res.string.stats_mark_killed, playerOrHero(killer), victim?.let { playerOrHero(it) }.orEmpty())
        }
    }

    @Composable
    fun outcome(outcome: Outcome): String = stringResource(
        when (outcome) {
            Outcome.Win -> Res.string.stats_win
            Outcome.Loss -> Res.string.stats_loss
            Outcome.Other -> Res.string.stats_not_scored
        },
    )

    @Composable
    fun kind(kind: MatchKind): String? = when (kind) {
        MatchKind.Ranked -> stringResource(Res.string.stats_mode_ranked)
        MatchKind.Normal -> stringResource(Res.string.stats_mode_normal)
        MatchKind.Other -> null
    }

    @Composable
    fun team(team: Team): String = stringResource(if (team == Team.Amber) Res.string.stats_team_hidden_king else Res.string.stats_team_archmother)

    @Composable
    fun period(period: StatsPeriod): String = stringResource(
        when (period) {
            StatsPeriod.Week -> Res.string.stats_period_week
            StatsPeriod.Month -> Res.string.stats_period_month
            StatsPeriod.Season -> Res.string.stats_period_season
            StatsPeriod.AllTime -> Res.string.stats_period_all
        },
    )

    @Composable
    fun periodPhrase(period: StatsPeriod): String = stringResource(
        when (period) {
            StatsPeriod.Week -> Res.string.stats_in_week
            StatsPeriod.Month -> Res.string.stats_in_month
            StatsPeriod.Season -> Res.string.stats_in_season
            StatsPeriod.AllTime -> Res.string.stats_in_all_time
        },
    )

    @Composable
    fun periodTail(period: StatsPeriod): String = when (period) {
        StatsPeriod.Week -> stringResource(Res.string.stats_last_week)
        StatsPeriod.Month -> stringResource(Res.string.stats_last_month)
        else -> periodPhrase(period)
    }

    @Composable
    fun played(seconds: Long?): String = when {
        seconds == null -> none()
        seconds >= 3600 -> stringResource(Res.string.stats_hours, StatsFormat.hours(seconds))
        else -> stringResource(Res.string.stats_minutes, (seconds / 60).toInt())
    }

    @Composable
    fun none(): String = stringResource(Res.string.stats_no_value)

    @Composable
    fun count(value: Number?): String = value?.let(StatsFormat::count) ?: none()

    @Composable
    fun thousands(value: Number?): String = value?.let(StatsFormat::thousands) ?: none()

    @Composable
    fun decimal(value: Double?): String = value?.let(StatsFormat::decimal) ?: none()

    @Composable
    fun percent(fraction: Double?): String = fraction?.let { "${StatsFormat.percentOf(it)}%" } ?: none()

    @Composable
    fun duration(seconds: Int?): String = seconds?.let(StatsFormat::duration) ?: none()
}
