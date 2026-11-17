package gg.hideouthq.feature.imports

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import gg.hideouthq.core.designsystem.component.ButtonVariant
import gg.hideouthq.core.designsystem.component.HideoutButton
import gg.hideouthq.core.designsystem.theme.HideoutDimens
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import gg.hideouthq.core.ui.AlertModal
import gg.hideouthq.core.ui.WindowModal
import gg.hideouthq.resources.Res
import gg.hideouthq.resources.import_dialog_cancel
import org.jetbrains.compose.resources.stringResource

/**
 * Library's import: the import page over [picker], shown while it is set. Closing never stops an import, and the dialog
 * closes itself once the run finished or waits, since Library then shows the mods or says what the run waits for. A run
 * that stopped keeps it open with why.
 */
@Composable
fun ImportDialog(picker: ImportPicker?, modal: WindowModal, onClose: () -> Unit) {
    var last by remember { mutableStateOf(picker) }
    if (picker != null) last = picker
    val shown = last ?: return
    val ui by shown.ui.collectAsStateWithLifecycle(ImportUi())
    val close = remember { FocusRequester() }
    val (title, lead) = importHeading(ui)
    if (picker != null && ui.settled) LaunchedEffect(Unit) { onClose() }
    AlertModal(visible = picker != null, modal = modal, title = title, onDismiss = onClose, initialFocus = close, width = DialogWidth) {
        val scheme = MaterialTheme.colorScheme
        val type = HideoutTheme.typography
        Column(verticalArrangement = Arrangement.spacedBy(HideoutDimens.AlertDialog.Gap)) {
            Column {
                ui.from?.let { from -> Text(fromLine(from), style = type.labelBadge, color = scheme.outline, modifier = Modifier.padding(bottom = 6.dp)) }
                Text(title, style = type.titleDialog, color = scheme.onSurface)
                Text(lead, style = type.bodyDialog, color = scheme.onSurfaceVariant, modifier = Modifier.padding(top = HideoutDimens.AlertDialog.BodyGap))
            }
            ImportContent(ui, shown, Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()))
            Row(Modifier.fillMaxWidth().padding(top = HideoutDimens.AlertDialog.FooterTopPadding), horizontalArrangement = Arrangement.spacedBy(HideoutDimens.AlertDialog.FooterGap)) {
                Spacer(Modifier.weight(1f))
                HideoutButton(stringResource(Res.string.import_dialog_cancel), onClose, Modifier.focusRequester(close), variant = ButtonVariant.Text)
                if (!ui.listing) HideoutButton(importLabel(ui), shown::startImport, enabled = ui.canImport)
            }
        }
    }
}

private val DialogWidth = 580.dp
