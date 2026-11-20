package gg.hideouthq.feature.library.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import gg.hideouthq.core.designsystem.component.CoverArt
import gg.hideouthq.core.designsystem.component.HideoutIcon
import gg.hideouthq.core.designsystem.component.HideoutSwitch
import gg.hideouthq.core.designsystem.component.LocalModCardStyle
import gg.hideouthq.core.designsystem.component.ProgressBar
import gg.hideouthq.core.designsystem.icon.HideoutIcons
import gg.hideouthq.core.designsystem.interaction.collectInteractionStates
import gg.hideouthq.core.designsystem.interaction.focusRing
import gg.hideouthq.core.designsystem.interaction.hideoutClickable
import gg.hideouthq.core.designsystem.theme.HideoutDimens
import gg.hideouthq.core.designsystem.theme.HideoutMotion
import gg.hideouthq.core.designsystem.theme.HideoutShapes
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import gg.hideouthq.core.designsystem.theme.HideoutWeights
import gg.hideouthq.core.library.OrderView
import gg.hideouthq.core.model.ModCardStyle
import gg.hideouthq.core.ui.CompatChip
import gg.hideouthq.core.ui.WindowModal
import gg.hideouthq.feature.library.DownloadPhase
import gg.hideouthq.feature.library.LibraryActions
import gg.hideouthq.feature.library.LibraryRow
import gg.hideouthq.feature.library.SelectGesture
import gg.hideouthq.resources.Res
import gg.hideouthq.resources.library_more
import gg.hideouthq.resources.library_switch
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun AutoFillGrid(modifier: Modifier = Modifier, content: @Composable (columnWidth: Dp) -> Unit) {
    val under = LocalModCardStyle.current == ModCardStyle.UnderImage
    val gap = if (under) HideoutDimens.Card.UnderColumnGap else HideoutDimens.LibraryCard.Gap
    val rowGap = if (under) HideoutDimens.Card.UnderRowGap else HideoutDimens.LibraryCard.Gap
    BoxWithConstraints(modifier) {
        val columns = (((maxWidth + gap) / (HideoutDimens.LibraryCard.MinColumn + gap)).toInt()).coerceAtLeast(1)
        val columnWidth = (maxWidth - gap * (columns - 1)) / columns
        FlowRow(horizontalArrangement = Arrangement.spacedBy(gap), verticalArrangement = Arrangement.spacedBy(rowGap), modifier = Modifier.fillMaxWidth()) {
            content(columnWidth)
        }
    }
}

@Composable
internal fun LibraryCardGrid(
    rows: List<LibraryRow>,
    selectedIds: Set<String>,
    onSelect: (String, SelectGesture) -> Unit,
    actions: LibraryActions,
    modal: WindowModal,
    modifier: Modifier = Modifier,
    view: OrderView? = null,
) {
    val order = rows.map { it.installId }
    val visible = rows.filter { it.visible }.map { it.installId }
    AutoFillGrid(modifier) { columnWidth ->
        rows.forEach { row ->
            if (!row.visible) return@forEach
            key(row.installId) {
                LibraryCard(row, row.installId in selectedIds, Modifier.width(columnWidth), onSelect, actions, modal, view, order, visible, if (row.installId in selectedIds) selectedIds.size else 1)
            }
        }
    }
}

@Composable
internal fun LibraryCard(
    row: LibraryRow,
    selected: Boolean,
    modifier: Modifier,
    onSelect: (String, SelectGesture) -> Unit,
    actions: LibraryActions,
    modal: WindowModal,
    view: OrderView?,
    order: List<String>,
    visible: List<String>,
    menuCount: Int,
) {
    val source = remember { MutableInteractionSource() }
    val states = source.collectInteractionStates()
    val motion = HideoutTheme.motion
    val scheme = MaterialTheme.colorScheme
    val colors = HideoutTheme.colors
    var menuOpen by remember { mutableStateOf(false) }
    val more = remember { FocusRequester() }
    val focus = remember { FocusRequester() }
    val scale by animateFloatAsState(if (states.hovered) HideoutMotion.HoverScaleCard else 1f, motion.spatialFast(), label = "libraryCardHover")
    val dim = if (row.enabled) 1f else HideoutMotion.DimmedAlpha
    val under = LocalModCardStyle.current == ModCardStyle.UnderImage
    val shape = if (under) RoundedCornerShape(HideoutDimens.Card.UnderImageRadius) else HideoutShapes.Card
    val titleInk = if (under) scheme.onSurface else colors.onImage
    val metaInk = if (under) scheme.outline else colors.onImageVariant
    val transferring = row.operation?.phase as? DownloadPhase.Transferring
    val lifted = Modifier.graphicsLayer { scaleX = scale; scaleY = scale }
    val picture = Modifier
        .clip(shape)
        .then(
            if (selected) Modifier
                .border(HideoutDimens.LibraryCard.SelectedRing, scheme.primary, shape)
                .border(HideoutDimens.LibraryCard.SelectedRing + HideoutDimens.LibraryCard.SelectedGap, scheme.surface, shape)
            else Modifier,
        )
        .focusRing(states.focusVisible, shape)
    val interact = Modifier
        .selectGestures(row.installId, selected, onSelect, focus, onMenu = { menuOpen = true })
        .semantics { this.selected = selected }
        .focusRequester(focus)
        .hideoutClickable(source, onClick = { onSelect(row.installId, SelectGesture.Only) })
    val art: @Composable BoxScope.() -> Unit = {
        Box(Modifier.fillMaxSize().alpha(if (row.enabled) 1f else CardArtOffAlpha)) {
            CoverArt(seed = row.installId, modifier = Modifier.fillMaxSize(), shape = shape, imageUrl = row.coverFullUrl)
        }
        if (!under) Box(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(CardScrimFraction)
                .align(Alignment.BottomCenter)
                .background(Brush.verticalGradient(listOf(Color.Transparent, colors.imageScrim))),
        )
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(HideoutDimens.Card.BadgeInset)
                .size(HideoutDimens.LibraryCard.Rank)
                .background(colors.glass, HideoutShapes.Full),
            contentAlignment = Alignment.Center,
        ) {
            Text(row.rank.toString().padStart(2, '0'), style = HideoutTheme.typography.mono, color = colors.onImage)
        }
        Box(Modifier.align(Alignment.TopEnd).padding(HideoutDimens.Card.BadgeInset)) {
            DarkCircleButton(HideoutIcons.MoreHorizontal, stringResource(Res.string.library_more, row.title), Modifier.focusRequester(more)) {
                if (!selected) onSelect(row.installId, SelectGesture.Only)
                menuOpen = true
            }
            InstalledRowMenu(row, menuOpen, { menuOpen = false }, more, actions, modal, view, order, visible, menuCount)
        }
        if (transferring != null) {
            ProgressBar(
                transferring.percent / 100f,
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = HideoutDimens.Downloading.CardBarInset, end = HideoutDimens.Downloading.CardBarInset, bottom = HideoutDimens.Downloading.CardBarBottom)
                    .fillMaxWidth(),
            )
        }
    }
    val caption: @Composable (Modifier) -> Unit = { captionModifier ->
        Row(
            modifier = captionModifier.fillMaxWidth().alpha(dim),
            horizontalArrangement = Arrangement.spacedBy(HideoutDimens.Card.CaptionGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(if (under) HideoutDimens.Card.UnderLineGap else 0.dp)) {
                Text(
                    row.title,
                    style = if (under) HideoutTheme.typography.titleCard.copy(fontSize = 14.5.sp, fontWeight = HideoutWeights.Label) else HideoutTheme.typography.titleCard,
                    color = titleInk, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (row.compatPlace || row.damage != null || row.hiddenModels > 0) {
                    Row(Modifier.height(HideoutDimens.Chip.HeightSmall), horizontalArrangement = Arrangement.spacedBy(HideoutDimens.Library.NoteGap), verticalAlignment = Alignment.CenterVertically) {
                        row.damage?.let { DamageChip(it) }
                        row.compat?.let { CompatChip(it, row.enabled, row.title, onClick = { actions.openCompat(row.installId) }) }
                        if (row.hiddenModels > 0) HiddenModelsChip(row) { actions.inspectFilePriority(row.installId) }
                    }
                }
                val meta = row.statusLine()
                if (meta.isNotEmpty()) Text(
                    meta,
                    style = if (under) HideoutTheme.typography.subtitle.copy(fontSize = 12.5.sp) else HideoutTheme.typography.subtitle,
                    color = metaInk, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            RowUpdateActions(row, actions, content = titleInk)
            HideoutSwitch(
                checked = row.enabled,
                onCheckedChange = { actions.setEnabled(row.installId, it) },
                contentDescription = stringResource(Res.string.library_switch, row.title),
                modifier = Modifier.alpha(if (row.canSwitch || row.applying) 1f else HideoutMotion.DisabledAlpha),
                enabled = row.canSwitch,
            )
        }
    }
    if (under) {
        Column(modifier.then(lifted).then(interact), verticalArrangement = Arrangement.spacedBy(HideoutDimens.Card.UnderTextGap)) {
            Box(Modifier.fillMaxWidth().aspectRatio(HideoutDimens.Card.UnderImageRatio).then(picture), content = art)
            caption(Modifier.padding(horizontal = HideoutDimens.Card.UnderTextInset))
        }
    } else {
        Box(modifier.then(lifted).height(HideoutDimens.LibraryCard.Height).then(picture).then(interact)) {
            art()
            caption(
                Modifier.align(Alignment.BottomStart).padding(
                    start = HideoutDimens.Card.CaptionHorizontalInset,
                    end = HideoutDimens.Card.CaptionHorizontalInset,
                    bottom = if (transferring != null) HideoutDimens.Downloading.CardBarInset else HideoutDimens.Card.CaptionBottomInset,
                ),
            )
        }
    }
}

@Composable
internal fun DarkCircleButton(icon: ImageVector, contentDescription: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .size(HideoutDimens.LibraryCard.More)
            .background(HideoutTheme.colors.glass, HideoutShapes.Full)
            .semantics { this.contentDescription = contentDescription }
            .hideoutClickable(source, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        HideoutIcon(icon, contentDescription = null, tint = HideoutTheme.colors.onImage)
    }
}

internal const val CardScrimFraction = 0.64f
private const val CardArtOffAlpha = 0.38f
