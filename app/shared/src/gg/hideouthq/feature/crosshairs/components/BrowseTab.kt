package gg.hideouthq.feature.crosshairs.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import gg.hideouthq.core.crosshair.Crosshair
import gg.hideouthq.core.crosshair.community.BrowseQuery
import gg.hideouthq.core.crosshair.community.CrosshairSort
import gg.hideouthq.core.crosshair.community.CrosshairStyle
import gg.hideouthq.core.data.DataError
import gg.hideouthq.core.data.Loadable
import gg.hideouthq.core.data.SavedNote
import gg.hideouthq.core.designsystem.component.ButtonSize
import gg.hideouthq.core.designsystem.component.ButtonVariant
import gg.hideouthq.core.designsystem.component.ChipTone
import gg.hideouthq.core.designsystem.component.EmptyState
import gg.hideouthq.core.designsystem.component.FilterChip
import gg.hideouthq.core.designsystem.component.FilterChipStyle
import gg.hideouthq.core.designsystem.component.GlassBadge
import gg.hideouthq.core.designsystem.component.HideoutButton
import gg.hideouthq.core.designsystem.component.OverlayScrollbar
import gg.hideouthq.core.designsystem.component.PageCard
import gg.hideouthq.core.designsystem.component.ScrollHairline
import gg.hideouthq.core.designsystem.component.SearchField
import gg.hideouthq.core.designsystem.component.SearchFieldSize
import gg.hideouthq.core.designsystem.component.SegmentedControl
import gg.hideouthq.core.designsystem.component.SegmentedStyle
import gg.hideouthq.core.designsystem.component.StatusChip
import gg.hideouthq.core.designsystem.component.rememberScrollbarAdapter
import gg.hideouthq.core.designsystem.component.rememberShimmer
import gg.hideouthq.core.designsystem.component.skeleton
import gg.hideouthq.core.designsystem.icon.HideoutIcons
import gg.hideouthq.core.designsystem.interaction.collectInteractionStates
import gg.hideouthq.core.designsystem.interaction.focusRing
import gg.hideouthq.core.designsystem.interaction.hideoutClickable
import gg.hideouthq.core.designsystem.theme.HideoutShapes
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import gg.hideouthq.core.ui.ModLabels
import gg.hideouthq.core.ui.SavedNoteLine
import gg.hideouthq.core.ui.crosshair.CrosshairBackdrop
import gg.hideouthq.core.ui.crosshair.CrosshairTile
import gg.hideouthq.core.ui.fitsBeside
import gg.hideouthq.core.ui.transition.staggeredEnter
import gg.hideouthq.feature.crosshairs.CommunityCard
import gg.hideouthq.feature.crosshairs.CrosshairsActions
import gg.hideouthq.feature.crosshairs.CrosshairsUiState
import gg.hideouthq.resources.Res
import gg.hideouthq.resources.crosshairs_by
import gg.hideouthq.resources.crosshairs_clear_filters
import gg.hideouthq.resources.crosshairs_color
import gg.hideouthq.resources.crosshairs_copied
import gg.hideouthq.resources.crosshairs_copy_code
import gg.hideouthq.resources.crosshairs_dot
import gg.hideouthq.resources.crosshairs_edit_copy
import gg.hideouthq.resources.crosshairs_failed_offline
import gg.hideouthq.resources.crosshairs_failed_title
import gg.hideouthq.resources.crosshairs_failed_unavailable
import gg.hideouthq.resources.crosshairs_fixed_gap
import gg.hideouthq.resources.crosshairs_gap
import gg.hideouthq.resources.crosshairs_in_use
import gg.hideouthq.resources.crosshairs_in_use_next_start
import gg.hideouthq.resources.crosshairs_in_use_waiting
import gg.hideouthq.resources.crosshairs_length
import gg.hideouthq.resources.crosshairs_no_match
import gg.hideouthq.resources.crosshairs_off
import gg.hideouthq.resources.crosshairs_on
import gg.hideouthq.resources.crosshairs_opacity
import gg.hideouthq.resources.crosshairs_outline
import gg.hideouthq.resources.crosshairs_save
import gg.hideouthq.resources.crosshairs_saved
import gg.hideouthq.resources.crosshairs_search
import gg.hideouthq.resources.crosshairs_sort_new
import gg.hideouthq.resources.crosshairs_sort_popular
import gg.hideouthq.resources.crosshairs_sort_this_week
import gg.hideouthq.resources.crosshairs_style_all
import gg.hideouthq.resources.crosshairs_style_dot_only
import gg.hideouthq.resources.crosshairs_style_fixed_gap
import gg.hideouthq.resources.crosshairs_style_outline
import gg.hideouthq.resources.crosshairs_style_small
import gg.hideouthq.resources.crosshairs_style_with_dot
import gg.hideouthq.resources.crosshairs_thickness
import gg.hideouthq.resources.retry
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun BrowseTab(state: CrosshairsUiState, actions: CrosshairsActions) {
    val results = state.browse.results
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val reduced = HideoutTheme.motion.reduced
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val beside = fitsBeside(maxWidth, PanelWidth)
        val panel: (@Composable (Modifier) -> Unit)? = when (results) {
            is Loadable.Failed -> null
            Loadable.Loading -> { modifier -> LoadingPanel(modifier, previewBeside = !beside) }
            is Loadable.Ready -> results.value.selected?.let { picked -> { modifier -> CommunityPanel(picked, state, actions, modifier, previewBeside = !beside) } }
        }
        val pick: (String) -> Unit = { id ->
            actions.selectCommunity(id)
            if (!beside) scope.launch { if (reduced) list.scrollToItem(0) else list.animateScrollToItem(0) }
        }
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(ColumnGap)) {
            BrowseList(state, actions, list, panel.takeUnless { beside }, pick, Modifier.weight(1f).fillMaxHeight())
            if (beside && panel != null) ScrollPane(Modifier.width(PanelWidth).fillMaxHeight()) { panel(Modifier.fillMaxWidth()) }
        }
    }
}

@Composable
private fun BrowseList(state: CrosshairsUiState, actions: CrosshairsActions, list: LazyListState, panel: (@Composable (Modifier) -> Unit)?, onPick: (String) -> Unit, modifier: Modifier) {
    val results = state.browse.results
    val query = state.browse.query
    val toolbarIndex = if (panel != null) 1 else 0
    val stuck by remember(list, toolbarIndex) {
        derivedStateOf { list.firstVisibleItemIndex > toolbarIndex || (list.firstVisibleItemIndex == toolbarIndex && list.firstVisibleItemScrollOffset > 0) }
    }
    var seen by rememberSaveable { mutableStateOf(query.toString()) }
    LaunchedEffect(query) {
        if (query.toString() != seen) {
            seen = query.toString()
            if (stuck) list.scrollToItem(toolbarIndex)
        }
    }
    BoxWithConstraints(modifier) {
        val columns = ((maxWidth - GridInset * 2 + ColumnGap) / (MinColumn + ColumnGap)).toInt().coerceAtLeast(1)
        LazyColumn(Modifier.fillMaxSize(), list, PaddingValues(bottom = PageBottom)) {
            if (panel != null) item(key = PanelKey) { panel(Modifier.fillMaxWidth().padding(bottom = ColumnGap)) }
            stickyHeader(key = ToolbarKey) { ToolbarCard(query, state.backdrop, actions, stuck) }
            when (results) {
                is Loadable.Failed -> cardItem(FailedKey) {
                    EmptyState(
                        HideoutIcons.Warning, stringResource(Res.string.crosshairs_failed_title),
                        stringResource(if (results.error == DataError.Offline) Res.string.crosshairs_failed_offline else Res.string.crosshairs_failed_unavailable),
                    ) { HideoutButton(stringResource(Res.string.retry), actions::retry, variant = ButtonVariant.Tonal) }
                }
                Loadable.Loading -> tileRows(List(columns * LoadingRows) { it }, columns, id = { it }) { _, modifier -> LoadingTile(modifier) }
                is Loadable.Ready -> {
                    val ready = results.value
                    if (ready.cards.isEmpty()) {
                        cardItem(NoMatchKey) { NoMatch(actions::clearFilters.takeIf { query.text.isNotEmpty() || query.styles.isNotEmpty() }) }
                    } else {
                        tileRows(ready.cards, columns, id = { it.crosshair.id }) { card, modifier ->
                            CommunityTile(card, card == ready.selected, state.backdrop, { onPick(card.crosshair.id) }, modifier)
                        }
                    }
                }
            }
            item(key = EndKey) { Spacer(Modifier.fillMaxWidth().height(CardInset + 4.dp).background(MaterialTheme.colorScheme.surfaceContainer, CardBottomShape)) }
        }
        OverlayScrollbar(rememberScrollbarAdapter(list), Modifier.align(Alignment.TopEnd))
    }
}

@Composable
private fun ToolbarCard(query: BrowseQuery, backdrop: CrosshairBackdrop, actions: CrosshairsActions, stuck: Boolean) {
    val scheme = MaterialTheme.colorScheme
    Box(Modifier.fillMaxWidth().background(scheme.surface)) {
        Column(Modifier.fillMaxWidth().background(scheme.surfaceContainer, CardTopShape).padding(start = CardInset, top = CardInset, end = CardInset)) {
            Toolbar(query, backdrop, actions)
        }
        ScrollHairline(stuck, Modifier.align(Alignment.BottomCenter))
    }
}

private fun LazyListScope.cardItem(key: String, content: @Composable () -> Unit) = item(key = key) {
    Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).padding(horizontal = CardInset)) { content() }
}

private fun <T> LazyListScope.tileRows(cells: List<T>, columns: Int, id: (T) -> Any, tile: @Composable (T, Modifier) -> Unit) {
    val rows = cells.chunked(columns)
    itemsIndexed(rows, key = { _, row -> id(row.first()) }) { index, row ->
        Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).padding(start = GridInset, top = if (index == 0) GridTop else RowGap, end = GridInset)) {
            Row(Modifier.staggeredEnter(1 + index), horizontalArrangement = Arrangement.spacedBy(ColumnGap)) {
                row.forEach { cell -> key(id(cell)) { tile(cell, Modifier.weight(1f)) } }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Toolbar(query: BrowseQuery, backdrop: CrosshairBackdrop, actions: CrosshairsActions) {
    val sorts = mapOf(
        CrosshairSort.Popular to stringResource(Res.string.crosshairs_sort_popular),
        CrosshairSort.ThisWeek to stringResource(Res.string.crosshairs_sort_this_week),
        CrosshairSort.New to stringResource(Res.string.crosshairs_sort_new),
    )
    val backdrops = CrosshairBackdrop.Choices.associateWith { backdropLabel(it) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        SearchField(query.text, actions::setQuery, stringResource(Res.string.crosshairs_search), Modifier.weight(1f), size = SearchFieldSize.Toolbar)
        SegmentedControl(CrosshairSort.entries, query.sort, actions::setSort, sorts::getValue, style = SegmentedStyle.Card)
    }
    Row(Modifier.padding(top = 12.dp, bottom = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(stringResource(Res.string.crosshairs_style_all), query.styles.isEmpty(), { actions.clearStyles() }, style = FilterChipStyle.Outlined)
            CrosshairStyle.entries.forEach { style ->
                FilterChip(styleLabel(style), style in query.styles, { on -> actions.setStyle(style, on) }, style = FilterChipStyle.Outlined)
            }
        }
        SegmentedControl(CrosshairBackdrop.Choices, backdrop, actions::setBackdrop, backdrops::getValue, style = SegmentedStyle.Card)
    }
}

@Composable
private fun CommunityTile(card: CommunityCard, selected: Boolean, backdrop: CrosshairBackdrop, onClick: () -> Unit, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    val type = HideoutTheme.typography
    val source = remember { MutableInteractionSource() }
    val states = source.collectInteractionStates()
    val ring by animateColorAsState(
        when {
            selected -> scheme.primary
            states.hovered -> scheme.surfaceContainerHighest
            else -> scheme.surfaceContainerHighest.copy(alpha = 0f)
        },
        HideoutTheme.motion.effectsFast(), label = "tileRing",
    )
    Column(
        modifier.semantics { this.selected = selected }.hideoutClickable(source, onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(TileRatio).outerRing(ring, TileRadius).focusRing(states.focusVisible, TileShape)) {
            CrosshairTile(card.crosshair.crosshair, backdrop, TileZoom, TileShape, Modifier.matchParentSize())
            when {
                card.inUse -> GlassBadge(stringResource(Res.string.crosshairs_in_use), Modifier.padding(8.dp), dot = true)
                card.saved -> GlassBadge(stringResource(Res.string.crosshairs_saved), Modifier.padding(8.dp))
            }
        }
        Column(Modifier.padding(horizontal = 2.dp)) {
            Text(card.crosshair.name, style = type.titleRow, color = scheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(byline(card), Modifier.padding(top = 1.dp), style = type.subtitle, color = scheme.outline, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun NoMatch(onClear: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 40.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(Res.string.crosshairs_no_match), style = HideoutTheme.typography.body, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (onClear != null) HideoutButton(stringResource(Res.string.crosshairs_clear_filters), onClear, variant = ButtonVariant.Text, size = ButtonSize.Small)
    }
}

@Composable
private fun CommunityPanel(card: CommunityCard, state: CrosshairsUiState, actions: CrosshairsActions, modifier: Modifier, previewBeside: Boolean) {
    val preview: @Composable (Modifier) -> Unit = { previewModifier ->
        Box(previewModifier.aspectRatio(TileRatio)) {
            CrosshairTile(card.crosshair.crosshair, state.backdrop, PanelZoom, RoundedCornerShape(18.dp), Modifier.matchParentSize())
            if (card.crosshair.crosshair.drawsNothing) DrawsNothingNote(Modifier.align(Alignment.Center))
        }
    }
    PageCard(modifier, contentPadding = PaddingValues(8.dp)) {
        if (previewBeside) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                preview(Modifier.weight(1f))
                Column(Modifier.weight(1f)) { PanelDetails(card, state, actions, top = 6.dp) }
            }
        } else {
            preview(Modifier.fillMaxWidth())
            PanelDetails(card, state, actions, top = 14.dp)
        }
    }
}

@Composable
private fun PanelDetails(card: CommunityCard, state: CrosshairsUiState, actions: CrosshairsActions, top: Dp) {
    val scheme = MaterialTheme.colorScheme
    val type = HideoutTheme.typography
    val crosshair = card.crosshair
    Column {
        Column(Modifier.padding(start = 8.dp, top = top, end = 8.dp, bottom = 6.dp)) {
            Text(crosshair.name, style = type.titleSection, color = scheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(byline(card, prefixed = true), Modifier.padding(top = 2.dp), style = type.captionSmall, color = scheme.outline, maxLines = 1)
        }
        Settings(crosshair.crosshair, Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
        Column(Modifier.padding(start = 8.dp, top = 16.dp, end = 8.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val note = state.note
            when {
                !card.inUse -> UseInGameButton(state.cannotUse, actions::useCommunity, fillWidth = true)
                note is SavedNote.Failed || note == SavedNote.GameFolderOnly -> SavedNoteLine(note, actions::retrySave, Modifier.heightIn(min = 44.dp))
                else -> InUseChip(waiting = note == SavedNote.Waiting)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val tonal = PaddingValues(horizontal = 12.dp)
                HideoutButton(
                    stringResource(if (card.saved) Res.string.crosshairs_saved else Res.string.crosshairs_save), actions::toggleSaved, Modifier.weight(1f),
                    variant = ButtonVariant.Tonal, enabled = card.canToggleSaved, contentPadding = tonal,
                )
                HideoutButton(stringResource(Res.string.crosshairs_edit_copy), actions::editCopy, Modifier.weight(1f), variant = ButtonVariant.Tonal, contentPadding = tonal)
            }
            var copied by rememberCopied()
            HideoutButton(
                stringResource(if (copied) Res.string.crosshairs_copied else Res.string.crosshairs_copy_code),
                { actions.copyCode { copied = true } },
                Modifier.align(Alignment.CenterHorizontally),
                variant = ButtonVariant.Text, size = ButtonSize.Small, leadingIcon = if (copied) HideoutIcons.Check else HideoutIcons.Copy, leadingIconSize = 16.dp,
            )
        }
    }
}

@Composable
private fun InUseChip(waiting: Boolean) {
    Box(Modifier.fillMaxWidth().height(44.dp).background(MaterialTheme.colorScheme.secondaryContainer, HideoutShapes.Full), contentAlignment = Alignment.Center) {
        StatusChip(
            stringResource(if (waiting) Res.string.crosshairs_in_use_waiting else Res.string.crosshairs_in_use_next_start),
            ChipTone.Good, leadingIcon = HideoutIcons.Check,
        )
    }
}

@Composable
private fun Settings(crosshair: Crosshair, modifier: Modifier) {
    val on = stringResource(Res.string.crosshairs_on)
    val off = stringResource(Res.string.crosshairs_off)
    val rows = listOf(
        stringResource(Res.string.crosshairs_color) to crosshair.color.hex,
        stringResource(Res.string.crosshairs_length) to crosshair.lines.length.toString(),
        stringResource(Res.string.crosshairs_thickness) to crosshair.lines.thickness.toString(),
        stringResource(Res.string.crosshairs_gap) to crosshair.lines.gap.toString(),
        stringResource(Res.string.crosshairs_opacity) to percent(crosshair.lines.opacity),
        stringResource(Res.string.crosshairs_dot) to percent(crosshair.dot.opacity),
        stringResource(Res.string.crosshairs_outline) to if (crosshair.lines.border.shows) on else off,
        stringResource(Res.string.crosshairs_fixed_gap) to if (crosshair.fixedGap) on else off,
    )
    Column(modifier) {
        rows.chunked(2).forEachIndexed { index, pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                pair.forEachIndexed { column, (label, value) ->
                    Setting(label, value, if (index == 0 && column == 0) crosshair.color.toColor() else null, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun Setting(label: String, value: String, swatch: Color?, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    val line = scheme.onSurface.copy(alpha = RuleAlpha)
    Row(
        modifier
            .height(34.dp)
            .drawBehind { drawLine(line, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1.dp.toPx()) },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f), style = HideoutTheme.typography.captionSmall, color = scheme.onSurfaceVariant, maxLines = 1)
        if (swatch != null) Box(Modifier.size(12.dp).swatch(swatch, RoundedCornerShape(4.dp)))
        Text(value, style = valueStyle(), color = scheme.onSurface, maxLines = 1)
    }
}

@Composable
private fun LoadingTile(modifier: Modifier) {
    val sweep = rememberShimmer()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(TileRatio).skeleton(sweep, TileShape))
        Box(Modifier.width(110.dp).height(14.dp).skeleton(sweep, LineShape))
    }
}

@Composable
private fun LoadingPanel(modifier: Modifier, previewBeside: Boolean) {
    val sweep = rememberShimmer()
    PageCard(modifier, contentPadding = PaddingValues(8.dp)) {
        if (previewBeside) {
            Row(Modifier.height(NarrowPanelHeight), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f).aspectRatio(TileRatio).skeleton(sweep, RoundedCornerShape(18.dp)))
                Box(Modifier.weight(1f)) { Box(Modifier.padding(start = 8.dp, top = 10.dp).width(160.dp).height(20.dp).skeleton(sweep, LineShape)) }
            }
        } else {
            Box(Modifier.fillMaxWidth().aspectRatio(TileRatio).skeleton(sweep, RoundedCornerShape(18.dp)))
            Box(Modifier.padding(start = 8.dp, top = 18.dp, bottom = 120.dp).width(160.dp).height(20.dp).skeleton(sweep, LineShape))
        }
    }
}

@Composable
private fun byline(card: CommunityCard, prefixed: Boolean = false): String {
    val date = if (card.thisYear) ModLabels.dayMonth(card.made) else ModLabels.date(card.made)
    val creator = card.crosshair.creator?.let { if (prefixed) stringResource(Res.string.crosshairs_by, it) else it }
    return ModLabels.join(creator, date)
}

@Composable
private fun styleLabel(style: CrosshairStyle): String = stringResource(
    when (style) {
        CrosshairStyle.Small -> Res.string.crosshairs_style_small
        CrosshairStyle.DotOnly -> Res.string.crosshairs_style_dot_only
        CrosshairStyle.WithDot -> Res.string.crosshairs_style_with_dot
        CrosshairStyle.Outline -> Res.string.crosshairs_style_outline
        CrosshairStyle.FixedGap -> Res.string.crosshairs_style_fixed_gap
    },
)

private val PanelWidth = 380.dp

private val NarrowPanelHeight = 359.dp
private val MinColumn = 200.dp
private val ColumnGap = 12.dp
private val RowGap = 18.dp
private val CardInset = 10.dp

private val GridInset = 12.dp
private val GridTop = 14.dp
private val CardTopShape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
private val CardBottomShape = RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp)
private const val PanelKey = "panel"
private const val ToolbarKey = "toolbar"
private const val FailedKey = "failed"
private const val NoMatchKey = "noMatch"
private const val EndKey = "end"
private val TileRadius = 14.dp
private val TileShape = RoundedCornerShape(TileRadius)
private val LineShape = RoundedCornerShape(6.dp)
private const val TileRatio = 16f / 10f
private const val TileZoom = 3f
private const val PanelZoom = 5f
private const val LoadingRows = 2
private const val RuleAlpha = 0.06f
