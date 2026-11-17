package gg.hideouthq.core.designsystem.component

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import gg.hideouthq.core.designsystem.theme.HideoutTheme

@Composable
fun Eyebrow(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = HideoutTheme.typography.eyebrow,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Text(text = text.uppercase(), modifier = modifier, style = style, color = color, maxLines = 1)
}
