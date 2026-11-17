package gg.hideouthq.feature.stats.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import gg.hideouthq.core.designsystem.component.SkeletonBlock
import gg.hideouthq.core.designsystem.component.rememberShimmer
import gg.hideouthq.core.designsystem.component.skeleton
import gg.hideouthq.core.designsystem.shape.Cookie
import gg.hideouthq.feature.stats.Coverage
import gg.hideouthq.feature.stats.StatsTab
import gg.hideouthq.resources.Res
import gg.hideouthq.resources.stats_fetching
import gg.hideouthq.resources.stats_fetching_progress
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun LoadingLine(width: Dp?, modifier: Modifier = Modifier, height: Dp = 10.dp, shape: Shape = RoundedCornerShape(6.dp)) {
    SkeletonBlock(rememberShimmer(), width, height, shape, modifier)
}

@Composable
internal fun LoadingRows(rows: Int, rowHeight: Dp = 56.dp) {
    val sweep = rememberShimmer()
    Column {
        repeat(rows) {
            Row(Modifier.fillMaxWidth().height(rowHeight).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(36.dp).skeleton(sweep, CircleShape))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SkeletonBlock(sweep, 140.dp, 12.dp)
                    SkeletonBlock(sweep, 90.dp, 10.dp)
                }
                SkeletonBlock(sweep, 110.dp, 8.dp)
            }
        }
    }
}

@Composable
internal fun HeaderSkeleton(progress: Coverage?) {
    val sweep = rememberShimmer()
    val text = progress?.let { stringResource(Res.string.stats_fetching_progress, it.covered, it.of) } ?: stringResource(Res.string.stats_fetching)
    StatsCard(Modifier.fillMaxWidth().semantics { contentDescription = text }, contentPadding = PaddingValues(28.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(StatsDimens.SelfAvatar).skeleton(sweep, Cookie.Twelve.shape))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SkeletonBlock(sweep, 220.dp, 32.dp, RoundedCornerShape(8.dp))
                Text(text, style = statsType().meta, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    repeat(3) { SkeletonBlock(sweep, 120.dp, 30.dp, CircleShape) }
                }
            }
            SkeletonBlock(sweep, 300.dp, 116.dp, StatsShapes.RankGlass)
        }
        Row(Modifier.padding(top = 24.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            repeat(7) { SkeletonBlock(sweep, null, 40.dp, modifier = Modifier.weight(1f)) }
        }
    }
}

@Composable
internal fun TabSkeleton(tab: StatsTab, modifier: Modifier = Modifier) {
    val sweep = rememberShimmer()
    BoxWithConstraints(modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        val width = maxWidth
        val wide = width >= 880.dp
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (tab == StatsTab.Overview) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    repeat(if (width >= 1000.dp) 5 else 3) { SkeletonBlock(sweep, null, StatsDimens.KpiMinHeight, StatsShapes.Tile, Modifier.weight(1f)) }
                }
            }
            Pair(
                wide && tab != StatsTab.Matches,
                first = { RowsSkeleton(sweep, if (tab == StatsTab.Matches) 8 else 6) },
                second = { RowsSkeleton(sweep, 4) },
            )
        }
    }
}

@Composable
private fun RowsSkeleton(sweep: State<Float>, rows: Int) {
    StatsCard {
        SkeletonBlock(sweep, 160.dp, 20.dp, modifier = Modifier.padding(start = 12.dp, top = 16.dp, bottom = 16.dp))
        repeat(rows) {
            Row(Modifier.fillMaxWidth().height(StatsDimens.MatchRowHeight).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(StatsDimens.HeroTile).skeleton(sweep, Cookie.Eight.shape))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SkeletonBlock(sweep, 140.dp, 12.dp)
                    SkeletonBlock(sweep, 90.dp, 10.dp)
                }
                SkeletonBlock(sweep, 70.dp, 12.dp)
                SkeletonBlock(sweep, 70.dp, 12.dp)
            }
        }
    }
}
