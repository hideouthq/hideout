package gg.hideouthq.core.ui.catalog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import gg.hideouthq.core.data.CatalogFreshness
import gg.hideouthq.core.data.DataError
import gg.hideouthq.core.designsystem.component.ButtonVariant
import gg.hideouthq.core.designsystem.component.EmptyState
import gg.hideouthq.core.designsystem.component.HideoutButton
import gg.hideouthq.core.designsystem.component.HideoutIconButton
import gg.hideouthq.core.designsystem.component.HideoutTooltip
import gg.hideouthq.core.designsystem.icon.HideoutIcons
import gg.hideouthq.core.designsystem.theme.HideoutDimens
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import gg.hideouthq.core.model.CalendarDate
import gg.hideouthq.core.ui.ModLabels
import gg.hideouthq.core.ui.busyWhen
import gg.hideouthq.resources.Res
import gg.hideouthq.resources.busy_catalogue_body
import gg.hideouthq.resources.busy_checks
import gg.hideouthq.resources.busy_gamebanana
import gg.hideouthq.resources.catalog_check_failed_offline
import gg.hideouthq.resources.catalog_check_failed_unavailable
import gg.hideouthq.resources.catalog_checking
import gg.hideouthq.resources.catalog_checking_progress
import gg.hideouthq.resources.catalog_last_checked
import gg.hideouthq.resources.catalog_refresh
import gg.hideouthq.resources.retry
import gg.hideouthq.resources.sync_failed_offline
import gg.hideouthq.resources.sync_failed_title
import gg.hideouthq.resources.sync_failed_unavailable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * This screen state once the catalogue has data. Until then the catalogue's streams are silent, so
 * the state comes from [placeholder] instead, given the freshness so far, including any check under
 * way, so a cold load can still show its progress.
 */
fun <T : Any> Flow<T>.whileCatalogLoads(freshness: Flow<CatalogFreshness>, placeholder: (CatalogFreshness) -> T): Flow<T> =
    combine(map<T, T?> { it }.onStart { emit(null) }, freshness) { content, status ->
        content ?: placeholder(status)
    }

@Composable
fun CatalogBusy(until: Instant, modifier: Modifier = Modifier) {
    EmptyState(
        modifier = modifier,
        icon = HideoutIcons.Clock,
        title = stringResource(Res.string.busy_gamebanana),
        message = stringResource(Res.string.busy_catalogue_body, busyWhen(until)),
    )
}

@Composable
fun CatalogLoadError(error: DataError, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    EmptyState(
        modifier = modifier,
        icon = HideoutIcons.Warning,
        title = stringResource(Res.string.sync_failed_title),
        message = stringResource(if (error == DataError.Offline) Res.string.sync_failed_offline else Res.string.sync_failed_unavailable),
        action = { HideoutButton(stringResource(Res.string.retry), onRetry, variant = ButtonVariant.Tonal) },
    )
}

@Composable
fun CatalogFreshnessBar(freshness: CatalogFreshness, onRefresh: () -> Unit, modifier: Modifier = Modifier) {
    val running = freshness.running
    val error = freshness.error
    val checkedAt = freshness.checkedAt
    val busy = freshness.busyUntil
    val text = when {
        running != null && busy != null -> stringResource(Res.string.busy_checks, busyWhen(busy))
        running != null -> running.fraction?.let { stringResource(Res.string.catalog_checking_progress, (it * 100).toInt()) }
            ?: stringResource(Res.string.catalog_checking)
        error == DataError.Offline -> stringResource(Res.string.catalog_check_failed_offline)
        error != null -> stringResource(Res.string.catalog_check_failed_unavailable)
        else -> null
    }
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(HideoutDimens.Pill.RowGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (text != null) {
            Text(
                text = text,
                style = HideoutTheme.typography.captionSmall,
                color = if (error != null && running == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
        val refresh = @Composable { HideoutIconButton(HideoutIcons.Refresh, stringResource(Res.string.catalog_refresh), onRefresh, iconSize = HideoutDimens.Icon.Small, enabled = running == null) }
        if (checkedAt != null && text == null) HideoutTooltip(stringResource(Res.string.catalog_last_checked, checkedLabel(checkedAt))) { refresh() } else refresh()
    }
}

@Composable
private fun checkedLabel(checkedAt: Long): String {
    val zone = ZoneId.systemDefault()
    val at = java.time.Instant.ofEpochSecond(checkedAt).atZone(zone)
    return if (at.toLocalDate() == LocalDate.now(zone)) {
        ModLabels.clock(at.hour, at.minute)
    } else {
        ModLabels.dayMonth(CalendarDate(at.year, at.monthValue, at.dayOfMonth))
    }
}
