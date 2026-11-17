package gg.hideouthq.core.designsystem.component

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import gg.hideouthq.core.designsystem.theme.HideoutDimens
import gg.hideouthq.core.designsystem.theme.HideoutMotion
import gg.hideouthq.core.designsystem.theme.HideoutShapes
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

enum class EmptyStateTone { Neutral, Positive }

@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    tone: EmptyStateTone = EmptyStateTone.Neutral,
    action: (@Composable () -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val motion = HideoutTheme.motion
    val tileScale = remember { Animatable(if (motion.reduced) 1f else HideoutMotion.EmptyStateEnterScale) }
    val textAlpha = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        coroutineScope {
            launch { tileScale.animateTo(1f, motion.spatialFast()) }
            launch { textAlpha.animateTo(1f, motion.enterFade()) }
        }
    }
    val (tileColor, iconColor) = when (tone) {
        EmptyStateTone.Neutral -> scheme.surfaceContainerHigh to scheme.onSurface
        EmptyStateTone.Positive -> scheme.primaryContainer to scheme.primary
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = HideoutDimens.EmptyState.HorizontalPadding, vertical = HideoutDimens.EmptyState.VerticalPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(HideoutDimens.EmptyState.Gap),
    ) {
        Box(
            modifier = Modifier
                .graphicsLayer {
                    scaleX = tileScale.value
                    scaleY = tileScale.value
                }
                .size(HideoutDimens.EmptyState.IconTile)
                .background(tileColor, HideoutShapes.EmptyStateTile),
            contentAlignment = Alignment.Center,
        ) {
            HideoutIcon(icon, contentDescription = null, tint = iconColor)
        }
        Column(
            modifier = Modifier.graphicsLayer { alpha = textAlpha.value },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(HideoutDimens.EmptyState.Gap),
        ) {
            Text(
                text = title,
                style = HideoutTheme.typography.titleEmpty,
                color = scheme.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = HideoutDimens.EmptyState.TitleTopPadding),
            )
            Text(text = message, style = HideoutTheme.typography.captionSmall, color = scheme.outline, textAlign = TextAlign.Center)
            if (action != null) {
                Box(Modifier.padding(top = HideoutDimens.EmptyState.ActionTopPadding)) { action() }
            }
        }
    }
}
