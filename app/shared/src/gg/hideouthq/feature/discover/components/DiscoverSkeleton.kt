package gg.hideouthq.feature.discover.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import gg.hideouthq.core.data.CatalogFreshness
import gg.hideouthq.core.designsystem.component.SkeletonBlock
import gg.hideouthq.core.designsystem.component.rememberShimmer
import gg.hideouthq.core.designsystem.component.skeleton
import gg.hideouthq.core.designsystem.theme.HideoutDimens
import gg.hideouthq.core.designsystem.theme.HideoutShapes
import gg.hideouthq.core.ui.catalog.CatalogFreshnessBar
import gg.hideouthq.core.ui.transition.staggeredEnter

@Composable
internal fun DiscoverSkeleton(freshness: CatalogFreshness, onRefresh: () -> Unit, modifier: Modifier = Modifier) {
    val sweep = rememberShimmer()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(HideoutDimens.MainPane.SectionGap)) {
        Column(Modifier.fillMaxWidth().staggeredEnter(0)) {
            CatalogFreshnessBar(freshness, onRefresh, Modifier.align(Alignment.End))
            SkeletonBlock(sweep, null, HideoutDimens.Hero.Height, HideoutShapes.Hero)
        }
        SectionSkeleton(sweep, HideoutDimens.HeroAvatar.MinItemWidth, HideoutDimens.HeroAvatar.ItemGap, Modifier.staggeredEnter(1)) {
            Column(Modifier.weight(1f), Arrangement.spacedBy(HideoutDimens.HeroAvatar.LabelGap), Alignment.CenterHorizontally) {
                Box(Modifier.size(HideoutDimens.HeroAvatar.Size).skeleton(sweep, CircleShape))
                SkeletonBlock(sweep, 40.dp, 10.dp)
            }
        }
        listOf(HideoutDimens.Card.HeightDiscover, HideoutDimens.Card.HeightGameplay).forEachIndexed { index, height ->
            SectionSkeleton(sweep, HideoutDimens.Card.MinColumnDiscover, HideoutDimens.Card.Gap, Modifier.staggeredEnter(2 + index)) {
                SkeletonBlock(sweep, null, height, HideoutShapes.Card, Modifier.weight(1f))
            }
        }
    }
}

@Composable
internal fun CardsSkeleton(count: Int, height: Dp) {
    val sweep = rememberShimmer()
    repeat(count) { SkeletonBlock(sweep, null, height, HideoutShapes.Card) }
}

@Composable
internal fun SoundRowsSkeleton() {
    val sweep = rememberShimmer()
    repeat(SoundRows) { SkeletonBlock(sweep, null, HideoutDimens.ListRow.Height, HideoutShapes.Card) }
}

private const val SoundRows = 4

@Composable
private fun SectionSkeleton(sweep: State<Float>, minWidth: Dp, gap: Dp, modifier: Modifier, item: @Composable RowScope.() -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(HideoutDimens.MainPane.BlockGap)) {
        SkeletonBlock(sweep, 200.dp, 20.dp, modifier = Modifier.padding(vertical = 8.dp))
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val count = ((maxWidth + gap) / (minWidth + gap)).toInt().coerceAtLeast(1)
            Row(horizontalArrangement = Arrangement.spacedBy(gap)) { repeat(count) { item() } }
        }
    }
}
