package gg.hideouthq.feature.library.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import gg.hideouthq.core.designsystem.component.CoverArt
import gg.hideouthq.core.designsystem.component.HideoutIcon
import gg.hideouthq.core.designsystem.component.HideoutIconButton
import gg.hideouthq.core.designsystem.component.HideoutSwitch
import gg.hideouthq.core.designsystem.component.HideoutTooltip
import gg.hideouthq.core.designsystem.component.ProgressBar
import gg.hideouthq.core.designsystem.icon.HideoutIcons
import gg.hideouthq.core.designsystem.interaction.hideoutClickable
import gg.hideouthq.core.designsystem.theme.HideoutDimens
import gg.hideouthq.core.designsystem.theme.HideoutMotion
import gg.hideouthq.core.designsystem.theme.HideoutShapes
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import gg.hideouthq.core.library.OrderView
import gg.hideouthq.core.ui.CompatChip
import gg.hideouthq.core.ui.WindowModal
import gg.hideouthq.core.ui.transition.staggeredEnter
import gg.hideouthq.feature.library.DownloadPhase
import gg.hideouthq.feature.library.DragPreview
import gg.hideouthq.feature.library.DragReorder
import gg.hideouthq.feature.library.LibraryActions
import gg.hideouthq.feature.library.LibraryRow
import gg.hideouthq.feature.library.SelectGesture
import gg.hideouthq.resources.Res
import gg.hideouthq.resources.library_column_index
import gg.hideouthq.resources.library_column_on
import gg.hideouthq.resources.library_column_order
import gg.hideouthq.resources.library_column_order_tip
import gg.hideouthq.resources.library_more
import gg.hideouthq.resources.library_reorder_handle
import gg.hideouthq.resources.library_switch
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

private const val FirstRowStagger = 2

@Composable
internal fun InstalledSetList(
    rows: List<LibraryRow>,
    selectedIds: Set<String>,
    onSelect: (String, SelectGesture) -> Unit,
    actions: LibraryActions,
    modal: WindowModal,
    modifier: Modifier = Modifier,
    view: OrderView? = null,
    scrollState: ScrollState? = null,
    viewport: Rect = Rect.Zero,
) {
    val exits = remember { RowExits() }
    val listed = remember(rows, exits.finished) { exits.listed(rows) }
    val ids = rows.map { it.installId }
    val visibleIds = rows.filter { it.visible }.map { it.installId }
    val layoutOrder = remember(listed.map { (row, _) -> row.installId to row.visible }.toSet()) { listed.map { it.first.installId } }
    var preview by remember { mutableStateOf<DragPreview?>(null) }
    var listTop by remember { mutableFloatStateOf(0f) }
    val density = LocalDensity.current
    val motion = HideoutTheme.motion
    val rowStridePx = with(density) { HideoutDimens.ListRow.Height.toPx() }
    val headerPx = with(density) { HideoutDimens.ListRow.HeaderHeight.toPx() }
    val edgePx = rowStridePx
    val currentViewport by rememberUpdatedState(viewport)
    LaunchedEffect(ids, visibleIds, rows.all { it.draggable }, view) {
        preview = DragReorder.reconcile(preview, ids, rows.all { it.draggable }, view, visibleIds)
    }
    LaunchedEffect(preview?.draggingId, scrollState) {
        val scroll = scrollState ?: return@LaunchedEffect
        var previousFrame = withFrameNanos { it }
        while (preview?.draggingId != null) {
            val frame = withFrameNanos { it }
            val seconds = ((frame - previousFrame) / 1_000_000_000f).coerceAtMost(0.05f)
            previousFrame = frame
            val active = preview ?: break
            val bounds = currentViewport
            if (bounds.height <= 0f) continue
            val center = listTop + headerPx + (active.startIndex + 0.5f) * rowStridePx + active.accumulatedPx
            val speed = when {
                center < bounds.top + edgePx -> -((bounds.top + edgePx - center) / edgePx).coerceIn(0f, 1f)
                center > bounds.bottom - edgePx -> ((center - bounds.bottom + edgePx) / edgePx).coerceIn(0f, 1f)
                else -> 0f
            }
            val consumed = scroll.scrollBy(speed * rowStridePx * 8f * seconds)
            if (consumed != 0f) preview = preview?.let { DragReorder.drag(it, consumed, rowStridePx) }
        }
    }
    val visualOrder = preview?.order ?: ids
    val layoutIndexById = layoutOrder.withIndex().associate { (index, id) -> id to index }
    val displayed = listed.sortedBy { (row, _) -> layoutIndexById[row.installId] ?: Int.MAX_VALUE }
    val visibleLayout = displayed.filter { it.first.visible && it.second }.map { it.first.installId }
    val visibleLayoutIndexById = visibleLayout.withIndex().associate { (index, id) -> id to index }
    val visibleIdSet = visibleIds.toSet()
    val visibleOrder = visualOrder.filter { it in visibleIdSet }
    val visibleOrderIndexById = visibleOrder.withIndex().associate { (index, id) -> id to index }
    val visualOrderIndexById = visualOrder.withIndex().associate { (index, id) -> id to index }
    Column(modifier.onGloballyPositioned { listTop = it.positionInRoot().y }) {
        Header(Modifier.staggeredEnter(FirstRowStagger))
        displayed.forEachIndexed { order, (row, present) ->
            key(row.installId) {
                val visible = present && row.visible
                val shown = remember { MutableTransitionState(visible) }
                shown.targetState = visible
                if (!present && shown.isIdle && !shown.currentState) LaunchedEffect(Unit) { exits.finish(row.installId) }
                val drag = preview?.takeIf { it.draggingId == row.installId }
                val dragging = drag != null
                val layoutIndex = visibleLayoutIndexById[row.installId] ?: -1
                val target = when {
                    !visible -> 0f
                    drag != null -> DragReorder.offset(drag, layoutIndex, rowStridePx)
                    else -> ((visibleOrderIndexById[row.installId] ?: -1) - layoutIndex) * rowStridePx
                }
                val offset = remember(layoutOrder) { Animatable(target) }
                var settlingDrag by remember { mutableStateOf(false) }
                LaunchedEffect(target, dragging, motion, offset) {
                    if (dragging) {
                        settlingDrag = true
                        offset.snapTo(target)
                    } else {
                        offset.animateTo(target, motion.spatialFast())
                        settlingDrag = false
                    }
                }
                val raised = dragging || settlingDrag
                AnimatedVisibility(
                    visibleState = shown,
                    enter = expandVertically(motion.spatialDefault()) + fadeIn(motion.effectsDefault()),
                    exit = shrinkVertically(motion.spatialDefault()) + fadeOut(motion.exitFade()),
                    modifier = Modifier
                        .zIndex(if (raised) 1f else 0f)
                        .offset { IntOffset(0, (if (dragging) target else offset.value).roundToInt()) }
                        .staggeredEnter(FirstRowStagger + 1 + order),
                ) {
                    InstalledRow(
                        row = row.copy(rank = visualOrderIndexById[row.installId]?.plus(1) ?: row.rank),
                        selected = row.installId in selectedIds,
                        menuCount = if (row.installId in selectedIds) selectedIds.size else 1,
                        raised = raised,
                        onSelect = onSelect,
                        actions = actions,
                        modal = modal,
                        view = view,
                        totalCount = ids.size,
                        onDragStart = {
                            val started = DragReorder.start(ids, row.installId, visibleIds, view)
                            preview = started.copy(accumulatedPx = offset.value - (started.startIndex - layoutIndex) * rowStridePx)
                        },
                        onDrag = { deltaY -> preview = preview?.let { DragReorder.drag(it, deltaY, rowStridePx) } },
                        onDragEnd = {
                            val dropped = preview?.let(DragReorder::drop)
                            preview = dropped?.copy(draggingId = null)
                            val id = dropped?.draggingId ?: return@InstalledRow
                            dropped.view?.let { actions.moveSet(it, id, dropped.order.indexOf(id)) }
                        },
                        onDragCancel = { preview = null },
                    )
                }
            }
        }
    }
}

private class RowExits {
    private var previous: List<LibraryRow> = emptyList()
    private val leaving = LinkedHashMap<String, LibraryRow>()

    var finished by mutableIntStateOf(0)
        private set

    fun listed(rows: List<LibraryRow>): List<Pair<LibraryRow, Boolean>> {
        val ids = rows.mapTo(HashSet()) { it.installId }
        previous.filter { it.installId !in ids }.forEach { leaving[it.installId] = it }
        ids.forEach { leaving.remove(it) }
        previous = rows
        return rows.map { it to true } + leaving.values.map { it to false }
    }

    fun finish(installId: String) {
        if (leaving.remove(installId) != null) finished++
    }
}

@Composable
private fun Header(modifier: Modifier = Modifier) {
    val style = HideoutTheme.typography.labelSection
    val color = MaterialTheme.colorScheme.outline
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(HideoutDimens.ListRow.HeaderHeight)
            .padding(start = HideoutDimens.ListRow.StartPadding, end = HideoutDimens.ListRow.EndPadding),
        horizontalArrangement = Arrangement.spacedBy(HideoutDimens.ListRow.Gap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.width(HideoutDimens.ListRow.DragHandleWidth))
        Text(stringResource(Res.string.library_column_index), style = style, color = color, modifier = Modifier.width(HideoutDimens.ListRow.IndexWidth))
        Box(Modifier.weight(1f)) {
            HideoutTooltip(stringResource(Res.string.library_column_order_tip)) { Text(stringResource(Res.string.library_column_order), style = style, color = color, maxLines = 1) }
        }
        Text(stringResource(Res.string.library_column_on), style = style, color = color, textAlign = TextAlign.Center, modifier = Modifier.width(HideoutDimens.ListRow.SwitchColumnWidth))
        Spacer(Modifier.width(HideoutDimens.ListRow.MoreWidth))
    }
}

@Composable
private fun InstalledRow(
    row: LibraryRow,
    onSelect: (String, SelectGesture) -> Unit,
    actions: LibraryActions,
    modal: WindowModal,
    view: OrderView?,
    totalCount: Int,
    selected: Boolean,
    menuCount: Int,
    raised: Boolean,
    onDragStart: () -> Unit,
    onDrag: (deltaY: Float) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val scheme = MaterialTheme.colorScheme
    val dim = if (row.enabled) 1f else HideoutMotion.DimmedAlpha
    var menuOpen by remember { mutableStateOf(false) }
    val more = remember { FocusRequester() }
    val focus = remember { FocusRequester() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(HideoutDimens.ListRow.Height)
            .background(
                when {
                    selected -> HideoutTheme.colors.selectedRow.compositeOver(scheme.surface)
                    raised -> scheme.surfaceContainerHigh
                    else -> Color.Transparent
                },
                HideoutShapes.Row,
            )
            .selectGestures(row.installId, selected, onSelect, focus, onMenu = { menuOpen = true })
            .semantics { this.selected = selected }
            .padding(start = HideoutDimens.ListRow.StartPadding, end = HideoutDimens.ListRow.EndPadding)
            .focusRequester(focus)
            .hideoutClickable(source, onClick = { onSelect(row.installId, SelectGesture.Only) }),
        horizontalArrangement = Arrangement.spacedBy(HideoutDimens.ListRow.Gap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DragHandle(row, onDragStart, onDrag, onDragEnd, onDragCancel)
        Text(
            row.rank.toString().padStart(2, '0'),
            style = HideoutTheme.typography.mono,
            color = scheme.outline,
            modifier = Modifier.width(HideoutDimens.ListRow.IndexWidth),
        )
        Box(Modifier.fillMaxHeight().alpha(dim), contentAlignment = Alignment.Center) {
            CoverArt(seed = row.installId, modifier = Modifier.size(HideoutDimens.ListRow.Thumb), shape = HideoutShapes.Thumb, imageUrl = row.coverUrl)
        }
        Column(Modifier.weight(1f).alpha(dim)) {
            Row(
                Modifier.heightIn(min = if (row.compatPlace || row.damage != null || row.hiddenModels > 0) HideoutDimens.Chip.HeightSmall else 0.dp),
                horizontalArrangement = Arrangement.spacedBy(HideoutDimens.Library.NoteGap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(row.title, style = HideoutTheme.typography.titleRow, color = scheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                row.damage?.let { DamageChip(it) }
                row.compat?.let { CompatChip(it, row.enabled, row.title, onClick = { actions.openCompat(row.installId) }) }
                if (row.hiddenModels > 0) HiddenModelsChip(row) { actions.inspectFilePriority(row.installId) }
            }
            val meta = row.statusLine()
            if (meta.isNotEmpty()) Text(meta, style = HideoutTheme.typography.subtitle, color = scheme.outline, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        RowUpdateActions(row, actions)
        val transferring = row.operation?.phase as? DownloadPhase.Transferring
        if (transferring != null) ProgressBar(transferring.percent / 100f, Modifier.width(HideoutDimens.ListRow.SizeWidth))
        HideoutSwitch(
            checked = row.enabled,
            onCheckedChange = { actions.setEnabled(row.installId, it) },
            contentDescription = stringResource(Res.string.library_switch, row.title),
            modifier = Modifier.alpha(if (row.canSwitch || row.applying) 1f else HideoutMotion.DisabledAlpha),
            enabled = row.canSwitch,
        )
        Box {
            HideoutIconButton(
                icon = HideoutIcons.MoreHorizontal,
                contentDescription = stringResource(Res.string.library_more, row.title),
                onClick = {
                    if (!selected) onSelect(row.installId, SelectGesture.Only)
                    menuOpen = true
                },
                modifier = Modifier.focusRequester(more),
            )
            InstalledRowMenu(row, menuOpen, { menuOpen = false }, more, actions, modal, view, totalCount, menuCount)
        }
    }
}

@Composable
private fun DragHandle(row: LibraryRow, onDragStart: () -> Unit, onDrag: (Float) -> Unit, onDragEnd: () -> Unit, onDragCancel: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val start by rememberUpdatedState(onDragStart)
    val drag by rememberUpdatedState(onDrag)
    val end by rememberUpdatedState(onDragEnd)
    val cancel by rememberUpdatedState(onDragCancel)
    val description = stringResource(Res.string.library_reorder_handle, row.title)
    Box(
        modifier = Modifier
            .width(HideoutDimens.ListRow.DragHandleWidth)
            .height(HideoutDimens.ListRow.DragHandleHeight)
            .alpha(if (row.draggable || row.applying) 1f else HideoutMotion.DisabledAlpha)
            .semantics(mergeDescendants = true) {
                contentDescription = description
                if (!row.draggable) disabled()
            }
            .then(
                if (row.draggable) {
                    Modifier.pointerInput(row.installId) {
                        detectDragGestures(
                            onDragStart = { start() },
                            onDrag = { change, amount -> change.consume(); drag(amount.y) },
                            onDragEnd = { end() },
                            onDragCancel = { cancel() },
                        )
                    }
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        HideoutIcon(HideoutIcons.DragHandle, contentDescription = null, tint = scheme.outline)
    }
}
