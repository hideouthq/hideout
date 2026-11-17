package gg.hideouthq.feature.moddetail.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import gg.hideouthq.core.designsystem.component.FactItem
import gg.hideouthq.core.designsystem.theme.HideoutDimens
import gg.hideouthq.core.ui.ModLabels
import gg.hideouthq.feature.moddetail.ModHeader
import gg.hideouthq.resources.Res
import gg.hideouthq.resources.fact_downloads
import gg.hideouthq.resources.fact_last_modified
import gg.hideouthq.resources.fact_likes
import gg.hideouthq.resources.fact_size
import gg.hideouthq.resources.fact_version
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun DetailsBlock(header: ModHeader, version: String?, versionShown: Boolean, sizeBytes: Long?, modifier: Modifier = Modifier) {
    val facts = listOfNotNull(
        header.downloads?.let { stringResource(Res.string.fact_downloads) to ModLabels.count(it) },
        stringResource(Res.string.fact_likes) to ModLabels.count(header.likes),
        stringResource(Res.string.fact_last_modified) to ModLabels.date(header.updated),
        sizeBytes?.let { stringResource(Res.string.fact_size) to ModLabels.size(it) },
        version?.takeIf { it.isNotBlank() && !versionShown }?.let { stringResource(Res.string.fact_version) to it },
    )
    Column(modifier, verticalArrangement = Arrangement.spacedBy(HideoutDimens.Fact.GridRowGap)) {
        facts.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(HideoutDimens.Fact.GridColumnGap)) {
                pair.forEach { (label, value) -> FactItem(null, value, Modifier.weight(1f), label = label) }
                if (pair.size == 1) Column(Modifier.weight(1f)) {}
            }
        }
    }
}
