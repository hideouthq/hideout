package gg.hideouthq.core.designsystem.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import gg.hideouthq.resources.Res
import java.net.URI

object HideoutWeights {
    val Body = FontWeight(450)
    val Medium = FontWeight(500)
    val Value = FontWeight(550)
    val Label = FontWeight(600)
    val Title = FontWeight(650)
    val Heavy = FontWeight(700)
    internal val All = listOf(Body, Medium, Value, Label, Title, Heavy)
}

@Immutable
data class HideoutTypography(
    val displayHero: TextStyle,
    val displayHeroCompact: TextStyle,
    val displayTitle: TextStyle,
    val headlinePage: TextStyle,
    val headlineDetail: TextStyle,
    val titleSection: TextStyle,
    val titleEmpty: TextStyle,
    val displayCollection: TextStyle,
    val titleBrand: TextStyle,
    val titlePanel: TextStyle,
    val titleCard: TextStyle,
    val titleRow: TextStyle,
    val titleSetting: TextStyle,
    val titleDialog: TextStyle,
    val titleUpdateCard: TextStyle,
    val titleHighlight: TextStyle,
    val headlineVersion: TextStyle,
    val factValue: TextStyle,
    val body: TextStyle,
    val bodyStrong: TextStyle,
    val authorName: TextStyle,
    val authorRole: TextStyle,
    val bodyProse: TextStyle,
    val bodyLead: TextStyle,
    val bodyDialog: TextStyle,
    val notice: TextStyle,
    val labelNav: TextStyle,
    val labelButton: TextStyle,
    val labelButtonLarge: TextStyle,
    val labelPill: TextStyle,
    val labelMenuItem: TextStyle,
    val labelTag: TextStyle,
    val labelChip: TextStyle,
    val labelTab: TextStyle,
    val labelTabCount: TextStyle,
    val labelBadge: TextStyle,
    val labelSection: TextStyle,
    val labelStatus: TextStyle,
    val labelGroup: TextStyle,
    val labelAvatar: TextStyle,
    val caption: TextStyle,
    val captionSmall: TextStyle,
    val captionCompact: TextStyle,
    val tooltipDetail: TextStyle,
    val breadcrumbCurrent: TextStyle,
    val subtitle: TextStyle,
    val eyebrow: TextStyle,
    val eyebrowSmall: TextStyle,
    val badge: TextStyle,
    val labelVersionTag: TextStyle,
    val initialsHero: TextStyle,
    val initialsAuthor: TextStyle,
    val initialsChip: TextStyle,
    val stageWord: TextStyle,
    val mono: TextStyle,
    val monoHint: TextStyle,
    val monoCount: TextStyle,
    val monoKey: TextStyle,
    val monoPath: TextStyle,
    val monoCode: TextStyle,
)

internal val LocalHideoutTypography = staticCompositionLocalOf<HideoutTypography> {
    error("HideoutTypography is provided by HideoutTheme")
}

private val OpticalSizes = listOf(10f, 11f, 12f, 12.5f, 13f, 14f, 14.5f, 15f, 15.5f, 17f, 18f, 20f, 22f, 26f, 30f, 38f, 44f, 48f, 144f)
private const val OpticalSizeAxis = "opsz"

private val CssLineHeight = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.None,
)

private const val LineHeightFloor = 1.001f

/** A line height of [ratio] × [fontSize] that Skia honours even at 1 (see [LineHeightFloor]). */
fun cssLineHeight(fontSize: TextUnit, ratio: Float): TextUnit = (fontSize.value * maxOf(ratio, LineHeightFloor)).sp

private class FontFile(private val path: String) {
    private val bytes by lazy { URI(Res.getUri(path)).toURL().readBytes() }

    /** Compose keys a typeface by identity, weight and style, so the identity carries the variation. */
    fun font(weight: FontWeight, variation: FontVariation.Settings = FontVariation.Settings(weight, FontStyle.Normal)) =
        Font("$path ${variation.settings}", { bytes }, weight, FontStyle.Normal, variation)
}

// Upstream without the glyph variations of the axes Hideout pins: Compose copies the whole file into native memory per
// variation, so its size is paid many times over.
private val SansFlex = FontFile("font/google_sans_flex.ttf")
private val MonoRegular = FontFile("font/jetbrains_mono_regular.ttf")
private val MonoMedium = FontFile("font/jetbrains_mono_medium.ttf")

private fun buildTypography(sans: Map<Float, FontFamily>, mono: FontFamily): HideoutTypography {
    fun style(
        size: Float,
        lineHeight: Float,
        weight: FontWeight,
        tracking: TextUnit = 0.em,
        opticalSize: Float = size,
    ) = TextStyle(
        fontFamily = sans.getValue(opticalSize),
        fontSize = size.sp,
        lineHeight = cssLineHeight(size.sp, lineHeight / size),
        fontWeight = weight,
        letterSpacing = tracking,
        lineHeightStyle = CssLineHeight,
    )

    fun monoStyle(size: Float, lineHeight: Float, weight: FontWeight = FontWeight.Normal) = TextStyle(
        fontFamily = mono,
        fontSize = size.sp,
        lineHeight = lineHeight.sp,
        fontWeight = weight,
        lineHeightStyle = CssLineHeight,
    )

    return HideoutTypography(
        displayHero = style(48f, 50f, HideoutWeights.Title, (-0.02).em),
        displayHeroCompact = style(30f, 32f, HideoutWeights.Title, (-0.02).em),
        displayTitle = style(38f, 41f, HideoutWeights.Title, (-0.025).em),
        headlinePage = style(26f, 30f, HideoutWeights.Title, (-0.015).em),
        headlineDetail = style(26f, 29f, HideoutWeights.Title, (-0.015).em),
        titleSection = style(20f, 26f, HideoutWeights.Title, (-0.01).em),
        titleEmpty = style(17f, 23f, HideoutWeights.Title),
        displayCollection = style(44f, 46.2f, HideoutWeights.Title, (-0.025).em),
        titleBrand = style(17f, 22f, HideoutWeights.Title, (-0.01).em),
        titlePanel = style(15f, 20f, HideoutWeights.Title),
        titleCard = style(15.5f, 19f, HideoutWeights.Title),
        titleRow = style(14.5f, 19f, HideoutWeights.Label),
        titleSetting = style(15f, 20f, HideoutWeights.Label),
        titleDialog = style(22f, 25.3f, HideoutWeights.Title, (-0.01).em),
        titleUpdateCard = style(13.5f, 16.875f, HideoutWeights.Title, opticalSize = 14f),
        titleHighlight = style(14.5f, 19f, HideoutWeights.Title),
        headlineVersion = style(18f, 25f, HideoutWeights.Title, (-0.01).em),
        factValue = style(14.5f, 19f, HideoutWeights.Value),
        body = style(14f, 19f, HideoutWeights.Body),
        bodyStrong = style(14f, 19f, HideoutWeights.Label),
        authorName = style(14f, 17.5f, HideoutWeights.Label),
        authorRole = style(12f, 15f, HideoutWeights.Body),
        bodyProse = style(14.5f, 23f, HideoutWeights.Body),
        bodyLead = style(15.5f, 24f, HideoutWeights.Body),
        bodyDialog = style(13.5f, 20.25f, HideoutWeights.Body, opticalSize = 14f),
        notice = style(13f, 19f, HideoutWeights.Body),
        labelNav = style(14f, 19f, HideoutWeights.Medium),
        labelButton = style(14f, 19f, HideoutWeights.Title),
        labelButtonLarge = style(15f, 20f, HideoutWeights.Title),
        labelPill = style(13f, 17f, HideoutWeights.Label),
        labelMenuItem = style(13.5f, 18f, HideoutWeights.Medium, opticalSize = 14f),
        labelTag = style(10.5f, 14f, HideoutWeights.Heavy, 0.04.em, opticalSize = 10f),
        labelChip = style(12.5f, 16f, HideoutWeights.Label),
        labelTab = style(14f, 18.9f, HideoutWeights.Label),
        labelTabCount = style(12f, 16.2f, HideoutWeights.Label),
        labelBadge = style(12.5f, 16f, HideoutWeights.Title),
        labelSection = style(12f, 16f, HideoutWeights.Label),
        labelStatus = style(12f, 16f, HideoutWeights.Title),
        labelGroup = style(13f, 17f, HideoutWeights.Title),
        labelAvatar = style(12f, 16f, HideoutWeights.Value),
        caption = style(12f, 16f, HideoutWeights.Body),
        captionSmall = style(13f, 17f, HideoutWeights.Body),
        captionCompact = style(11.5f, 15f, HideoutWeights.Body, opticalSize = 11f),
        tooltipDetail = style(11.5f, 15f, HideoutWeights.Medium, opticalSize = 11f),
        breadcrumbCurrent = style(13f, 17f, HideoutWeights.Label),
        subtitle = style(12.5f, 17f, HideoutWeights.Body),
        eyebrow = style(12f, 16f, HideoutWeights.Heavy, 0.09.em),
        eyebrowSmall = style(11f, 15f, HideoutWeights.Heavy, 0.09.em),
        badge = style(11f, 15f, HideoutWeights.Heavy),
        labelVersionTag = style(11f, 15f, HideoutWeights.Title),
        initialsHero = style(18f, 24f, HideoutWeights.Title),
        initialsAuthor = style(13f, 17f, HideoutWeights.Heavy),
        initialsChip = style(10f, 13f, HideoutWeights.Heavy),
        stageWord = style(160f, 160f, HideoutWeights.Heavy, (-0.04).em, opticalSize = 144f),
        mono = monoStyle(11.5f, 15f),
        monoHint = monoStyle(11f, 15f),
        monoCount = monoStyle(12f, 16f, FontWeight.Medium),
        monoKey = monoStyle(10f, 13f, FontWeight.Medium),
        monoPath = monoStyle(12.5f, 17f),
        monoCode = monoStyle(13f, 22f),
    )
}

internal val HideoutType = buildTypography(
    sans = OpticalSizes.associateWith { size ->
        FontFamily(
            HideoutWeights.All.map { weight ->
                SansFlex.font(
                    weight,
                    FontVariation.Settings(FontVariation.weight(weight.weight), FontVariation.Setting(OpticalSizeAxis, size)),
                )
            },
        )
    },
    mono = FontFamily(MonoRegular.font(FontWeight.Normal), MonoMedium.font(FontWeight.Medium)),
)

internal fun HideoutTypography.toMaterialTypography() = Typography(
    displayLarge = displayHero,
    displayMedium = displayHero,
    displaySmall = displayHeroCompact,
    headlineLarge = headlinePage,
    headlineMedium = headlinePage,
    headlineSmall = headlinePage,
    titleLarge = titleSection,
    titleMedium = titleBrand,
    titleSmall = titleCard,
    bodyLarge = bodyProse,
    bodyMedium = body,
    bodySmall = subtitle,
    labelLarge = labelButton,
    labelMedium = labelPill,
    labelSmall = labelChip,
)
