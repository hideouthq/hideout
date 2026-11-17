package gg.hideouthq.feature.browse.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import gg.hideouthq.core.designsystem.component.RemovableChip
import gg.hideouthq.core.designsystem.component.RollingText
import gg.hideouthq.core.designsystem.theme.HideoutDimens
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import gg.hideouthq.core.model.ModCategory
import gg.hideouthq.core.ui.ModLabels
import gg.hideouthq.resources.Res
import gg.hideouthq.resources.browse_count
import gg.hideouthq.resources.browse_for_query
import gg.hideouthq.resources.browse_in_category
import gg.hideouthq.resources.browse_remove_filter
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

internal data class ActiveFilter(val key: String, val label: String, val onRemove: () -> Unit)

@Composable
internal fun ResultLine(
    count: Int,
    category: ModCategory?,
    query: String,
    active: List<ActiveFilter>,
    modifier: Modifier = Modifier,
) {
    var text = pluralStringResource(Res.plurals.browse_count, count, count)
    if (category != null) text = stringResource(Res.string.browse_in_category, text, ModLabels.plural(category))
    if (query.isNotBlank()) text = stringResource(Res.string.browse_for_query, text, query.trim())
    val motion = HideoutTheme.motion
    val shown = remember { mutableStateListOf<ActiveFilter>() }
    active.forEach { filter -> if (shown.none { it.key == filter.key }) shown.add(filter) }
    val activeKeys = active.associateBy { it.key }
    FlowRow(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = HideoutDimens.Chip.Height)
            .padding(start = HideoutDimens.Browse.ResultLineStartPadding),
        horizontalArrangement = Arrangement.spacedBy(HideoutDimens.Pill.RowGap),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        RollingText(
            text = text,
            style = HideoutTheme.typography.captionSmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.height(HideoutDimens.Chip.Height).padding(end = HideoutDimens.Browse.LabelEndPadding),
        )
        shown.forEach { filter ->
            val current = activeKeys[filter.key]
            AnimatedVisibility(
                visible = current != null,
                enter = expandHorizontally(motion.spatialDefault()) + fadeIn(motion.effectsDefault()),
                exit = shrinkHorizontally(motion.spatialDefault()) + fadeOut(motion.exitFade()),
            ) {
                val chip = current ?: filter
                RemovableChip(chip.label, stringResource(Res.string.browse_remove_filter, chip.label), onRemove = chip.onRemove)
            }
        }
    }
}
