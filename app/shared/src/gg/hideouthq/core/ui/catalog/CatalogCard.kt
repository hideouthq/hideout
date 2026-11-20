package gg.hideouthq.core.ui.catalog

import androidx.compose.ui.unit.sp
import gg.hideouthq.core.model.ModCardStyle
import gg.hideouthq.core.designsystem.component.LocalModCardStyle
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.background
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import gg.hideouthq.core.data.InstallStatus
import gg.hideouthq.core.designsystem.component.HideoutIcon
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import gg.hideouthq.core.designsystem.theme.HideoutShapes
import gg.hideouthq.resources.profile_install_badge_on
import gg.hideouthq.resources.profile_install_badge_library
import gg.hideouthq.resources.profile_install_badge_downloading
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import gg.hideouthq.core.designsystem.component.CoverArt
import gg.hideouthq.core.designsystem.component.GlassBadge
import gg.hideouthq.core.designsystem.component.GlassBadgeSize
import gg.hideouthq.core.designsystem.component.CardFavourite
import gg.hideouthq.core.designsystem.component.ModCard
import gg.hideouthq.core.designsystem.icon.HideoutIcons
import gg.hideouthq.core.designsystem.theme.HideoutDimens
import gg.hideouthq.core.model.ModCategory
import gg.hideouthq.core.ui.ModLabels
import gg.hideouthq.resources.Res
import gg.hideouthq.resources.badge_3d_preview
import gg.hideouthq.resources.badge_mature
import gg.hideouthq.resources.badge_sound_sample
import gg.hideouthq.resources.downloads_count
import gg.hideouthq.resources.favourite_named
import org.jetbrains.compose.resources.stringResource

private val MatureBlur = 28.dp

@Composable
fun CatalogCard(
    item: CatalogItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = HideoutDimens.Card.HeightBrowse,
    rank: Int? = null,
    coverModifier: Modifier = Modifier,
    titleModifier: Modifier = Modifier,
    decorationModifier: Modifier = Modifier,
    onFavourite: ((Boolean) -> Unit)? = null,
) {
    val (badge, badgeLabel) = item.badge()
    val downloads = item.downloads?.let(ModLabels::count)
    ModCard(
        title = item.title,
        subtitle = item.subtitle(),
        stat = downloads,
        statDescription = downloads?.let { stringResource(Res.string.downloads_count, it) },
        onClick = onClick,
        cover = {
            CoverArt(
                seed = item.id,
                modifier = coverModifier.fillMaxSize().then(if (item.blurred) Modifier.blur(MatureBlur) else Modifier),
                imageUrl = item.coverUrl,
                tintIndex = ModLabels.tint(item.coverTone),
                waveform = item.waveform,
            )
            if (item.blurred) GlassBadge(stringResource(Res.string.badge_mature), Modifier.align(Alignment.Center), GlassBadgeSize.Regular)
        },
        modifier = modifier,
        height = height,
        rank = rank,
        libraryBadge = item.installation?.takeIf { it is InstallStatus.Installed || it is InstallStatus.Downloading }?.let { status -> { LibraryBadge(status) } },
        badgeIcon = badge,
        badgeDescription = badgeLabel,
        titleModifier = titleModifier,
        decorationModifier = decorationModifier,
        favourite = onFavourite?.let { CardFavourite(item.favourite, stringResource(Res.string.favourite_named, item.title), it) },
    )
}

@Composable
private fun LibraryBadge(status: InstallStatus) {
    val on = (status as? InstallStatus.Installed)?.enabled == true
    val scheme = MaterialTheme.colorScheme
    val ink = if (on) scheme.secondary else HideoutTheme.colors.onImage
    val under = LocalModCardStyle.current == ModCardStyle.UnderImage
    val label = when (status) {
        is InstallStatus.Installed -> stringResource(if (on) Res.string.profile_install_badge_on else Res.string.profile_install_badge_library)
        is InstallStatus.Downloading -> stringResource(Res.string.profile_install_badge_downloading, status.percent)
        else -> return
    }
    val icon = if (on) HideoutIcons.Check else if (status is InstallStatus.Downloading) HideoutIcons.Download else HideoutIcons.Layers
    if (under) {
        val quiet = if (on) scheme.secondary else scheme.onSurfaceVariant
        Row(
            Modifier.height(20.dp).background(if (on) scheme.secondaryContainer else scheme.surfaceContainerHigh, HideoutShapes.Full).padding(start = 6.dp, end = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            HideoutIcon(icon, null, size = 12.dp, tint = quiet)
            Text(label, style = HideoutTheme.typography.caption.copy(fontSize = 11.sp, fontWeight = FontWeight(650)), color = quiet, maxLines = 1)
        }
        return
    }
    Row(
        Modifier.height(28.dp).background(if (on) scheme.secondaryContainer else Color.Black.copy(alpha = 0.55f), HideoutShapes.Full).padding(start = 8.dp, end = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        HideoutIcon(icon, null, size = 14.dp, tint = ink)
        Text(label, style = HideoutTheme.typography.caption.copy(fontWeight = FontWeight(650)), color = ink)
    }
}

fun CatalogItem.subtitle(): String = ModLabels.join(heroName ?: kind, author)

@Composable
private fun CatalogItem.badge(): Pair<ImageVector?, String?> = when {
    category == ModCategory.Skins && has3dPreview -> HideoutIcons.Cube to stringResource(Res.string.badge_3d_preview)
    category == ModCategory.Sounds -> HideoutIcons.Sound to stringResource(Res.string.badge_sound_sample)
    else -> null to null
}
