package gg.hideouthq.feature.library.components

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import gg.hideouthq.core.designsystem.component.HideoutIcon
import gg.hideouthq.core.designsystem.component.HideoutIconButton
import gg.hideouthq.core.designsystem.component.HideoutMenu
import gg.hideouthq.core.designsystem.component.HideoutTooltip
import gg.hideouthq.core.designsystem.component.MenuEntry
import gg.hideouthq.core.designsystem.icon.HideoutIcons
import gg.hideouthq.core.designsystem.interaction.collectInteractionStates
import gg.hideouthq.core.designsystem.interaction.focusRing
import gg.hideouthq.core.designsystem.interaction.hideoutSelectable
import gg.hideouthq.core.designsystem.theme.HideoutDimens
import gg.hideouthq.core.designsystem.theme.HideoutShapes
import gg.hideouthq.core.ui.WindowModal
import gg.hideouthq.feature.library.LibraryActions
import gg.hideouthq.feature.library.LibraryLayout
import gg.hideouthq.resources.Res
import gg.hideouthq.resources.library_more_actions
import gg.hideouthq.resources.library_turn_all_off
import gg.hideouthq.resources.library_view_cards
import gg.hideouthq.resources.library_view_list
import gg.hideouthq.resources.profile_library_import
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun LibraryViewToggle(layout: LibraryLayout, onLayout: (LibraryLayout) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceContainer, HideoutShapes.Full)
            .padding(HideoutDimens.Segmented.Padding)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(HideoutDimens.Segmented.Gap),
    ) {
        ViewToggleSegment(HideoutIcons.ViewList, layout == LibraryLayout.List, stringResource(Res.string.library_view_list)) { onLayout(LibraryLayout.List) }
        ViewToggleSegment(HideoutIcons.Grid, layout == LibraryLayout.Cards, stringResource(Res.string.library_view_cards)) { onLayout(LibraryLayout.Cards) }
    }
}

@Composable
private fun ViewToggleSegment(icon: ImageVector, selected: Boolean, label: String, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val states = source.collectInteractionStates()
    val scheme = MaterialTheme.colorScheme
    HideoutTooltip(label, below = true) {
        Box(
            modifier = Modifier
                .wrapContentWidth()
                .size(HideoutDimens.ViewToggle.SegmentWidth, HideoutDimens.Segmented.SegmentHeight)
                .background(if (selected) scheme.surfaceContainerHighest else Color.Transparent, HideoutShapes.Full)
                .focusRing(states.focusVisible, HideoutShapes.Full)
                .semantics { contentDescription = label }
                .hideoutSelectable(selected, source, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            HideoutIcon(icon, contentDescription = null, tint = if (selected) scheme.onSurface else scheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun LibraryMoreMenu(canImport: Boolean, canTurnAllOff: Boolean, actions: LibraryActions, modal: WindowModal) {
    if (!canImport && !canTurnAllOff) return
    var open by remember { mutableStateOf(false) }
    val more = remember { FocusRequester() }
    val label = stringResource(Res.string.library_more_actions)
    Box {
        HideoutTooltip(label, below = true) { HideoutIconButton(HideoutIcons.MoreHorizontal, label, { open = true }, Modifier.focusRequester(more)) }
        if (open) {
            DisposableEffect(Unit) {
                modal.hold(more)
                onDispose { modal.release(more) }
            }
            HideoutMenu(
                entries = listOfNotNull(
                    MenuEntry.Item(stringResource(Res.string.profile_library_import), HideoutIcons.Import, actions::importFromManager).takeIf { canImport },
                    MenuEntry.Item(stringResource(Res.string.library_turn_all_off), HideoutIcons.Minus, actions::turnAllOff).takeIf { canTurnAllOff },
                ),
                onClose = { byKey ->
                    open = false
                    if (byKey) more.requestFocus()
                },
                label = label,
            )
        }
    }
}
