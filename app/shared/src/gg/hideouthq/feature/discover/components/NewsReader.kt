package gg.hideouthq.feature.discover.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import gg.hideouthq.core.designsystem.component.ButtonVariant
import gg.hideouthq.core.designsystem.component.CoverArt
import gg.hideouthq.core.designsystem.component.HideoutButton
import gg.hideouthq.core.designsystem.icon.HideoutIcons
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import gg.hideouthq.core.model.NewsPost
import gg.hideouthq.core.ui.AlertModal
import gg.hideouthq.core.ui.WindowModal
import gg.hideouthq.resources.Res
import gg.hideouthq.resources.news_close
import gg.hideouthq.resources.news_open_on_steam
import org.jetbrains.compose.resources.stringResource
import gg.hideouthq.core.designsystem.theme.HideoutDimens.News as Dimens

@Composable
internal fun NewsReader(post: NewsPost?, now: Long, modal: WindowModal, onOpen: (NewsPost) -> Unit, onDismiss: () -> Unit) {
    var last by remember { mutableStateOf(post) }
    if (post != null) last = post
    val close = remember { FocusRequester() }
    AlertModal(visible = post != null, modal = modal, title = last?.title.orEmpty(), onDismiss = onDismiss, initialFocus = close, width = Dimens.ReaderWidth) {
        val shown = last ?: return@AlertModal
        val scheme = MaterialTheme.colorScheme
        val type = HideoutTheme.typography
        Column(verticalArrangement = Arrangement.spacedBy(Dimens.ReaderGap)) {
            shown.imageUrl?.let { url ->
                CoverArt(seed = shown.id, modifier = Modifier.fillMaxWidth().height(Dimens.ReaderImageHeight).clip(RoundedCornerShape(Dimens.Radius)), imageUrl = url)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.MetaGap), verticalAlignment = Alignment.CenterVertically) {
                KindTag(shown.kind)
                Text(age(shown.postedAt, now), style = type.subtitle, color = scheme.outline)
            }
            Text(shown.title, Modifier.semantics { heading() }, style = type.titleDialog, color = scheme.onSurface)
            Column(
                Modifier.heightIn(max = Dimens.ReaderBodyMaxHeight).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Dimens.ReaderParagraphGap),
            ) {
                shown.body.forEach { paragraph -> Text(paragraph, style = type.bodyDialog, color = scheme.onSurfaceVariant) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Dimens.MetaGap, Alignment.End)) {
                HideoutButton(stringResource(Res.string.news_close), onDismiss, Modifier.focusRequester(close), variant = ButtonVariant.Text)
                HideoutButton(stringResource(Res.string.news_open_on_steam), { onOpen(shown) }, leadingIcon = HideoutIcons.OpenExternal)
            }
        }
    }
}
