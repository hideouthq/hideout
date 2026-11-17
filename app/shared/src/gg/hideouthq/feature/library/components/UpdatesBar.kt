package gg.hideouthq.feature.library.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import gg.hideouthq.core.designsystem.component.ButtonSize
import gg.hideouthq.core.designsystem.component.ButtonVariant
import gg.hideouthq.core.designsystem.component.HideoutButton
import gg.hideouthq.core.designsystem.icon.HideoutIcons
import gg.hideouthq.core.designsystem.theme.HideoutDimens
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import gg.hideouthq.core.ui.busyWhen
import gg.hideouthq.feature.library.LibraryActions
import gg.hideouthq.feature.library.LibraryFilter
import gg.hideouthq.feature.library.UpdatesUi
import gg.hideouthq.resources.Res
import gg.hideouthq.resources.busy_checks
import gg.hideouthq.resources.library_updates_all
import gg.hideouthq.resources.library_updates_available
import gg.hideouthq.resources.library_updates_check
import gg.hideouthq.resources.library_updates_checking
import gg.hideouthq.resources.library_updates_keep_all
import gg.hideouthq.resources.library_updates_review
import gg.hideouthq.resources.library_updates_unchecked
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun UpdatesBar(updates: UpdatesUi, canInstall: Boolean, actions: LibraryActions, modifier: Modifier = Modifier) {
    if (!updates.checkable) return
    Row(
        modifier.fillMaxWidth().padding(horizontal = HideoutDimens.Library.HeaderHorizontalPadding),
        horizontalArrangement = Arrangement.spacedBy(HideoutDimens.Library.ToolbarGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(summary(updates), style = HideoutTheme.typography.captionSmall, color = MaterialTheme.colorScheme.outline, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (updates.review > 0 && !updates.checking) {
            HideoutButton(pluralStringResource(Res.plurals.library_updates_review, updates.review, updates.review), { actions.setFilter(LibraryFilter.Review) }, variant = ButtonVariant.Text, size = ButtonSize.Small)
            HideoutButton(stringResource(Res.string.library_updates_keep_all), actions::keepAllCurrentFiles, variant = ButtonVariant.Text, size = ButtonSize.Small)
        }
        HideoutButton(
            stringResource(Res.string.library_updates_check),
            actions::checkForUpdates,
            variant = ButtonVariant.Text,
            size = ButtonSize.Small,
            leadingIcon = HideoutIcons.Refresh,
            leadingIconSize = HideoutDimens.Icon.Small,
            enabled = !updates.checking,
        )
        if (updates.available > 0) {
            HideoutButton(
                stringResource(Res.string.library_updates_all, updates.available),
                actions::updateAll,
                variant = ButtonVariant.Tonal,
                size = ButtonSize.Small,
                leadingIcon = HideoutIcons.Download,
                leadingIconSize = HideoutDimens.Icon.Small,
                enabled = canInstall,
            )
        }
    }
}

@Composable
private fun summary(updates: UpdatesUi): String {
    updates.busyUntil?.takeIf { updates.checking }?.let { return stringResource(Res.string.busy_checks, busyWhen(it)) }
    if (updates.checking) return stringResource(Res.string.library_updates_checking)
    val parts = listOfNotNull(
        updates.available.takeIf { it > 0 }?.let { pluralStringResource(Res.plurals.library_updates_available, it, it) },
        updates.unchecked.takeIf { it > 0 }?.let { pluralStringResource(Res.plurals.library_updates_unchecked, it, it) },
    )
    return parts.joinToString(" · ")
}
