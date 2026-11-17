package gg.hideouthq.feature.stats.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import gg.hideouthq.core.designsystem.component.ButtonVariant
import gg.hideouthq.core.designsystem.component.HideoutButton
import gg.hideouthq.core.designsystem.component.HideoutIcon
import gg.hideouthq.core.designsystem.component.SkeletonBlock
import gg.hideouthq.core.designsystem.component.rememberShimmer
import gg.hideouthq.core.designsystem.component.skeleton
import gg.hideouthq.core.designsystem.icon.HideoutIcons
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import gg.hideouthq.feature.stats.MatchPendingContent
import gg.hideouthq.feature.stats.MatchPendingPhase
import gg.hideouthq.feature.stats.Team
import gg.hideouthq.resources.Res
import gg.hideouthq.resources.stats_pending_check_again
import gg.hideouthq.resources.stats_pending_check_now
import gg.hideouthq.resources.stats_pending_on_the_way_body
import gg.hideouthq.resources.stats_pending_on_the_way_title
import gg.hideouthq.resources.stats_pending_slow_body
import gg.hideouthq.resources.stats_pending_slow_title
import gg.hideouthq.resources.stats_pending_unavailable_body
import gg.hideouthq.resources.stats_pending_unavailable_title
import gg.hideouthq.resources.stats_scoreboard
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun MatchPendingBody(content: MatchPendingContent, onCheck: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        MatchPendingStatusCard(content, onCheck)
        if (content.phase != MatchPendingPhase.Unavailable) {
            val sweep = rememberShimmer(active = content.phase == MatchPendingPhase.OnTheWay)
            MatchPendingTimelineSkeleton(sweep)
            MatchPendingScoreboardSkeleton(sweep)
        }
    }
}

private class PhaseLook(val icon: ImageVector, val tile: Color, val tint: Color, val title: String, val body: String)

@Composable
private fun phaseLook(phase: MatchPendingPhase): PhaseLook {
    val scheme = MaterialTheme.colorScheme
    val primaryTile = scheme.primary.copy(alpha = 0.15f)
    return when (phase) {
        MatchPendingPhase.OnTheWay -> PhaseLook(
            HideoutIcons.CloudDownload, primaryTile, scheme.primary,
            stringResource(Res.string.stats_pending_on_the_way_title), stringResource(Res.string.stats_pending_on_the_way_body),
        )
        MatchPendingPhase.Slow -> PhaseLook(
            HideoutIcons.Clock, primaryTile, scheme.primary,
            stringResource(Res.string.stats_pending_slow_title), stringResource(Res.string.stats_pending_slow_body),
        )
        MatchPendingPhase.Unavailable -> PhaseLook(
            HideoutIcons.Info, scheme.surfaceContainerHighest, scheme.onSurfaceVariant,
            stringResource(Res.string.stats_pending_unavailable_title), stringResource(Res.string.stats_pending_unavailable_body),
        )
    }
}

@Composable
private fun MatchPendingStatusCard(content: MatchPendingContent, onCheck: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val type = statsType()
    val phase = content.phase
    val look = phaseLook(phase)
    StatsCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(22.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
            Box(Modifier.size(48.dp).clip(RoundedCornerShape(16.dp)).background(look.tile), contentAlignment = Alignment.Center) {
                HideoutIcon(look.icon, null, tint = look.tint)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(look.title, style = type.cardTitle, color = scheme.onSurface, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                Text(look.body, style = type.meta, color = scheme.onSurfaceVariant, modifier = Modifier.widthIn(max = 660.dp))
            }
            if (phase != MatchPendingPhase.OnTheWay) {
                CheckButton(stringResource(if (phase == MatchPendingPhase.Slow) Res.string.stats_pending_check_now else Res.string.stats_pending_check_again), content.checking, onCheck)
            }
        }
        if (phase != MatchPendingPhase.Unavailable) {
            Spacer(Modifier.height(20.dp))
            PendingProgress(phase)
        }
    }
}

@Composable
private fun CheckButton(text: String, checking: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(contentAlignment = Alignment.Center) {
        HideoutButton(text, onClick, variant = ButtonVariant.Tonal, enabled = !checking, contentColor = if (checking) Color.Transparent else null)
        if (checking) CircularProgressIndicator(Modifier.size(16.dp), color = scheme.onSurface, strokeWidth = 2.dp)
    }
}

@Composable
private fun PendingProgress(phase: MatchPendingPhase) {
    val reduced = HideoutTheme.motion.reduced
    val wavelength = 20.dp
    LinearWavyProgressIndicator(
        progress = { 1f },
        modifier = Modifier.fillMaxWidth().height(10.dp).clearAndSetSemantics { progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate },
        color = MaterialTheme.colorScheme.primary,
        trackColor = Color.Transparent,
        amplitude = { if (reduced) 0f else 1f },
        wavelength = wavelength,
        waveSpeed = if (reduced) 0.dp else wavelength / (if (phase == MatchPendingPhase.OnTheWay) 0.9f else 1.6f),
    )
}

@Composable
private fun MatchPendingTimelineSkeleton(sweep: State<Float>) {
    StatsCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(20.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            SkeletonBlock(sweep, 120.dp, 30.dp, RoundedCornerShape(15.dp))
            Spacer(Modifier.weight(1f))
            SkeletonBlock(sweep, 160.dp, 14.dp)
        }
        SkeletonBlock(sweep, 360.dp, 12.dp, modifier = Modifier.padding(top = 14.dp))
        val guide = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
        Box(Modifier.fillMaxWidth().height(260.dp).padding(top = 16.dp).skeleton(sweep, RoundedCornerShape(12.dp))) {
            Canvas(Modifier.fillMaxSize().padding(start = 48.dp, end = 12.dp, top = 18.dp, bottom = 18.dp)) {
                for (fraction in listOf(0f, 0.5f, 1f)) {
                    val y = size.height * fraction
                    drawLine(guide, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
                }
            }
            Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.SpaceBetween) {
                for (label in listOf("+20k", "0", "−20k")) Text(label, style = statsType().caption, color = guide)
            }
        }
    }
}

@Composable
private fun MatchPendingScoreboardSkeleton(sweep: State<Float>) {
    StatsCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(20.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(Res.string.stats_scoreboard), style = statsType().cardTitle, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.weight(1f))
            SkeletonBlock(sweep, 180.dp, 28.dp, CircleShape)
        }
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            for (team in listOf(Team.Sapphire, Team.Amber)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(StatsColors.team(team)))
                        Text(StatsLabels.team(team), style = statsType().rowTitle, color = MaterialTheme.colorScheme.onSurface)
                    }
                    repeat(6) { ScoreboardSkeletonRow(sweep) }
                }
            }
        }
    }
}

@Composable
private fun ScoreboardSkeletonRow(sweep: State<Float>) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(36.dp).skeleton(sweep, CircleShape))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    SkeletonBlock(sweep, 96.dp, 10.dp, modifier = Modifier.testTag("pendingPlayerName"))
                    SkeletonBlock(sweep, 56.dp, 8.dp)
                }
                SkeletonBlock(sweep, 38.dp, 10.dp)
                SkeletonBlock(sweep, 46.dp, 10.dp)
            }
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val itemSize = ((maxWidth - 33.dp) / 12).coerceAtMost(20.dp)
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    repeat(12) { Box(Modifier.size(itemSize).skeleton(sweep, RoundedCornerShape(6.dp))) }
                }
            }
        }
    }
}
