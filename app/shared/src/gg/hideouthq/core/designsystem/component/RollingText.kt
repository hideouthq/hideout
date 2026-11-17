package gg.hideouthq.core.designsystem.component

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import gg.hideouthq.core.designsystem.theme.HideoutMotion
import gg.hideouthq.core.designsystem.theme.HideoutTheme

@Composable
fun RollingText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = LocalContentColor.current,
    contentAlignment: Alignment = Alignment.CenterStart,
    overflow: TextOverflow = TextOverflow.Clip,
) {
    val motion = HideoutTheme.motion
    val travelPx = with(LocalDensity.current) { HideoutMotion.RollOffset.roundToPx() }
    // AnimatedContent aligns against its largest child, not its own size, so a Box does the alignment.
    Box(modifier, contentAlignment = contentAlignment) {
        AnimatedContent(
            targetState = text,
            contentAlignment = contentAlignment,
            transitionSpec = {
                val distance = if (motion.reduced) 0 else travelPx
                (slideInVertically(motion.spatialDefault()) { distance } + fadeIn(motion.effectsDefault()))
                    .togetherWith(slideOutVertically(motion.spatialDefault()) { -distance } + fadeOut(motion.effectsDefault()))
                    .using(SizeTransform(clip = false))
            },
            label = "rollingText",
        ) { value ->
            Text(text = value, style = style, color = color, maxLines = 1, overflow = overflow)
        }
    }
}
