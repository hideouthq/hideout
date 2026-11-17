package gg.hideouthq.core.designsystem.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import gg.hideouthq.core.model.Accent

internal object HideoutPalette {
    val Background = Color(0xFF000000)
    val Pane = Color(0xFF141313)
    val Surface2 = Color(0xFF1D1C1D)
    val Surface3 = Color(0xFF2A2829)
    val Surface4 = Color(0xFF393637)
    val OnSurface = Color(0xFFE6E1E1)
    val OnSurfaceVariant = Color(0xFFCBC5CA)
    val Subtle = Color(0xFF948F94)
    val Outline = Color(0xFF302E30)
    val Primary = Color(0xFFCBC4CB)
    val OnPrimary = Color(0xFF2A272C)
    val PrimaryContainer = Color(0xFF262328)
    val OnPrimaryContainer = Color(0xFFEAE4EA)
    val Accent = Color(0xFFB5CCBA)
    val OnAccent = Color(0xFF213528)
    val AccentContainer = Color(0xFF26332B)
    val Tertiary = Color(0xFFD1C3C6)
    val OnTertiary = Color(0xFF372E30)
    val TertiaryContainer = Color(0xFF31292B)
    val OnTertiaryContainer = Color(0xFFEDDFE1)
    val Stage = Color(0xFF0B0A0B)
    val Error = Color(0xFFFFB4AB)
    val ErrorContainer = Color(0xFF5A1F1F)
    val OnErrorContainer = Color(0xFFFFDAD4)
    val White = Color(0xFFFFFFFF)
    val Black = Color(0xFF000000)
    val GlassInk = Color(0xFF0A0A0C)
    val CodeGutter = Color(0xFF5C585C)
    val Status = Color(0xFF9FD0AE)
    val Caution = Color(0xFFE6B977)
    val CautionContainer = Color(0xFF3A2E1F)
    val Mint = Color(0xFF7FE3D0)
    val Match = Color(0xFF8FB4E8)
    val MatchContainer = Color(0xFF1E2A3A)
    val FailContainer = Color(0xFF4A2522)

    val CoverGreen = Color(0xFF2B2F2C)
    val CoverCream = Color(0xFF33302B)
    val CoverTeal = Color(0xFF2A2F33)
    val CoverMoss = Color(0xFF2F302A)
    val CoverEmber = Color(0xFF352C2B)
    val CoverInk = Color(0xFF2A292A)

    val StageTop = Color(0xFF0F0E0F)
    val StageBottom = Color(0xFF0E0D0E)
    val TurntableSide = Color(0xFF121112)
    val TurntableTop = Color(0xFF262426)
    val TurntableTopEnd = Color(0xFF1A191A)
    val TurntableLoading = Color(0xFF1C1B1C)

    val KeywordLilac = Color(0xFFC6B6E6)
    val KeywordCopper = Color(0xFFE0A98F)
    val KeywordGreen = Color(0xFF9FD0AE)
    val KeywordBlue = Color(0xFF9FC3E0)
    val KeywordGold = Color(0xFFE3C98F)
    val KeywordRose = Color(0xFFE3AFC0)
    val KeywordPink = Color(0xFFE8A0B4)
}

internal val HideoutColorScheme: ColorScheme = darkColorScheme(
    primary = HideoutPalette.Primary,
    onPrimary = HideoutPalette.OnPrimary,
    primaryContainer = HideoutPalette.PrimaryContainer,
    onPrimaryContainer = HideoutPalette.OnPrimaryContainer,
    inversePrimary = HideoutPalette.PrimaryContainer,
    secondary = HideoutPalette.Accent,
    onSecondary = HideoutPalette.OnAccent,
    secondaryContainer = HideoutPalette.AccentContainer,
    onSecondaryContainer = HideoutPalette.Accent,
    tertiary = HideoutPalette.Tertiary,
    onTertiary = HideoutPalette.OnTertiary,
    tertiaryContainer = HideoutPalette.TertiaryContainer,
    onTertiaryContainer = HideoutPalette.OnTertiaryContainer,
    error = HideoutPalette.Error,
    errorContainer = HideoutPalette.ErrorContainer,
    onErrorContainer = HideoutPalette.OnErrorContainer,
    background = HideoutPalette.Background,
    onBackground = HideoutPalette.OnSurface,
    surface = HideoutPalette.Pane,
    onSurface = HideoutPalette.OnSurface,
    surfaceVariant = HideoutPalette.Surface3,
    onSurfaceVariant = HideoutPalette.OnSurfaceVariant,
    surfaceTint = HideoutPalette.Primary,
    inverseSurface = HideoutPalette.OnSurface,
    inverseOnSurface = HideoutPalette.Pane,
    outline = HideoutPalette.Subtle,
    outlineVariant = HideoutPalette.Outline,
    scrim = HideoutPalette.Black,
    surfaceBright = HideoutPalette.Surface4,
    surfaceDim = HideoutPalette.Background,
    surfaceContainerLowest = HideoutPalette.Background,
    surfaceContainerLow = HideoutPalette.Pane,
    surfaceContainer = HideoutPalette.Surface2,
    surfaceContainerHigh = HideoutPalette.Surface3,
    surfaceContainerHighest = HideoutPalette.Surface4,
)

internal class AccentPalette(
    val primary: Color,
    val onPrimary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val selectedAlpha: Float = 0.10f,
)

internal val Accent.palette: AccentPalette
    get() = when (this) {
        Accent.Graphite -> AccentPalette(HideoutPalette.Primary, HideoutPalette.OnPrimary, HideoutPalette.PrimaryContainer, HideoutPalette.OnPrimaryContainer, selectedAlpha = 0.09f)
        Accent.Ember -> AccentPalette(Color(0xFFF4BB8A), Color(0xFF4A2810), Color(0xFF35261C), Color(0xFFFFDCC4))
        Accent.Soul -> AccentPalette(Color(0xFF8ED9CB), Color(0xFF003730), Color(0xFF1B2C29), Color(0xFFACF3E5))
        Accent.Ocean -> AccentPalette(Color(0xFFA8C8F7), Color(0xFF0D2F5A), Color(0xFF1E2734), Color(0xFFD5E3FF))
        Accent.Violet -> AccentPalette(Color(0xFFCCBEFF), Color(0xFF33275F), Color(0xFF28243A), Color(0xFFE8DEFF))
        Accent.Rose -> AccentPalette(Color(0xFFF3B5C5), Color(0xFF4D2433), Color(0xFF34232A), Color(0xFFFFD9E3))
    }

internal fun hideoutColorScheme(accent: Accent): ColorScheme = accent.palette.let {
    HideoutColorScheme.copy(
        primary = it.primary,
        onPrimary = it.onPrimary,
        primaryContainer = it.primaryContainer,
        onPrimaryContainer = it.onPrimaryContainer,
        inversePrimary = it.primaryContainer,
        surfaceTint = it.primary,
    )
}

internal fun hideoutColors(accent: Accent): HideoutColors = accent.palette.let {
    HideoutDarkColors.copy(
        selectedRow = it.primary.copy(alpha = it.selectedAlpha),
        selectedVersion = it.primary.copy(alpha = 0.12f),
        codeLocked = it.primary.copy(alpha = 0.06f),
        codeLockedEdge = it.primary.copy(alpha = 0.6f),
    )
}

@Immutable
data class HideoutColors(
    /** Tint behind the selected list row or the playing sound row. */
    val selectedRow: Color,
    val stage: Color,
    val stageSpotlight: Color,
    /** Bottom of the fade on image cards that keeps the caption readable. */
    val imageScrim: Color,
    /** Round badges sitting on images. */
    val glass: Color,
    /** Buttons, chips and rows drawn on the hero panel. */
    val heroInset: Color,
    /** Buttons drawn directly on an image. */
    val imageInset: Color,
    val imageInsetHover: Color,
    val glassOnHero: Color,
    val glassOnHeroHover: Color,
    val heroPillGlass: Color,
    /** Pills and segmented controls over a picture (Crosshairs' preview), flat in place of a backdrop blur. */
    val glassPill: Color,
    /** The smaller glass badges on picture tiles, a little denser so they read over busy art. */
    val glassBadge: Color,
    val glassSelection: Color,
    val onGlass: Color,
    val onImage: Color,
    val onImageVariant: Color,
    val overlayScrim: Color,
    val lightboxScrim: Color,
    val toolbarShadow: Color,
    /** The scrim behind the install dialog, over the whole window, sidebar included. */
    val dialogScrim: Color,
    val dialogShadow: Color,
    val selectedVersion: Color,
    /** The darker fill that sweeps across the Install button as a download progresses. */
    val progressSweep: Color,
    /** The window close button's fill on hover, and its icon. */
    val closeHover: Color,
    val onCloseHover: Color,
    val coverPlaceholders: List<Color>,
    /** The Studio backdrop's gradient: [stageTop] → [stage] at 60% → [stageBottom]. */
    val stageTop: Color,
    val stageBottom: Color,
    /** The turntable's side, its top from front to back, and its flat top while a model loads. */
    val turntableSide: Color,
    val turntableTop: Color,
    val turntableTopEnd: Color,
    val turntableLoading: Color,
    /** Discover's section keywords and their icon tiles, and nothing else. */
    val sectionHero: Color,
    val sectionSkins: Color,
    val sectionGameplay: Color,
    val sectionHud: Color,
    val sectionSounds: Color,
    val sectionNews: Color,
    val sectionCollections: Color,
    /** Steam news kinds, and the changelog's line tags. */
    val newsPatch: Color,
    val newsAnnouncement: Color,
    val changeAdded: Color,
    val changeFixed: Color,
    val changeImproved: Color,
    /** The code editor's line numbers, and the shade and left edge of its locked lines. */
    val codeGutter: Color,
    val codeLocked: Color,
    val codeLockedEdge: Color,
    /** Good news about a state: "In use", "Saved · applies next launch", a win. */
    val status: Color,
    /** A soft warning in text, such as a command the library does not know. */
    val caution: Color,
    val cautionContainer: Color,
    /** Custom games' own keyword: its Discover heading, the New badge and the curated tick. */
    val sectionCustomGames: Color,
    /** A custom game that is full and playing, and the other side on its scoreboard. */
    val match: Color,
    val matchContainer: Color,
    val failContainer: Color,
)

internal val HideoutDarkColors = HideoutColors(
    selectedRow = HideoutPalette.Primary.copy(alpha = 0.09f),
    stage = HideoutPalette.Stage,
    stageSpotlight = HideoutPalette.Primary.copy(alpha = 0.11f),
    imageScrim = HideoutPalette.Black.copy(alpha = 0.84f),
    glass = HideoutPalette.Black.copy(alpha = 0.55f),
    heroInset = HideoutPalette.Black.copy(alpha = 0.28f),
    imageInset = HideoutPalette.Black.copy(alpha = 0.50f),
    imageInsetHover = HideoutPalette.Black.copy(alpha = 0.64f),
    glassOnHero = HideoutPalette.White.copy(alpha = 0.12f),
    glassOnHeroHover = HideoutPalette.White.copy(alpha = 0.18f),
    heroPillGlass = HideoutPalette.Black.copy(alpha = 0.42f),
    glassPill = HideoutPalette.GlassInk.copy(alpha = 0.55f),
    glassBadge = HideoutPalette.GlassInk.copy(alpha = 0.62f),
    glassSelection = HideoutPalette.White.copy(alpha = 0.9f),
    onGlass = HideoutPalette.White.copy(alpha = 0.78f),
    onImage = HideoutPalette.White,
    onImageVariant = HideoutPalette.White.copy(alpha = 0.76f),
    overlayScrim = HideoutPalette.Black.copy(alpha = 0.55f),
    lightboxScrim = HideoutPalette.Black.copy(alpha = 0.9f),
    toolbarShadow = HideoutPalette.Black.copy(alpha = 0.55f),
    dialogScrim = HideoutPalette.Black.copy(alpha = 0.62f),
    dialogShadow = HideoutPalette.Black.copy(alpha = 0.6f),
    selectedVersion = HideoutPalette.Primary.copy(alpha = 0.12f),
    progressSweep = HideoutPalette.Black.copy(alpha = 0.18f),
    closeHover = HideoutPalette.ErrorContainer,
    onCloseHover = HideoutPalette.OnErrorContainer,
    coverPlaceholders = listOf(
        HideoutPalette.CoverGreen,
        HideoutPalette.CoverCream,
        HideoutPalette.CoverTeal,
        HideoutPalette.CoverMoss,
        HideoutPalette.CoverEmber,
        HideoutPalette.CoverInk,
    ),
    stageTop = HideoutPalette.StageTop,
    stageBottom = HideoutPalette.StageBottom,
    turntableSide = HideoutPalette.TurntableSide,
    turntableTop = HideoutPalette.TurntableTop,
    turntableTopEnd = HideoutPalette.TurntableTopEnd,
    turntableLoading = HideoutPalette.TurntableLoading,
    sectionHero = HideoutPalette.KeywordLilac,
    sectionSkins = HideoutPalette.KeywordCopper,
    sectionGameplay = HideoutPalette.KeywordGreen,
    sectionHud = HideoutPalette.KeywordBlue,
    sectionSounds = HideoutPalette.KeywordGold,
    sectionNews = HideoutPalette.KeywordRose,
    sectionCollections = HideoutPalette.KeywordPink,
    newsPatch = HideoutPalette.KeywordGreen,
    newsAnnouncement = HideoutPalette.KeywordRose,
    changeAdded = HideoutPalette.KeywordGreen,
    changeFixed = HideoutPalette.KeywordBlue,
    changeImproved = HideoutPalette.KeywordGold,
    codeGutter = HideoutPalette.CodeGutter,
    codeLocked = HideoutPalette.Primary.copy(alpha = 0.06f),
    codeLockedEdge = HideoutPalette.Primary.copy(alpha = 0.6f),
    status = HideoutPalette.Status,
    caution = HideoutPalette.Caution,
    cautionContainer = HideoutPalette.CautionContainer,
    sectionCustomGames = HideoutPalette.Mint,
    match = HideoutPalette.Match,
    matchContainer = HideoutPalette.MatchContainer,
    failContainer = HideoutPalette.FailContainer,
)

internal val LocalHideoutColors = staticCompositionLocalOf { HideoutDarkColors }

/** Brightens a colour the way CSS `filter: brightness(factor)` does, for hover states. */
fun Color.brightened(factor: Float): Color = if (factor == 1f) this else Color(
    red = (red * factor).coerceAtMost(1f),
    green = (green * factor).coerceAtMost(1f),
    blue = (blue * factor).coerceAtMost(1f),
    alpha = alpha,
)
