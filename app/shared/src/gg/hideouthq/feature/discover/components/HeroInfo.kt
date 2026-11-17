package gg.hideouthq.feature.discover.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.unit.Dp
import gg.hideouthq.core.data.InstallStatus
import gg.hideouthq.core.designsystem.component.AvatarChip
import gg.hideouthq.core.designsystem.component.AvatarFill
import gg.hideouthq.core.designsystem.component.BalancedText
import gg.hideouthq.core.designsystem.component.ButtonSize
import gg.hideouthq.core.designsystem.component.ButtonVariant
import gg.hideouthq.core.designsystem.component.Eyebrow
import gg.hideouthq.core.designsystem.component.HideoutButton
import gg.hideouthq.core.designsystem.component.InstallButton
import gg.hideouthq.core.designsystem.component.ShapedAvatar
import gg.hideouthq.core.designsystem.icon.HideoutIcons
import gg.hideouthq.core.designsystem.shape.Cookie
import gg.hideouthq.core.designsystem.theme.HideoutDimens
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import gg.hideouthq.core.designsystem.theme.HideoutWeights
import gg.hideouthq.core.designsystem.theme.cssLineHeight
import gg.hideouthq.core.ui.ModLabels
import gg.hideouthq.core.ui.boldValue
import gg.hideouthq.core.ui.catalog.CardTransition.title
import gg.hideouthq.core.ui.rememberInstallOrigin
import gg.hideouthq.feature.discover.DiscoverActions
import gg.hideouthq.feature.discover.FeaturedMod
import gg.hideouthq.resources.Res
import gg.hideouthq.resources.discover_by_author
import gg.hideouthq.resources.discover_eyebrow
import gg.hideouthq.resources.discover_preview_3d
import gg.hideouthq.resources.discover_stat_downloads
import gg.hideouthq.resources.discover_stat_updated
import gg.hideouthq.resources.install_cancel
import gg.hideouthq.resources.install_cancel_named
import org.jetbrains.compose.resources.stringResource

private val HeroOnImageSecondary = Color.White.copy(alpha = 0.72f)
private val HeroOnImageStat = Color.White.copy(alpha = 0.66f)

@Composable
internal fun HeroInfo(featured: FeaturedMod, actions: DiscoverActions, cardWidth: Dp, modifier: Modifier = Modifier) {
    val motion = HideoutTheme.motion
    fun fadeSpec(staggerIndex: Int) = fadeIn(motion.enterFade(motion.staggerDelay(staggerIndex))) togetherWith fadeOut(motion.exitFade())

    Column(modifier, horizontalAlignment = Alignment.Start) {
        AnimatedContent(featured, contentKey = { it.item.id }, transitionSpec = { fadeSpec(0) }, label = "heroEyebrow") {
            Eyebrow(stringResource(Res.string.discover_eyebrow), color = HeroOnImageSecondary)
        }
        AnimatedContent(
            targetState = featured,
            contentKey = { it.item.id },
            transitionSpec = { fadeSpec(1) using SizeTransform(clip = false) },
            label = "heroTitle",
        ) { shown ->
            Column {
                BalancedText(
                    text = shown.item.title,
                    style = heroTitleStyle(cardWidth),
                    color = Color.White,
                    modifier = Modifier.padding(top = HideoutDimens.Hero.TitleTopGap),
                    textModifier = Modifier.title(shown.item.id),
                )
                HeroMeta(shown, Modifier.padding(top = HideoutDimens.Hero.MetaTopGap))
            }
        }
        AnimatedContent(
            featured,
            contentKey = { it.item.id },
            transitionSpec = { fadeSpec(2) },
            modifier = Modifier.padding(top = HideoutDimens.Hero.ActionsTopGap),
            label = "heroActions",
        ) { shown -> HeroActions(shown, actions) }
        AnimatedContent(
            featured,
            contentKey = { it.item.id },
            transitionSpec = { fadeSpec(3) },
            modifier = Modifier.padding(top = HideoutDimens.Hero.StatsTopGap),
            label = "heroStats",
        ) { shown -> HeroStats(shown) }
    }
}

@Composable
private fun heroTitleStyle(cardWidth: Dp) = with(LocalDensity.current) {
    val size = (cardWidth * HideoutDimens.Hero.TitleCardFraction).coerceIn(HideoutDimens.Hero.TitleMinSize, HideoutDimens.Hero.TitleMaxSize).toSp()
    HideoutTheme.typography.displayHero.copy(fontSize = size, lineHeight = cssLineHeight(size, HideoutDimens.Hero.TitleLineHeight))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HeroMeta(featured: FeaturedMod, modifier: Modifier) {
    val item = featured.item
    val style = HideoutTheme.typography.body
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(HideoutDimens.Hero.MetaGap),
        verticalArrangement = Arrangement.spacedBy(HideoutDimens.Hero.MetaGap),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        val hero = item.heroName
        if (hero != null) {
            AvatarChip(hero, container = HideoutTheme.colors.glassOnHero) {
                ShapedAvatar(
                    initials = hero.take(2),
                    shape = Cookie.Eight.shape,
                    fill = AvatarFill.Highlight,
                    size = HideoutDimens.Hero.ChipAvatar,
                    textStyle = HideoutTheme.typography.initialsChip,
                    imageUrl = item.heroIconUrl,
                )
            }
        }
        Text(ModLabels.singular(item.category), style = style, color = HeroOnImageSecondary)
        item.author?.let {
            Text(ModLabels.Separator.trim(), style = style, color = HeroOnImageSecondary)
            Text(stringResource(Res.string.discover_by_author, it), style = style, color = HeroOnImageSecondary)
        }
    }
}

@Composable
private fun HeroActions(featured: FeaturedMod, actions: DiscoverActions) {
    val id = featured.item.id
    val origin = rememberInstallOrigin(id)
    Column {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(HideoutDimens.Button.Gap),
            verticalArrangement = Arrangement.spacedBy(HideoutDimens.Button.Gap),
        ) {
            InstallButton(
                state = ModLabels.installButton(featured.install),
                labels = ModLabels.install(featured.install),
                onClick = if (ModLabels.installButtonEnabled(featured.install)) ({ origin.claim(); actions.install(id) }) else null,
                installedVariant = ButtonVariant.GlassOnHero,
                contentPadding = PaddingValues(start = HideoutDimens.Hero.InstallStartPadding, end = HideoutDimens.Hero.InstallEndPadding),
                modifier = origin.modifier,
            )
            if (featured.install.gettingFiles) {
                val named = stringResource(Res.string.install_cancel_named, featured.item.title)
                HideoutButton(
                    text = stringResource(Res.string.install_cancel),
                    onClick = { actions.cancelInstall(id) },
                    variant = ButtonVariant.GlassOnHero,
                    size = ButtonSize.Large,
                    modifier = Modifier.semantics { contentDescription = named },
                )
            }
            if (featured.item.has3dPreview) {
                HideoutButton(
                    text = stringResource(Res.string.discover_preview_3d),
                    onClick = { actions.previewMod(id) },
                    variant = ButtonVariant.GlassOnHero,
                    size = ButtonSize.Large,
                    leadingIcon = HideoutIcons.Cube,
                    contentPadding = PaddingValues(start = HideoutDimens.Hero.PreviewStartPadding, end = HideoutDimens.Hero.PreviewEndPadding),
                )
            }
        }
        ModLabels.installWait(featured.install)?.let { wait ->
            Text(wait, style = HideoutTheme.typography.caption, color = HeroOnImageSecondary, modifier = Modifier.padding(top = HideoutDimens.Button.Gap))
        }
    }
}

private val InstallStatus.gettingFiles: Boolean
    get() = this == InstallStatus.Preparing || this is InstallStatus.Busy || this == InstallStatus.Waiting || this is InstallStatus.Downloading

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HeroStats(featured: FeaturedMod) {
    val downloads = featured.item.downloads?.let(ModLabels::count)
    val updated = ModLabels.dayMonth(featured.updated)
    val stats = listOfNotNull(
        downloads?.let { stringResource(Res.string.discover_stat_downloads, it) to it },
        stringResource(Res.string.discover_stat_updated, updated) to updated,
    )
    val strong = SpanStyle(color = Color.White, fontWeight = HideoutWeights.Title)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(HideoutDimens.Hero.StatsGap)) {
        stats.forEach { (text, value) ->
            Text(boldValue(text, value, strong), style = HideoutTheme.typography.captionSmall, color = HeroOnImageStat)
        }
    }
}

