package gg.hideouthq.feature.shell

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import gg.hideouthq.core.designsystem.component.HideoutIcon
import gg.hideouthq.core.designsystem.component.TopBarChip
import gg.hideouthq.core.designsystem.icon.HideoutIcons
import gg.hideouthq.core.designsystem.theme.HideoutDimens
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import gg.hideouthq.core.designsystem.theme.HideoutWeights
import gg.hideouthq.core.library.GameInfoRewrite
import gg.hideouthq.core.library.RewriteOutcome
import gg.hideouthq.core.library.RewrittenBy
import gg.hideouthq.feature.imports.managerName
import gg.hideouthq.resources.Res
import gg.hideouthq.resources.helper_peek
import gg.hideouthq.resources.mod_lines_after_game
import gg.hideouthq.resources.mod_lines_back
import gg.hideouthq.resources.mod_lines_by_program
import gg.hideouthq.resources.mod_lines_by_unknown
import gg.hideouthq.resources.mod_lines_by_update
import gg.hideouthq.resources.mod_lines_put_back
import gg.hideouthq.resources.mod_lines_waiting
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/** Stands out only while the mods are off, and goes once its panel has been opened and closed. */
@Composable
internal fun RewriteChip(rewrite: GameInfoRewrite, expanded: Boolean, onToggle: () -> Unit, onRead: () -> Unit, modifier: Modifier = Modifier) {
    var wasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(expanded) {
        if (expanded) wasOpen = true
        else if (wasOpen) {
            wasOpen = false
            onRead()
        }
    }
    val scheme = MaterialTheme.colorScheme
    val caution = HideoutTheme.colors.caution
    val dimens = HideoutDimens.TopBarChip
    val off = rewrite.outcome == RewriteOutcome.WaitingForGame
    val label = stringResource(if (off) Res.string.mod_lines_waiting else Res.string.mod_lines_back)
    val content = if (off) caution else scheme.onSurface
    val container = if (off) HideoutTheme.colors.cautionContainer else scheme.surfaceContainer
    TopBarChip(
        label = label,
        expanded = expanded,
        onToggle = onToggle,
        panelWidth = dimens.UpdatePanelWidth,
        modifier = modifier.layoutId(CollapsibleChip),
        container = container,
        hoverContainer = if (off) caution.copy(alpha = OffHoverMix).compositeOver(container) else scheme.surfaceContainerHigh,
        outlined = !off,
        panel = { RewritePanel(rewrite, label) },
    ) {
        LabelWhenItFits(
            icon = {
                Box(Modifier.size(dimens.IconSlot), contentAlignment = Alignment.Center) {
                    HideoutIcon(if (off) HideoutIcons.Clock else HideoutIcons.Check, null, size = dimens.Icon, tint = content)
                }
            },
            label = { Text(label, style = ChipText, color = content, maxLines = 1, softWrap = false) },
        )
    }
}

/** The icon and its label, or the icon alone in a round chip when the width it is given can't hold both. */
@Composable
private fun LabelWhenItFits(icon: @Composable () -> Unit, label: @Composable () -> Unit) {
    val dimens = HideoutDimens.TopBarChip
    Layout(listOf(icon, label)) { (icons, labels), constraints ->
        val shown = icons.single().measure(Constraints())
        val text = labels.single().measure(Constraints())
        val start = dimens.UpdateStart.roundToPx()
        val full = start + shown.width + dimens.UpdateGap.roundToPx() + text.width + dimens.UpdateEnd.roundToPx()
        val fits = full <= constraints.maxWidth
        val width = if (fits) full else dimens.Height.roundToPx().coerceAtMost(constraints.maxWidth)
        val height = maxOf(shown.height, text.height)
        layout(width, height) {
            if (fits) {
                shown.place(start, (height - shown.height) / 2)
                text.place(full - dimens.UpdateEnd.roundToPx() - text.width, (height - text.height) / 2)
            } else {
                shown.place((width - shown.width) / 2, (height - shown.height) / 2)
            }
        }
    }
}

@Composable
private fun RewritePanel(rewrite: GameInfoRewrite, title: String) {
    val scheme = MaterialTheme.colorScheme
    val dimens = HideoutDimens.TopBarChip
    val off = rewrite.outcome == RewriteOutcome.WaitingForGame
    val cause = when (val by = rewrite.by) {
        RewrittenBy.GameUpdate -> stringResource(Res.string.mod_lines_by_update)
        is RewrittenBy.AnotherProgram -> by.manager?.let { stringResource(Res.string.mod_lines_by_program, managerName(it)) }
            ?: stringResource(Res.string.mod_lines_by_unknown)
    }
    val done = stringResource(if (off) Res.string.mod_lines_after_game else Res.string.mod_lines_put_back)
    val (tile, tint) = if (off) HideoutTheme.colors.cautionContainer to HideoutTheme.colors.caution else scheme.surfaceContainerHigh to scheme.onSurface
    Row(horizontalArrangement = Arrangement.spacedBy(dimens.HeaderGap), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(dimens.UpdateHeaderTile).background(tile, RoundedCornerShape(dimens.UpdateHeaderTileRadius)), contentAlignment = Alignment.Center) {
            HideoutIcon(if (off) HideoutIcons.Clock else HideoutIcons.Check, null, size = dimens.UpdateHeaderIcon, tint = tint)
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = HideoutTheme.typography.titleUpdateCard.copy(fontSize = 15.sp, lineHeight = 19.sp), color = scheme.onSurface)
            Text("$cause $done", Modifier.padding(top = 2.dp), style = HideoutTheme.typography.caption.copy(fontSize = 12.5.sp, lineHeight = 17.sp), color = scheme.outline)
        }
        Image(painterResource(Res.drawable.helper_peek), null, Modifier.size(width = 64.dp, height = 54.dp))
    }
}

private val ChipText @Composable get() = HideoutTheme.typography.labelStatus.copy(fontSize = 13.sp, lineHeight = 16.sp, fontWeight = HideoutWeights.Title)
private const val OffHoverMix = 0.16f
