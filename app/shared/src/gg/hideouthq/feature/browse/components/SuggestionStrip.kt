package gg.hideouthq.feature.browse.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import gg.hideouthq.core.designsystem.component.ChipTone
import gg.hideouthq.core.designsystem.component.StatusChip
import gg.hideouthq.core.designsystem.icon.HideoutIcons
import gg.hideouthq.core.designsystem.theme.HideoutDimens
import gg.hideouthq.core.designsystem.theme.HideoutShapes
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import gg.hideouthq.core.ui.ModLabels
import gg.hideouthq.core.ui.transition.chipCascade
import gg.hideouthq.feature.browse.BrowseSuggestion
import gg.hideouthq.resources.Res
import gg.hideouthq.resources.browse_filter_by
import gg.hideouthq.resources.browse_suggest_category
import gg.hideouthq.resources.browse_suggest_hero
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun SuggestionStrip(
    suggestions: List<BrowseSuggestion>,
    onApply: (BrowseSuggestion) -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer, HideoutShapes.Card)
            .padding(horizontal = HideoutDimens.Browse.StripHorizontalPadding, vertical = HideoutDimens.Browse.StripVerticalPadding),
        horizontalArrangement = Arrangement.spacedBy(HideoutDimens.Pill.RowGap),
        verticalArrangement = Arrangement.spacedBy(HideoutDimens.Pill.RowGap),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(Res.string.browse_filter_by),
            style = HideoutTheme.typography.captionSmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(end = HideoutDimens.Browse.LabelEndPadding),
        )
        suggestions.forEachIndexed { index, suggestion ->
            val (label, icon) = when (suggestion) {
                is BrowseSuggestion.Hero -> stringResource(Res.string.browse_suggest_hero, suggestion.name) to HideoutIcons.Person
                is BrowseSuggestion.Category -> stringResource(Res.string.browse_suggest_category, ModLabels.plural(suggestion.category)) to HideoutIcons.Grid
            }
            StatusChip(
                text = label,
                tone = ChipTone.Positive,
                leadingIcon = icon,
                onClick = { onApply(suggestion) },
                modifier = Modifier.chipCascade(index),
            )
        }
    }
}
