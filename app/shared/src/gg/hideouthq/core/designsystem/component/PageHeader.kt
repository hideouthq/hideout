package gg.hideouthq.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import gg.hideouthq.core.designsystem.theme.HideoutDimens
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import gg.hideouthq.core.designsystem.theme.cssLineHeight
import gg.hideouthq.core.ui.EndAlignedFlow

@Composable
fun PageHeader(title: String, subtitle: String, modifier: Modifier = Modifier, subtitleMaxWidth: Dp = Dp.Unspecified, actions: @Composable RowScope.() -> Unit = {}) {
    val scheme = MaterialTheme.colorScheme
    val type = HideoutTheme.typography
    EndAlignedFlow(
        modifier.padding(
            start = HideoutDimens.PageHeader.StartPadding,
            top = HideoutDimens.PageHeader.Padding,
            end = HideoutDimens.PageHeader.Padding,
            bottom = HideoutDimens.PageHeader.Padding,
        ),
        gap = HideoutDimens.PageHeader.Gap,
        lineGap = HideoutDimens.PageHeader.Gap,
        bottom = true,
    ) {
        Column {
            Text(title, style = type.displayHeroCompact.copy(lineHeight = cssLineHeight(30.sp, 1.15f)), color = scheme.onSurface)
            Text(subtitle, Modifier.padding(top = HideoutDimens.PageHeader.SubtitleGap).widthIn(max = subtitleMaxWidth), style = type.body, color = scheme.onSurfaceVariant)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(HideoutDimens.PageHeader.ActionGap), verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}
