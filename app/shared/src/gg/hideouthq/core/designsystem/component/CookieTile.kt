package gg.hideouthq.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import gg.hideouthq.core.designsystem.shape.Cookie

@Composable
fun CookieTile(fill: Color, size: Dp = 84.dp, content: @Composable () -> Unit) {
    Box(Modifier.size(size).clip(Cookie.Twelve.shape).background(fill), contentAlignment = Alignment.Center) { content() }
}
