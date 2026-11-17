package gg.hideouthq.feature.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import gg.hideouthq.core.designsystem.component.HideoutIcon
import gg.hideouthq.core.designsystem.component.HideoutIconButton
import gg.hideouthq.core.designsystem.component.HideoutMenu
import gg.hideouthq.core.designsystem.component.HideoutTooltip
import gg.hideouthq.core.designsystem.component.MenuAnchor
import gg.hideouthq.core.designsystem.component.MenuEntry
import gg.hideouthq.core.designsystem.component.ProfileTile
import gg.hideouthq.core.designsystem.icon.HideoutIcons
import gg.hideouthq.core.designsystem.interaction.collectInteractionStates
import gg.hideouthq.core.designsystem.interaction.focusRing
import gg.hideouthq.core.designsystem.interaction.hideoutClickable
import gg.hideouthq.core.designsystem.interaction.hideoutToggleable
import gg.hideouthq.core.designsystem.theme.HideoutDimens
import gg.hideouthq.core.designsystem.theme.HideoutShapes
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import gg.hideouthq.core.library.Profile
import gg.hideouthq.core.library.ProfileColour
import gg.hideouthq.core.ui.LibraryLabels
import gg.hideouthq.core.ui.LocalFocusVisibility
import gg.hideouthq.core.ui.color
import gg.hideouthq.core.ui.trackFieldFocus
import gg.hideouthq.resources.*
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun ProfileSidebar(state: ShellUiState, actions: ProfileActions, compact: Boolean) {
    val book = state.profiles
    Box(Modifier.fillMaxWidth()) {
        SidebarGroup(stringResource(Res.string.sidebar_profiles), compact)
        if (!compact) Row(Modifier.align(Alignment.BottomEnd)) {
            HideoutIconButton(HideoutIcons.Import, stringResource(Res.string.profile_import), actions::importProfile, size = 28.dp, enabled = state.canEditChoices)
            HideoutIconButton(HideoutIcons.Plus, stringResource(Res.string.profile_new), actions::newProfile, size = 28.dp, enabled = state.canEditChoices)
        }
    }
    val profiles = book?.profiles.orEmpty()
    val visible = if (!compact || profiles.size < 5) profiles else profiles.take(4).let { four ->
        if (four.any { it.id == book?.active }) four else four.take(3) + profiles.filter { it.id == book?.active }
    }
    var shown by remember { mutableStateOf(book?.active) }
    var switched by remember { mutableStateOf(false) }
    LaunchedEffect(book?.active) {
        val active = book?.active ?: return@LaunchedEffect
        if (shown != null && shown != active) switched = true
        shown = active
    }
    visible.forEach { profile ->
        ProfileRow(profile, profile.id == book?.active, switched, profiles.size > 1, state, actions, compact)
    }
    if (compact) {
        if (profiles.size >= 5) {
            var open by remember { mutableStateOf(false) }
            LaunchedEffect(state.canEditChoices) { if (!state.canEditChoices) open = false }
            Box {
                RailProfileButton("+${profiles.size - 4}", stringResource(Res.string.sidebar_profiles), state.canEditChoices) { open = true }
                val activeHint = stringResource(Res.string.profile_active)
                if (open) HideoutMenu(
                    profiles.map { profile ->
                        val active = profile.id == book?.active
                        MenuEntry.Item(profile.name, icon = null, onClick = { actions.switchProfile(profile.id) }, hint = activeHint.takeIf { active },
                            leading = { ProfileTile(profile.name.take(1).uppercase(), active, colour = profile.colour.color, size = HideoutDimens.Menu.ItemIcon) })
                    },
                    onClose = { open = false }, label = stringResource(Res.string.sidebar_profiles), anchor = MenuAnchor.Beside,
                )
            }
        }
        RailProfileButton("+", stringResource(Res.string.profile_new), state.canEditChoices, actions::newProfile)
    }
}

@Composable
private fun RailProfileButton(text: String, label: String, enabled: Boolean, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    HideoutTooltip(label) {
        Box(Modifier.size(56.dp, 44.dp).semantics { contentDescription = label }
            .hideoutClickable(source, enabled = enabled, onClick = onClick).alpha(if (enabled) 1f else .45f), contentAlignment = Alignment.Center) {
            val line = MaterialTheme.colorScheme.outlineVariant
            Box(Modifier.size(26.dp).drawBehind {
                drawRoundRect(line, cornerRadius = CornerRadius(9.dp.toPx()),
                    style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 2.dp.toPx()))))
            }, contentAlignment = Alignment.Center) {
                Text(text, style = HideoutTheme.typography.caption, color = MaterialTheme.colorScheme.outline)
            }
        }
    }
}

@Composable
private fun ProfileRow(profile: Profile, active: Boolean, follow: Boolean, deletable: Boolean, state: ShellUiState, actions: ProfileActions, compact: Boolean) {
    val interaction = remember(profile.id) { MutableInteractionSource() }
    val states = interaction.collectInteractionStates()
    var menu by remember { mutableStateOf(false) }
    var focusWithin by remember { mutableStateOf(false) }
    val keyboard = LocalFocusVisibility.current.keyboardInUse
    val options = states.hovered || menu || focusWithin && keyboard
    val optionsFocus = remember { FocusRequester() }
    val inView = remember { BringIntoViewRequester() }
    LaunchedEffect(active, follow) {
        if (!active || !follow) return@LaunchedEffect
        withFrameNanos {}
        inView.bringIntoView()
    }
    val rename = (state.profileEditor as? ProfileEditor.Rename)?.takeIf { it.id == profile.id }
    val scheme = MaterialTheme.colorScheme
    val label = stringResource(if (active) Res.string.profile_active_label else Res.string.profile_switch_label, profile.name)
    LaunchedEffect(state.canEditChoices) { if (!state.canEditChoices) menu = false }
    Column(Modifier.fillMaxWidth().bringIntoViewRequester(inView)) {
        Box(Modifier.fillMaxWidth().height(if (compact) 44.dp else 40.dp).hoverable(interaction).onFocusChanged { focusWithin = it.hasFocus }
            .background(if (menu || states.hovered) scheme.surfaceContainerLow else scheme.background, HideoutShapes.Row)) {
            if (rename != null && !compact) {
                val focus = remember { FocusRequester() }
                LaunchedEffect(Unit) { focus.requestFocus() }
                var field by rememberNameField(rename.name)
                var focused by remember { mutableStateOf(false) }
                Row(Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    ProfileTile(profile.name.take(1).uppercase(), active, colour = profile.colour.color)
                    BasicTextField(
                        field, { if (it.text != field.text) actions.setProfileName(it.text); field = it },
                        Modifier.weight(1f).focusRequester(focus).trackFieldFocus(focused).onFocusChanged {
                            if (focused && !it.isFocused) actions.leaveRename(profile.id)
                            focused = it.isFocused
                        }.semantics { contentDescription = "Rename profile" }.onPreviewKeyEvent {
                            if (it.type != KeyEventType.KeyDown) false else when (it.key) {
                                Key.Enter, Key.NumPadEnter -> { if (validProfileName(rename.name, state, profile.id)) actions.confirmProfile(); true }
                                Key.Escape -> { actions.dismissProfile(); true }
                                else -> false
                            }
                        },
                        enabled = state.canEditChoices,
                        singleLine = true,
                        textStyle = HideoutTheme.typography.body.copy(color = scheme.onSurface),
                        cursorBrush = SolidColor(scheme.primary),
                    )
                    HideoutIconButton(HideoutIcons.Check, stringResource(Res.string.profile_rename), actions::confirmProfile, size = 24.dp, enabled = state.canEditChoices && validProfileName(rename.name, state, profile.id))
                }
            } else {
                val content: @Composable () -> Unit = {
                    Row(
                        Modifier.fillMaxWidth().height(if (compact) 44.dp else 40.dp).semantics { contentDescription = label }
                            .onRightClick(state.canEditChoices) { menu = true }.focusRing(states.focusVisible, HideoutShapes.Row)
                            .hideoutClickable(interaction, enabled = state.canEditChoices) { actions.switchProfile(profile.id) }
                            .alpha(if (state.canEditChoices || state.applying) 1f else 0.45f).padding(horizontal = if (compact) 0.dp else 12.dp),
                        horizontalArrangement = if (compact) Arrangement.Center else Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ProfileTile(profile.name.take(1).uppercase(), active, colour = profile.colour.color, size = if (compact) 26.dp else 20.dp)
                        if (!compact) {
                            Text(profile.name, Modifier.weight(1f), style = HideoutTheme.typography.body, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium, color = if (active) scheme.onSurface else scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(stringResource(Res.string.profile_mods_on, profile.enabled.size), Modifier.alpha(if (options) 0f else 1f), style = HideoutTheme.typography.caption, color = scheme.outline)
                        }
                    }
                }
                if (compact) HideoutTooltip(pluralStringResource(Res.plurals.sidebar_profile, profile.enabled.size, profile.name, profile.enabled.size), content = content) else content()
                if (!compact && options) HideoutIconButton(
                    HideoutIcons.MoreHorizontal, stringResource(Res.string.profile_options, profile.name), { menu = true },
                    Modifier.align(Alignment.CenterEnd).padding(end = 6.dp).focusRequester(optionsFocus), size = 28.dp, enabled = state.canEditChoices,
                )
                if (compact && options) HideoutIconButton(
                    HideoutIcons.MoreHorizontal, stringResource(Res.string.profile_options, profile.name), { menu = true },
                    Modifier.align(Alignment.BottomEnd).focusRequester(optionsFocus), size = 18.dp, iconSize = 12.dp, enabled = state.canEditChoices,
                )
            }
            if (menu) HideoutMenu(
                entries = listOf(
                    MenuEntry.Item(stringResource(Res.string.profile_switch), HideoutIcons.Check, { actions.switchProfile(profile.id) }, enabled = !active),
                    MenuEntry.Item(stringResource(Res.string.profile_rename), HideoutIcons.Rename, { actions.renameProfile(profile.id) }),
                    MenuEntry.Item(stringResource(Res.string.profile_duplicate), HideoutIcons.Duplicate, { actions.duplicateProfile(profile.id) }),
                    MenuEntry.Item(stringResource(Res.string.profile_share), HideoutIcons.Link, { actions.shareProfile(profile.id) }),
                    MenuEntry.Content {
                        Row(Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(Res.string.profile_colour), style = HideoutTheme.typography.caption, color = scheme.onSurface)
                            ProfileSwatches(profile.colour, { menu = false; actions.recolourProfile(profile.id, it) }, small = true)
                        }
                    },
                    MenuEntry.Separator,
                    MenuEntry.Item(stringResource(Res.string.profile_delete_menu), HideoutIcons.Trash, { actions.deleteProfile(profile.id) }, danger = true, enabled = deletable,
                        hint = stringResource(Res.string.profile_only_one).takeIf { !deletable }),
                ),
                onClose = { byKey -> menu = false; if (byKey) optionsFocus.requestFocus() }, label = stringResource(Res.string.profile_options, profile.name), anchor = MenuAnchor.Beside,
            )
        }
        if (rename != null) {
            ProfileNameError(rename.name, state, profile.id)
            if (!compact) state.profileEditorProblem?.let { Text(LibraryLabels.problem(it), color = scheme.error, style = HideoutTheme.typography.caption) }
        }
    }
}

@Composable
internal fun ProfileSwatches(selected: ProfileColour, onSelect: (ProfileColour) -> Unit, small: Boolean = false, enabled: Boolean = true) {
    Row(horizontalArrangement = Arrangement.spacedBy(if (small) 6.dp else 10.dp), verticalAlignment = Alignment.CenterVertically) {
        ProfileColour.entries.forEach { colour ->
            val checked = selected == colour
            val source = remember { MutableInteractionSource() }
            Box(
                Modifier.size(if (small) 20.dp else 28.dp)
                    .then(if (checked) Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape).padding(3.dp) else Modifier)
                    .background(colour.color, CircleShape)
                    .semantics { contentDescription = "${colour.name} colour" }
                    .hideoutToggleable(checked, source, enabled, Role.RadioButton) { onSelect(colour) },
                contentAlignment = Alignment.Center,
            ) {
                if (checked && !small) HideoutIcon(HideoutIcons.Check, null, size = 16.dp, tint = MaterialTheme.colorScheme.background)
            }
        }
    }
}

private fun Modifier.onRightClick(enabled: Boolean, onClick: () -> Unit): Modifier = pointerInput(enabled) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (!enabled || event.type != PointerEventType.Press || !event.buttons.isSecondaryPressed) continue
            event.changes.forEach { it.consume() }
            onClick()
        }
    }
}
