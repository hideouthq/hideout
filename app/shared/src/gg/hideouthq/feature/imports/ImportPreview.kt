package gg.hideouthq.feature.imports

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import gg.hideouthq.core.designsystem.component.ArtStage
import gg.hideouthq.core.designsystem.component.ChipSize
import gg.hideouthq.core.designsystem.component.ChipTone
import gg.hideouthq.core.designsystem.component.CoverArt
import gg.hideouthq.core.designsystem.component.FloatingCard
import gg.hideouthq.core.designsystem.component.ProfileTile
import gg.hideouthq.core.designsystem.component.StatusChip
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import gg.hideouthq.core.designsystem.theme.HideoutWeights
import gg.hideouthq.core.library.Profile
import gg.hideouthq.core.ui.color
import gg.hideouthq.resources.Res
import gg.hideouthq.resources.import_found
import gg.hideouthq.resources.import_left_out
import gg.hideouthq.resources.import_profile_on
import gg.hideouthq.resources.library_filter_off
import gg.hideouthq.resources.library_filter_on
import org.jetbrains.compose.resources.stringResource

@Composable
fun ImportPreviewPanel(state: ImportUi) {
    val preview = state.preview ?: return
    val shown = state.rows.filter { it.item == ImportItem.Mods || it.item == ImportItem.Profiles }
    if (shown.isEmpty()) return
    ArtStage {
        Row(Modifier.height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            shown.forEach { row ->
                PreviewCard(itemName(row.item), row.ticked, Modifier.width(300.dp).fillMaxHeight()) {
                    if (row.item == ImportItem.Profiles) preview.profiles.take(ShownRows).forEach { PreviewProfileRow(it) }
                    else {
                        preview.mods.take(ShownRows).forEach { PreviewModRow(it.name, stringResource(if (it.enabled) Res.string.library_filter_on else Res.string.library_filter_off)) }
                        if (preview.found.isNotEmpty()) {
                            CardTitle(stringResource(Res.string.import_found), Modifier.padding(top = 8.dp))
                            preview.found.take(ShownRows).forEach { PreviewModRow(it.name, it.file) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PreviewCard(title: String, ticked: Boolean, modifier: Modifier, content: @Composable () -> Unit) {
    FloatingCard(modifier.then(if (ticked) Modifier else Modifier.leftOut()), RoundedCornerShape(22.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            CardTitle(title, chip = !ticked)
            content()
        }
    }
}

@Composable
private fun CardTitle(title: String, modifier: Modifier = Modifier, chip: Boolean = false) {
    val scheme = MaterialTheme.colorScheme
    val type = HideoutTheme.typography
    Row(modifier.fillMaxWidth().height(24.dp).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = type.titleRow.copy(fontSize = type.labelMenuItem.fontSize, fontWeight = HideoutWeights.Title), color = scheme.onSurface, modifier = Modifier.weight(1f))
        if (chip) StatusChip(stringResource(Res.string.import_left_out), ChipTone.Neutral, size = ChipSize.Badge)
    }
}

@Composable
private fun PreviewModRow(name: String, caption: String) {
    val scheme = MaterialTheme.colorScheme
    val type = HideoutTheme.typography
    Row(Modifier.fillMaxWidth().height(42.dp).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        CoverArt(name, Modifier.size(44.dp, 30.dp), shape = RoundedCornerShape(8.dp))
        Text(name, style = type.bodyStrong, color = scheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text(caption, style = type.caption.copy(fontWeight = HideoutWeights.Medium), color = scheme.outline, maxLines = 1)
    }
}

@Composable
private fun PreviewProfileRow(profile: Profile) {
    val scheme = MaterialTheme.colorScheme
    val type = HideoutTheme.typography
    Row(Modifier.fillMaxWidth().height(42.dp).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        ProfileTile(profile.name.take(1).uppercase(), active = false, colour = profile.colour.color, size = 22.dp)
        Text(profile.name, style = type.bodyStrong, color = scheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text(stringResource(Res.string.import_profile_on, profile.enabled.size), style = type.caption.copy(fontWeight = HideoutWeights.Medium), color = scheme.outline)
    }
}

private fun Modifier.leftOut(): Modifier = drawWithContent {
    val paint = Paint().apply {
        alpha = LeftOutAlpha
        colorFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })
    }
    drawIntoCanvas { canvas ->
        canvas.saveLayer(Rect(Offset.Zero, size), paint)
        drawContent()
        canvas.restore()
    }
}

private const val LeftOutAlpha = 0.32f
private const val ShownRows = 5
