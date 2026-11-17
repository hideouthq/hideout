package gg.hideouthq.feature.customgames

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import gg.hideouthq.core.customgames.CustomGames
import gg.hideouthq.core.customgames.HowToPlay
import gg.hideouthq.core.customgames.Install
import gg.hideouthq.core.customgames.LegendEntry
import gg.hideouthq.core.customgames.SectionKind
import gg.hideouthq.core.customgames.StepIcon
import gg.hideouthq.core.customgames.download
import gg.hideouthq.core.designsystem.component.BackLink
import gg.hideouthq.core.designsystem.component.ButtonVariant
import gg.hideouthq.core.designsystem.component.HideoutButton
import gg.hideouthq.core.designsystem.component.HideoutIcon
import gg.hideouthq.core.designsystem.component.ScrollColumn
import gg.hideouthq.core.designsystem.component.isScrolled
import gg.hideouthq.core.designsystem.icon.HideoutIcons
import gg.hideouthq.core.designsystem.image.LocalRemoteImages
import gg.hideouthq.core.designsystem.interaction.collectInteractionStates
import gg.hideouthq.core.designsystem.interaction.focusRing
import gg.hideouthq.core.designsystem.interaction.hideoutClickable
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import gg.hideouthq.core.designsystem.theme.HideoutWeights
import gg.hideouthq.core.model.CoverImage
import gg.hideouthq.core.ui.ImageViewer
import gg.hideouthq.core.ui.PaneScaffold
import gg.hideouthq.core.ui.SearchTopBar
import gg.hideouthq.core.ui.WindowModal
import gg.hideouthq.core.ui.transition.staggeredEnter
import gg.hideouthq.resources.*
import org.jetbrains.compose.resources.stringResource
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun CustomGameModeRoute(games: CustomGames, key: String, modal: WindowModal, onBack: () -> Unit, onSearch: (String) -> Unit) {
    val modes by games.modes.collectAsStateWithLifecycle()
    val live by games.live.collectAsStateWithLifecycle()
    val restarts by games.restartsDeadlock.collectAsStateWithLifecycle()
    val today = remember { LocalDate.now() }
    val card = remember(modes, key, today) { modeCard(modes, key, today) }
    val block = hostBlock(live.session)
    var hosting by rememberSaveable { mutableStateOf(false) }
    val scroll = rememberScrollState()
    PaneScaffold(topBar = { SearchTopBar("", onSearch, stringResource(Res.string.search_catalogue)) }, scrolled = scroll.isScrolled, staggered = true) {
        ScrollColumn(
            Modifier.fillMaxSize(), state = scroll,
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 2.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Crumbs(card?.mode?.listing?.name, onBack, Modifier.staggeredEnter(0))
            if (card == null) {
                Gone(onBack, Modifier.staggeredEnter(1))
            } else {
                Banner(card, block, { hosting = true }, Modifier.staggeredEnter(1))
                Body(card, modal, Modifier.staggeredEnter(2))
            }
        }
    }
    HostDialog(card?.mode?.takeIf { hosting }, modes, block, restarts, games, modal) { hosting = false }
}

@Composable
private fun Crumbs(name: String?, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val type = HideoutTheme.typography
    Row(modifier.height(36.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        BackLink(stringResource(Res.string.nav_custom_games), onBack)
        if (name != null) {
            Text(stringResource(Res.string.breadcrumb_separator), style = type.caption.copy(fontSize = 13.sp), color = scheme.outline)
            Text(name, style = type.caption.copy(fontSize = 13.sp), color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun Gone(onBack: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(top = 48.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(Res.string.cg_mode_gone), style = HideoutTheme.typography.bodyStrong, color = MaterialTheme.colorScheme.onSurfaceVariant)
        HideoutButton(stringResource(Res.string.cg_back_to_library), onBack, variant = ButtonVariant.Tonal)
    }
}

@Composable
private fun Banner(card: ModeCard.Hostable, block: HostBlock?, onHost: () -> Unit, modifier: Modifier = Modifier) {
    val listing = card.mode.listing
    val art = listing.art
    Box(modifier.fillMaxWidth().height(320.dp)) {
        BoxWithConstraints(Modifier.fillMaxSize().clip(BannerShape)) {
            ModeArtSlot(card.mode.id.value, art.backdrop, Modifier.fillMaxSize(), BannerShape, glyph = null)
            if (art.layered) {
                Box(Modifier.fillMaxSize().drawBehind { drawRect(Brush.horizontalGradient(0f to Shade.copy(alpha = 0.94f), 0.30f to Shade.copy(alpha = 0.80f), 0.48f to Shade.copy(alpha = 0f))) })
                ArtLayer(art.render, Modifier.align(Alignment.TopEnd).offset(x = -RenderFromRight, y = 20.dp).size(260.dp, 300.dp))
            } else {
                art.render?.let { render ->
                    ModeArtSlot(
                        card.mode.id.value, render,
                        Modifier.align(Alignment.BottomEnd).padding(end = maxWidth * 0.1f).width(maxWidth * 0.22f).fillMaxHeight(0.9f),
                        RoundedCornerShape(0.dp), glyph = null,
                    )
                }
                Box(Modifier.fillMaxSize().drawBehind { drawRect(Brush.horizontalGradient(0f to Shade.copy(alpha = 0.94f), 0.36f to Shade.copy(alpha = 0.78f), 0.66f to Shade.copy(alpha = 0f))) })
            }
            BannerText(card, block, onHost)
        }
        if (art.layered) ArtLayer(art.overlay, Modifier.align(Alignment.TopEnd).offset(x = 20.dp, y = (-40).dp).size(400.dp, 220.dp))
    }
}

@Composable
private fun BoxScope.BannerText(card: ModeCard.Hostable, block: HostBlock?, onHost: () -> Unit) {
    val listing = card.mode.listing
    val glass = HideoutTheme.colors
    Column(Modifier.align(Alignment.BottomStart).padding(start = 32.dp, bottom = 30.dp, end = 32.dp).widthIn(max = 470.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            GlassChip(HideoutIcons.Users, playersText(listing))
            minutesText(listing, about = true)?.let { GlassChip(HideoutIcons.Clock, it) }
        }
        Text(
            listing.name,
            style = HideoutTheme.typography.displayHero.copy(fontSize = 46.sp, lineHeight = 47.sp, fontWeight = HideoutWeights.Title, letterSpacing = (-0.025).em),
            color = glass.onImage, maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
        Text(
            listing.summary ?: listing.description,
            style = HideoutTheme.typography.body.copy(fontSize = 15.sp, lineHeight = 22.5.sp), color = glass.onImage.copy(alpha = 0.78f),
            maxLines = 3, overflow = TextOverflow.Ellipsis,
        )
        Blocked(block?.let { blockText(it) }, Modifier.padding(top = 4.dp)) {
            HideoutButton(stringResource(Res.string.cg_host_a_game), onHost, leadingIcon = HideoutIcons.Play, leadingIconSize = 16.dp, enabled = block == null)
        }
    }
}

@Composable
private fun GlassChip(icon: ImageVector, text: String) {
    val onImage = HideoutTheme.colors.onImage
    Row(
        Modifier.height(28.dp).background(onImage.copy(alpha = 0.12f), CircleShape).padding(horizontal = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HideoutIcon(icon, null, size = 14.dp, tint = onImage)
        Text(text, style = HideoutTheme.typography.caption.copy(fontSize = 12.sp, fontWeight = HideoutWeights.Label), color = onImage, maxLines = 1)
    }
}

@Composable
private fun Body(card: ModeCard.Hostable, modal: WindowModal, modifier: Modifier = Modifier) {
    val listing = card.mode.listing
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (listing.howToPlay.isNotEmpty() || listing.art.explainer != null) {
                Section(stringResource(Res.string.cg_how_to_play)) {
                    listing.art.explainer?.let { Explainer(card.mode.id.value, it) }
                    if (listing.legend.isNotEmpty()) Legend(listing.legend)
                    if (listing.howToPlay.isNotEmpty()) {
                        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            listing.howToPlay.forEachIndexed { index, rule -> Rule(index + 1, rule, Modifier.weight(1f).fillMaxHeight()) }
                        }
                    }
                }
            }
            if (listing.art.screenshots.isNotEmpty()) {
                Section(stringResource(Res.string.cg_screenshots)) { Screenshots(card.mode.id.value, listing.art.screenshots, modal) }
            }
            if (listing.whatsNew.isNotEmpty()) {
                Section(stringResource(Res.string.cg_whats_new, card.mode.version), trailing = listing.released?.let(::dayText)) {
                    Column(Modifier.padding(start = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        listing.whatsNew.forEach { line ->
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("•", style = proseStyle(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(line, style = proseStyle(), color = MaterialTheme.colorScheme.onSurface)
                            }
                        }
                    }
                }
            }
        }
        Facts(card, Modifier.width(360.dp))
    }
}

@Composable
internal fun ColumnScope.Screenshots(seed: String, urls: List<String>, modal: WindowModal) {
    var viewing by remember(urls) { mutableStateOf<Int?>(null) }
    urls.withIndex().chunked(3).forEach { row ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            row.forEach { (index, url) -> Screenshot(seed, url, stringResource(Res.string.cg_screenshot, index + 1, urls.size), Modifier.weight(1f)) { viewing = index } }
            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
    viewing?.let { index ->
        val images = remember(urls) { urls.map { CoverImage(it) } }
        ImageViewer(seed, images, index, onIndexChange = { viewing = it }, onDismiss = { viewing = null }, modal = modal)
    }
}

@Composable
private fun Screenshot(seed: String, url: String, description: String, modifier: Modifier, onOpen: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val focused = source.collectInteractionStates().focusVisible
    ModeArtSlot(
        seed, url,
        modifier
            .aspectRatio(16f / 9f)
            .focusRing(focused, ScreenshotShape)
            .semantics { contentDescription = description }
            .hideoutClickable(source, onClick = onOpen),
        ScreenshotShape, glyph = null,
    )
}

@Composable
private fun proseStyle() = HideoutTheme.typography.body.copy(fontSize = 14.sp, lineHeight = 21.7.sp)

@Composable
private fun Section(title: String, trailing: String? = null, content: @Composable ColumnScope.() -> Unit) {
    val scheme = MaterialTheme.colorScheme
    CgCard(gap = 16.dp) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = HideoutTheme.typography.titleCard.copy(fontSize = 16.sp, fontWeight = HideoutWeights.Title), color = scheme.onSurface)
            trailing?.let { Text(it, style = HideoutTheme.typography.caption.copy(fontSize = 13.sp), color = scheme.outline) }
        }
        content()
    }
}

@Composable
private fun Explainer(seed: String, url: String) {
    val shape = RoundedCornerShape(18.dp)
    val image = LocalRemoteImages.current.rememberImage(url, ContentScale.Fit)
    val size = image?.takeIf { it.loaded }?.painter?.intrinsicSize?.takeIf { it.isSpecified && it.width > 0 && it.height > 0 }
    if (image == null || size == null) {
        ModeArtSlot(seed, url, Modifier.fillMaxWidth().height(230.dp), shape, glyph = 48.dp, glyphAlignment = Alignment.Center)
    } else {
        Image(image.painter, null, Modifier.fillMaxWidth().aspectRatio(size.width / size.height).clip(shape).then(image.layoutModifier), contentScale = ContentScale.Fit)
    }
}

@Composable
private fun Legend(entries: List<LegendEntry>) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        entries.forEach { entry ->
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).background(Color(0xFF000000.toInt() or entry.color), RoundedCornerShape(3.dp)))
                Text(entry.label, style = HideoutTheme.typography.caption.copy(fontSize = 13.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun Rule(number: Int, rule: HowToPlay, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val type = HideoutTheme.typography
    val accent = HideoutTheme.colors.sectionCustomGames
    Column(modifier.background(scheme.surfaceContainerHigh, RoundedCornerShape(18.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(32.dp).background(scheme.surfaceContainerHighest, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                when (val icon = rule.icon) {
                    null -> Text(number.toString(), style = type.labelPill.copy(fontWeight = FontWeight(650)), color = accent)
                    else -> HideoutIcon(icon.vector, null, size = 18.dp, tint = accent)
                }
            }
            Text(rule.title, style = type.bodyStrong.copy(fontSize = 14.sp, fontWeight = HideoutWeights.Title), color = scheme.onSurface)
        }
        Text(rule.body, style = type.caption.copy(fontSize = 12.5.sp, lineHeight = 18.sp), color = scheme.onSurfaceVariant)
    }
}

private val StepIcon.vector: ImageVector
    get() = when (this) {
        StepIcon.Hook -> HideoutIcons.Hook
        StepIcon.Souls -> HideoutIcons.Souls
        StepIcon.Trophy -> HideoutIcons.Trophy
    }

@Composable
private fun Facts(card: ModeCard.Hostable, modifier: Modifier = Modifier) {
    val listing = card.mode.listing
    val settingsNote = stringResource(Res.string.cg_fact_settings_note)
    CgCard(modifier, gap = 0.dp) {
        val rows = buildList<@Composable () -> Unit> {
            listing.facts.forEach { fact -> add { Fact(fact.label, fact.value, fact.note) } }
            listing.settings.forEach { setting ->
                add { Fact(setting.label, setting.options.first { it.value == setting.default }.label, settingsNote) }
            }
            add { Fact(stringResource(Res.string.cg_fact_players), playersText(listing)) }
            minutesText(listing, about = true)?.let { add { Fact(stringResource(Res.string.cg_fact_length), it) } }
            add { Fact(stringResource(Res.string.cg_fact_version), card.mode.version, listing.released?.let { stringResource(Res.string.cg_fact_updated, dayText(it)) }) }
            add { DownloadFact(card) }
            if (listing.creators.isNotEmpty()) add { MadeBy(listing.creators.joinToString(", "), listing.curated) }
            listing.sections.forEach { section ->
                add {
                    when (section) {
                        SectionKind.Scoreboard -> Fact(stringResource(Res.string.cg_fact_live_score), stringResource(Res.string.cg_fact_live_score_note))
                    }
                }
            }
        }
        rows.forEachIndexed { index, row ->
            if (index > 0) HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
            row()
        }
    }
}

@Composable
private fun DownloadFact(card: ModeCard.Hostable) {
    val key = stringResource(Res.string.cg_fact_download)
    when {
        card.folder -> Fact(stringResource(Res.string.cg_fact_folder), stringResource(Res.string.cg_badge_folder))
        card.install is Install.Installed -> Fact(key, stringResource(Res.string.cg_mb, megabytes(card.install.record.bytes)), stringResource(Res.string.cg_fact_installed_note))
        else -> card.mode.download?.let { Fact(key, stringResource(Res.string.cg_mb, megabytes(it)), stringResource(Res.string.cg_fact_download_note)) }
    }
}

@Composable
private fun MadeBy(creators: String, curated: Boolean) {
    FactRow(stringResource(Res.string.cg_fact_made_by)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (curated) HideoutIcon(HideoutIcons.Verified, null, size = 15.dp, tint = HideoutTheme.colors.sectionCustomGames)
            Text(creators, style = factValue(), color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
private fun Fact(key: String, value: String, note: String? = null) {
    FactRow(key) {
        Text(value, style = factValue(), color = MaterialTheme.colorScheme.onSurface)
        note?.let { Text(it, Modifier.padding(top = 2.dp), style = HideoutTheme.typography.caption.copy(fontSize = 12.sp, lineHeight = 16.sp), color = MaterialTheme.colorScheme.outline) }
    }
}

@Composable
private fun FactRow(key: String, value: @Composable ColumnScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 11.dp)) {
        Text(key, Modifier.width(110.dp).padding(end = 12.dp), style = HideoutTheme.typography.caption.copy(fontSize = 12.5.sp, fontWeight = HideoutWeights.Label, lineHeight = 19.sp), color = MaterialTheme.colorScheme.outline)
        Column(Modifier.weight(1f), content = value)
    }
}

@Composable
private fun factValue() = HideoutTheme.typography.body.copy(fontSize = 13.5.sp, lineHeight = 19.sp)

private fun dayText(date: LocalDate): String = date.format(DayFormat)

private val DayFormat = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)
private val BannerShape = RoundedCornerShape(28.dp)
private val ScreenshotShape = RoundedCornerShape(16.dp)
private val Shade = Color(0xFF0A0D0F)
private val RenderFromRight = 308.dp
