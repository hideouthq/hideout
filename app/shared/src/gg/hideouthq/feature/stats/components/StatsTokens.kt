package gg.hideouthq.feature.stats.components

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import gg.hideouthq.core.designsystem.theme.HideoutPalette
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import gg.hideouthq.core.designsystem.theme.HideoutTypography
import gg.hideouthq.core.designsystem.theme.HideoutWeights
import gg.hideouthq.core.designsystem.theme.cssLineHeight
import gg.hideouthq.feature.stats.ItemCategory
import gg.hideouthq.feature.stats.Team
import gg.hideouthq.feature.stats.Tint

internal object StatsColors {
    val Win = HideoutPalette.Status
    val Loss = Color(0xFFFFB4AB)
    private val HiddenKing = Color(0xFFE58F95)
    private val Archmother = Color(0xFF8FB4E8)
    val Weapon = Color(0xFFE8A86F)
    val Vitality = Color(0xFF8FCB9B)
    val Spirit = Color(0xFFB9A3E3)
    val Gold = Color(0xFFD8C9A8)

    fun chip(color: Color) = color.copy(alpha = 0.14f)

    fun team(team: Team?) = when (team) {
        Team.Amber -> HiddenKing
        Team.Sapphire -> Archmother
        null -> Gold
    }

    fun category(category: ItemCategory?) = when (category) {
        ItemCategory.Weapon -> Weapon
        ItemCategory.Vitality -> Vitality
        ItemCategory.Spirit -> Spirit
        null -> Color(0xFF948F94)
    }

    val OnArt = Color.White
    val OnArtVariant = Color.White.copy(alpha = 0.72f)
    val OnArtLabel = Color.White.copy(alpha = 0.6f)
    val OnArtChip = Color.White.copy(alpha = 0.86f)
    val ArtChip = Color.White.copy(alpha = 0.08f)
    val ArtGlass = Color.Black.copy(alpha = 0.34f)
    val ArtStrip = Color.Black.copy(alpha = 0.24f)
    val ArtStripTop = Color.White.copy(alpha = 0.05f)
    val ArtStripDivider = Color.White.copy(alpha = 0.07f)
    val ArtButtonHover = Color.White.copy(alpha = 0.10f)
    val PipOff = Color.White.copy(alpha = 0.14f)

    val ArtPlaceholder = listOf(
        Color(92, 128, 214).copy(alpha = 0.58f),
        Color(150, 112, 210).copy(alpha = 0.36f),
        Color(110, 190, 220).copy(alpha = 0.24f),
    )
    val ArtBaseStart = Color(0xFF14161D)
    val ArtBaseEnd = Color(0xFF0F1014)

    val HeaderShade = Brush.horizontalGradient(
        0f to Color(20, 19, 19).copy(alpha = 0.94f),
        0.40f to Color(20, 19, 19).copy(alpha = 0.78f),
        0.78f to Color(20, 19, 19).copy(alpha = 0.30f),
        1f to Color(20, 19, 19).copy(alpha = 0.42f),
    )

    val FeaturedShade = Brush.horizontalGradient(0f to Color.Black.copy(alpha = 0.72f), 0.55f to Color.Black.copy(alpha = 0.34f), 1f to Color.Transparent)

    val PanelShade = Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.05f), 0.30f to Color.Black.copy(alpha = 0.05f), 1f to Color.Black.copy(alpha = 0.7f))

    val SelfAvatar = Brush.linearGradient(listOf(Color(0xFFD9D2D9), Color(0xFFA79FA8)))
    val OnSelfAvatar = Color(0xFF2A272C)

    val PlayerTints = listOf(
        Tint(Color(0xFF2B3A31), Color(0xFFB5CCBA)),
        Tint(Color(0xFF2A3440), Color(0xFFB4C7D6)),
        Tint(Color(0xFF33303A), Color(0xFFCBC4CB)),
        Tint(Color(0xFF3D2F29), Color(0xFFD4B5A6)),
        Tint(Color(0xFF3A3527), Color(0xFFD8C9A8)),
        Tint(Color(0xFF3A2D30), Color(0xFFD1C3C6)),
    )

    val HeroFallback = Tint(Color(0xFF2A2829), Color(0xFFCBC5CA))
}

internal object StatsShapes {
    val Header = RoundedCornerShape(28.dp)
    val CardRadius = 24.dp
    val Card = RoundedCornerShape(CardRadius)
    val Tile = RoundedCornerShape(22.dp)
    val RankGlass = RoundedCornerShape(24.dp)
    val Featured = RoundedCornerShape(18.dp)
    val GlassTile = RoundedCornerShape(18.dp)
    val Row = RoundedCornerShape(16.dp)
    val SmallRow = RoundedCornerShape(14.dp)
}

internal object StatsDimens {
    val CardHeaderHeight = 52.dp
    val SelfAvatar = 84.dp
    val RankBadge = 88.dp
    val HeroTile = 40.dp
    val SmallHeroTile = 36.dp
    val MatchRowHeight = 62.dp
    val KpiMinHeight = 176.dp
    val MatchHeroTile = 96.dp
    val HeroPanelWidth = 380.dp
}

internal fun TextStyle.resized(size: TextUnit): TextStyle =
    copy(fontSize = size, lineHeight = if (lineHeight.isSp && fontSize.isSp) (lineHeight.value * size.value / fontSize.value).sp else lineHeight)

@Immutable
internal class StatsType(
    val name: TextStyle,
    val rankName: TextStyle,
    val kpiValue: TextStyle,
    val kpiUnit: TextStyle,
    val kpiSub: TextStyle,
    val stripValue: TextStyle,
    val cardTitle: TextStyle,
    val teamTitle: TextStyle,
    val featuredValue: TextStyle,
    val eyebrow: TextStyle,
    val stripLabel: TextStyle,
    val rowTitle: TextStyle,
    val rowSub: TextStyle,
    val note: TextStyle,
    val result: TextStyle,
    val value: TextStyle,
    val percent: TextStyle,
    val kda: TextStyle,
    val chip: TextStyle,
    val delta: TextStyle,
    val meta: TextStyle,
    val caption: TextStyle,
    val small: TextStyle,
    val itemCode: TextStyle,
    val you: TextStyle,
    val setupTitle: TextStyle,
)

@Composable
internal fun statsType(): StatsType {
    val type = HideoutTheme.typography
    return remember(type) { buildStatsType(type) }
}

private fun buildStatsType(type: HideoutTypography): StatsType {
    fun TextStyle.sized(size: Float, line: Float, weight: FontWeight = fontWeight ?: HideoutWeights.Body, tracking: Float = 0f) =
        copy(fontSize = size.sp, lineHeight = cssLineHeight(size.sp, line), fontWeight = weight, letterSpacing = tracking.em)

    fun TextStyle.tabular() = copy(fontFeatureSettings = "tnum")
    return StatsType(
        name = type.displayHeroCompact.sized(32f, 1.1f, HideoutWeights.Title, -0.02f),
        rankName = type.headlinePage.sized(28f, 1.1f, HideoutWeights.Title, -0.015f),
        kpiValue = type.displayHeroCompact.sized(34f, 1.1f, HideoutWeights.Title, -0.025f).tabular(),
        kpiUnit = type.titleBrand.sized(16f, 1.1f, HideoutWeights.Label),
        kpiSub = type.subtitle.sized(12.5f, 1.35f).tabular(),
        stripValue = type.titleSection.sized(19f, 1.35f, HideoutWeights.Title).tabular(),
        cardTitle = type.titleBrand.sized(17f, 1.35f, HideoutWeights.Title, -0.005f),
        teamTitle = type.titleBrand.sized(16f, 1.35f, HideoutWeights.Title),
        featuredValue = type.titleSection.sized(20f, 1.35f, HideoutWeights.Title).tabular(),
        eyebrow = type.eyebrowSmall.sized(11.5f, 1.35f, HideoutWeights.Heavy, 0.09f),
        stripLabel = type.eyebrow.sized(12f, 1.35f, HideoutWeights.Heavy, 0.08f),
        rowTitle = type.titleRow.sized(14.5f, 1.35f, HideoutWeights.Label),
        rowSub = type.caption.sized(12f, 1.35f).tabular(),
        note = type.caption.sized(12f, 1.35f),
        result = type.bodyStrong.sized(14f, 1.35f, HideoutWeights.Heavy),
        value = type.bodyStrong.sized(14f, 1.35f, HideoutWeights.Label).tabular(),
        percent = type.labelPill.sized(13f, 1.35f, HideoutWeights.Title).tabular(),
        kda = type.mono.copy(fontSize = 13.sp, lineHeight = cssLineHeight(13.sp, 1.35f), fontWeight = FontWeight.Medium),
        chip = type.labelChip.sized(12f, 1.35f, HideoutWeights.Title),
        delta = type.labelBadge.sized(11.5f, 1.35f, HideoutWeights.Heavy),
        meta = type.captionSmall.sized(13f, 1.35f),
        caption = type.subtitle.sized(12.5f, 1.35f),
        small = type.captionCompact.sized(11.5f, 1.35f),
        itemCode = type.badge.sized(11.5f, 1.35f, HideoutWeights.Heavy, -0.01f),
        you = type.labelTag.sized(10.5f, 1.35f, HideoutWeights.Heavy),
        setupTitle = type.displayHeroCompact.sized(30f, 1.15f, HideoutWeights.Title, -0.02f),
    )
}
