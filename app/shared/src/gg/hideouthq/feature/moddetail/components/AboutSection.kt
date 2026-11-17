package gg.hideouthq.feature.moddetail.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import gg.hideouthq.core.designsystem.theme.HideoutDimens
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import gg.hideouthq.core.model.ModDetail
import gg.hideouthq.preview.BundleManifest
import gg.hideouthq.resources.Res
import gg.hideouthq.resources.detail_about
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun About(detail: ModDetail, preview: BundleManifest?, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = HideoutDimens.Detail.ColumnsHorizontalPadding),
        verticalArrangement = Arrangement.spacedBy(HideoutDimens.Detail.AboutGap),
    ) {
        Text(
            stringResource(Res.string.detail_about),
            style = HideoutTheme.typography.titleSection,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { heading() },
        )
        Text(detail.about, style = HideoutTheme.typography.bodyProse, color = MaterialTheme.colorScheme.onSurfaceVariant)
        preview?.let { PreviewCredit(it) }
    }
}

@Composable
private fun PreviewCredit(manifest: BundleManifest) {
    SelectionContainer { Text(manifest.attribution, style = HideoutTheme.typography.caption, color = MaterialTheme.colorScheme.outline) }
}
