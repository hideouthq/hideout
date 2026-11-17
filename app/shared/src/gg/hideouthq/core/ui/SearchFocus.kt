package gg.hideouthq.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.focus.FocusRequester

@Stable
class SearchFocus {
    private val requesters = mutableListOf<FocusRequester>()

    fun request(): Boolean {
        val requester = requesters.lastOrNull() ?: return false
        return runCatching { requester.requestFocus() }.isSuccess
    }

    internal fun register(requester: FocusRequester) {
        requesters.add(requester)
    }

    internal fun unregister(requester: FocusRequester) {
        requesters.remove(requester)
    }
}

val LocalSearchFocus = staticCompositionLocalOf { SearchFocus() }

@Composable
fun rememberSearchFocusRequester(): FocusRequester {
    val requester = remember { FocusRequester() }
    val searchFocus = LocalSearchFocus.current
    DisposableEffect(requester, searchFocus) {
        searchFocus.register(requester)
        onDispose { searchFocus.unregister(requester) }
    }
    return requester
}
