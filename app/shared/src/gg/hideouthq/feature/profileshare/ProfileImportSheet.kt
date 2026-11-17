package gg.hideouthq.feature.profileshare

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import gg.hideouthq.AppContainer
import gg.hideouthq.core.designsystem.component.ButtonVariant
import gg.hideouthq.core.designsystem.component.CoverArt
import gg.hideouthq.core.designsystem.component.HideoutButton
import gg.hideouthq.core.designsystem.component.TextInput
import gg.hideouthq.core.designsystem.theme.HideoutDimens
import gg.hideouthq.core.designsystem.theme.HideoutShapes
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import gg.hideouthq.core.ui.AlertModal
import gg.hideouthq.core.ui.LibraryLabels
import gg.hideouthq.core.ui.WindowModal
import gg.hideouthq.feature.shell.ProfileNameField
import gg.hideouthq.feature.shell.profileNameMessage
import gg.hideouthq.resources.Res
import gg.hideouthq.resources.import_body
import gg.hideouthq.resources.import_create
import gg.hideouthq.resources.import_field
import gg.hideouthq.resources.import_installed
import gg.hideouthq.resources.import_looking
import gg.hideouthq.resources.import_missing
import gg.hideouthq.resources.import_placeholder
import gg.hideouthq.resources.import_read
import gg.hideouthq.resources.import_summary
import gg.hideouthq.resources.import_title
import gg.hideouthq.resources.import_unknown
import gg.hideouthq.resources.import_unreachable
import gg.hideouthq.resources.import_unreachable_nowhere
import gg.hideouthq.resources.import_unreadable
import gg.hideouthq.resources.profile_cancel
import gg.hideouthq.resources.profile_import
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** The profile import, opened from the profile menu or by a profile link the system hands Hideout. */
@Composable
fun ProfileImportHost(container: AppContainer, modal: WindowModal) {
    val viewModel = viewModel { ProfileImportViewModel(container.modLibrary, container.catalog, container.profileShares, container.profileImports) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    ProfileImportSheet(state.sheet, modal, viewModel)
}

@Composable
internal fun ProfileImportSheet(sheet: ImportSheet?, modal: WindowModal, actions: ProfileImportActions) {
    var last by remember { mutableStateOf(sheet) }
    if (sheet != null) last = sheet
    val shown = last ?: return
    val focus = remember { FocusRequester() }
    val title = (shown as? ImportSheet.Reviewing)?.let { stringResource(Res.string.import_title, it.shared) } ?: stringResource(Res.string.profile_import)
    LaunchedEffect(shown::class) { if (sheet != null && shown !is ImportSheet.Looking) focus.requestFocus() }
    AlertModal(sheet != null, modal, title, actions::dismissImport, focus, width = HideoutDimens.Collection.SheetWidth) {
        Column(verticalArrangement = Arrangement.spacedBy(HideoutDimens.AlertDialog.Gap)) {
            Text(title, style = HideoutTheme.typography.titleDialog, color = MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
            when (shown) {
                is ImportSheet.Asking -> Asking(shown, focus, actions)
                ImportSheet.Looking -> Looking()
                is ImportSheet.Reviewing -> Reviewing(shown, focus, actions)
            }
        }
    }
}

@Composable
private fun Asking(sheet: ImportSheet.Asking, focus: FocusRequester, actions: ProfileImportActions) {
    val scheme = MaterialTheme.colorScheme
    val type = HideoutTheme.typography
    Text(stringResource(Res.string.import_body), style = type.bodyDialog, color = scheme.onSurfaceVariant)
    TextInput(
        sheet.text, actions::setImportText, stringResource(Res.string.import_field), Modifier.fillMaxWidth().focusRequester(focus),
        placeholder = stringResource(Res.string.import_placeholder), onSubmit = actions::readImport,
    )
    val problem = when (sheet.problem) {
        null -> null
        ImportProblem.Unreadable -> stringResource(Res.string.import_unreadable)
        ImportProblem.Missing -> stringResource(Res.string.import_missing)
        ImportProblem.Unreachable -> sheet.site?.let { stringResource(Res.string.import_unreachable, it) } ?: stringResource(Res.string.import_unreachable_nowhere)
    }
    problem?.let { Text(it, style = type.bodyDialog, color = scheme.error) }
    Footer(actions) { HideoutButton(stringResource(Res.string.import_read), actions::readImport, enabled = sheet.canRead) }
}

@Composable
private fun Looking() {
    Row(Modifier.heightIn(min = HideoutDimens.Field.Height), horizontalArrangement = Arrangement.spacedBy(HideoutDimens.ListRow.Gap), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(HideoutDimens.Icon.Small), color = MaterialTheme.colorScheme.primary)
        Text(stringResource(Res.string.import_looking), style = HideoutTheme.typography.bodyDialog, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ColumnScope.Reviewing(sheet: ImportSheet.Reviewing, focus: FocusRequester, actions: ProfileImportActions) {
    val scheme = MaterialTheme.colorScheme
    val type = HideoutTheme.typography
    val count = sheet.mods.size
    Text(pluralStringResource(Res.plurals.import_summary, count, count), style = type.bodyDialog, color = scheme.onSurfaceVariant)
    ProfileNameField(sheet.name, actions::setImportName, focus, sheet.nameProblem != null, actions::confirmImport)
    sheet.nameProblem?.let { Text(profileNameMessage(it, sheet.name), style = type.caption, color = scheme.error) }
    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(HideoutDimens.Collection.SheetRowGap)) {
        sheet.mods.forEach { row -> key(row.modId) { ModRow(row) } }
    }
    sheet.blocked?.let { Text(LibraryLabels.problem(it), style = type.caption, color = scheme.outline) }
    sheet.problem?.let { Text(LibraryLabels.problem(it), style = type.caption, color = scheme.error) }
    Footer(actions) { HideoutButton(pluralStringResource(Res.plurals.import_create, count, count), actions::confirmImport, enabled = sheet.canConfirm) }
}

@Composable
private fun ModRow(row: SharedRow) {
    val scheme = MaterialTheme.colorScheme
    val type = HideoutTheme.typography
    val caption = listOfNotNull(row.hero, stringResource(Res.string.import_installed).takeIf { row.installed }).joinToString(" · ")
    Row(
        Modifier.fillMaxWidth().heightIn(min = HideoutDimens.Collection.SheetRowHeight),
        horizontalArrangement = Arrangement.spacedBy(HideoutDimens.ListRow.Gap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoverArt(seed = row.modId, modifier = Modifier.size(HideoutDimens.ListRow.Thumb), shape = HideoutShapes.Thumb, imageUrl = row.coverUrl)
        Column(Modifier.weight(1f)) {
            Text(row.title ?: stringResource(Res.string.import_unknown, row.submission.toString()), style = type.bodyStrong, color = scheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (caption.isNotEmpty()) Text(caption, style = type.caption, color = scheme.outline, maxLines = 1)
        }
    }
}

@Composable
private fun Footer(actions: ProfileImportActions, confirm: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = HideoutDimens.AlertDialog.FooterTopPadding),
        horizontalArrangement = Arrangement.spacedBy(HideoutDimens.AlertDialog.FooterGap, Alignment.End),
    ) {
        HideoutButton(stringResource(Res.string.profile_cancel), actions::dismissImport, variant = ButtonVariant.Text)
        confirm()
    }
}
