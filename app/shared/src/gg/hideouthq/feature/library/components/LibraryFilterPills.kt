package gg.hideouthq.feature.library.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import gg.hideouthq.core.designsystem.component.Pill
import gg.hideouthq.core.designsystem.component.PillRow
import gg.hideouthq.core.ui.ModLabels
import gg.hideouthq.feature.library.FilterPill
import gg.hideouthq.feature.library.LibraryFilter
import gg.hideouthq.resources.Res
import gg.hideouthq.resources.library_filter_all
import gg.hideouthq.resources.library_filter_review
import gg.hideouthq.resources.library_filter_updates
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun LibraryFilterPills(filters: List<FilterPill>, selected: LibraryFilter, onSelect: (LibraryFilter) -> Unit, modifier: Modifier = Modifier) {
    PillRow(selectedKey = selected, modifier = modifier) {
        filters.forEach { pill ->
            val label = when (val filter = pill.filter) {
                LibraryFilter.All -> stringResource(Res.string.library_filter_all)
                is LibraryFilter.Category -> ModLabels.plural(filter.category)
                LibraryFilter.Updates -> stringResource(Res.string.library_filter_updates)
                LibraryFilter.Review -> stringResource(Res.string.library_filter_review)
            }
            Pill(label, pill.filter == selected, { onSelect(pill.filter) }, count = pill.count, indicator = indicator, key = pill.filter)
        }
    }
}
