package gg.hideouthq.feature.discover.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import gg.hideouthq.core.designsystem.component.CoverArt
import gg.hideouthq.core.designsystem.component.HideoutIcon
import gg.hideouthq.core.designsystem.component.LinkButton
import gg.hideouthq.core.designsystem.icon.HideoutIcons
import gg.hideouthq.core.designsystem.interaction.collectInteractionStates
import gg.hideouthq.core.designsystem.interaction.focusRing
import gg.hideouthq.core.designsystem.interaction.hideoutClickable
import gg.hideouthq.core.designsystem.theme.HideoutShapes
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import gg.hideouthq.core.designsystem.theme.HideoutWeights
import gg.hideouthq.core.model.CoverTone
import gg.hideouthq.core.model.NewsKind
import gg.hideouthq.core.model.NewsPost
import gg.hideouthq.resources.Res
import gg.hideouthq.resources.deadlock_news
import gg.hideouthq.resources.discover_keyword_news
import gg.hideouthq.resources.discover_news
import gg.hideouthq.resources.discover_news_all
import gg.hideouthq.resources.news_announcement
import gg.hideouthq.resources.news_days_ago
import gg.hideouthq.resources.news_hours_ago
import gg.hideouthq.resources.news_just_now
import gg.hideouthq.resources.news_patch_notes
import gg.hideouthq.resources.news_read
import gg.hideouthq.resources.news_read_post
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import gg.hideouthq.core.designsystem.theme.HideoutDimens.News as Dimens

@Composable
internal fun NewsSection(posts: List<NewsPost>, now: Long, onRead: (NewsPost) -> Unit, onAll: () -> Unit, modifier: Modifier = Modifier) {
    val lead = posts.firstOrNull() ?: return
    Section(
        stringResource(Res.string.discover_news), stringResource(Res.string.discover_keyword_news), HideoutIcons.SectionNews, HideoutTheme.colors.sectionNews, modifier,
        trailing = { LinkButton(stringResource(Res.string.discover_news_all), onAll) },
    ) {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(Dimens.ColumnGap)) {
            LeadPost(lead, now, onRead, Modifier.weight(Dimens.LeadWeight).fillMaxHeight())
            val rest = posts.drop(1).take(ListedPosts)
            if (rest.isNotEmpty()) PostList(rest, now, onRead, Modifier.weight(1f))
        }
    }
}

@Composable
private fun LeadPost(post: NewsPost, now: Long, onRead: (NewsPost) -> Unit, modifier: Modifier) {
    val colors = HideoutTheme.colors
    val type = HideoutTheme.typography
    val source = remember { MutableInteractionSource() }
    val states = source.collectInteractionStates()
    val shape = RoundedCornerShape(Dimens.Radius)
    val description = stringResource(Res.string.news_read_post, post.title)
    Box(
        modifier
            .heightIn(min = Dimens.LeadMinHeight)
            .clip(shape)
            .focusRing(states.focusVisible, shape)
            .hideoutClickable(source, role = Role.Button, onClick = { onRead(post) })
            .semantics { contentDescription = description },
    ) {
        if (post.imageUrl.isNullOrBlank()) {
            Image(painterResource(Res.drawable.deadlock_news), null, Modifier.matchParentSize(), contentScale = ContentScale.Crop)
        } else {
            CoverArt(seed = post.id, modifier = Modifier.matchParentSize(), imageUrl = post.imageUrl, tintIndex = CoverTone.Ember.ordinal)
        }
        Box(
            Modifier
                .matchParentSize()
                .background(Brush.verticalGradient(1f - Dimens.ScrimFraction to Color.Transparent, 1f to colors.imageScrim)),
        )
        Column(
            Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = Dimens.LeadHorizontal, vertical = Dimens.LeadVertical),
            verticalArrangement = Arrangement.spacedBy(Dimens.LeadGap),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.MetaGap), verticalAlignment = Alignment.CenterVertically) {
                KindTag(post.kind)
                Text(age(post.postedAt, now), style = type.subtitle, color = colors.onImageVariant)
            }
            Text(post.title, style = type.titleSection.copy(fontSize = LeadTitleSize, lineHeight = LeadTitleLine), color = colors.onImage, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (post.excerpt.isNotBlank()) {
                Text(post.excerpt, Modifier.widthIn(max = Dimens.ExcerptMaxWidth), style = type.bodyDialog, color = colors.onImageVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Row(Modifier.fillMaxWidth().padding(top = Dimens.FootTop), horizontalArrangement = Arrangement.End) {
                Row(
                    Modifier
                        .height(Dimens.ReadHeight)
                        .background(colors.onImage.copy(alpha = Dimens.ReadFillAlpha), HideoutShapes.Full)
                        .padding(start = Dimens.ReadStart, end = Dimens.ReadEnd),
                    horizontalArrangement = Arrangement.spacedBy(Dimens.ReadGap),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(Res.string.news_read), style = type.labelGroup, color = colors.onImage)
                    HideoutIcon(HideoutIcons.ChevronRight, contentDescription = null, size = Dimens.ReadIcon, tint = colors.onImage)
                }
            }
        }
    }
}

@Composable
internal fun KindTag(kind: NewsKind) {
    val tint = kind.color
    Row(
        Modifier
            .height(Dimens.TagHeight)
            .background(Color.Black.copy(alpha = Dimens.GlassAlpha), HideoutShapes.Full)
            .padding(start = Dimens.TagStart, end = Dimens.TagEnd),
        horizontalArrangement = Arrangement.spacedBy(Dimens.TagGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HideoutIcon(kind.icon, contentDescription = null, size = Dimens.TagIcon, tint = tint)
        Text(kind.label(), style = HideoutTheme.typography.captionCompact.copy(fontWeight = HideoutWeights.Heavy, letterSpacing = TagTracking), color = tint)
    }
}

@Composable
private fun PostList(posts: List<NewsPost>, now: Long, onRead: (NewsPost) -> Unit, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier
            .fillMaxHeight()
            .background(scheme.surfaceContainer, RoundedCornerShape(Dimens.Radius))
            .padding(Dimens.ListPadding),
        verticalArrangement = Arrangement.spacedBy(Dimens.ListGap),
    ) {
        posts.forEach { post -> PostRow(post, now, onRead) }
    }
}

@Composable
private fun PostRow(post: NewsPost, now: Long, onRead: (NewsPost) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val type = HideoutTheme.typography
    val source = remember { MutableInteractionSource() }
    val states = source.collectInteractionStates()
    val shape = RoundedCornerShape(Dimens.RowRadius)
    val tint = post.kind.color
    Row(
        Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = Dimens.RowMinHeight)
            .background(if (states.hovered) scheme.surfaceContainerHigh else scheme.surfaceContainerHigh.copy(alpha = 0f), shape)
            .focusRing(states.focusVisible, shape)
            .hideoutClickable(source, role = Role.Button, onClick = { onRead(post) })
            .padding(start = Dimens.RowStart, end = Dimens.RowEnd, top = Dimens.RowVertical, bottom = Dimens.RowVertical),
        horizontalArrangement = Arrangement.spacedBy(Dimens.RowGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(Dimens.IconTile).background(tint.copy(alpha = Dimens.IconTintAlpha), RoundedCornerShape(Dimens.IconRadius)), contentAlignment = Alignment.Center) {
            HideoutIcon(post.kind.icon, contentDescription = null, size = Dimens.Icon, tint = tint)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Dimens.RowTextGap)) {
            Text(post.kind.label().uppercase(), style = type.badge.copy(letterSpacing = KindTracking), color = scheme.outline)
            Text(post.title, style = type.titleRow, color = scheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(age(post.postedAt, now), Modifier.align(Alignment.Top).padding(top = Dimens.RowTimeTop), style = type.caption, color = scheme.outline)
    }
}

@Composable
internal fun age(postedAt: Long, now: Long): String {
    val seconds = (now - postedAt).coerceAtLeast(0)
    return when {
        seconds < SecondsPerHour -> stringResource(Res.string.news_just_now)
        seconds < SecondsPerDay -> (seconds / SecondsPerHour).toInt().let { pluralStringResource(Res.plurals.news_hours_ago, it, it) }
        else -> (seconds / SecondsPerDay).toInt().let { pluralStringResource(Res.plurals.news_days_ago, it, it) }
    }
}

internal val NewsKind.icon: ImageVector
    get() = when (this) {
        NewsKind.PatchNotes -> HideoutIcons.PatchNotes
        NewsKind.Announcement -> HideoutIcons.Announcement
    }

internal val NewsKind.color: Color
    @Composable get() = when (this) {
        NewsKind.PatchNotes -> HideoutTheme.colors.newsPatch
        NewsKind.Announcement -> HideoutTheme.colors.newsAnnouncement
    }

@Composable
internal fun NewsKind.label(): String = stringResource(if (this == NewsKind.PatchNotes) Res.string.news_patch_notes else Res.string.news_announcement)

private const val ListedPosts = 4
private const val SecondsPerHour = 3_600L
private const val SecondsPerDay = 86_400L
private val LeadTitleSize = 24.sp
private val LeadTitleLine = 27.6.sp
private val TagTracking = 0.03.em
private val KindTracking = 0.06.em
