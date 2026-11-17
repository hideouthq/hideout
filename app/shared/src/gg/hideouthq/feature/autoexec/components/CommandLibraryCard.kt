package gg.hideouthq.feature.autoexec.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import gg.hideouthq.core.designsystem.component.CardHeader
import gg.hideouthq.core.designsystem.component.FilterChip
import gg.hideouthq.core.designsystem.component.FilterChipStyle
import gg.hideouthq.core.designsystem.component.HideoutIcon
import gg.hideouthq.core.designsystem.component.OverlayScrollbar
import gg.hideouthq.core.designsystem.component.PageCard
import gg.hideouthq.core.designsystem.component.ScrollHairline
import gg.hideouthq.core.designsystem.component.SearchField
import gg.hideouthq.core.designsystem.component.SearchFieldSize
import gg.hideouthq.core.designsystem.component.isScrolled
import gg.hideouthq.core.designsystem.component.rememberScrollbarAdapter
import gg.hideouthq.core.designsystem.icon.HideoutIcons
import gg.hideouthq.core.designsystem.interaction.collectInteractionStates
import gg.hideouthq.core.designsystem.interaction.focusRing
import gg.hideouthq.core.designsystem.interaction.hideoutClickable
import gg.hideouthq.core.designsystem.theme.HideoutMotion
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import gg.hideouthq.feature.autoexec.AutoexecPageActions
import gg.hideouthq.feature.autoexec.LibraryItem
import gg.hideouthq.feature.autoexec.LibraryUi
import gg.hideouthq.resources.Res
import gg.hideouthq.resources.autoexec_commands
import gg.hideouthq.resources.autoexec_library
import gg.hideouthq.resources.autoexec_library_add
import gg.hideouthq.resources.autoexec_library_all
import gg.hideouthq.resources.autoexec_library_none
import gg.hideouthq.resources.autoexec_library_note
import gg.hideouthq.resources.autoexec_library_remove
import gg.hideouthq.resources.autoexec_library_search
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CommandLibraryCard(library: LibraryUi, actions: AutoexecPageActions, modifier: Modifier = Modifier, fitHeight: Boolean = false) {
    PageCard(modifier) {
        CardHeader(stringResource(Res.string.autoexec_library), pluralStringResource(Res.plurals.autoexec_commands, library.total, library.total))
        SearchField(library.query, actions::search, stringResource(Res.string.autoexec_library_search), Modifier.fillMaxWidth().padding(start = 4.dp, top = 2.dp, end = 4.dp), size = SearchFieldSize.Toolbar)
        FlowRow(
            Modifier.padding(start = 4.dp, top = 12.dp, end = 4.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            FilterChip(stringResource(Res.string.autoexec_library_all), library.category == null, { actions.filter(null) }, style = FilterChipStyle.Outlined)
            library.categories.forEach { (category, count) ->
                FilterChip(categoryName(category), library.category == category, { on -> actions.filter(category.takeIf { on }) }, style = FilterChipStyle.Outlined, count = count.toString())
            }
        }
        if (fitHeight) {
            val scroll = rememberScrollState()
            val search = "${library.query}\u0000${library.category}"
            var seen by rememberSaveable { mutableStateOf(search) }
            LaunchedEffect(search) {
                if (search != seen) {
                    seen = search
                    scroll.scrollTo(0)
                }
            }
            Box(Modifier.weight(1f, fill = false)) {
                Column(Modifier.verticalScroll(scroll)) { Commands(library, actions) }
                Box(Modifier.matchParentSize()) {
                    ScrollHairline(scroll.isScrolled, Modifier.align(Alignment.TopCenter))
                    OverlayScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.TopEnd))
                }
            }
        } else {
            Commands(library, actions)
        }
    }
}

@Composable
private fun Commands(library: LibraryUi, actions: AutoexecPageActions) {
    val scheme = MaterialTheme.colorScheme
    val type = autoexecType()
    Row(Modifier.padding(start = 8.dp, top = 10.dp, end = 8.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        HideoutIcon(HideoutIcons.Info, null, Modifier.padding(top = 1.dp), size = 15.dp, tint = scheme.outline)
        Text(stringResource(Res.string.autoexec_library_note), style = type.small, color = scheme.outline)
    }
    if (library.groups.isEmpty()) Text(stringResource(Res.string.autoexec_library_none), Modifier.padding(start = 10.dp, top = 12.dp, bottom = 8.dp), style = type.note, color = scheme.outline)
    library.groups.forEach { group ->
        GroupLabel(categoryName(group.category), group.items.size.toString(), start = 10.dp, top = 8.dp)
        group.items.forEach { item -> key(item.command.name) { LibraryRow(item, library.canAdd, actions) } }
    }
}

@Composable
private fun LibraryRow(item: LibraryItem, enabled: Boolean, actions: AutoexecPageActions) {
    val scheme = MaterialTheme.colorScheme
    val motion = HideoutTheme.motion
    val type = autoexecType()
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val fill by animateColorAsState(if (hovered) scheme.surfaceContainerHigh else scheme.surfaceContainerHigh.copy(alpha = 0f), motion.effectsFast(), label = "libraryRow")
    val badge by animateColorAsState(if (hovered) scheme.surfaceContainerHighest else scheme.surfaceContainerHigh, motion.effectsFast(), label = "libraryBadge")
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(fill).hoverable(source).padding(start = 10.dp, top = 9.dp, end = 6.dp, bottom = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(item.command.name, Modifier.weight(1f, fill = false), style = type.code, color = scheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Box(Modifier.height(20.dp).clip(RoundedCornerShape(6.dp)).background(badge).padding(horizontal = 7.dp), contentAlignment = Alignment.Center) {
                    Text(item.command.suggested, style = type.badge, color = scheme.onSurfaceVariant, maxLines = 1)
                }
            }
            Text(item.command.description, Modifier.padding(top = 3.dp), style = type.note, color = scheme.outline)
        }
        AddButton(item, rowHovered = hovered, enabled = enabled) { actions.toggle(item) }
    }
}

@Composable
private fun AddButton(item: LibraryItem, rowHovered: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val motion = HideoutTheme.motion
    val source = remember { MutableInteractionSource() }
    val states = source.collectInteractionStates()
    val shape = RoundedCornerShape(12.dp)
    val fill by animateColorAsState(
        when {
            item.added -> scheme.secondaryContainer
            rowHovered || states.hovered -> scheme.surfaceContainerHighest
            else -> scheme.surfaceContainerHigh
        },
        motion.effectsFast(), label = "libraryButton",
    )
    val tint by animateColorAsState(if (item.added) scheme.secondary else scheme.onSurface, motion.effectsFast(), label = "libraryButtonIcon")
    val label = stringResource(if (item.added) Res.string.autoexec_library_remove else Res.string.autoexec_library_add, item.command.name)
    Box(
        Modifier.size(36.dp).alpha(if (enabled) 1f else HideoutMotion.DisabledAlpha).focusRing(states.focusVisible, shape).clip(shape).background(fill)
            .semantics { contentDescription = label }.hideoutClickable(source, enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        HideoutIcon(if (item.added) HideoutIcons.Check else HideoutIcons.Plus, null, size = 18.dp, tint = tint)
    }
}
