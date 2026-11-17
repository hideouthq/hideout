package gg.hideouthq.core.designsystem.icon

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import gg.hideouthq.resources.Res
import gg.hideouthq.resources.hideout_app_icon
import gg.hideouthq.resources.hideout_app_icon_small
import org.jetbrains.compose.resources.painterResource
import java.awt.Image

/**
 * The app icon at each size a desktop shows a window's icon at, for `Window.iconImages`. Under 24 pixels it is the
 * one-card mark, because the two fanned cards blur together there.
 */
@Composable
fun rememberWindowIcons(): List<Image> {
    val full = painterResource(Res.drawable.hideout_app_icon)
    val small = painterResource(Res.drawable.hideout_app_icon_small)
    return remember(full, small) {
        WindowIconSizes.map { pixels ->
            (if (pixels < SmallMarkBelow) small else full).toAwtImage(Density(1f), LayoutDirection.Ltr, Size(pixels.toFloat(), pixels.toFloat()))
        }
    }
}

private val WindowIconSizes = listOf(16, 20, 24, 32, 48, 64, 128, 256)
private const val SmallMarkBelow = 24
