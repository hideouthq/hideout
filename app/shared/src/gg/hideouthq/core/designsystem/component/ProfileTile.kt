package gg.hideouthq.core.designsystem.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.TextStyle
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import gg.hideouthq.core.designsystem.theme.HideoutDimens
import gg.hideouthq.core.designsystem.theme.HideoutTheme

@Composable
fun ProfileTile(
    letter: String,
    active: Boolean,
    modifier: Modifier = Modifier,
    colour: Color = MaterialTheme.colorScheme.secondary,
    size: Dp = HideoutDimens.NavItem.ProfileTile,
    textStyle: TextStyle = HideoutTheme.typography.badge,
) {
    val spec = HideoutTheme.motion.effectsDefault<Color>()
    val background by animateColorAsState(if (active) colour else Color.Transparent, spec, "profileTile")
    val content by animateColorAsState(if (active) Color(0xFF1D1B1D) else colour, spec, "profileTileText")
    val shape = RoundedCornerShape(size * 0.35f)
    Box(
        modifier = modifier.size(size).background(background, shape)
            .then(if (active) Modifier else Modifier.border(1.5.dp, colour, shape)),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = letter, style = textStyle, color = content, maxLines = 1)
    }
}
