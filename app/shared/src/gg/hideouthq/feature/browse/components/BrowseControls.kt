package gg.hideouthq.feature.browse.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import gg.hideouthq.core.designsystem.component.Pill
import gg.hideouthq.core.designsystem.component.PillRow
import gg.hideouthq.core.designsystem.component.SegmentedControl
import gg.hideouthq.core.designsystem.theme.HideoutDimens
import gg.hideouthq.core.model.ModCategory
import gg.hideouthq.core.ui.ModLabels
import gg.hideouthq.feature.browse.BrowseSort
import gg.hideouthq.resources.Res
import gg.hideouthq.resources.browse_all
import gg.hideouthq.resources.browse_sort_downloads
import gg.hideouthq.resources.browse_sort_newest
import gg.hideouthq.resources.browse_sort_trending
import org.jetbrains.compose.resources.stringResource

private const val AllCategories = "all"

@Composable
internal fun BrowseControls(
    category: ModCategory?,
    sort: BrowseSort,
    onCategory: (ModCategory?) -> Unit,
    onSort: (BrowseSort) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(HideoutDimens.Pill.RowGap),
        verticalAlignment = Alignment.Top,
    ) {
        PillRow(selectedKey = category ?: AllCategories, modifier = Modifier.weight(1f)) {
            Pill(stringResource(Res.string.browse_all), category == null, { onCategory(null) }, indicator = indicator, key = AllCategories)
            ModCategory.entries.forEach { entry ->
                Pill(ModLabels.plural(entry), category == entry, { onCategory(entry) }, indicator = indicator, key = entry)
            }
        }
        val labels = mapOf(
            BrowseSort.Trending to stringResource(Res.string.browse_sort_trending),
            BrowseSort.Downloads to stringResource(Res.string.browse_sort_downloads),
            BrowseSort.Newest to stringResource(Res.string.browse_sort_newest),
        )
        SegmentedControl(BrowseSort.entries, sort, onSort, { labels.getValue(it) })
    }
}
