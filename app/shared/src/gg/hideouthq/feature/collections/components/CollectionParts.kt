package gg.hideouthq.feature.collections.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import gg.hideouthq.core.designsystem.component.AvatarFill
import gg.hideouthq.core.designsystem.component.AvatarFilterChip
import gg.hideouthq.core.designsystem.component.BackLink
import gg.hideouthq.core.designsystem.component.ButtonSize
import gg.hideouthq.core.designsystem.component.ButtonVariant
import gg.hideouthq.core.designsystem.component.HideoutButton
import gg.hideouthq.core.designsystem.component.ScrollColumn
import gg.hideouthq.core.designsystem.component.SegmentedControl
import gg.hideouthq.core.designsystem.component.ShapedAvatar
import gg.hideouthq.core.designsystem.component.isScrolled
import gg.hideouthq.core.designsystem.icon.HideoutIcons
import gg.hideouthq.core.designsystem.interaction.collectInteractionStates
import gg.hideouthq.core.designsystem.interaction.focusRing
import gg.hideouthq.core.designsystem.interaction.hideoutClickable
import gg.hideouthq.core.designsystem.theme.HideoutDimens
import gg.hideouthq.core.designsystem.theme.HideoutShapes
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import gg.hideouthq.core.ui.PaneScaffold
import gg.hideouthq.core.ui.ScrollToTopEffect
import gg.hideouthq.core.ui.SearchTopBar
import gg.hideouthq.feature.collections.CollectionHero
import gg.hideouthq.feature.collections.CollectionSort
import gg.hideouthq.feature.collections.CollectionSummary
import gg.hideouthq.resources.Res
import gg.hideouthq.resources.breadcrumb_separator
import gg.hideouthq.resources.collection_about
import gg.hideouthq.resources.collection_copied
import gg.hideouthq.resources.collection_crumb_collections
import gg.hideouthq.resources.collection_crumb_discover
import gg.hideouthq.resources.collection_filter_all
import gg.hideouthq.resources.collection_heroes
import gg.hideouthq.resources.collection_make_profile
import gg.hideouthq.resources.collection_share
import gg.hideouthq.resources.collection_show_more
import gg.hideouthq.resources.collection_showing
import gg.hideouthq.resources.collection_skins
import gg.hideouthq.resources.collection_sort_new
import gg.hideouthq.resources.collection_sort_popular
import gg.hideouthq.resources.search_catalogue
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun CollectionPane(
    onSearch: (String) -> Unit,
    onDiscover: () -> Unit,
    current: String?,
    onCollections: (() -> Unit)?,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scroll = rememberScrollState()
    ScrollToTopEffect(scroll)
    val padding = HideoutDimens.MainPane.ContentPadding
    PaneScaffold(
        topBar = { SearchTopBar(query = "", onQueryChange = onSearch, placeholder = stringResource(Res.string.search_catalogue)) },
        scrolled = scroll.isScrolled,
        staggered = true,
    ) {
        ScrollColumn(
            modifier = Modifier.fillMaxSize(),
            state = scroll,
            contentPadding = PaddingValues(start = padding, end = padding, bottom = padding),
            verticalArrangement = Arrangement.spacedBy(HideoutDimens.Collection.PageGap),
        ) {
            CollectionCrumbs(onDiscover, current, onCollections)
            content()
        }
    }
}

@Composable
private fun CollectionCrumbs(onDiscover: () -> Unit, current: String?, onCollections: (() -> Unit)?) {
    val scheme = MaterialTheme.colorScheme
    val type = HideoutTheme.typography
    val dimens = HideoutDimens.Collection
    Row(Modifier.fillMaxWidth().height(dimens.CrumbHeight), horizontalArrangement = Arrangement.spacedBy(dimens.CrumbGap), verticalAlignment = Alignment.CenterVertically) {
        BackLink(stringResource(Res.string.collection_crumb_discover), onDiscover)
        Text(stringResource(Res.string.breadcrumb_separator), style = type.captionSmall, color = scheme.outline)
        val source = remember { MutableInteractionSource() }
        val states = source.collectInteractionStates()
        val color by animateColorAsState(
            when {
                onCollections == null -> scheme.onSurfaceVariant
                states.hovered -> scheme.onSurface
                else -> scheme.outline
            },
            HideoutTheme.motion.effectsFast(),
            label = "crumbLink",
        )
        Text(
            stringResource(Res.string.collection_crumb_collections), style = type.captionSmall, color = color, maxLines = 1,
            modifier = if (onCollections == null) Modifier else Modifier.focusRing(states.focusVisible, HideoutShapes.Tile).hideoutClickable(source, role = Role.Button, onClick = onCollections),
        )
        if (current != null) {
            Text(stringResource(Res.string.breadcrumb_separator), style = type.captionSmall, color = scheme.outline)
            Text(current, style = type.captionSmall, color = scheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

@Composable
internal fun CollectionBanner(collection: CollectionSummary, copied: Boolean, onMakeProfile: () -> Unit, onShare: () -> Unit, modifier: Modifier = Modifier) {
    val dimens = HideoutDimens.Collection
    val colors = HideoutTheme.colors
    Box(modifier.fillMaxWidth().height(dimens.BannerHeight).clip(RoundedCornerShape(dimens.BannerRadius))) {
        CollectionBackdrop(collection.look, FigureLayout.Banner, Modifier.matchParentSize())
        CollectionFigures(collection.figures, FigureLayout.Banner, collection.look.rim, Modifier.matchParentSize())
        Box(Modifier.matchParentSize().background(BannerShade))
        Column(Modifier.align(Alignment.BottomStart).padding(start = dimens.BannerTextStart, bottom = dimens.BannerTextBottom).widthIn(max = dimens.BannerTextMaxWidth)) {
            collection.seasonEnd?.let { SeasonChip(it, Modifier.padding(bottom = dimens.BannerChipGap)) }
            Text(collection.title, style = HideoutTheme.typography.displayCollection, color = colors.onImage, maxLines = 1)
            Text(
                stringResource(
                    Res.string.collection_about,
                    pluralStringResource(Res.plurals.collection_skins, collection.skins, collection.skins),
                    pluralStringResource(Res.plurals.collection_heroes, collection.heroes, collection.heroes),
                ),
                style = HideoutTheme.typography.bodyProse,
                color = colors.onImageVariant,
                modifier = Modifier.padding(top = dimens.BannerAboutGap),
            )
            Row(Modifier.padding(top = dimens.BannerActionsGap), horizontalArrangement = Arrangement.spacedBy(HideoutDimens.Button.Gap)) {
                HideoutButton(
                    stringResource(Res.string.collection_make_profile, collection.title), onMakeProfile,
                    leadingIcon = HideoutIcons.Plus, leadingIconSize = HideoutDimens.Icon.Small,
                )
                HideoutButton(
                    stringResource(if (copied) Res.string.collection_copied else Res.string.collection_share), onShare,
                    variant = ButtonVariant.GlassOnHero, leadingIcon = if (copied) HideoutIcons.Check else HideoutIcons.Share, leadingIconSize = HideoutDimens.Icon.Small,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CollectionFilters(
    total: Int,
    heroes: List<CollectionHero>,
    hero: String?,
    sort: CollectionSort,
    onHero: (String?) -> Unit,
    onSort: (CollectionSort) -> Unit,
    modifier: Modifier = Modifier,
) {
    val gap = HideoutDimens.Collection.FilterGap
    val popular = stringResource(Res.string.collection_sort_popular)
    val new = stringResource(Res.string.collection_sort_new)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(gap)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(gap), verticalArrangement = Arrangement.spacedBy(gap)) {
            AvatarFilterChip(stringResource(Res.string.collection_filter_all), "$total", hero == null, { onHero(null) })
            heroes.forEach { candidate ->
                AvatarFilterChip(candidate.name, "${candidate.count}", hero == candidate.id, { onHero(candidate.id) }) {
                    HeroPortrait(candidate, HideoutDimens.Chip.Avatar)
                }
            }
        }
        SegmentedControl(CollectionSort.entries, sort, onSort, label = { if (it == CollectionSort.Popular) popular else new }, modifier = Modifier.align(Alignment.End))
    }
}

@Composable
internal fun HeroPortrait(hero: CollectionHero, size: Dp) =
    ShapedAvatar(hero.initials, CircleShape, AvatarFill.Neutral, size, HideoutTheme.typography.initialsChip, imageUrl = hero.iconUrl)

@Composable
internal fun ShowMore(shown: Int, total: Int, onMore: () -> Unit) {
    val dimens = HideoutDimens.Collection
    Row(
        Modifier.fillMaxWidth().padding(top = dimens.FooterTopPadding),
        horizontalArrangement = Arrangement.spacedBy(dimens.FooterGap, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(Res.string.collection_showing, shown, total), style = HideoutTheme.typography.subtitle, color = MaterialTheme.colorScheme.outline)
        HideoutButton(stringResource(Res.string.collection_show_more), onMore, variant = ButtonVariant.Tonal, size = ButtonSize.Medium)
    }
}

private val BannerShade = Brush.horizontalGradient(
    0f to Color.Black.copy(alpha = 0.92f),
    0.34f to Color.Black.copy(alpha = 0.72f),
    0.62f to Color.Black.copy(alpha = 0f),
)
