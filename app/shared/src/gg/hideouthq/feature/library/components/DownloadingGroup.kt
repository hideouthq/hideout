package gg.hideouthq.feature.library.components

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import gg.hideouthq.core.designsystem.component.CoverArt
import gg.hideouthq.core.designsystem.component.HideoutIconButton
import gg.hideouthq.core.designsystem.component.ProgressBar
import gg.hideouthq.core.designsystem.icon.HideoutIcons
import gg.hideouthq.core.designsystem.interaction.hideoutClickable
import gg.hideouthq.core.designsystem.theme.HideoutDimens
import gg.hideouthq.core.designsystem.theme.HideoutShapes
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import gg.hideouthq.core.ui.ModLabels
import gg.hideouthq.core.ui.busyWhen
import gg.hideouthq.feature.library.DownloadPhase
import gg.hideouthq.feature.library.DownloadRow
import gg.hideouthq.feature.library.LibraryActions
import gg.hideouthq.resources.Res
import gg.hideouthq.resources.busy_goes_on
import gg.hideouthq.resources.busy_starts
import gg.hideouthq.resources.install_stalled
import gg.hideouthq.resources.library_adding
import gg.hideouthq.resources.library_adding_cancel
import gg.hideouthq.resources.library_download_cancel
import gg.hideouthq.resources.library_download_cancelling
import gg.hideouthq.resources.library_download_choosing
import gg.hideouthq.resources.library_download_facts
import gg.hideouthq.resources.library_download_finishing
import gg.hideouthq.resources.library_download_preparing
import gg.hideouthq.resources.library_download_reading
import gg.hideouthq.resources.library_download_waiting
import gg.hideouthq.resources.library_download_waiting_custom_game
import gg.hideouthq.resources.library_download_waiting_game
import gg.hideouthq.resources.library_downloading
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun DownloadPhase.subtitle(): String = when (this) {
    DownloadPhase.Preparing -> stringResource(Res.string.library_download_preparing)
    DownloadPhase.Reading -> stringResource(Res.string.library_download_reading)
    is DownloadPhase.Busy -> stringResource(if (doneBytes > 0) Res.string.busy_goes_on else Res.string.busy_starts, busyWhen(until))
    DownloadPhase.Choosing -> stringResource(Res.string.library_download_choosing)
    is DownloadPhase.Waiting -> stringResource(Res.string.library_download_waiting)
    is DownloadPhase.Transferring -> {
        val facts = stringResource(Res.string.library_download_facts, ModLabels.size(doneBytes), ModLabels.size(totalBytes))
        if (stalled) ModLabels.join(stringResource(Res.string.install_stalled), facts) else facts
    }
    DownloadPhase.Finishing -> stringResource(Res.string.library_download_finishing)
    DownloadPhase.WaitingForGame -> stringResource(Res.string.library_download_waiting_game)
    DownloadPhase.WaitingForCustomGame -> stringResource(Res.string.library_download_waiting_custom_game)
    DownloadPhase.Cancelling -> stringResource(Res.string.library_download_cancelling)
}

@Composable
private fun groupTitle(downloads: List<DownloadRow>): String =
    stringResource(if (downloads.none { it.hasModPage }) Res.string.library_adding else Res.string.library_downloading)

@Composable
private fun cancelLabel(download: DownloadRow): String =
    stringResource(if (download.hasModPage) Res.string.library_download_cancel else Res.string.library_adding_cancel, download.title.orEmpty())

private val DownloadPhase.waitsOnSomething: Boolean get() = this is DownloadPhase.Busy || this is DownloadPhase.Transferring && stalled

@Composable
internal fun DownloadingListGroup(
    downloads: List<DownloadRow>,
    actions: LibraryActions,
    modifier: Modifier = Modifier,
) {
    if (downloads.isEmpty()) return
    val scheme = MaterialTheme.colorScheme
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(HideoutDimens.Library.GroupGap)) {
        Row(
            modifier = Modifier.fillMaxWidth().height(HideoutDimens.Downloading.HeaderHeight).padding(horizontal = HideoutDimens.ListRow.StartPadding),
            horizontalArrangement = Arrangement.spacedBy(HideoutDimens.ListRow.Gap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(groupTitle(downloads), style = HideoutTheme.typography.labelSection, color = scheme.outline)
            Text(downloads.size.toString(), style = HideoutTheme.typography.labelSection, color = scheme.onSurfaceVariant)
        }
        downloads.forEach { download -> key(download.modId) { DownloadingListRow(download, actions) } }
    }
}

@Composable
private fun DownloadingListRow(download: DownloadRow, actions: LibraryActions) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth().height(HideoutDimens.ListRow.Height)
            .hideoutClickable(remember { MutableInteractionSource() }, enabled = download.hasModPage, onClick = { actions.viewPage(download.modId) })
            .padding(start = HideoutDimens.ListRow.StartPadding, end = HideoutDimens.ListRow.EndPadding),
        horizontalArrangement = Arrangement.spacedBy(HideoutDimens.ListRow.Gap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.width(HideoutDimens.ListRow.DragHandleWidth))
        Text("–", style = HideoutTheme.typography.mono, color = scheme.outline, modifier = Modifier.width(HideoutDimens.ListRow.IndexWidth))
        CoverArt(seed = download.modId, modifier = Modifier.size(HideoutDimens.ListRow.Thumb), shape = HideoutShapes.Thumb, imageUrl = download.coverUrl)
        Column(Modifier.weight(1f)) {
            Text(download.title.orEmpty(), style = HideoutTheme.typography.titleRow, color = scheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                text = if (download.phase.waitsOnSomething) download.phase.subtitle() else ModLabels.join(
                    download.category?.let { ModLabels.singular(it) },
                    download.heroOrKind,
                    download.author,
                    download.phase.subtitle(),
                ),
                style = HideoutTheme.typography.subtitle,
                color = scheme.outline,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        val transferring = download.phase as? DownloadPhase.Transferring
        if (transferring != null) ProgressBar(transferring.percent / 100f, Modifier.width(HideoutDimens.Downloading.BarWidth))
        if (download.canCancel) {
            HideoutIconButton(
                icon = HideoutIcons.Close,
                contentDescription = cancelLabel(download),
                onClick = { actions.cancelDownload(download.modId) },
            )
        }
    }
}

@Composable
internal fun DownloadingCardGroup(
    downloads: List<DownloadRow>,
    actions: LibraryActions,
    modifier: Modifier = Modifier,
) {
    if (downloads.isEmpty()) return
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(HideoutDimens.Library.GroupGap)) {
        Row(
            modifier = Modifier.fillMaxWidth().height(HideoutDimens.LibraryCard.Rank).padding(horizontal = HideoutDimens.ListRow.StartPadding),
            horizontalArrangement = Arrangement.spacedBy(HideoutDimens.ListRow.Gap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(groupTitle(downloads), style = HideoutTheme.typography.labelSection, color = MaterialTheme.colorScheme.outline)
            Text(downloads.size.toString(), style = HideoutTheme.typography.labelSection, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        AutoFillGrid { columnWidth ->
            downloads.forEach { download -> key(download.modId) { DownloadingCard(download, Modifier.width(columnWidth), actions) } }
        }
    }
}

@Composable
private fun DownloadingCard(download: DownloadRow, modifier: Modifier, actions: LibraryActions) {
    val colors = HideoutTheme.colors
    val transferring = download.phase as? DownloadPhase.Transferring
    Box(modifier.height(HideoutDimens.LibraryCard.Height).clip(HideoutShapes.Card)
        .hideoutClickable(remember { MutableInteractionSource() }, enabled = download.hasModPage, onClick = { actions.viewPage(download.modId) })) {
        CoverArt(seed = download.modId, modifier = Modifier.fillMaxSize(), shape = HideoutShapes.Card, imageUrl = download.coverFullUrl)
        Box(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(CardScrimFraction)
                .align(Alignment.BottomCenter)
                .background(Brush.verticalGradient(listOf(Color.Transparent, colors.imageScrim))),
        )
        if (download.canCancel) {
            DarkCircleButton(
                HideoutIcons.Close,
                cancelLabel(download),
                Modifier.align(Alignment.TopEnd).padding(HideoutDimens.Card.BadgeInset),
            ) { actions.cancelDownload(download.modId) }
        }
        if (transferring != null) {
            ProgressBar(
                transferring.percent / 100f,
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = HideoutDimens.Downloading.CardBarInset, end = HideoutDimens.Downloading.CardBarInset, bottom = HideoutDimens.Downloading.CardBarBottom)
                    .fillMaxWidth(),
            )
        }
        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(
                    start = HideoutDimens.Card.CaptionHorizontalInset,
                    end = HideoutDimens.Card.CaptionHorizontalInset,
                    bottom = if (transferring != null) HideoutDimens.Downloading.CardBarInset else HideoutDimens.Card.CaptionBottomInset,
                ),
            horizontalArrangement = Arrangement.spacedBy(HideoutDimens.Card.CaptionGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(download.title.orEmpty(), style = HideoutTheme.typography.titleCard, color = colors.onImage, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(download.phase.subtitle(), style = HideoutTheme.typography.subtitle, color = colors.onImageVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (transferring != null) {
                Text("${transferring.percent}%", style = HideoutTheme.typography.mono, color = colors.onImage)
            }
        }
    }
}
