package gg.hideouthq.feature.crosshairs.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import gg.hideouthq.core.designsystem.component.ButtonVariant
import gg.hideouthq.core.designsystem.component.HideoutButton
import gg.hideouthq.core.designsystem.component.TextInput
import gg.hideouthq.core.designsystem.theme.HideoutDimens
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import gg.hideouthq.core.ui.AlertModal
import gg.hideouthq.core.ui.WindowModal
import gg.hideouthq.feature.crosshairs.CrosshairsActions
import gg.hideouthq.feature.crosshairs.ImportUi
import gg.hideouthq.resources.Res
import gg.hideouthq.resources.crosshairs_import_add
import gg.hideouthq.resources.crosshairs_import_body
import gg.hideouthq.resources.crosshairs_import_cancel
import gg.hideouthq.resources.crosshairs_import_field
import gg.hideouthq.resources.crosshairs_import_placeholder
import gg.hideouthq.resources.crosshairs_import_title
import gg.hideouthq.resources.crosshairs_import_unreadable
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun ImportDialog(importing: ImportUi?, modal: WindowModal, actions: CrosshairsActions) {
    val field = remember { FocusRequester() }
    var shown by remember { mutableStateOf(ImportUi()) }
    if (importing != null) shown = importing
    val title = stringResource(Res.string.crosshairs_import_title)
    AlertModal(visible = importing != null, modal = modal, title = title, onDismiss = actions::dismissImport, initialFocus = field) {
        val scheme = MaterialTheme.colorScheme
        val type = HideoutTheme.typography
        Column(verticalArrangement = Arrangement.spacedBy(HideoutDimens.AlertDialog.Gap)) {
            Text(title, style = type.titleDialog, color = scheme.onSurface)
            Text(stringResource(Res.string.crosshairs_import_body), style = type.bodyDialog, color = scheme.onSurfaceVariant)
            TextInput(
                shown.text, actions::setImportText, stringResource(Res.string.crosshairs_import_field), Modifier.fillMaxWidth().focusRequester(field),
                placeholder = stringResource(Res.string.crosshairs_import_placeholder), onSubmit = actions::confirmImport, lineBreaks = true,
            )
            if (shown.unreadable) Text(stringResource(Res.string.crosshairs_import_unreadable), style = type.bodyDialog, color = scheme.error)
            Row(
                Modifier.fillMaxWidth().padding(top = HideoutDimens.AlertDialog.FooterTopPadding),
                horizontalArrangement = Arrangement.spacedBy(HideoutDimens.AlertDialog.FooterGap, Alignment.End),
            ) {
                HideoutButton(stringResource(Res.string.crosshairs_import_cancel), actions::dismissImport, variant = ButtonVariant.Text)
                HideoutButton(stringResource(Res.string.crosshairs_import_add), actions::confirmImport, enabled = shown.text.isNotBlank())
            }
        }
    }
}
