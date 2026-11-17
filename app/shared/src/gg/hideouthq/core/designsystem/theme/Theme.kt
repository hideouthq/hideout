package gg.hideouthq.core.designsystem.theme

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import gg.hideouthq.core.model.Accent
import gg.hideouthq.core.ui.LocalReducedMotion
import gg.hideouthq.core.ui.NoIndication

private const val SelectionBackgroundAlpha = 0.35f

/**
 * Hideout's theme: Material 3 Expressive with the design's colour scheme in [accent], type, shapes and motion
 * scheme, plus [HideoutTheme.colors], [HideoutTheme.typography] and [HideoutTheme.motion] for the
 * tokens Material has no role for. Sizes live in [HideoutDimens], radii in [HideoutRadius].
 */
@Composable
fun HideoutTheme(
    reducedMotion: Boolean = LocalReducedMotion.current,
    accent: Accent = Accent.Graphite,
    content: @Composable () -> Unit,
) {
    val materialTypography = remember { HideoutType.toMaterialTypography() }
    val motion = remember(reducedMotion) { hideoutMotion(reducedMotion) }
    val scheme = remember(accent) { hideoutColorScheme(accent) }
    val colors = remember(accent) { hideoutColors(accent) }
    val selectionColors = remember(accent) {
        TextSelectionColors(
            handleColor = scheme.primary,
            backgroundColor = scheme.primary.copy(alpha = SelectionBackgroundAlpha),
        )
    }

    MaterialExpressiveTheme(
        colorScheme = scheme,
        motionScheme = motion.scheme,
        shapes = HideoutMaterialShapes,
        typography = materialTypography,
    ) {
        CompositionLocalProvider(
            LocalHideoutColors provides colors,
            LocalHideoutTypography provides HideoutType,
            LocalHideoutMotion provides motion,
            LocalReducedMotion provides reducedMotion,
            LocalContentColor provides MaterialTheme.colorScheme.onSurface,
            LocalTextSelectionColors provides selectionColors,
            LocalIndication provides NoIndication,
            content = content,
        )
    }
}

object HideoutTheme {
    val colors: HideoutColors
        @Composable @ReadOnlyComposable get() = LocalHideoutColors.current

    val typography: HideoutTypography
        @Composable @ReadOnlyComposable get() = LocalHideoutTypography.current

    val motion: HideoutMotion
        @Composable @ReadOnlyComposable get() = LocalHideoutMotion.current
}
