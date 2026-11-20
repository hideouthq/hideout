package gg.hideouthq.feature.moddetail.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import gg.hideouthq.core.ui.windowDragArea
import gg.hideouthq.core.ui.windowControlsInset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import gg.hideouthq.core.designsystem.component.AuthorRow
import gg.hideouthq.core.designsystem.component.ChipTone
import gg.hideouthq.core.designsystem.component.Eyebrow
import gg.hideouthq.core.designsystem.component.HideoutDivider
import gg.hideouthq.core.designsystem.component.ScrollColumn
import gg.hideouthq.core.designsystem.component.StatusChip
import gg.hideouthq.core.designsystem.icon.HideoutIcons
import gg.hideouthq.core.designsystem.theme.HideoutDimens
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import gg.hideouthq.core.model.Author
import gg.hideouthq.core.model.ModDetail
import gg.hideouthq.core.ui.ModLabels
import gg.hideouthq.core.ui.transition.staggeredEnter
import gg.hideouthq.core.ui.catalog.CardTransition.title
import gg.hideouthq.feature.moddetail.ModHeader
import gg.hideouthq.feature.moddetail.Need
import gg.hideouthq.core.designsystem.component.AuthorStack
import gg.hideouthq.core.designsystem.component.AvatarFill
import gg.hideouthq.core.designsystem.component.ShapedAvatar
import gg.hideouthq.resources.Res
import gg.hideouthq.resources.authors_more
import gg.hideouthq.resources.authors_show_all
import gg.hideouthq.resources.detail_not_on_patch
import gg.hideouthq.resources.detail_works_on_patch
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun DetailSidePanel(
    header: ModHeader,
    detail: ModDetail?,
    sizeBytes: Long?,
    installedFiles: Set<Long>,
    checked: Boolean,
    needs: List<Need>,
    canInstall: Boolean,
    onOpenNeed: (Need) -> Unit,
    onInstallNeed: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var changelog by remember(header.modId) { mutableStateOf(false) }
    val updates = detail?.updates.orEmpty()
    val motion = HideoutTheme.motion
    Column(modifier.fillMaxSize()) {
        PanelHeader(header, Modifier.staggeredEnter(0))
        AnimatedContent(
            targetState = changelog && updates.isNotEmpty(),
            modifier = Modifier.weight(1f),
            transitionSpec = {
                val forward = if (targetState) 1 else -1
                val travel: (Int) -> Int = { if (motion.reduced) 0 else it * forward }
                (slideInHorizontally(motion.spatialDefault()) { travel(it) } + fadeIn(motion.effectsDefault())) togetherWith
                    (slideOutHorizontally(motion.spatialDefault()) { -travel(it) } + fadeOut(motion.effectsFast()))
            },
            label = "sidePanelChangelog",
        ) { open ->
            if (open) {
                Changelog(updates, detail?.updatesUrl, installedFiles, onBack = { changelog = false }, Modifier.fillMaxSize())
            } else {
                PanelBody(header, detail, sizeBytes, checked, onWhatsNew = { changelog = true }, Modifier.fillMaxSize()) {
                    if (needs.isNotEmpty()) Needs(needs, canInstall, onOpenNeed, onInstallNeed, Modifier.staggeredEnter(4))
                }
            }
        }
    }
}

@Composable
private fun PanelHeader(header: ModHeader, modifier: Modifier) {
    val endInset = windowControlsInset()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(HideoutDimens.SidePanel.HeaderHeight)
            .windowDragArea()
            .padding(
                start = HideoutDimens.SidePanel.HeaderStartPadding,
                end = if (endInset > 0.dp) endInset else HideoutDimens.SidePanel.HeaderEndPadding,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(modifier, horizontalArrangement = Arrangement.spacedBy(HideoutDimens.Detail.EyebrowHeroIconGap), verticalAlignment = Alignment.CenterVertically) {
            header.heroIconUrl?.let { url ->
                ShapedAvatar(
                    initials = "",
                    shape = CircleShape,
                    fill = AvatarFill.Neutral,
                    size = HideoutDimens.Detail.EyebrowHeroIcon,
                    textStyle = HideoutTheme.typography.eyebrowSmall,
                    imageUrl = url,
                )
            }
            Eyebrow(
                text = ModLabels.join(ModLabels.singular(header.category), header.heroName ?: header.kind),
                style = HideoutTheme.typography.eyebrowSmall,
            )
        }
    }
}

@Composable
private fun PanelBody(
    header: ModHeader,
    detail: ModDetail?,
    sizeBytes: Long?,
    checked: Boolean,
    onWhatsNew: () -> Unit,
    modifier: Modifier,
    extra: @Composable () -> Unit,
) {
    ScrollColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = HideoutDimens.SidePanel.Padding,
            end = HideoutDimens.SidePanel.Padding,
            bottom = HideoutDimens.SidePanel.Padding,
        ),
        verticalArrangement = Arrangement.spacedBy(HideoutDimens.SidePanel.BlockGap),
    ) {
        Title(header, Modifier.staggeredEnter(0))
        if (detail != null && detail.authors.isNotEmpty()) Authors(detail.authors, Modifier.staggeredEnter(1))
        PatchChip(header.worksOnCurrentPatch, checked, Modifier.staggeredEnter(2))
        HideoutDivider(Modifier.staggeredEnter(4))
        val latest = detail?.updates?.firstOrNull()
        DetailsBlock(header, detail?.version, versionShown = latest?.version != null, sizeBytes, Modifier.staggeredEnter(4))
        latest?.let { WhatsNewRow(it, onWhatsNew, Modifier.staggeredEnter(4)) }
        extra()
    }
}

@Composable
private fun Title(header: ModHeader, modifier: Modifier) {
    Text(
        text = header.title,
        style = HideoutTheme.typography.headlineDetail,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier
            .title(header.modId)
            .semantics { heading() },
    )
}

@Composable
private fun PatchChip(works: Boolean, checked: Boolean, modifier: Modifier) {
    when {
        works -> StatusChip(stringResource(Res.string.detail_works_on_patch), ChipTone.Good, modifier, leadingIcon = HideoutIcons.Check)
        checked -> StatusChip(stringResource(Res.string.detail_not_on_patch), ChipTone.Neutral, modifier, leadingIcon = HideoutIcons.Info)
        else -> StatusChip(stringResource(Res.string.detail_not_on_patch), ChipTone.Warning, modifier, leadingIcon = HideoutIcons.Warning)
    }
}

@Composable
internal fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = HideoutTheme.typography.labelSection,
        color = MaterialTheme.colorScheme.outline,
        modifier = modifier.semantics { heading() },
    )
}

@Composable
private fun Authors(authors: List<Author>, modifier: Modifier) {
    var expanded by remember(authors) { mutableStateOf(false) }
    val shown = if (expanded || authors.size <= AuthorsShown + 1) authors else authors.take(AuthorsShown)
    val rest = authors.drop(shown.size)
    Column(
        modifier.animateContentSize(HideoutTheme.motion.spatialDefault()),
        verticalArrangement = Arrangement.spacedBy(HideoutDimens.Author.ListGap),
    ) {
        shown.forEachIndexed { index, author -> AuthorRow(author.name, author.role, index) }
        if (rest.isNotEmpty()) {
            val faces = rest.take(StackedAvatars)
            AuthorStack(
                names = faces.map { it.name },
                moreLabel = (rest.size - faces.size).takeIf { it > 0 }?.let { stringResource(Res.string.authors_more, it) },
                description = stringResource(Res.string.authors_show_all, authors.size),
                onClick = { expanded = true },
            )
        }
    }
}

private const val AuthorsShown = 3
private const val StackedAvatars = 3
