package gg.hideouthq.feature.moddetail.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.em
import gg.hideouthq.core.designsystem.component.HideoutIcon
import gg.hideouthq.core.designsystem.component.HideoutIconButton
import gg.hideouthq.core.designsystem.component.LinkButton
import gg.hideouthq.core.designsystem.component.ScrollColumn
import gg.hideouthq.core.designsystem.icon.HideoutIcons
import gg.hideouthq.core.designsystem.interaction.collectInteractionStates
import gg.hideouthq.core.designsystem.interaction.focusRing
import gg.hideouthq.core.designsystem.interaction.hideoutClickable
import gg.hideouthq.core.designsystem.theme.HideoutDimens
import gg.hideouthq.core.designsystem.theme.HideoutShapes
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import gg.hideouthq.core.designsystem.theme.HideoutWeights
import gg.hideouthq.core.model.ChangeKindTag
import gg.hideouthq.core.model.ModUpdate
import gg.hideouthq.core.ui.ModLabels
import gg.hideouthq.resources.Res
import gg.hideouthq.resources.changelog_added
import gg.hideouthq.resources.changelog_back
import gg.hideouthq.resources.changelog_count
import gg.hideouthq.resources.changelog_fixed
import gg.hideouthq.resources.changelog_full_history
import gg.hideouthq.resources.changelog_improved
import gg.hideouthq.resources.changelog_title
import gg.hideouthq.resources.changelog_whats_new
import gg.hideouthq.resources.changelog_whats_new_plain
import gg.hideouthq.resources.changelog_you_have_this
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import gg.hideouthq.core.designsystem.theme.HideoutDimens.Changelog as Log

@Composable
internal fun WhatsNewRow(latest: ModUpdate, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val source = remember { MutableInteractionSource() }
    val states = source.collectInteractionStates()
    val shape = RoundedCornerShape(Log.RowRadius)
    Row(
        modifier
            .fillMaxWidth()
            .background(if (states.hovered) scheme.surfaceContainerHigh else scheme.surfaceContainer, shape)
            .focusRing(states.focusVisible, shape)
            .hideoutClickable(source, role = Role.Button, onClick = onClick)
            .padding(start = Log.RowStart, top = Log.RowPadding, end = Log.RowPadding, bottom = Log.RowPadding),
        horizontalArrangement = Arrangement.spacedBy(Log.RowGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(HideoutDimens.Fact.IconTile).background(scheme.surfaceContainerHigh, HideoutShapes.Tile), contentAlignment = Alignment.Center) {
            HideoutIcon(HideoutIcons.ViewList, contentDescription = null, tint = scheme.onSurface)
        }
        Column(Modifier.weight(1f)) {
            Text(
                latest.version?.let { stringResource(Res.string.changelog_whats_new, it) } ?: stringResource(Res.string.changelog_whats_new_plain),
                style = HideoutTheme.typography.caption,
                color = scheme.outline,
            )
            Text(ModLabels.join(latest.title, ModLabels.dayMonth(latest.date)), style = HideoutTheme.typography.factValue, color = scheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        HideoutIcon(HideoutIcons.ChevronRight, contentDescription = null, size = Log.Chevron, tint = scheme.outline)
    }
}

@Composable
internal fun Changelog(updates: List<ModUpdate>, historyUrl: String?, installedFiles: Set<Long>, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val uriHandler = LocalUriHandler.current
    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().padding(start = HideoutDimens.SidePanel.Padding + Log.HeaderStart, end = HideoutDimens.SidePanel.Padding + Log.HeaderEnd, bottom = Log.HeaderBottom),
            horizontalArrangement = Arrangement.spacedBy(Log.HeaderGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HideoutIconButton(HideoutIcons.Back, stringResource(Res.string.changelog_back), onBack)
            Text(stringResource(Res.string.changelog_title), Modifier.weight(1f).semantics { heading() }, style = HideoutTheme.typography.titleSection, color = scheme.onSurface)
            Text(pluralStringResource(Res.plurals.changelog_count, updates.size, updates.size), style = HideoutTheme.typography.captionSmall, color = scheme.outline)
        }
        ScrollColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(start = HideoutDimens.SidePanel.Padding, end = HideoutDimens.SidePanel.Padding, bottom = HideoutDimens.SidePanel.Padding),
            verticalArrangement = Arrangement.spacedBy(Log.ListGap),
        ) {
            updates.forEach { update -> UpdateCard(update, update.files.any { it in installedFiles }) }
            if (historyUrl != null) LinkButton(stringResource(Res.string.changelog_full_history), { uriHandler.openUri(historyUrl) })
        }
    }
}

@Composable
private fun UpdateCard(update: ModUpdate, installed: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val type = HideoutTheme.typography
    val shape = RoundedCornerShape(Log.CardRadius)
    Column(
        Modifier
            .fillMaxWidth()
            .background(scheme.surfaceContainer, shape)
            .then(if (installed) Modifier.border(Log.CurrentRing, scheme.outlineVariant, shape) else Modifier)
            .padding(start = Log.CardHorizontal, end = Log.CardHorizontal, top = Log.CardTop, bottom = Log.CardBottom),
        verticalArrangement = Arrangement.spacedBy(Log.CardGap),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(Log.HeaderGap), verticalAlignment = Alignment.CenterVertically) {
            update.version?.let { version ->
                Box(Modifier.height(Log.VersionHeight).background(scheme.surfaceContainerHighest, RoundedCornerShape(Log.VersionRadius)).padding(horizontal = Log.VersionHorizontal), contentAlignment = Alignment.Center) {
                    Text(version, style = type.mono, color = scheme.onSurface)
                }
            }
            Text(ModLabels.date(update.date), Modifier.weight(1f), style = type.subtitle, color = scheme.outline)
            if (installed) {
                Box(Modifier.height(Log.VersionHeight).background(scheme.secondaryContainer, HideoutShapes.Full).padding(horizontal = Log.VersionHorizontal), contentAlignment = Alignment.Center) {
                    Text(stringResource(Res.string.changelog_you_have_this), style = type.captionCompact.copy(fontWeight = HideoutWeights.Title), color = scheme.secondary)
                }
            }
        }
        Text(update.title, style = type.titlePanel, color = scheme.onSurface)
        update.lines.forEach { line ->
            Row(horizontalArrangement = Arrangement.spacedBy(Log.LineGap)) {
                Text(
                    line.kind?.let { stringResource(it.label).uppercase() }.orEmpty(),
                    Modifier.width(Log.TagWidth).padding(top = Log.TagTop),
                    style = type.badge.copy(letterSpacing = TagTracking),
                    color = line.kind?.color ?: Color.Unspecified,
                    softWrap = false,
                    overflow = TextOverflow.Visible,
                )
                Text(line.text, Modifier.weight(1f), style = type.notice, color = scheme.onSurfaceVariant)
            }
        }
    }
}

private val ChangeKindTag.label get() = when (this) {
    ChangeKindTag.Added -> Res.string.changelog_added
    ChangeKindTag.Fixed -> Res.string.changelog_fixed
    ChangeKindTag.Improved -> Res.string.changelog_improved
}

private val ChangeKindTag.color: Color
    @Composable get() = when (this) {
        ChangeKindTag.Added -> HideoutTheme.colors.changeAdded
        ChangeKindTag.Fixed -> HideoutTheme.colors.changeFixed
        ChangeKindTag.Improved -> HideoutTheme.colors.changeImproved
    }

private val TagTracking = 0.04.em
