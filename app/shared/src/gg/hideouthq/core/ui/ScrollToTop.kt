package gg.hideouthq.core.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember

/** Counts requests to scroll the current screen to the top (a sidebar click on its own section). */
val LocalScrollToTopRequests = compositionLocalOf { 0 }

@Composable
internal fun OnScrollToTop(action: suspend () -> Unit) {
    val requests = LocalScrollToTopRequests.current
    val seen = remember { intArrayOf(requests) }
    LaunchedEffect(requests) {
        if (requests != seen[0]) {
            seen[0] = requests
            action()
        }
    }
}

@Composable
fun ScrollToTopEffect(state: ScrollState) = OnScrollToTop { state.animateScrollTo(0) }

@Composable
fun ScrollToTopEffect(state: LazyGridState) = OnScrollToTop { state.animateScrollToItem(0) }
