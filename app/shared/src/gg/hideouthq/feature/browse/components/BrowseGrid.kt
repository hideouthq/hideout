package gg.hideouthq.feature.browse.components

import gg.hideouthq.core.designsystem.component.gaps
import gg.hideouthq.core.designsystem.component.LocalModCardStyle
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import gg.hideouthq.core.designsystem.component.ButtonVariant
import gg.hideouthq.core.designsystem.component.EmptyState
import gg.hideouthq.core.designsystem.component.HideoutButton
import gg.hideouthq.core.designsystem.component.OverlayScrollbar
import gg.hideouthq.core.designsystem.component.SkeletonBlock
import gg.hideouthq.core.designsystem.component.rememberScrollbarAdapter
import gg.hideouthq.core.designsystem.component.rememberShimmer
import gg.hideouthq.core.designsystem.icon.HideoutIcons
import gg.hideouthq.core.designsystem.theme.HideoutDimens
import gg.hideouthq.core.designsystem.theme.HideoutMotion
import gg.hideouthq.core.designsystem.theme.HideoutShapes
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import gg.hideouthq.core.ui.catalog.CatalogItem
import gg.hideouthq.core.ui.gridColumns
import gg.hideouthq.core.ui.transition.staggeredEnter
import gg.hideouthq.resources.Res
import gg.hideouthq.resources.browse_clear_filters
import gg.hideouthq.resources.browse_empty_message
import gg.hideouthq.resources.browse_clear_search
import gg.hideouthq.resources.browse_empty_search_message
import gg.hideouthq.resources.browse_empty_search_title
import gg.hideouthq.resources.browse_empty_title
import org.jetbrains.compose.resources.stringResource

private const val HeaderKey = "header"
private const val EmptyKey = "empty"
private const val SkeletonRows = 4

@Composable
internal fun BrowseGrid(
    results: List<CatalogItem>,
    loading: Boolean,
    state: LazyGridState,
    onClearFilters: () -> Unit,
    search: String?,
    header: @Composable () -> Unit,
    card: @Composable (item: CatalogItem, modifier: Modifier) -> Unit,
    modifier: Modifier = Modifier,
) {
    val motion = HideoutTheme.motion
    val sweep = rememberShimmer(active = loading)
    val known = remember { mutableSetOf<String>() }
    val firstResults = remember { booleanArrayOf(true) }
    SideEffect {
        if (!loading) {
            known.clear()
            results.mapTo(known) { it.id }
            firstResults[0] = false
        }
    }
    BoxWithConstraints(modifier) {
        val gap = HideoutDimens.Card.Gap
        val gaps = LocalModCardStyle.current.gaps
        val density = LocalDensity.current
        val inner = constraints.maxWidth - with(density) { HideoutDimens.MainPane.ContentPadding.roundToPx() } * 2
        val columns = density.gridColumns(inner, HideoutDimens.Card.MinColumnBrowse, gaps.column)
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            state = state,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = HideoutDimens.MainPane.ContentPadding,
                end = HideoutDimens.MainPane.ContentPadding,
                bottom = HideoutDimens.MainPane.ContentPadding,
            ),
            horizontalArrangement = Arrangement.spacedBy(gaps.column),
            verticalArrangement = Arrangement.spacedBy(gaps.row),
        ) {
            item(key = HeaderKey, span = { GridItemSpan(maxLineSpan) }) { header() }
            itemsIndexed(results, key = { _, item -> item.id }) { index, item ->
                val isNew = remember(item.id) { !firstResults[0] && item.id !in known }
                val scale = remember(item.id) { Animatable(if (isNew && !motion.reduced) HideoutMotion.EnterScaleCard else 1f) }
                LaunchedEffect(item.id) { scale.animateTo(1f, motion.spatialDefault()) }
                card(
                    item,
                    Modifier
                        .animateItem(fadeInSpec = motion.effectsDefault(), placementSpec = motion.spatialDefault(), fadeOutSpec = motion.exitFade())
                        .staggeredEnter(1 + index / columns)
                        .graphicsLayer {
                            scaleX = scale.value
                            scaleY = scale.value
                        },
                )
            }
            if (loading) items(columns * SkeletonRows) { SkeletonBlock(sweep, null, HideoutDimens.Card.HeightBrowse, HideoutShapes.Card, Modifier.staggeredEnter(1 + it / columns)) }
            item(key = EmptyKey, span = { GridItemSpan(maxLineSpan) }) {
                AnimatedVisibility(
                    visible = !loading && results.isEmpty(),
                    enter = expandVertically(motion.spatialDefault(), expandFrom = Alignment.Top) + fadeIn(motion.effectsDefault()),
                    exit = shrinkVertically(motion.spatialDefault()) + fadeOut(motion.exitFade()),
                ) {
                    EmptyState(
                        modifier = Modifier.padding(top = gap),
                        icon = HideoutIcons.Search,
                        title = search?.let { stringResource(Res.string.browse_empty_search_title, it) } ?: stringResource(Res.string.browse_empty_title),
                        message = stringResource(if (search != null) Res.string.browse_empty_search_message else Res.string.browse_empty_message),
                        action = { HideoutButton(stringResource(if (search != null) Res.string.browse_clear_search else Res.string.browse_clear_filters), onClearFilters, variant = ButtonVariant.Tonal) },
                    )
                }
            }
        }
        OverlayScrollbar(rememberScrollbarAdapter(state) { columns }, Modifier.align(Alignment.TopEnd))
    }
}
