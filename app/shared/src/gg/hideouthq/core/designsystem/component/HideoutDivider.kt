package gg.hideouthq.core.designsystem.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Modifier
import gg.hideouthq.core.designsystem.theme.HideoutDimens
import gg.hideouthq.core.designsystem.theme.HideoutTheme

@Composable
fun HideoutDivider(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(HideoutDimens.Hairline).background(MaterialTheme.colorScheme.outlineVariant))
}

@Composable
fun ScrollHairline(visible: Boolean, modifier: Modifier = Modifier) {
    val alpha by animateFloatAsState(if (visible) 1f else 0f, HideoutTheme.motion.effectsDefault(), label = "scrollHairline")
    HideoutDivider(modifier.graphicsLayer { this.alpha = alpha })
}
