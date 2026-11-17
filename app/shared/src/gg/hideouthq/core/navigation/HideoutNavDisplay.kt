package gg.hideouthq.core.navigation

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.updateTransition
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.rememberDecoratedNavEntries
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import gg.hideouthq.core.ui.LocalScrollToTopRequests
import gg.hideouthq.core.ui.transition.LocalNavAnimatedVisibilityScope
import gg.hideouthq.core.ui.transition.LocalNavEntryKey
import gg.hideouthq.core.ui.transition.LocalNavTransition
import gg.hideouthq.core.ui.transition.LocalSharedTransitionScope
import gg.hideouthq.core.ui.transition.LocalTravelOutline
import gg.hideouthq.core.ui.transition.LocalTravellingModId
import gg.hideouthq.core.ui.transition.NavTransitionKind
import gg.hideouthq.core.ui.transition.TravelOutline

/**
 * Shows the navigator's current entry. Built on Navigation 3's runtime: each history entry gets
 * its own saved state and ViewModels while retained in either history stack, so Back and Forward
 * restore the same visit. Navigation 3's ready-made NavDisplay animates whole destinations and fixes its
 * own z-order; here the frame stays still and each screen animates its own parts (see
 * [gg.hideouthq.core.ui.PaneScaffold]), inside one [SharedTransitionLayout] so elements can
 * travel between screens.
 */
@Composable
fun HideoutNavDisplay(
    navigator: HideoutNavigator,
    modifier: Modifier = Modifier,
    content: @Composable (HistoryEntry) -> Unit,
) {
    val viewModelStoreOwner = checkNotNull(LocalViewModelStoreOwner.current) { "HideoutNavDisplay needs a ViewModelStoreOwner" }
    val entries = rememberDecoratedNavEntries(
        backStack = navigator.retainedEntries,
        entryDecorators = listOf(
            rememberSaveableStateHolderNavEntryDecorator(),
            rememberViewModelStoreNavEntryDecorator(viewModelStoreOwner),
        ),
        entryProvider = { entry -> NavEntry(entry, contentKey = entry.id) { content(it) } },
    )
    val kind = when {
        navigator.direction == NavDirection.Forward && navigator.current.route is Route.ModDetail -> NavTransitionKind.OpenMod
        navigator.direction == NavDirection.Back && navigator.previous?.route is Route.ModDetail -> NavTransitionKind.CloseMod
        else -> NavTransitionKind.Section
    }
    val travelling = when (kind) {
        NavTransitionKind.OpenMod -> (navigator.current.route as? Route.ModDetail)?.modId
        NavTransitionKind.CloseMod -> (navigator.previous?.route as? Route.ModDetail)?.modId
        NavTransitionKind.Section -> null
    }
    val travelOutline = remember { TravelOutline() }
    val transition = updateTransition(entries.first { it.contentKey == navigator.current.id }, label = "navDisplay")
    LaunchedEffect(transition) {
        snapshotFlow { (transition.currentState.contentKey as Long).takeIf { transition.currentState == transition.targetState && !transition.isRunning } }
            .collect { navigator.settledEntryId = it }
    }
    SharedTransitionLayout(modifier) {
        transition.AnimatedContent(
            contentKey = { it.contentKey },
            transitionSpec = {
                ContentTransform(
                    targetContentEnter = EnterTransition.None,
                    initialContentExit = ExitTransition.None,
                    targetContentZIndex = navigator.navigationCount.toFloat(),
                    sizeTransform = null,
                )
            },
        ) { entry ->
            val id = entry.contentKey as Long
            CompositionLocalProvider(
                LocalNavAnimatedVisibilityScope provides this,
                LocalSharedTransitionScope provides this@SharedTransitionLayout,
                LocalNavEntryKey provides id,
                LocalScrollToTopRequests provides navigator.scrollToTopRequests(id),
                LocalNavTransition provides kind,
                LocalTravellingModId provides travelling,
                LocalTravelOutline provides travelOutline,
            ) {
                entry.Content()
            }
        }
    }
}
