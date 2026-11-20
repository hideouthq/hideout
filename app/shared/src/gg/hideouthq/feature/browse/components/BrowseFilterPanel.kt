package gg.hideouthq.feature.browse.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import gg.hideouthq.core.designsystem.component.ButtonSize
import gg.hideouthq.core.designsystem.component.ButtonVariant
import gg.hideouthq.core.designsystem.component.FilterChip
import gg.hideouthq.core.designsystem.component.HideoutButton
import gg.hideouthq.core.designsystem.component.HideoutDivider
import gg.hideouthq.core.designsystem.component.OutlinedChip
import gg.hideouthq.core.designsystem.component.PaneHeader
import gg.hideouthq.core.designsystem.component.ScrollColumn
import gg.hideouthq.core.designsystem.component.SearchField
import gg.hideouthq.core.designsystem.component.SearchFieldSize
import gg.hideouthq.core.designsystem.component.SegmentedControl
import gg.hideouthq.core.designsystem.component.SwitchRow
import gg.hideouthq.core.designsystem.icon.HideoutIcons
import gg.hideouthq.core.designsystem.theme.HideoutDimens
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import gg.hideouthq.feature.browse.BrowseActions
import gg.hideouthq.feature.browse.BrowseFilters
import gg.hideouthq.feature.browse.HeroChip
import gg.hideouthq.feature.browse.MatureMods
import gg.hideouthq.feature.browse.UpdatedWithin
import gg.hideouthq.resources.Res
import gg.hideouthq.resources.favourites
import gg.hideouthq.resources.filters_3d_preview
import gg.hideouthq.resources.filters_all_heroes
import gg.hideouthq.resources.filters_blur_mature
import gg.hideouthq.resources.filters_clear
import gg.hideouthq.resources.filters_current_patch
import gg.hideouthq.resources.filters_find_hero
import gg.hideouthq.resources.filters_hero
import gg.hideouthq.resources.filters_hero_any
import gg.hideouthq.resources.filters_hero_picked
import gg.hideouthq.resources.filters_show_fewer
import gg.hideouthq.resources.filters_show_only
import gg.hideouthq.resources.filters_title
import gg.hideouthq.resources.filters_updated
import gg.hideouthq.resources.filters_updated_any
import gg.hideouthq.resources.filters_updated_month
import gg.hideouthq.resources.filters_updated_week
import gg.hideouthq.resources.filters_updated_year
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun BrowseFilterPanel(
    filters: BrowseFilters,
    heroChips: List<HeroChip>,
    heroCount: Int,
    viewerFilters: Boolean,
    mature: MatureMods,
    hasFavourites: Boolean,
    actions: BrowseActions,
) {
    Column(Modifier.fillMaxSize()) {
        PaneHeader(
            title = stringResource(Res.string.filters_title),
            icon = HideoutIcons.Filter,
        ) {
            HideoutButton(stringResource(Res.string.filters_clear), actions::clearPanel, variant = ButtonVariant.Text, size = ButtonSize.Small)
        }
        ScrollColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(
                start = HideoutDimens.SidePanel.Padding,
                end = HideoutDimens.SidePanel.Padding,
                top = HideoutDimens.Browse.PanelTopPadding,
                bottom = HideoutDimens.SidePanel.Padding,
            ),
            verticalArrangement = Arrangement.spacedBy(HideoutDimens.SidePanel.SectionGap),
        ) {
            HeroSection(filters, heroChips, heroCount, actions)
            HideoutDivider()
            Column(verticalArrangement = Arrangement.spacedBy(HideoutDimens.Browse.ShowOnlyGap)) {
                SectionLabel(stringResource(Res.string.filters_show_only), Modifier.padding(bottom = HideoutDimens.Browse.SectionLabelBottomPadding))
                if (hasFavourites || filters.favourites) SwitchRow(stringResource(Res.string.favourites), filters.favourites, actions::setFavouritesOnly, icon = HideoutIcons.Star)
                if (viewerFilters) SwitchRow(stringResource(Res.string.filters_3d_preview), filters.only3d, actions::setOnly3d, icon = HideoutIcons.Cube)
                SwitchRow(stringResource(Res.string.filters_current_patch), filters.currentPatch, actions::setCurrentPatch, icon = HideoutIcons.Check)
                if (mature != MatureMods.Hidden) SwitchRow(stringResource(Res.string.filters_blur_mature), mature == MatureMods.Blurred, actions::setBlurMature, icon = HideoutIcons.Shield)
            }
            HideoutDivider()
            Column(verticalArrangement = Arrangement.spacedBy(HideoutDimens.Browse.HeroSectionGap)) {
                SectionLabel(stringResource(Res.string.filters_updated))
                val labels = mapOf(
                    UpdatedWithin.Any to stringResource(Res.string.filters_updated_any),
                    UpdatedWithin.Week to stringResource(Res.string.filters_updated_week),
                    UpdatedWithin.Month to stringResource(Res.string.filters_updated_month),
                    UpdatedWithin.Year to stringResource(Res.string.filters_updated_year),
                )
                SegmentedControl(UpdatedWithin.entries, filters.updated, actions::setUpdated, { labels.getValue(it) }, Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun HeroSection(filters: BrowseFilters, heroChips: List<HeroChip>, heroCount: Int, actions: BrowseActions) {
    Column(verticalArrangement = Arrangement.spacedBy(HideoutDimens.Browse.HeroSectionGap)) {
        Row(Modifier.fillMaxWidth()) {
            SectionLabel(stringResource(Res.string.filters_hero), Modifier.weight(1f))
            val picked = filters.heroIds.size
            SectionLabel(if (picked == 0) stringResource(Res.string.filters_hero_any) else stringResource(Res.string.filters_hero_picked, picked))
        }
        SearchField(
            query = filters.heroQuery,
            onQueryChange = actions::setHeroQuery,
            placeholder = stringResource(Res.string.filters_find_hero),
            modifier = Modifier.fillMaxWidth(),
            size = SearchFieldSize.Small,
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth().animateContentSize(HideoutTheme.motion.spatialDefault()),
            horizontalArrangement = Arrangement.spacedBy(HideoutDimens.Chip.Gap),
            verticalArrangement = Arrangement.spacedBy(HideoutDimens.Chip.Gap),
        ) {
            heroChips.forEach { chip ->
                key(chip.id) { FilterChip(chip.name, chip.selected, { actions.toggleHero(chip.id, it) }) }
            }
            if (filters.heroQuery.isBlank()) {
                if (filters.showAllHeroes) {
                    OutlinedChip(stringResource(Res.string.filters_show_fewer), { actions.setShowAllHeroes(false) })
                } else {
                    OutlinedChip(stringResource(Res.string.filters_all_heroes, heroCount), { actions.setShowAllHeroes(true) })
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = HideoutTheme.typography.labelSection,
        color = MaterialTheme.colorScheme.outline,
        modifier = modifier.semantics { heading() },
    )
}
