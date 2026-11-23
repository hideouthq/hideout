package gg.hideouthq.feature.discover.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import gg.hideouthq.core.data.InstallStatus
import gg.hideouthq.core.designsystem.component.CardScrim
import gg.hideouthq.core.designsystem.component.CoverArt
import gg.hideouthq.core.designsystem.component.LinkButton
import gg.hideouthq.core.designsystem.component.LocalModCardStyle
import gg.hideouthq.core.designsystem.component.ModCard
import gg.hideouthq.core.designsystem.component.SectionHeader
import gg.hideouthq.core.designsystem.component.SoundRow
import gg.hideouthq.core.designsystem.component.gaps
import gg.hideouthq.core.designsystem.icon.HideoutIcons
import gg.hideouthq.core.designsystem.theme.HideoutDimens
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import gg.hideouthq.core.ui.AdaptiveGrid
import gg.hideouthq.core.ui.FlexItem
import gg.hideouthq.core.ui.FlexWrapRow
import gg.hideouthq.core.ui.ModLabels
import gg.hideouthq.core.ui.gridColumns
import gg.hideouthq.core.ui.catalog.CardTransition.card
import gg.hideouthq.core.ui.catalog.CardTransition.cover
import gg.hideouthq.core.ui.catalog.CardTransition.decoration
import gg.hideouthq.core.ui.catalog.CardTransition.title
import gg.hideouthq.core.ui.catalog.CatalogCard
import gg.hideouthq.core.ui.catalog.CatalogItem
import gg.hideouthq.core.ui.catalog.subtitle
import gg.hideouthq.core.ui.rememberInstallOrigin
import gg.hideouthq.feature.discover.SoundItem
import gg.hideouthq.resources.Res
import gg.hideouthq.resources.discover_keyword_hud
import gg.hideouthq.resources.discover_keyword_sounds
import gg.hideouthq.resources.discover_see_all
import gg.hideouthq.resources.discover_trending_hud
import gg.hideouthq.resources.discover_trending_sounds
import gg.hideouthq.resources.downloads_count
import gg.hideouthq.resources.install
import gg.hideouthq.resources.install_installed
import gg.hideouthq.resources.sound_install
import gg.hideouthq.resources.sound_installed
import gg.hideouthq.resources.sound_play
import gg.hideouthq.resources.sound_playing
import gg.hideouthq.resources.sound_preview_cancel
import gg.hideouthq.resources.sound_preview_loading
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun Section(
    title: String,
    keyword: String,
    icon: ImageVector,
    accent: Color,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(HideoutDimens.MainPane.BlockGap)) {
        SectionHeader(title, trailing = trailing, icon = icon, accent = accent, keyword = keyword)
        content()
    }
}

@Composable
internal fun TrendingCards(
    title: String,
    keyword: String,
    icon: ImageVector,
    accent: Color,
    items: List<CatalogItem>,
    height: Dp,
    filling: Boolean,
    onOpen: (String) -> Unit,
    onSeeAll: () -> Unit,
    modifier: Modifier,
) {
    Section(title, keyword, icon, accent, modifier, trailing = { LinkButton(stringResource(Res.string.discover_see_all), onSeeAll) }) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val gaps = LocalModCardStyle.current.gaps
            val columns = LocalDensity.current.gridColumns(constraints.maxWidth, HideoutDimens.Card.MinColumnDiscover, gaps.column)
            AdaptiveGrid(HideoutDimens.Card.MinColumnDiscover, columnGap = gaps.column, rowGap = gaps.row) {
                if (items.isEmpty() && filling) CardsSkeleton(maxOf(4, columns), height)
                items.take(maxOf(4, columns)).forEachIndexed { index, item ->
                    CatalogCard(
                        item = item,
                        onClick = { onOpen(item.id) },
                        modifier = Modifier.card(),
                        height = height,
                        rank = index + 1,
                        coverModifier = Modifier.cover(item.id),
                        titleModifier = Modifier.title(item.id),
                        decorationModifier = Modifier.decoration(item.id),
                    )
                }
            }
        }
    }
}

private val ColumnItems = listOf(
    FlexItem(HideoutDimens.Discover.HudBasis, grow = HideoutDimens.Discover.HudGrow),
    FlexItem(HideoutDimens.Discover.SoundsBasis, grow = HideoutDimens.Discover.SoundsGrow),
)

@Composable
internal fun HudAndSounds(
    hud: List<CatalogItem>,
    sounds: List<SoundItem>,
    filling: Boolean,
    onOpen: (String) -> Unit,
    onSeeAllHud: () -> Unit,
    onSeeAllSounds: () -> Unit,
    onToggleSample: (String) -> Unit,
    onInstall: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    FlexWrapRow(ColumnItems, HideoutDimens.MainPane.ColumnGap, HideoutDimens.MainPane.SectionGap, modifier) {
        Section(
            stringResource(Res.string.discover_trending_hud), stringResource(Res.string.discover_keyword_hud), HideoutIcons.SectionHud, HideoutTheme.colors.sectionHud,
            trailing = { LinkButton(stringResource(Res.string.discover_see_all), onSeeAllHud) },
        ) {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val gaps = LocalModCardStyle.current.gaps
                val columns = LocalDensity.current.gridColumns(constraints.maxWidth, HideoutDimens.Card.MinColumnHud, gaps.column).coerceIn(2, 4)
                AdaptiveGrid(HideoutDimens.Card.MinColumnHud, columnGap = gaps.column, rowGap = gaps.row) {
                    if (hud.isEmpty() && filling) CardsSkeleton(columns, HideoutDimens.Card.HeightHud)
                    hud.take(columns).forEach { item -> HudCard(item, onOpen) }
                }
            }
        }
        Section(
            stringResource(Res.string.discover_trending_sounds), stringResource(Res.string.discover_keyword_sounds), HideoutIcons.SectionSounds, HideoutTheme.colors.sectionSounds,
            trailing = { LinkButton(stringResource(Res.string.discover_see_all), onSeeAllSounds) },
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(HideoutDimens.ListRow.ListGap)) {
                if (sounds.isEmpty() && filling) SoundRowsSkeleton()
                sounds.forEach { sound -> SoundSampleRow(sound, onToggleSample, onInstall) }
            }
        }
    }
}

@Composable
private fun HudCard(item: CatalogItem, onOpen: (String) -> Unit) {
    val stat = item.downloads?.let(ModLabels::count)
    ModCard(
        title = item.title,
        subtitle = item.subtitle(),
        stat = stat,
        onClick = { onOpen(item.id) },
        cover = {
            CoverArt(
                seed = item.id,
                modifier = Modifier.cover(item.id).fillMaxSize(),
                imageUrl = item.coverUrl,
                icon = HideoutIcons.Grid,
                tint = MaterialTheme.colorScheme.surfaceContainer,
            )
        },
        modifier = Modifier.card(),
        height = HideoutDimens.Card.HeightHud,
        statDescription = stat?.let { stringResource(Res.string.downloads_count, it) },
        scrimFraction = CardScrim.HudPreview,
        titleModifier = Modifier.title(item.id),
        decorationModifier = Modifier.decoration(item.id),
    )
}

@Composable
private fun SoundSampleRow(sound: SoundItem, onToggle: (String) -> Unit, onInstall: (String) -> Unit) {
    val item = sound.item
    val playing = sound.playingSeconds
    val loading = sound.playback?.loading == true
    val detail = if (playing != null) stringResource(Res.string.sound_playing, clockTime(playing)) else item.downloads?.let { stringResource(Res.string.downloads_count, ModLabels.count(it)) }
    val installed = sound.install is InstallStatus.Installed
    val origin = rememberInstallOrigin(item.id)
    SoundRow(
        title = item.title,
        subtitle = if (loading) stringResource(Res.string.sound_preview_loading) else ModLabels.join(item.kind, detail),
        loading = loading,
        waveform = sound.waveform.map { HideoutDimens.SoundRow.WaveformHeight * it },
        playing = sound.playing,
        progress = sound.progress,
        onPlayingChange = { onToggle(item.id) },
        playDescription = if (loading) stringResource(Res.string.sound_preview_cancel) else stringResource(Res.string.sound_play, item.title),
        installIcon = if (installed) HideoutIcons.Check else HideoutIcons.Download,
        installDescription = stringResource(if (installed) Res.string.sound_installed else Res.string.sound_install, item.title),
        installTooltip = stringResource(if (installed) Res.string.install_installed else Res.string.install),
        onInstall = if (ModLabels.installButtonEnabled(sound.install)) ({ origin.claim(); onInstall(item.id) }) else null,
        playable = sound.playable,
        installModifier = origin.modifier,
    )
}

private fun clockTime(seconds: Int): String = "${seconds / SecondsPerMinute}:${(seconds % SecondsPerMinute).toString().padStart(2, '0')}"

private const val SecondsPerMinute = 60

