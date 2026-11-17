package gg.hideouthq.feature.appupdate

import androidx.compose.runtime.Composable
import gg.hideouthq.core.appupdate.AppUpdatePhase
import gg.hideouthq.core.appupdate.RestartStage
import gg.hideouthq.core.appupdate.UpdateFailure
import gg.hideouthq.core.appupdate.WhatsNewHeadline
import gg.hideouthq.core.model.CalendarDate
import gg.hideouthq.resources.Res
import gg.hideouthq.resources.app_update_available_line
import gg.hideouthq.resources.app_update_checked_days
import gg.hideouthq.resources.app_update_checked_hours
import gg.hideouthq.resources.app_update_checked_just_now
import gg.hideouthq.resources.app_update_checked_minutes
import gg.hideouthq.resources.app_update_confirm_password
import gg.hideouthq.resources.app_update_downloading_line
import gg.hideouthq.resources.app_update_failed_connection
import gg.hideouthq.resources.app_update_failed_disk
import gg.hideouthq.resources.app_update_failed_install
import gg.hideouthq.resources.app_update_failed_mismatch
import gg.hideouthq.resources.app_update_failed_not_replaced
import gg.hideouthq.resources.app_update_failed_server
import gg.hideouthq.resources.app_update_failed_unwritable
import gg.hideouthq.resources.app_update_headline_available
import gg.hideouthq.resources.app_update_headline_installed
import gg.hideouthq.resources.app_update_headline_installed_undated
import gg.hideouthq.resources.app_update_headline_ready
import gg.hideouthq.resources.app_update_ready_line
import gg.hideouthq.resources.app_update_waiting_mods
import gg.hideouthq.resources.date_day_month_year
import gg.hideouthq.resources.months_long
import gg.hideouthq.resources.size_mb
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringArrayResource
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Instant

private const val BytesPerMegabyte = 1_048_576L
private const val Tenths = 10L

internal fun updateMegabytes(bytes: Long): String {
    val tenths = (bytes * Tenths + BytesPerMegabyte / 2) / BytesPerMegabyte
    return "${tenths / Tenths}.${tenths % Tenths}"
}

@Composable
internal fun updateSize(bytes: Long): String = stringResource(Res.string.size_mb, updateMegabytes(bytes))

@Composable
internal fun phaseLine(phase: AppUpdatePhase): String = when (phase) {
    AppUpdatePhase.Idle -> ""
    is AppUpdatePhase.GitHubOnly -> phase.version.toString()
    is AppUpdatePhase.Available -> stringResource(Res.string.app_update_available_line, phase.version.toString(), updateSize(phase.pkg.sizeBytes))
    is AppUpdatePhase.Downloading -> stringResource(Res.string.app_update_downloading_line, updateMegabytes(phase.doneBytes), updateSize(phase.pkg.sizeBytes))
    is AppUpdatePhase.Ready -> when (phase.restart) {
        RestartStage.Waiting -> stringResource(Res.string.app_update_waiting_mods)
        RestartStage.Installing -> stringResource(Res.string.app_update_confirm_password)
        RestartStage.Idle, RestartStage.Exiting -> stringResource(Res.string.app_update_ready_line, phase.version.toString())
    }
    is AppUpdatePhase.Failed -> if (phase.why == UpdateFailure.NotReplaced) stringResource(Res.string.app_update_failed_not_replaced, phase.version.toString()) else failureLine(phase.why)
}

@Composable
private fun failureLine(why: UpdateFailure): String = stringResource(
    when (why) {
        UpdateFailure.Connection -> Res.string.app_update_failed_connection
        UpdateFailure.Server -> Res.string.app_update_failed_server
        UpdateFailure.Mismatch -> Res.string.app_update_failed_mismatch
        UpdateFailure.Disk -> Res.string.app_update_failed_disk
        UpdateFailure.Install -> Res.string.app_update_failed_install
        UpdateFailure.Unwritable -> Res.string.app_update_failed_unwritable
        UpdateFailure.NotReplaced -> Res.string.app_update_failed_not_replaced
    },
)

@Composable
internal fun headlineLine(headline: WhatsNewHeadline): String = when (headline) {
    is WhatsNewHeadline.Available -> stringResource(Res.string.app_update_headline_available, headline.version.toString(), headline.running.toString())
    is WhatsNewHeadline.Ready -> stringResource(Res.string.app_update_headline_ready, headline.version.toString(), headline.running.toString())
    is WhatsNewHeadline.Installed -> headline.released?.let { stringResource(Res.string.app_update_headline_installed, headline.running.toString(), longDate(it)) }
        ?: stringResource(Res.string.app_update_headline_installed_undated, headline.running.toString())
}

@Composable
internal fun longDate(date: CalendarDate): String =
    stringResource(Res.string.date_day_month_year, date.day, stringArrayResource(Res.array.months_long)[date.month - 1], date.year)

@Composable
internal fun checkedAgo(checkedAt: Instant, now: Instant): String {
    val since = now - checkedAt
    val minutes = since.inWholeMinutes.toInt()
    val hours = since.inWholeHours.toInt()
    val days = since.inWholeDays.toInt()
    return when {
        minutes < 1 -> stringResource(Res.string.app_update_checked_just_now)
        hours < 1 -> pluralStringResource(Res.plurals.app_update_checked_minutes, minutes, minutes)
        days < 1 -> pluralStringResource(Res.plurals.app_update_checked_hours, hours, hours)
        else -> pluralStringResource(Res.plurals.app_update_checked_days, days, days)
    }
}
