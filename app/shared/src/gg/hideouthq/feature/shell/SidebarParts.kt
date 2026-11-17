package gg.hideouthq.feature.shell

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import gg.hideouthq.core.designsystem.component.RollingText
import gg.hideouthq.core.designsystem.icon.HideoutIcons
import gg.hideouthq.core.designsystem.theme.HideoutDimens
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import gg.hideouthq.core.designsystem.theme.HideoutWeights
import gg.hideouthq.resources.Res
import gg.hideouthq.resources.busy_gamebanana
import gg.hideouthq.resources.download_percent
import gg.hideouthq.resources.hideout_logo
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun BrandRow(name: String, modifier: Modifier = Modifier, compact: Boolean = false) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(HideoutDimens.Sidebar.BrandHeight)
            .padding(horizontal = if (compact) 0.dp else HideoutDimens.Sidebar.BrandPadding),
        horizontalArrangement = if (compact) Arrangement.Center else Arrangement.spacedBy(HideoutDimens.Sidebar.BrandGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painter = painterResource(Res.drawable.hideout_logo),
            contentDescription = if (compact) name else null,
            modifier = Modifier.size(HideoutDimens.Sidebar.Logo),
        )
        if (!compact) Text(name, style = HideoutTheme.typography.titleBrand, color = scheme.onSurface, maxLines = 1, softWrap = false, modifier = Modifier.semantics { heading() })
    }
}

@Composable
internal fun TransferTrailing(percent: Int, count: Int, held: Boolean = false, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val unit = if (count == 1) "mod" else "mods"
    val description = if (held) stringResource(Res.string.busy_gamebanana) else "Downloading $count $unit: $percent%"
    Row(
        modifier = modifier.semantics(mergeDescendants = true) { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(HideoutDimens.Sidebar.TransferGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (held) {
            Icon(HideoutIcons.Clock, null, Modifier.size(HideoutDimens.Sidebar.TransferRing), tint = scheme.secondary)
        } else {
            Canvas(Modifier.size(HideoutDimens.Sidebar.TransferRing)) {
                val stroke = Stroke(HideoutDimens.Sidebar.TransferRingStroke.toPx(), cap = StrokeCap.Round)
                drawArc(scheme.surfaceContainerHighest, startAngle = -90f, sweepAngle = 360f, useCenter = false, style = stroke)
                drawArc(scheme.secondary, startAngle = -90f, sweepAngle = 360f * (percent / 100f).coerceIn(0f, 1f), useCenter = false, style = stroke)
            }
            RollingText(
                text = stringResource(Res.string.download_percent, percent),
                style = HideoutTheme.typography.caption.copy(fontWeight = HideoutWeights.Medium),
                color = scheme.secondary,
            )
        }
    }
}
