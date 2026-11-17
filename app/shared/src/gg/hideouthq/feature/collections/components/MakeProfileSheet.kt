package gg.hideouthq.feature.collections.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.style.TextOverflow
import gg.hideouthq.core.designsystem.component.ButtonSize
import gg.hideouthq.core.designsystem.component.ButtonVariant
import gg.hideouthq.core.designsystem.component.HideoutButton
import gg.hideouthq.core.designsystem.component.HideoutMenu
import gg.hideouthq.core.designsystem.component.MenuEntry
import gg.hideouthq.core.designsystem.icon.HideoutIcons
import gg.hideouthq.core.designsystem.theme.HideoutDimens
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import gg.hideouthq.core.ui.AlertModal
import gg.hideouthq.core.ui.LibraryLabels
import gg.hideouthq.core.ui.WindowModal
import gg.hideouthq.feature.collections.CollectionActions
import gg.hideouthq.feature.collections.HeroPick
import gg.hideouthq.feature.collections.ProfileSheet
import gg.hideouthq.feature.shell.ProfileNameField
import gg.hideouthq.feature.shell.profileNameMessage
import gg.hideouthq.resources.Res
import gg.hideouthq.resources.collection_make_profile
import gg.hideouthq.resources.collection_profile_create
import gg.hideouthq.resources.collection_profile_installed
import gg.hideouthq.resources.collection_profile_pick
import gg.hideouthq.resources.collection_profile_skin_none
import gg.hideouthq.resources.profile_cancel
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun MakeProfileSheet(sheet: ProfileSheet?, modal: WindowModal, actions: CollectionActions) {
    var last by remember { mutableStateOf(sheet) }
    if (sheet != null) last = sheet
    val shown = last ?: return
    val scheme = MaterialTheme.colorScheme
    val type = HideoutTheme.typography
    val focus = remember { FocusRequester() }
    val title = stringResource(Res.string.collection_make_profile, shown.collection)
    AlertModal(sheet != null, modal, title, actions::dismissProfileSheet, focus, width = HideoutDimens.Collection.SheetWidth) {
        Column(verticalArrangement = Arrangement.spacedBy(HideoutDimens.AlertDialog.Gap)) {
            Text(title, style = type.titleDialog)
            ProfileNameField(shown.name, actions::setProfileName, focus, shown.nameProblem != null, actions::confirmProfile)
            shown.nameProblem?.let { Text(profileNameMessage(it, shown.name), style = type.caption, color = scheme.error) }
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(HideoutDimens.Collection.SheetRowGap)) {
                shown.rows.forEach { row -> key(row.hero.id) { PickRow(row, actions::pick) } }
            }
            shown.blocked?.let { Text(LibraryLabels.problem(it), style = type.caption, color = scheme.outline) }
            shown.problem?.let { Text(LibraryLabels.problem(it), style = type.caption, color = scheme.error) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                HideoutButton(stringResource(Res.string.profile_cancel), actions::dismissProfileSheet, variant = ButtonVariant.Text)
                HideoutButton(pluralStringResource(Res.plurals.collection_profile_create, shown.picks, shown.picks), actions::confirmProfile, enabled = shown.canConfirm)
            }
        }
    }
}

@Composable
private fun PickRow(row: HeroPick, onPick: (String, String?) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val type = HideoutTheme.typography
    val dimens = HideoutDimens.Collection
    val picked = row.options.firstOrNull { it.modId == row.picked }
    val none = stringResource(Res.string.collection_profile_skin_none)
    val installed = stringResource(Res.string.collection_profile_installed)
    var choosing by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().heightIn(min = dimens.SheetRowHeight),
        horizontalArrangement = Arrangement.spacedBy(HideoutDimens.ListRow.Gap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HeroPortrait(row.hero, dimens.SheetPortrait)
        Column(Modifier.weight(1f)) {
            Text(row.hero.name, style = type.bodyStrong, color = scheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (picked?.installed == true) Text(installed, style = type.caption, color = scheme.outline, maxLines = 1)
        }
        Box {
            HideoutButton(
                picked?.title ?: none, { choosing = true },
                variant = ButtonVariant.Tonal, size = ButtonSize.Small, trailingIcon = HideoutIcons.ChevronDown,
                modifier = Modifier.padding(start = HideoutDimens.Button.Gap),
            )
            if (choosing) HideoutMenu(
                row.options.map { option ->
                    MenuEntry.Item(option.title, if (option.modId == row.picked) HideoutIcons.Check else null, { onPick(row.hero.id, option.modId) }, hint = installed.takeIf { option.installed })
                } + MenuEntry.Separator + MenuEntry.Item(none, if (row.picked == null) HideoutIcons.Check else null, { onPick(row.hero.id, null) }),
                { choosing = false },
                stringResource(Res.string.collection_profile_pick, row.hero.name),
            )
        }
    }
}
