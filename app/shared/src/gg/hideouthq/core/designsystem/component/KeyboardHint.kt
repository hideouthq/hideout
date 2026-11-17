package gg.hideouthq.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import gg.hideouthq.core.designsystem.theme.HideoutDimens
import gg.hideouthq.core.designsystem.theme.HideoutShapes
import gg.hideouthq.core.designsystem.theme.HideoutTheme

@Composable
fun KeyboardHint(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, HideoutShapes.Full)
            .padding(horizontal = HideoutDimens.Search.HintHorizontalPadding, vertical = HideoutDimens.Search.HintVerticalPadding),
        contentAlignment = Alignment.Center,
    ) {
        RollingText(
            text = text,
            style = HideoutTheme.typography.monoHint,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            contentAlignment = Alignment.Center,
        )
    }
}
