package gg.hideouthq.feature.library.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextOverflow
import gg.hideouthq.core.designsystem.component.ButtonSize
import gg.hideouthq.core.designsystem.component.ButtonVariant
import gg.hideouthq.core.designsystem.component.HideoutButton
import gg.hideouthq.core.designsystem.component.HideoutIcon
import gg.hideouthq.core.designsystem.icon.HideoutIcons
import gg.hideouthq.core.designsystem.theme.HideoutDimens
import gg.hideouthq.core.designsystem.theme.HideoutShapes
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import gg.hideouthq.core.ui.AlertModal
import gg.hideouthq.core.ui.CompatChip
import gg.hideouthq.core.ui.CompatDialog
import gg.hideouthq.core.ui.WindowModal
import gg.hideouthq.feature.library.CompatOpen
import gg.hideouthq.feature.library.CompatUi
import gg.hideouthq.feature.library.LibraryActions
import gg.hideouthq.resources.Res
import gg.hideouthq.resources.compat_bar_checking
import gg.hideouthq.resources.compat_bar_dismiss
import gg.hideouthq.resources.compat_bar_repairable
import gg.hideouthq.resources.compat_bar_repaired
import gg.hideouthq.resources.compat_bar_repairing
import gg.hideouthq.resources.compat_bar_restored
import gg.hideouthq.resources.compat_bar_restoring
import gg.hideouthq.resources.compat_bar_review
import gg.hideouthq.resources.compat_bar_unfinished
import gg.hideouthq.resources.compat_bar_waiting
import gg.hideouthq.resources.compat_close
import gg.hideouthq.resources.compat_repair_all
import gg.hideouthq.resources.compat_retry_all
import gg.hideouthq.resources.compat_review_lead
import gg.hideouthq.resources.compat_review_lead_automatic
import gg.hideouthq.resources.compat_review_title
import gg.hideouthq.resources.retry
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun CompatibilityBar(compat: CompatUi, actions: LibraryActions, modifier: Modifier = Modifier) {
    if (!compat.shown) return
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier.fillMaxWidth().background(scheme.surfaceContainerHigh, HideoutShapes.Row).padding(HideoutDimens.Library.FileRowHorizontalPadding),
        horizontalArrangement = Arrangement.spacedBy(HideoutDimens.Library.NoteGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val problems = compat.repairable.isNotEmpty() || compat.unfinished > 0
        Box(Modifier.size(HideoutDimens.Icon.Size), contentAlignment = Alignment.Center) {
            when {
                compat.working -> CircularProgressIndicator(Modifier.size(HideoutDimens.Icon.Small), color = scheme.tertiary, strokeWidth = HideoutDimens.Chip.SpinnerStroke)
                problems -> HideoutIcon(HideoutIcons.Warning, contentDescription = null, tint = scheme.tertiary)
                else -> HideoutIcon(HideoutIcons.Shield, contentDescription = null, tint = scheme.onSurfaceVariant)
            }
        }
        val finished = compat.finished
        val parts = listOfNotNull(
            compat.repairing.takeIf { it > 0 }?.let { pluralStringResource(Res.plurals.compat_bar_repairing, it, it) },
            compat.restoring.takeIf { it > 0 }?.let { pluralStringResource(Res.plurals.compat_bar_restoring, it, it) },
            compat.waiting.takeIf { it > 0 }?.let { pluralStringResource(Res.plurals.compat_bar_waiting, it, it) },
            finished?.repaired?.takeIf { it > 0 }?.let { pluralStringResource(Res.plurals.compat_bar_repaired, it, it) },
            finished?.restored?.takeIf { it > 0 }?.let { pluralStringResource(Res.plurals.compat_bar_restored, it, it) },
            compat.unfinished.takeIf { it > 0 }?.let { pluralStringResource(Res.plurals.compat_bar_unfinished, it, it) },
            compat.repairable.size.takeIf { it > 0 }?.let { pluralStringResource(Res.plurals.compat_bar_repairable, it, it) },
        )
        val text = (if (compat.checking) parts + stringResource(Res.string.compat_bar_checking) else parts).joinToString(" · ")
        Text(text, style = HideoutTheme.typography.notice, color = scheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (finished != null) HideoutButton(stringResource(Res.string.compat_bar_dismiss), actions::dismissCompat, variant = ButtonVariant.Text, size = ButtonSize.Small)
        if (compat.automatic && compat.retry.isNotEmpty()) HideoutButton(stringResource(Res.string.retry), actions::repairAll, variant = ButtonVariant.Tonal, size = ButtonSize.Small)
        else HideoutButton(stringResource(Res.string.compat_bar_review), actions::reviewCompat, variant = ButtonVariant.Tonal, size = ButtonSize.Small)
    }
}

@Composable
internal fun CompatibilityDialogs(compat: CompatUi, modal: WindowModal, actions: LibraryActions) {
    val mod = compat.open as? CompatOpen.Mod
    CompatDialog(
        title = mod?.title,
        install = mod?.install,
        enabled = mod?.enabled == true,
        unavailable = compat.unavailable,
        canChange = compat.canChange,
        modal = modal,
        onApply = { mod?.let { actions.applyRepair(it.installId) } },
        onOriginal = { mod?.let { actions.useOriginal(it.installId) } },
        onCheckAgain = actions::checkCompatibility,
        onDismiss = actions::closeCompat,
    )
    ReviewDialog(compat.open as? CompatOpen.Review, compat, modal, actions)
}

@Composable
private fun ReviewDialog(review: CompatOpen.Review?, compat: CompatUi, modal: WindowModal, actions: LibraryActions) {
    var last by remember { mutableStateOf(review) }
    if (review != null) last = review
    val shown = last ?: return
    val close = remember { FocusRequester() }
    val title = stringResource(Res.string.compat_review_title)
    AlertModal(visible = review != null, modal = modal, title = title, onDismiss = actions::closeCompat, initialFocus = close) {
        val scheme = MaterialTheme.colorScheme
        val type = HideoutTheme.typography
        var offered by remember { mutableIntStateOf(0) }
        if (compat.offered.isNotEmpty()) offered = compat.offered.size
        Column(verticalArrangement = Arrangement.spacedBy(HideoutDimens.AlertDialog.Gap)) {
            Text(title, style = type.titleDialog, color = scheme.onSurface)
            Text(stringResource(if (compat.automatic) Res.string.compat_review_lead_automatic else Res.string.compat_review_lead), style = type.bodyDialog, color = scheme.onSurfaceVariant)
            Column(
                Modifier.fillMaxWidth().heightIn(max = HideoutDimens.Library.UpdateListMaxHeight).background(scheme.surface, HideoutShapes.Row)
                    .verticalScroll(rememberScrollState()).padding(vertical = HideoutDimens.Library.FilesBoxVerticalPadding),
            ) {
                shown.items.forEach { item ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = HideoutDimens.Chip.HeightSmall)
                            .padding(horizontal = HideoutDimens.Library.FileRowHorizontalPadding, vertical = HideoutDimens.Library.FilesBoxVerticalPadding),
                        horizontalArrangement = Arrangement.spacedBy(HideoutDimens.Library.FileRowGap),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(item.title, style = type.titleRow, color = scheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        item.compat?.let { CompatChip(it, item.enabled, item.title, onClick = { actions.openCompat(item.installId) }) }
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(top = HideoutDimens.AlertDialog.FooterTopPadding),
                horizontalArrangement = Arrangement.spacedBy(HideoutDimens.AlertDialog.FooterGap, Alignment.End),
            ) {
                HideoutButton(
                    stringResource(Res.string.compat_close),
                    actions::closeCompat,
                    modifier = Modifier.focusRequester(close),
                    variant = ButtonVariant.Text,
                    size = ButtonSize.Regular,
                    contentPadding = PaddingValues(horizontal = HideoutDimens.AlertDialog.CancelPadding),
                )
                if (offered > 0) {
                    HideoutButton(
                        stringResource(if (compat.automatic) Res.string.compat_retry_all else Res.string.compat_repair_all, offered),
                        actions::repairAll,
                        leadingIcon = HideoutIcons.Shield,
                        leadingIconSize = HideoutDimens.Icon.Small,
                        enabled = review != null && compat.canChange && compat.offered.isNotEmpty(),
                    )
                }
            }
        }
    }
}
