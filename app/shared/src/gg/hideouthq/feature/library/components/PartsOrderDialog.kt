package gg.hideouthq.feature.library.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextOverflow
import gg.hideouthq.core.designsystem.component.ButtonVariant
import gg.hideouthq.core.designsystem.component.HideoutButton
import gg.hideouthq.core.designsystem.component.HideoutIconButton
import gg.hideouthq.core.designsystem.icon.HideoutIcons
import gg.hideouthq.core.designsystem.theme.HideoutDimens
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import gg.hideouthq.core.library.InstalledSet
import gg.hideouthq.core.library.ManifestRecord
import gg.hideouthq.core.library.SetKey
import gg.hideouthq.core.ui.AlertModal
import gg.hideouthq.core.ui.WindowModal
import gg.hideouthq.resources.Res
import gg.hideouthq.resources.install_dialog_part_down
import gg.hideouthq.resources.install_dialog_part_up
import gg.hideouthq.resources.order_move_cancel
import gg.hideouthq.resources.parts_order_body
import gg.hideouthq.resources.parts_order_save
import gg.hideouthq.resources.parts_order_title
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun PartsOrderDialog(record: ManifestRecord?, modal: WindowModal, onSave: (List<SetKey>) -> Unit, onDismiss: () -> Unit) {
    var shown by remember { mutableStateOf(record) }
    if (record != null) shown = record
    val mod = shown ?: return
    val keys = mod.sets.map { it.key }
    var order by remember(mod, record != null) { mutableStateOf(keys) }
    val focus = remember { FocusRequester() }
    val title = stringResource(Res.string.parts_order_title, mod.title)
    AlertModal(visible = record != null, modal = modal, title = title, onDismiss = onDismiss, initialFocus = focus) {
        val scheme = MaterialTheme.colorScheme
        val dimens = HideoutDimens.InstallDialog
        Column(verticalArrangement = Arrangement.spacedBy(HideoutDimens.AlertDialog.Gap)) {
            Text(title, style = HideoutTheme.typography.titleDialog, color = scheme.onSurface)
            Text(stringResource(Res.string.parts_order_body), style = HideoutTheme.typography.bodyDialog, color = scheme.onSurfaceVariant)
            Column(verticalArrangement = Arrangement.spacedBy(dimens.RowGap)) {
                order.forEachIndexed { index, key ->
                    val set = mod.set(key)
                    val name = partName(set)
                    fun move(by: Int) {
                        order = order.toMutableList().apply { add(index + by, removeAt(index)) }
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = dimens.RowMinHeight)
                            .background(scheme.surfaceContainerHigh, RoundedCornerShape(dimens.RowRadius))
                            .padding(start = dimens.RowStartPadding, end = dimens.RowEndPadding, top = dimens.RowVerticalPadding, bottom = dimens.RowVerticalPadding),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(dimens.RowLineGap)) {
                            Text(name, style = HideoutTheme.typography.titleSetting, color = scheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(partPlace(set, mod), style = HideoutTheme.typography.mono, color = scheme.outline, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (index > 0) HideoutIconButton(HideoutIcons.ArrowUp, stringResource(Res.string.install_dialog_part_up, name), { move(-1) })
                        if (index < order.lastIndex) HideoutIconButton(HideoutIcons.ArrowDown, stringResource(Res.string.install_dialog_part_down, name), { move(1) })
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = HideoutDimens.AlertDialog.FooterTopPadding),
                horizontalArrangement = Arrangement.spacedBy(HideoutDimens.AlertDialog.FooterGap, Alignment.End),
            ) {
                HideoutButton(stringResource(Res.string.order_move_cancel), onDismiss, Modifier.focusRequester(focus), variant = ButtonVariant.Text)
                HideoutButton(stringResource(Res.string.parts_order_save), {
                    onSave(order)
                    onDismiss()
                }, enabled = order != keys)
            }
        }
    }
}

private fun partPlace(set: InstalledSet, mod: ManifestRecord): String = set.componentKey.substringBeforeLast('/', "").ifEmpty {
    if (mod.sets.count { it.archiveName == set.archiveName } > 1) set.componentKey else set.archiveName
}

private fun partName(set: InstalledSet): String =
    set.componentKey.substringBeforeLast('/', "").substringAfterLast('/').takeIf { it.isNotEmpty() } ?: set.variant ?: set.archiveName
