package gg.hideouthq.feature.stats.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import gg.hideouthq.core.data.Loadable
import gg.hideouthq.core.designsystem.component.HideoutIcon
import gg.hideouthq.core.designsystem.component.LinkButton
import gg.hideouthq.core.designsystem.icon.HideoutIcons
import gg.hideouthq.core.designsystem.image.LocalRemoteImages
import gg.hideouthq.core.designsystem.interaction.collectInteractionStates
import gg.hideouthq.core.designsystem.interaction.focusRing
import gg.hideouthq.core.designsystem.interaction.hideoutClickable
import gg.hideouthq.core.designsystem.shape.Cookie
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import gg.hideouthq.feature.stats.Change
import gg.hideouthq.feature.stats.ChangeUnit
import gg.hideouthq.feature.stats.HeroVisual
import gg.hideouthq.feature.stats.MatchRowUi
import gg.hideouthq.feature.stats.PlayerVisual
import gg.hideouthq.feature.stats.StatsFormat
import gg.hideouthq.feature.stats.Tint
import gg.hideouthq.resources.Res
import gg.hideouthq.resources.stats_couldnt_load
import gg.hideouthq.resources.stats_kda_value
import gg.hideouthq.resources.stats_percent_change
import gg.hideouthq.resources.stats_points
import gg.hideouthq.resources.stats_retry
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun StatsCard(modifier: Modifier = Modifier, contentPadding: PaddingValues = PaddingValues(start = 8.dp, top = 6.dp, end = 8.dp, bottom = 8.dp), content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .clip(StatsShapes.Card)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(contentPadding),
        content = content,
    )
}

@Composable
internal fun SectionNote(text: String, onRetry: (() -> Unit)? = null, modifier: Modifier = Modifier.padding(12.dp)) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = statsType().caption, color = MaterialTheme.colorScheme.outline, modifier = Modifier.weight(1f, fill = false))
        if (onRetry != null) LinkButton(stringResource(Res.string.stats_retry), onRetry)
    }
}

@Composable
internal fun <T> Fetched(
    state: Loadable<T>,
    onRetry: () -> Unit,
    loading: @Composable () -> Unit,
    noteModifier: Modifier = Modifier.padding(12.dp),
    ready: @Composable (T) -> Unit,
) {
    when (state) {
        Loadable.Loading -> loading()
        is Loadable.Failed -> SectionNote(stringResource(Res.string.stats_couldnt_load), onRetry, noteModifier)
        is Loadable.Ready -> ready(state.value)
    }
}

@Composable
internal fun PlayerLines(player: PlayerVisual, sub: String?, names: Loadable<Unit>, modifier: Modifier = Modifier) {
    if (player.name.isEmpty() && names is Loadable.Loading) {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            LoadingLine(110.dp, height = 12.dp)
            if (sub != null) Text(sub, style = statsType().rowSub, color = MaterialTheme.colorScheme.outline, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    } else {
        TwoLine(StatsLabels.playerName(player, names), sub, modifier)
    }
}

@Composable
internal fun CardHeader(
    title: String,
    modifier: Modifier = Modifier,
    note: String? = null,
    afterTitle: (@Composable RowScope.() -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val type = statsType()
    Row(
        modifier.fillMaxWidth().heightIn(min = StatsDimens.CardHeaderHeight).padding(start = 12.dp, end = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = type.cardTitle, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
        if (afterTitle != null) Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically, content = afterTitle)
        if (note != null) {
            Text(note, style = type.caption, color = MaterialTheme.colorScheme.outline, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        } else {
            Spacer(Modifier.weight(1f))
        }
        trailing()
    }
}

@Composable
internal fun HeroTile(hero: HeroVisual?, size: Dp, textStyle: TextStyle, modifier: Modifier = Modifier, shape: Shape = Cookie.Eight.shape) {
    val tint = hero?.tint ?: StatsColors.HeroFallback
    val image = hero?.portraitUrl?.let { LocalRemoteImages.current.rememberImage(it, ContentScale.Crop) }
    Box(modifier.size(size).clip(shape).background(tint.container), contentAlignment = Alignment.Center) {
        if (image?.loaded != true) Text(hero?.initials ?: "?", style = textStyle, color = tint.content, maxLines = 1)
        if (image != null) Image(image.painter, contentDescription = null, modifier = Modifier.fillMaxSize().then(image.layoutModifier), contentScale = ContentScale.Crop)
    }
}

internal fun playerTint(player: PlayerVisual): Tint = player.tint ?: StatsColors.PlayerTints[(player.accountId % StatsColors.PlayerTints.size).toInt()]

@Composable
internal fun PlayerAvatar(player: PlayerVisual, size: Dp, textStyle: TextStyle, modifier: Modifier = Modifier, fill: Brush? = null, initialColor: Color? = null) {
    val tint = playerTint(player)
    val image = player.avatarUrl?.let { LocalRemoteImages.current.rememberImage(it, ContentScale.Crop) }
    Box(
        modifier.size(size).clip(CircleShape).then(if (fill != null) Modifier.background(fill) else Modifier.background(tint.container)),
        contentAlignment = Alignment.Center,
    ) {
        if (image?.loaded != true) Text(player.initial, style = textStyle, color = initialColor ?: tint.content, maxLines = 1)
        if (image != null) Image(image.painter, contentDescription = null, modifier = Modifier.fillMaxSize().then(image.layoutModifier), contentScale = ContentScale.Crop)
    }
}

@Composable
internal fun DeltaChip(change: Change, modifier: Modifier = Modifier) {
    val good = change.amount > 0
    val color = if (good) StatsColors.Win else StatsColors.Loss
    val amount = StatsFormat.change(change)
    val text = when (change.unit) {
        ChangeUnit.Points -> stringResource(Res.string.stats_points, amount)
        ChangeUnit.Percent -> stringResource(Res.string.stats_percent_change, amount)
        ChangeUnit.Plain -> amount
    }
    Row(
        modifier.height(22.dp).clip(CircleShape).background(StatsColors.chip(color)).padding(start = 5.dp, end = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HideoutIcon(if (good) HideoutIcons.ArrowUp else HideoutIcons.ArrowDown, contentDescription = null, size = 13.dp, tint = color)
        Text(text, style = statsType().delta, color = color, maxLines = 1)
    }
}

enum class ChipTone { Neutral, Up, Gold }

@Composable
internal fun StatChip(text: String, tone: ChipTone, modifier: Modifier = Modifier, icon: ImageVector? = null, height: Dp = 26.dp) {
    val (fill, content) = when (tone) {
        ChipTone.Neutral -> MaterialTheme.colorScheme.surfaceContainerHigh to MaterialTheme.colorScheme.onSurfaceVariant
        ChipTone.Up -> StatsColors.chip(StatsColors.Win) to StatsColors.Win
        ChipTone.Gold -> StatsColors.chip(StatsColors.Gold) to StatsColors.Gold
    }
    Row(
        modifier.height(height).clip(CircleShape).background(fill).padding(start = if (icon != null) 7.dp else 10.dp, end = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) HideoutIcon(icon, contentDescription = null, size = 14.dp, tint = content)
        Text(text, style = statsType().chip, color = content, maxLines = 1)
    }
}

@Composable
internal fun WinRateBar(fraction: Double?, modifier: Modifier = Modifier) {
    val type = statsType()
    val scheme = MaterialTheme.colorScheme
    val tick = scheme.surface
    val track = scheme.surfaceContainerHighest
    val shown by animateFloatAsState((fraction ?: 0.0).toFloat().coerceIn(0f, 1f), HideoutTheme.motion.spatialDefault(), label = "winRate")
    val color = if ((fraction ?: 0.0) < 0.5) StatsColors.Loss else StatsColors.Win
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.weight(1f).height(10.dp)) {
            val barHeight = 6.dp.toPx()
            val top = (size.height - barHeight) / 2
            val radius = CornerRadius(barHeight / 2)
            drawRoundRect(track, topLeft = Offset(0f, top), size = Size(size.width, barHeight), cornerRadius = radius)
            if (fraction != null) drawRoundRect(color, topLeft = Offset(0f, top), size = Size(size.width * shown, barHeight), cornerRadius = radius)
            val tickWidth = 1.5.dp.toPx()
            drawRect(tick, topLeft = Offset(size.width / 2 - tickWidth / 2, 0f), size = Size(tickWidth, size.height))
        }
        Text(
            text = StatsLabels.percent(fraction),
            style = type.percent,
            color = scheme.onSurface,
            textAlign = TextAlign.End,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.width(36.dp).wrapContentWidth(Alignment.End, unbounded = true),
        )
    }
}

@Composable
internal fun ShareBar(fraction: Float, color: Color, modifier: Modifier = Modifier) {
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val shown by animateFloatAsState(fraction.coerceIn(0f, 1f), HideoutTheme.motion.spatialDefault(), label = "share")
    Canvas(modifier.height(6.dp)) {
        val radius = CornerRadius(size.height / 2)
        drawRoundRect(track, cornerRadius = radius)
        if (shown > 0f) drawRoundRect(color, size = Size(size.width * shown, size.height), cornerRadius = radius)
    }
}

@Composable
internal fun StatsRow(
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    shape: Shape = StatsShapes.Row,
    selected: Boolean = false,
    hoverFill: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    contentDescription: String? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val states = source.collectInteractionStates()
    val selectedFill = HideoutTheme.colors.selectedRow
    val outline = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
    val fill by animateColorAsState(
        when {
            states.hovered && onClick != null -> hoverFill
            selected -> selectedFill
            else -> hoverFill.copy(alpha = 0f)
        },
        HideoutTheme.motion.effectsDefault(),
        label = "statsRow",
    )
    Row(
        modifier
            .clip(shape)
            .drawBehind {
                drawRect(fill)
                if (selected) {
                    val stroke = 1.5.dp.toPx()
                    val radius = (shape as? RoundedCornerShape)?.topStart?.toPx(size, this) ?: 0f
                    drawRoundRect(outline, topLeft = Offset(stroke / 2, stroke / 2), size = Size(size.width - stroke, size.height - stroke), cornerRadius = CornerRadius(radius), style = Stroke(stroke))
                }
            }
            .then(if (onClick != null) Modifier.focusRing(states.focusVisible, shape).hideoutClickable(source, role = Role.Button, onClick = onClick) else Modifier)
            .then(if (contentDescription != null) Modifier.semantics(mergeDescendants = true) { this.contentDescription = contentDescription } else Modifier),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

private val SlashPad = (2f / 0.6f).sp

@Composable
internal fun kdaText(kills: Int?, deaths: Int?, assists: Int?): AnnotatedString {
    val none = StatsLabels.none()
    return buildAnnotatedString {
        listOf(kills, deaths, assists).forEachIndexed { index, value ->
            if (index > 0) {
                withStyle(SpanStyle(fontSize = SlashPad)) { append(" ") }
                withStyle(SpanStyle(color = MaterialTheme.colorScheme.outline)) { append("/") }
                withStyle(SpanStyle(fontSize = SlashPad)) { append(" ") }
            }
            append(value?.toString() ?: none)
        }
    }
}

@Composable
internal fun NumberCell(text: String, modifier: Modifier) {
    val type = statsType()
    Text(text, style = type.value.resized(type.percent.fontSize * (13.5f / 13f)), color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.End, maxLines = 1, modifier = modifier)
}

@Composable
internal fun TwoLine(
    title: String,
    sub: String?,
    modifier: Modifier = Modifier,
    titleColor: Color = MaterialTheme.colorScheme.onSurface,
    titleStyle: TextStyle = statsType().rowTitle,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Text(title, style = titleStyle, color = titleColor, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth())
        if (sub != null) Text(sub, style = statsType().rowSub, color = MaterialTheme.colorScheme.outline, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
internal fun KdaCell(row: MatchRowUi, modifier: Modifier) {
    val type = statsType()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Text(kdaText(row.kills, row.deaths, row.assists), style = type.kda, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
        Text(stringResource(Res.string.stats_kda_value, StatsLabels.decimal(row.kda)), style = type.rowSub, color = MaterialTheme.colorScheme.outline, maxLines = 1)
    }
}

@Composable
internal fun TinyChip(text: String, fill: Color, content: Color) {
    Box(Modifier.height(20.dp).clip(CircleShape).background(fill).padding(horizontal = 7.dp), contentAlignment = Alignment.Center) {
        Text(text, style = statsType().you, color = content, maxLines = 1)
    }
}

@Composable
internal fun TableHeaderText(text: String, modifier: Modifier, end: Boolean = false, color: Color = MaterialTheme.colorScheme.outline) {
    Text(
        text, style = statsType().small.copy(fontWeight = HideoutTheme.typography.labelSection.fontWeight), color = color,
        textAlign = if (end) TextAlign.End else TextAlign.Start, maxLines = 1, modifier = modifier,
    )
}
