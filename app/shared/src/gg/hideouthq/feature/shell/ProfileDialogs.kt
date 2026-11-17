package gg.hideouthq.feature.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import gg.hideouthq.core.designsystem.component.ButtonSize
import gg.hideouthq.core.designsystem.component.ButtonVariant
import gg.hideouthq.core.designsystem.component.HideoutButton
import gg.hideouthq.core.designsystem.component.HideoutCheckbox
import gg.hideouthq.core.designsystem.component.HideoutMenu
import gg.hideouthq.core.designsystem.component.MenuEntry
import gg.hideouthq.core.designsystem.component.ProfileTile
import gg.hideouthq.core.designsystem.icon.HideoutIcons
import gg.hideouthq.core.designsystem.interaction.hideoutToggleable
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import gg.hideouthq.core.library.LibraryProblem
import gg.hideouthq.core.library.ProfileId
import gg.hideouthq.core.library.profileNameProblem
import gg.hideouthq.core.ui.AlertModal
import gg.hideouthq.core.ui.LibraryLabels
import gg.hideouthq.core.ui.WindowModal
import gg.hideouthq.core.ui.color
import gg.hideouthq.resources.*
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun ProfileDialogs(state: ShellUiState, actions: ProfileActions, modal: WindowModal, compact: Boolean) {
    fun dialog(of: ShellUiState) = of.profileEditor.takeUnless { it is ProfileEditor.Rename && !compact }
    var last by remember { mutableStateOf(state) }
    if (dialog(state) != null) last = if (state.profileSubmitting) state.copy(profiles = last.profiles) else state
    val shown = dialog(last) ?: return
    val focus = remember { FocusRequester() }
    val title = when (shown) {
        is ProfileEditor.New -> stringResource(Res.string.profile_new)
        is ProfileEditor.Rename -> stringResource(Res.string.profile_rename)
    }
    val canConfirm = last.canEditChoices && !last.profileSubmitting && when (shown) {
        is ProfileEditor.New -> validProfileName(shown.name, last)
        is ProfileEditor.Rename -> validProfileName(shown.name, last, shown.id)
    }
    val confirm = { if (canConfirm) actions.confirmProfile() }
    AlertModal(dialog(state) != null, modal, title, actions::dismissProfile, focus, width = 480.dp) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(title, Modifier.weight(1f), style = HideoutTheme.typography.titleDialog)
                if (shown is ProfileEditor.New) HideoutButton(
                    stringResource(Res.string.profile_import_link), actions::importProfile,
                    variant = ButtonVariant.Text, size = ButtonSize.Small, leadingIcon = HideoutIcons.Link,
                    enabled = state.canEditChoices && !state.profileSubmitting,
                )
            }
            when (shown) {
                is ProfileEditor.New -> NewProfileFields(shown, last, actions, focus, confirm)
                is ProfileEditor.Rename -> {
                    ProfileNameField(shown.name, actions::setProfileName, focus, !validProfileName(shown.name, last, shown.id), confirm)
                    ProfileNameError(shown.name, last, shown.id)
                }
            }
            last.profileEditorProblem?.let { Text(LibraryLabels.problem(it), color = MaterialTheme.colorScheme.error, style = HideoutTheme.typography.caption) }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                HideoutButton(stringResource(Res.string.profile_cancel), actions::dismissProfile, variant = ButtonVariant.Text)
                HideoutButton(
                    stringResource(when (shown) {
                        is ProfileEditor.New -> Res.string.profile_create
                        is ProfileEditor.Rename -> Res.string.profile_rename
                    }),
                    actions::confirmProfile,
                    enabled = canConfirm,
                )
            }
        }
    }
}

@Composable
private fun NewProfileFields(draft: ProfileEditor.New, state: ShellUiState, actions: ProfileActions, focus: FocusRequester, onSubmit: () -> Unit) {
    var typed by remember { mutableStateOf(false) }
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        ProfileTile(draft.name.trim().take(1).uppercase(), true, colour = draft.colour.color, size = 56.dp, textStyle = HideoutTheme.typography.titleDialog)
        ProfileNameField(draft.name, { typed = true; actions.setProfileName(it) }, focus, typed && !validProfileName(draft.name, state), onSubmit, Modifier.weight(1f))
    }
    if (typed) ProfileNameError(draft.name, state)
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(Res.string.profile_colour), Modifier.size(width = 56.dp, height = 28.dp), style = HideoutTheme.typography.caption, color = MaterialTheme.colorScheme.outline)
        ProfileSwatches(draft.colour, actions::setProfileColour, enabled = state.canEditChoices)
    }
    Text(stringResource(Res.string.profile_start_with), style = HideoutTheme.typography.caption, color = MaterialTheme.colorScheme.outline)
    val book = state.profiles ?: return
    val source = book.profiles.firstOrNull { it.id == draft.copyFrom } ?: book.activeProfile
    var choosing by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SeedOption(draft.copyFrom != null, { actions.setProfileSeed(source.id) }, state.canEditChoices) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(Res.string.profile_copy_on), style = HideoutTheme.typography.bodyStrong)
                    Box {
                        HideoutButton(source.name, { choosing = true }, variant = ButtonVariant.Tonal, size = ButtonSize.Small, enabled = state.canEditChoices)
                        if (choosing) HideoutMenu(
                            book.profiles.map { profile -> MenuEntry.Item(profile.name, HideoutIcons.Layers, { actions.setProfileSeed(profile.id) }) },
                            { choosing = false }, stringResource(Res.string.profile_start_with),
                        )
                    }
                }
                Text(pluralStringResource(Res.plurals.profile_copy_hint, source.enabled.size, source.enabled.size, source.name), style = HideoutTheme.typography.caption, color = MaterialTheme.colorScheme.outline)
            }
        }
        SeedOption(draft.copyFrom == null, { actions.setProfileSeed(null) }, state.canEditChoices) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(Res.string.profile_nothing_on), style = HideoutTheme.typography.bodyStrong)
                Text(stringResource(Res.string.profile_nothing_hint), style = HideoutTheme.typography.caption, color = MaterialTheme.colorScheme.outline)
            }
        }
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 44.dp)
            .hideoutToggleable(draft.activate, remember { MutableInteractionSource() }, state.canEditChoices, Role.Checkbox, actions::setActivateProfile),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        HideoutCheckbox(draft.activate)
        Text(stringResource(Res.string.profile_activate_now), style = HideoutTheme.typography.body)
    }
}

@Composable
private fun SeedOption(selected: Boolean, onSelect: () -> Unit, enabled: Boolean, content: @Composable () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp)
            .background(if (selected) scheme.primary.copy(alpha = 0.12f) else Color.Transparent, RoundedCornerShape(16.dp))
            .hideoutToggleable(selected, remember { MutableInteractionSource() }, enabled, Role.RadioButton) { onSelect() }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(20.dp).border(2.dp, if (selected) scheme.primary else scheme.outline, CircleShape).padding(5.dp)) {
            if (selected) Box(Modifier.size(10.dp).background(scheme.primary, CircleShape))
        }
        content()
    }
}

@Composable
internal fun ProfileNameField(name: String, onChange: (String) -> Unit, focus: FocusRequester, invalid: Boolean, onSubmit: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    var field by rememberNameField(name)
    Column(modifier.heightIn(min = 56.dp).background(scheme.surface, RoundedCornerShape(14.dp)).border(2.dp, if (invalid) scheme.error else scheme.primary, RoundedCornerShape(14.dp)).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.Center) {
        Text(stringResource(Res.string.profile_name), style = HideoutTheme.typography.caption, color = if (invalid) scheme.error else scheme.primary)
        BasicTextField(
            field, { if (it.text != field.text) onChange(it.text); field = it },
            Modifier.fillMaxWidth().focusRequester(focus).semantics { contentDescription = "Profile name" }.onPreviewKeyEvent {
                val enter = it.type == KeyEventType.KeyDown && (it.key == Key.Enter || it.key == Key.NumPadEnter)
                if (enter) onSubmit()
                enter
            },
            singleLine = true, textStyle = HideoutTheme.typography.body.copy(color = scheme.onSurface), cursorBrush = SolidColor(scheme.primary),
        )
    }
}

@Composable
internal fun rememberNameField(name: String): MutableState<TextFieldValue> = remember { mutableStateOf(TextFieldValue(name, TextRange(0, name.length))) }

internal fun validProfileName(name: String, state: ShellUiState, except: ProfileId? = null): Boolean =
    profileNameProblem(name, state.profiles?.profiles.orEmpty(), except) == null

@Composable
internal fun ProfileNameError(name: String, state: ShellUiState, except: ProfileId? = null) {
    val problem = profileNameProblem(name, state.profiles?.profiles.orEmpty(), except) ?: return
    Text(profileNameMessage(problem, name), style = HideoutTheme.typography.caption, color = MaterialTheme.colorScheme.error)
}

@Composable
internal fun profileNameMessage(problem: LibraryProblem, name: String): String = when (problem) {
    LibraryProblem.ProfileNameTaken -> stringResource(Res.string.profile_duplicate_name, name.trim())
    else -> stringResource(Res.string.profile_empty_name)
}
