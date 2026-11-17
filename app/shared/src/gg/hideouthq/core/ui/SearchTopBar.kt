package gg.hideouthq.core.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import gg.hideouthq.core.designsystem.component.SearchField
import gg.hideouthq.resources.Res
import gg.hideouthq.resources.search_hint_focused
import gg.hideouthq.resources.search_hint_shortcut
import org.jetbrains.compose.resources.stringResource

@Composable
fun SearchTopBar(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    focusOnArrival: Boolean = false,
    actions: @Composable () -> Unit = {},
) {
    val focusRequester = rememberSearchFocusRequester()
    if (focusOnArrival) {
        LaunchedEffect(focusRequester) {
            withFrameNanos { }
            runCatching { focusRequester.requestFocus() }
        }
    }
    PaneTopBar(
        modifier = modifier,
        center = {
            SearchField(
                query = query,
                onQueryChange = onQueryChange,
                placeholder = placeholder,
                modifier = Modifier.fillMaxWidth(),
                shortcutHint = stringResource(Res.string.search_hint_shortcut),
                focusedHint = stringResource(Res.string.search_hint_focused),
                focusRequester = focusRequester,
            )
        },
        end = actions,
    )
}
