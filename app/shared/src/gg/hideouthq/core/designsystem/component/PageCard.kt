package gg.hideouthq.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import gg.hideouthq.core.designsystem.theme.cssLineHeight

@Composable
fun PageCard(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(start = 8.dp, top = 6.dp, end = 8.dp, bottom = 8.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.clip(PageCardShape).background(MaterialTheme.colorScheme.surfaceContainer).padding(contentPadding), content = content)
}

@Composable
fun CardHeader(title: String, note: String, trailing: @Composable RowScope.() -> Unit = {}) {
    val scheme = MaterialTheme.colorScheme
    val type = HideoutTheme.typography
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(start = 12.dp, end = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = type.titleBrand.copy(lineHeight = cssLineHeight(17.sp, 1.35f), letterSpacing = (-0.005).em), color = scheme.onSurface, maxLines = 1)
        Text(note, Modifier.weight(1f), style = type.subtitle, color = scheme.outline, maxLines = 1, overflow = TextOverflow.Ellipsis)
        trailing()
    }
}

private val PageCardShape = RoundedCornerShape(24.dp)
