package gg.hideouthq.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp

@Composable
fun windowWidth(): Dp = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() }

@Composable
fun windowHeight(): Dp = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
