package gg.hideouthq.feature.library.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import gg.hideouthq.core.designsystem.component.HideoutMenu
import gg.hideouthq.core.designsystem.component.MenuEntry
import gg.hideouthq.core.designsystem.icon.HideoutIcons
import gg.hideouthq.core.library.OrderView
import gg.hideouthq.core.ui.WindowModal
import gg.hideouthq.feature.library.DragReorder
import gg.hideouthq.feature.library.LibraryActions
import gg.hideouthq.feature.library.LibraryRow
import gg.hideouthq.resources.Res
import gg.hideouthq.resources.library_files_count
import gg.hideouthq.resources.library_turn_off_selected
import gg.hideouthq.resources.library_turn_on_selected
import gg.hideouthq.resources.library_update_action
import gg.hideouthq.resources.menu_open_page
import gg.hideouthq.resources.menu_show_files
import gg.hideouthq.resources.menu_uninstall
import gg.hideouthq.resources.order_move_down
import gg.hideouthq.resources.order_move_to_position
import gg.hideouthq.resources.order_move_up
import gg.hideouthq.resources.profile_install_change_version
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun InstalledRowMenu(
    row: LibraryRow,
    open: Boolean,
    onClose: () -> Unit,
    more: FocusRequester,
    actions: LibraryActions,
    modal: WindowModal,
    view: OrderView?,
    order: List<String>,
    visible: List<String>,
    selectedCount: Int,
) {
    var positionView by remember { mutableStateOf<OrderView?>(null) }
    if (open) {
        DisposableEffect(Unit) {
            modal.hold(more)
            onDispose { modal.release(more) }
        }
        val update = row.updatePlan?.takeIf { row.canInstall && !row.busy }
        val page = row.page
        val shownAt = visible.indexOf(row.installId)
        val moveAmongShown = { to: Int -> view?.let { actions.moveSet(it, row.installId, DragReorder.place(order, visible, row.installId, to).indexOf(row.installId)) } }
        val moves = listOfNotNull(
            MenuEntry.Item(stringResource(Res.string.order_move_up), HideoutIcons.ArrowUp, { moveAmongShown(shownAt - 1) }).takeIf { row.draggable && shownAt > 0 },
            MenuEntry.Item(stringResource(Res.string.order_move_down), HideoutIcons.ArrowDown, { moveAmongShown(shownAt + 1) }).takeIf { row.draggable && shownAt in 0 until visible.lastIndex },
            MenuEntry.Item(stringResource(Res.string.order_move_to_position), HideoutIcons.Layers, { positionView = view }).takeIf { row.draggable && order.size > 2 },
        )
        val entries: List<MenuEntry> = if (selectedCount > 1) listOf(
            MenuEntry.Item(pluralStringResource(Res.plurals.library_turn_on_selected, selectedCount, selectedCount), HideoutIcons.Check, { actions.setSelectedEnabled(true) }),
            MenuEntry.Item(pluralStringResource(Res.plurals.library_turn_off_selected, selectedCount, selectedCount), HideoutIcons.Minus, { actions.setSelectedEnabled(false) }),
        ).filter { row.canToggle } else buildList<MenuEntry> {
            if (update != null) add(MenuEntry.Item(stringResource(Res.string.library_update_action), HideoutIcons.Download, { actions.update(update) }))
            if (page != null && row.canInstall && !row.busy && !row.held) {
                add(MenuEntry.Item(stringResource(Res.string.profile_install_change_version), HideoutIcons.SwapVersions, { actions.changeFiles(row.installId) }, hint = row.availableFiles?.let { stringResource(Res.string.library_files_count, row.installedFiles, it) }))
            }
            add(MenuEntry.Item(stringResource(Res.string.menu_show_files), HideoutIcons.Folder, { actions.showFiles(row.installId) }))
            if (page != null) add(MenuEntry.Item(stringResource(Res.string.menu_open_page), HideoutIcons.OpenExternal, { actions.viewPage(page) }))
            if (moves.isNotEmpty()) {
                add(MenuEntry.Separator)
                addAll(moves)
            }
            if (row.canToggle && !row.busy) {
                add(MenuEntry.Separator)
                add(MenuEntry.Item(stringResource(Res.string.menu_uninstall), HideoutIcons.Trash, { actions.uninstall(row.installId) }, danger = true))
            }
        }
        if (entries.isEmpty()) SideEffect(onClose) else HideoutMenu(
            entries = entries,
            onClose = { byKey ->
                onClose()
                if (byKey) more.requestFocus()
            },
            label = row.title,
        )
    }
    MoveToPositionDialog(
        visible = positionView != null,
        modal = modal,
        title = row.title,
        totalCount = positionView?.sets?.size ?: order.size,
        initialPosition = positionView?.sets?.indexOf(row.installId)?.plus(1) ?: row.rank,
        onConfirm = { position -> positionView?.let { actions.moveSet(it, row.installId, position - 1) } },
        onDismiss = { positionView = null },
    )
}
