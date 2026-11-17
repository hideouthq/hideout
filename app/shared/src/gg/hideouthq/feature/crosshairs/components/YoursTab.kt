package gg.hideouthq.feature.crosshairs.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import gg.hideouthq.core.crosshair.SavedCrosshair
import gg.hideouthq.core.designsystem.component.CardHeader
import gg.hideouthq.core.designsystem.component.GlassBadge
import gg.hideouthq.core.designsystem.component.GlassBadgeSize
import gg.hideouthq.core.designsystem.component.HideoutIcon
import gg.hideouthq.core.designsystem.component.HideoutMenu
import gg.hideouthq.core.designsystem.component.MenuEntry
import gg.hideouthq.core.designsystem.component.PageCard
import gg.hideouthq.core.designsystem.component.SegmentedControl
import gg.hideouthq.core.designsystem.component.SegmentedStyle
import gg.hideouthq.core.designsystem.icon.HideoutIcons
import gg.hideouthq.core.designsystem.interaction.collectInteractionStates
import gg.hideouthq.core.designsystem.interaction.focusRing
import gg.hideouthq.core.designsystem.interaction.hideoutClickable
import gg.hideouthq.core.designsystem.interaction.insetRing
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import gg.hideouthq.core.designsystem.theme.HideoutWeights
import gg.hideouthq.core.ui.MainAndSide
import gg.hideouthq.core.ui.WindowModal
import gg.hideouthq.core.ui.crosshair.CrosshairBackdrop
import gg.hideouthq.core.ui.crosshair.CrosshairTile
import gg.hideouthq.core.ui.fitsBeside
import gg.hideouthq.feature.crosshairs.CrosshairsActions
import gg.hideouthq.feature.crosshairs.CrosshairsUiState
import gg.hideouthq.feature.crosshairs.PreviewZoom
import gg.hideouthq.resources.Res
import gg.hideouthq.resources.crosshairs_delete
import gg.hideouthq.resources.crosshairs_duplicate
import gg.hideouthq.resources.crosshairs_in_use
import gg.hideouthq.resources.crosshairs_more
import gg.hideouthq.resources.crosshairs_new
import gg.hideouthq.resources.crosshairs_new_hint
import gg.hideouthq.resources.crosshairs_rename
import gg.hideouthq.resources.crosshairs_tag_in_use
import gg.hideouthq.resources.crosshairs_tag_not_in_use
import gg.hideouthq.resources.crosshairs_yours_hint
import gg.hideouthq.resources.crosshairs_yours_title
import gg.hideouthq.resources.crosshairs_zoom_actual
import gg.hideouthq.resources.crosshairs_zoom_six
import gg.hideouthq.resources.crosshairs_zoom_three
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun YoursTab(state: CrosshairsUiState, actions: CrosshairsActions, modal: WindowModal) {
    val yours = state.yours
    val selected = yours.selected
    val inUse = selected != null && selected.id == state.inUse?.id
    val name = remember { FocusRequester() }
    var renames by remember { mutableIntStateOf(0) }
    val main: @Composable (Modifier) -> Unit = { modifier ->
        Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (selected != null) Preview(selected, inUse, state.backdrop, yours.zoom, actions)
            PageCard(Modifier.fillMaxWidth()) {
                CardHeader(stringResource(Res.string.crosshairs_yours_title), stringResource(Res.string.crosshairs_yours_hint))
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 4.dp, top = 2.dp, end = 4.dp, bottom = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    yours.cards.forEach { card ->
                        YoursCard(card, card.id == selected?.id, card.id == state.inUse?.id, actions, modal) {
                            actions.selectYours(card.id)
                            renames++
                        }
                    }
                    NewCard(actions::create)
                }
            }
        }
    }
    val side: (@Composable (Modifier) -> Unit)? = selected?.let { { modifier -> CrosshairEditor(it, inUse, yours.nameDraft, state, actions, modifier, name, renames) } }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (side != null && fitsBeside(maxWidth, EditorWidth)) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ScrollPane(Modifier.weight(1f).fillMaxHeight()) { main(Modifier.fillMaxWidth()) }
                ScrollPane(Modifier.width(EditorWidth).fillMaxHeight()) { side(Modifier.fillMaxWidth()) }
            }
        } else {
            ScrollPane(Modifier.fillMaxSize()) { MainAndSide(EditorWidth, main, side) }
        }
    }
}

@Composable
private fun Preview(selected: SavedCrosshair, inUse: Boolean, backdrop: CrosshairBackdrop, zoom: PreviewZoom, actions: CrosshairsActions) {
    val backdrops = CrosshairBackdrop.Choices.associateWith { backdropLabel(it) }
    val zooms = mapOf(
        PreviewZoom.Actual to stringResource(Res.string.crosshairs_zoom_actual),
        PreviewZoom.Three to stringResource(Res.string.crosshairs_zoom_three),
        PreviewZoom.Six to stringResource(Res.string.crosshairs_zoom_six),
    )
    Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
        CrosshairTile(selected.crosshair, backdrop, zoom.zoom, PreviewShape, Modifier.matchParentSize())
        if (selected.crosshair.drawsNothing) DrawsNothingNote(Modifier.align(Alignment.Center))
        val inset = Modifier.padding(14.dp)
        SegmentedControl(CrosshairBackdrop.Choices, backdrop, actions::setBackdrop, backdrops::getValue, Modifier.align(Alignment.TopStart).then(inset), segmentPadding = 12.dp, style = SegmentedStyle.Glass)
        Row(Modifier.align(Alignment.BottomStart).fillMaxWidth().then(inset), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
            GlassBadge(
                stringResource(if (inUse) Res.string.crosshairs_tag_in_use else Res.string.crosshairs_tag_not_in_use, selected.name),
                Modifier.weight(1f, fill = false).padding(end = 12.dp).widthIn(max = BadgeMaxWidth), size = GlassBadgeSize.Regular, dot = inUse,
            )
            SegmentedControl(PreviewZoom.entries, zoom, actions::setZoom, zooms::getValue, segmentPadding = 12.dp, style = SegmentedStyle.Glass)
        }
    }
}

@Composable
private fun YoursCard(card: SavedCrosshair, selected: Boolean, inUse: Boolean, actions: CrosshairsActions, modal: WindowModal, onRename: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val type = HideoutTheme.typography
    val source = remember { MutableInteractionSource() }
    val states = source.collectInteractionStates()
    val more = remember { MutableInteractionSource() }
    val moreStates = more.collectInteractionStates()
    val moreFocus = remember { FocusRequester() }
    var menuOpen by remember { mutableStateOf(false) }
    val reveal = remember { BringIntoViewRequester() }
    var placed by remember { mutableStateOf(false) }
    LaunchedEffect(selected, placed) { if (selected && placed) reveal.bringIntoView() }
    val container by animateColorAsState(
        if (selected || states.hovered || menuOpen) scheme.surfaceContainerHigh else scheme.surfaceContainerHigh.copy(alpha = 0f),
        HideoutTheme.motion.effectsFast(), label = "yoursCard",
    )
    Box(Modifier.bringIntoViewRequester(reveal).onPlaced { placed = true }) {
        Column(
            Modifier
                .width(CardWidth)
                .background(container, YoursCardShape)
                .then(if (selected) Modifier.insetRing(YoursCardShape, scheme.primary) { 2.dp.toPx() } else Modifier)
                .focusRing(states.focusVisible, YoursCardShape)
                .semantics { this.selected = selected }
                .hideoutClickable(source) { actions.selectYours(card.id) }
                .padding(start = 6.dp, top = 6.dp, end = 6.dp, bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CrosshairTile(card.crosshair, CrosshairBackdrop.Card, CardZoom, TileShape, Modifier.fillMaxWidth().height(TileHeight))
            CardText(card.name) {
                if (inUse) {
                    Box(Modifier.size(6.dp).background(HideoutTheme.colors.status, CircleShape))
                    Text(stringResource(Res.string.crosshairs_in_use), style = type.captionCompact.copy(fontWeight = HideoutWeights.Title), color = HideoutTheme.colors.status, maxLines = 1)
                } else {
                    Text(originLabel(card.origin), style = type.captionCompact, color = scheme.outline, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        val shown = states.hovered || moreStates.hovered || moreStates.focusVisible || menuOpen
        val moreLabel = stringResource(Res.string.crosshairs_more, card.name)
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .padding(10.dp)
                .alpha(if (shown) 1f else 0f)
                .size(28.dp)
                .background(HideoutTheme.colors.glass, CircleShape)
                .focusRing(moreStates.focusVisible, CircleShape)
                .focusRequester(moreFocus)
                .semantics { contentDescription = moreLabel }
                .hideoutClickable(more) { menuOpen = true },
            contentAlignment = Alignment.Center,
        ) {
            HideoutIcon(HideoutIcons.MoreHorizontal, null, size = 18.dp, tint = HideoutTheme.colors.onImage)
            if (menuOpen) {
                DisposableEffect(Unit) {
                    modal.hold(moreFocus)
                    onDispose { modal.release(moreFocus) }
                }
                HideoutMenu(
                    entries = listOfNotNull(
                        MenuEntry.Item(stringResource(Res.string.crosshairs_rename), HideoutIcons.Rename, onRename),
                        MenuEntry.Item(stringResource(Res.string.crosshairs_duplicate), HideoutIcons.Duplicate, { actions.duplicate(card.id) }),
                        if (inUse) null else MenuEntry.Item(stringResource(Res.string.crosshairs_delete), HideoutIcons.Trash, { actions.delete(card.id) }, danger = true),
                    ),
                    onClose = { byKey ->
                        menuOpen = false
                        if (byKey) moreFocus.requestFocus()
                    },
                    label = moreLabel,
                )
            }
        }
    }
}

@Composable
private fun NewCard(onCreate: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val source = remember { MutableInteractionSource() }
    val states = source.collectInteractionStates()
    val container by animateColorAsState(if (states.hovered) scheme.surfaceContainerHigh else scheme.surfaceContainerHigh.copy(alpha = 0f), HideoutTheme.motion.effectsFast(), label = "newCard")
    Column(
        Modifier
            .width(CardWidth)
            .background(container, YoursCardShape)
            .focusRing(states.focusVisible, YoursCardShape)
            .hideoutClickable(source, onClick = onCreate)
            .padding(start = 6.dp, top = 6.dp, end = 6.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.fillMaxWidth().height(TileHeight).insetRing(TileShape, scheme.surfaceContainerHighest) { 1.5.dp.toPx() }, contentAlignment = Alignment.Center) {
            HideoutIcon(HideoutIcons.Plus, null, size = 22.dp, tint = scheme.onSurfaceVariant)
        }
        CardText(stringResource(Res.string.crosshairs_new)) {
            Text(stringResource(Res.string.crosshairs_new_hint), style = HideoutTheme.typography.captionCompact, color = scheme.outline, maxLines = 1)
        }
    }
}

@Composable
private fun CardText(name: String, source: @Composable () -> Unit) {
    Column(Modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(name, style = headingStyle(), color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) { source() }
    }
}

private val EditorWidth = 400.dp
private val BadgeMaxWidth = 320.dp
private val CardWidth = 132.dp
private val TileHeight = 76.dp
private val PreviewShape = RoundedCornerShape(24.dp)
private val YoursCardShape = RoundedCornerShape(18.dp)
private val TileShape = RoundedCornerShape(13.dp)
private const val CardZoom = 2.4f
