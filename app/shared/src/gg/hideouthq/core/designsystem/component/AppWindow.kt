package gg.hideouthq.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import gg.hideouthq.core.designsystem.theme.HideoutDimens
import gg.hideouthq.core.ui.FieldFocusTracker
import gg.hideouthq.core.ui.FocusVisibility
import gg.hideouthq.core.ui.LocalFieldFocusTracker
import gg.hideouthq.core.ui.clearFieldFocusOnOutsidePress
import gg.hideouthq.core.ui.LocalFocusVisibility
import gg.hideouthq.core.ui.LocalWindowControlsSpot
import gg.hideouthq.core.ui.WindowControlsSpot
import gg.hideouthq.core.ui.trackFocusVisibility

/**
 * The window: black behind everything and 8dp of padding around the panes. Nothing outside the
 * panes scrolls. It also decides when focus rings show (keyboard use only) and takes focus away
 * from a text field when you click elsewhere.
 */
@Composable
fun AppWindow(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val focusVisibility = remember { FocusVisibility() }
    val fieldFocus = remember { FieldFocusTracker() }
    val controls = LocalWindowControlsSpot.current ?: remember { WindowControlsSpot() }
    val focusManager = LocalFocusManager.current
    CompositionLocalProvider(
        LocalFocusVisibility provides focusVisibility,
        LocalFieldFocusTracker provides fieldFocus,
        LocalWindowControlsSpot provides controls,
    ) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .trackFocusVisibility(focusVisibility)
                .clearFieldFocusOnOutsidePress(fieldFocus, focusManager)
                .padding(HideoutDimens.Window.Padding),
            content = content,
        )
    }
}
