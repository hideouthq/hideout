package gg.hideouthq.core.designsystem.theme

import androidx.compose.ui.unit.dp

/** Sizes and spacing from the design (one CSS pixel = one dp). Base unit 4. */
object HideoutDimens {
    val Hairline = 1.dp
    val FocusRing = 2.dp

    object Window {
        val Padding = 8.dp
        val PaneGap = 8.dp
        val DefaultWidth = 1440.dp
        val DefaultHeight = 900.dp
        val MinWidth = 1000.dp
        val MinHeight = 680.dp
        /** At or above this width the side panel docks beside the main pane. */
        val DockedSidePanelMinWidth = 1180.dp
    }

    object Sidebar {
        val BrandHeight = TopBar.Height
        val BrandPadding = 8.dp
        val BrandGap = 10.dp
        val Logo = 32.dp
        val TransferRing = 16.dp
        val TransferRingStroke = 2.4.dp
        val TransferGap = 6.dp
    }

    object TopBarChip {
        val Height = 34.dp
        val Gap = 8.dp
        val HostingStart = 4.dp
        val HostingEnd = 10.dp
        val UpdateStart = 6.dp
        val UpdateEnd = 13.dp
        val UpdateGap = 6.dp
        val ModeTile = 26.dp
        val ModeTileRadius = 9.dp
        val StatusDot = 8.dp
        val StatusDotRing = 3.dp
        const val StatusDotRingAlpha = 0.22f
        val Chevron = 16.dp
        val IconSlot = 24.dp
        val Icon = 17.dp
        val RingIcon = 13.dp
        val Ring = 28.dp
        val RingStroke = 3.5.dp
        val Dot = 8.dp
        val DotRing = 2.dp
        val PanelGap = 10.dp
        val PanelRadius = 22.dp
        val PanelPadding = 16.dp
        val PanelGapInside = 12.dp
        val PanelShadowBlur = 60.dp
        val PanelShadowOffsetY = 24.dp
        val HostingPanelWidth = 320.dp
        val UpdatePanelWidth = 352.dp
        val HeaderGap = 12.dp
        val HostingHeaderTile = 44.dp
        val HostingHeaderTileRadius = 13.dp
        val UpdateHeaderTile = 40.dp
        val UpdateHeaderTileRadius = 13.dp
        val UpdateHeaderIcon = 20.dp
        val BoxRadius = 14.dp
        val ActionGap = 8.dp
        val UpdateActionGap = 6.dp
    }

    object AppUpdate {
        val ProgressHeight = 12.dp
        val WaveStroke = 3.4.dp
        val WaveAmplitude = 2.4.dp
        val Wavelength = 14.dp
        val WaveGap = 7.dp
        val WaveStopDot = 1.9.dp
        val ToastMargin = 20.dp
        val FooterTextButtonPadding = 20.dp
        val DialogWidth = 800.dp
        val DialogHeight = 640.dp
        val NarrowWindow = 860.dp
        val LogoTile = 52.dp
        val LogoSize = 30.dp
        val VersionColumn = 204.dp
        val VersionRow = 52.dp
        val VersionRadius = 14.dp
        val TagHeight = 20.dp
        val NotesGap = 22.dp
        val HighlightRadius = 18.dp
        val HighlightArt = 116.dp
        val HighlightArtSingle = 132.dp
        val GroupTile = 26.dp
        val GroupTileRadius = 9.dp
        val GroupIcon = 15.dp
        val GroupIndent = 36.dp
        val AreaColumn = 76.dp
        val HelperWidth = 78.dp
        val HelperHeight = 66.dp
        val HelperInset = 96.dp
    }

    object NavItem {
        val Height = 40.dp
        val HorizontalPadding = 12.dp
        val IconGap = 12.dp
        val ProfileTile = 20.dp
    }

    object LaunchCard {
        val Padding = 12.dp
        val Gap = 8.dp
        val GameTile = 36.dp
        val HeaderGap = 10.dp
        val HeaderBottomPadding = 2.dp
    }

    object SidePanel {
        val Width = 320.dp
        val Padding = 16.dp
        val HeaderHeight = 56.dp
        val HeaderStartPadding = 16.dp
        val HeaderEndPadding = 10.dp
        val HeaderGap = 8.dp
        val SectionGap = 20.dp
        val BlockGap = 16.dp
        val FooterTopPadding = 12.dp
        val EnterOffset = 24.dp
        /** A floating panel flung right faster than this closes. */
        val DismissVelocity = 600.dp
    }

    object WindowControls {
        val Button = 32.dp
        val Icon = 16.dp
        val Gap = 2.dp
        val Width = Button * 3 + Gap * 2
        /** From the side panel's right edge; on the main pane they follow the top bar's own padding. */
        val PanelEdgeInset = 8.dp
        val TopBarGap = 8.dp
        val HeaderGap = 16.dp
    }

    object TopBar {
        val Height = 56.dp
        val HorizontalPadding = 12.dp
        val SlotGap = 12.dp
        val NavButtonGap = 4.dp
        val BreadcrumbGap = 6.dp
        val ToolGap = 2.dp
    }

    object MainPane {
        val ContentPadding = 16.dp
        val SectionGap = 22.dp
        val BlockGap = 12.dp
        val ColumnGap = 16.dp
    }

    object Link {
        val Height = 36.dp
        val StartPadding = 12.dp
        val EndPadding = 6.dp
        val Gap = 4.dp
    }

    object SectionHeader {
        val MinHeight = 36.dp
        val HorizontalPadding = 4.dp
    }

    object Search {
        val Height = 40.dp
        val HeightSmall = 36.dp
        val MaxWidth = 420.dp
        /** The least the top bar's search keeps beside the chips: room for its hint and shortcut. */
        val MinWidth = 240.dp
        val StartPadding = 14.dp
        val StartPaddingSmall = 12.dp
        val EndPadding = 6.dp
        val Gap = 10.dp
        val GapSmall = 8.dp
        val InputInset = 2.dp
        val HintHorizontalPadding = 8.dp
        val HintVerticalPadding = 4.dp
        val HeightToolbar = 44.dp
    }

    object Field {
        val Height = 44.dp
        val HeightCompact = 36.dp
        val HorizontalPadding = 14.dp
        val HorizontalPaddingCompact = 12.dp
        val LeadingPadding = 8.dp
        val Gap = 8.dp
        val ValueWidth = 96.dp
    }

    object PageHeader {
        val Padding = 6.dp
        val StartPadding = 8.dp
        val Gap = 12.dp
        val ActionGap = 8.dp
        val SubtitleGap = 4.dp
    }

    object Button {
        val Height = 44.dp
        val HeightLarge = 48.dp
        val HeightSmall = 36.dp
        val HeightMedium = 40.dp
        val HorizontalPadding = 20.dp
        val HorizontalPaddingSmall = 16.dp
        val TextHorizontalPadding = 12.dp
        val Gap = 8.dp
    }

    object IconButton {
        val Size = 36.dp
        val SizeToolbar = 40.dp
    }

    object Icon {
        val Size = 20.dp
        val Small = 16.dp
        val Large = 26.dp
    }

    object Pill {
        val Height = 36.dp
        val HorizontalPadding = 14.dp
        val Gap = 6.dp
        val RowGap = 8.dp
        val PressWiden = 6.dp
    }

    object Chip {
        val Height = 32.dp
        val HeightSmall = 28.dp
        val HorizontalPadding = 12.dp
        val HorizontalPaddingSmall = 10.dp
        val LeadingPadding = 8.dp
        val AvatarLeadingPadding = 4.dp
        val TrailingIconPadding = 8.dp
        val Gap = 6.dp
        val DashedBorder = 1.5.dp
        val Spinner = 12.dp
        val SpinnerStroke = 1.5.dp
        val HeightBadge = 22.dp
        val BadgePadding = 8.dp
        val BadgeGap = 4.dp
        val BadgeIcon = 12.dp
        val Check = 15.dp
        val HeightAvatar = 40.dp
        val AvatarStartPadding = 5.dp
        val AvatarHorizontalPadding = 14.dp
        val AvatarGap = 8.dp
        val Avatar = 30.dp
        val SelectedRing = 2.dp
    }

    object GlassBadge {
        val Height = 24.dp
        val HeightRegular = 30.dp
        val Padding = 9.dp
        val PaddingRegular = 12.dp
        val DotPaddingRegular = 10.dp
        val Gap = 6.dp
        val GapRegular = 7.dp
        val Dot = 6.dp
        val DotRegular = 8.dp
    }

    object Segmented {
        val Padding = 3.dp
        val SegmentHeight = 30.dp
        val SegmentHorizontalPadding = 13.dp
        val Gap = 2.dp
        val IconSegmentStart = 12.dp
        val IconSegmentEnd = 16.dp
        val IconGap = 7.dp
        val Icon = 16.dp
    }

    object PillTabs {
        val Padding = 4.dp
        val Gap = 2.dp
        val Height = 40.dp
        val StartPadding = 14.dp
        val EndPadding = 18.dp
        val LabelGap = 8.dp
        val Icon = 18.dp
    }

    object Slider {
        val HandleWidth = 4.dp
        val HandleHeight = 36.dp
        val HandleGap = 7.dp
        val Halo = 3.dp
    }

    object CodeEditor {
        val Gutter = 44.dp
        val NumberEnd = 16.dp
        val EndPadding = 16.dp
        val VerticalPadding = 12.dp
        val LockedEdge = 2.dp
    }

    object Switch {
        val Width = 44.dp
        val Height = 26.dp
        val Thumb = 20.dp
        val ThumbPressedWidth = 26.dp
        val ThumbInset = 3.dp
        val RowHeight = 44.dp
    }

    object Card {
        val HeightDiscover = 190.dp
        val HeightGameplay = 172.dp
        val HeightBrowse = 184.dp
        val HeightHud = 232.dp
        val MinColumnDiscover = 250.dp
        val MinColumnBrowse = 240.dp
        val MinColumnHud = 260.dp
        val CaptionHorizontalInset = 14.dp
        val CaptionBottomInset = 12.dp
        val CaptionGap = 10.dp
        val Gap = 12.dp
        val Badge = 28.dp
        val BadgeInset = 10.dp
        val BadgeGap = 6.dp
        val BadgeHoverLift = 2.dp
        val StatGap = 4.dp

        val UnderImageRadius = 14.dp
        val UnderTextGap = 10.dp
        val UnderTextInset = 2.dp
        val UnderLineGap = 2.dp
        val UnderColumnGap = 14.dp
        val UnderRowGap = 18.dp
        val UnderRingGap = 2.dp
        val UnderRing = 2.dp
        const val UnderImageRatio = 16f / 9f
    }

    object Hero {
        val PaddingHorizontal = 32.dp
        val PaddingVertical = 30.dp
        val Art = 272.dp
        val TitleTopGap = 12.dp
        val MetaTopGap = 14.dp
        val MetaGap = 10.dp
        val ActionsTopGap = 22.dp
        val StatsTopGap = 18.dp
        val StatsGap = 16.dp
        val ChipAvatar = 24.dp
        val TitleMinSize = 30.dp
        val TitleMaxSize = 48.dp
        const val TitleLineHeight = 1.04f
        val InstallStartPadding = 20.dp
        val InstallEndPadding = 26.dp
        val PreviewStartPadding = 18.dp
        val PreviewEndPadding = 22.dp

        val Height = 388.dp
        val TextMaxWidth = 470.dp
        const val TextWidthFraction = 0.46f
        const val TitleCardFraction = 0.046f
        const val StageLeftFraction = 0.45f
        val StageWidth = 300.dp
        val StageMinGap = 16.dp
        val PagerEdgeInset = 18.dp
        val PagerPillPaddingTop = 6.dp
        val PagerPillPaddingEnd = 8.dp
        val PagerPillPaddingBottom = 6.dp
        val PagerPillPaddingStart = 6.dp
        val PagerPillGap = 6.dp
        val PagerPauseButton = 36.dp
        val PagerThumb = 40.dp
        val PagerThumbSlot = 46.dp
        val PagerThumbGap = 5.dp
        val PagerProgressWidth = 26.dp
        val PagerProgressHeight = 3.dp
        const val PagerThumbInactiveAlpha = 0.62f
        const val PagerThumbActiveScale = 1.08f
        val PagerRotationPeriodMillis = 8_000L
    }

    object HeroAvatar {
        val Size = 60.dp
        val LabelGap = 6.dp
        val MinItemWidth = 64.dp
        val HoverLift = 3.dp
        val ItemGap = 12.dp
    }

    object ListRow {
        val Height = 56.dp
        val Thumb = 40.dp
        val Gap = 10.dp
        val StartPadding = 4.dp
        val EndPadding = 6.dp
        val ListGap = 2.dp
        val DragHandleWidth = 28.dp
        val DragHandleHeight = 40.dp
        val IndexWidth = 22.dp
        val SizeWidth = 64.dp
        val SwitchColumnWidth = 44.dp
        val MoreWidth = 36.dp
        val HeaderHeight = 32.dp
    }

    object Library {
        val HeaderHorizontalPadding = 4.dp
        val HeaderTopPadding = 6.dp
        val HeaderBottomPadding = 2.dp
        val SummaryTopPadding = 2.dp
        val SummaryNameMax = 120.dp
        val PanelGap = 14.dp
        val PanelCover = 128.dp
        val FilesGap = 10.dp
        val FilesBoxVerticalPadding = 4.dp
        val FileRowHeight = 34.dp
        val FileRowHorizontalPadding = 12.dp
        val FileRowGap = 10.dp
        val OffNoteHorizontalPadding = 14.dp
        val OffNoteVerticalPadding = 12.dp
        val DownloadHeadingTop = 8.dp
        val DownloadHeadingBottom = 2.dp
        val NoteGap = 8.dp
        val NoteTopPadding = 6.dp
        val ToolbarGap = 8.dp
        val GroupGap = 6.dp
        val UpdateListMaxHeight = 280.dp
    }

    object ViewToggle {
        val SegmentWidth = 38.dp
    }

    object Downloading {
        val HeaderHeight = 28.dp
        val BarWidth = 132.dp
        val BarHeight = 4.dp
        val CardBarInset = 14.dp
        val CardBarBottom = 10.dp
    }

    object LibraryCard {
        val MinColumn = 230.dp
        val Gap = 12.dp
        val Height = 188.dp
        val Rank = 28.dp
        val More = 32.dp
        val SelectedRing = 2.dp
        val SelectedGap = 2.dp
    }

    object Menu {
        val Width = 240.dp
        val Radius = 18.dp
        val Padding = 6.dp
        val ItemGap = 1.dp
        val ItemHeight = 40.dp
        /** Nine and a half items: a longer list scrolls, and the cut row shows there is more. */
        val MaxListHeight = 389.dp
        val ItemRadius = 12.dp
        val ItemHorizontalPadding = 12.dp
        val ItemContentGap = 12.dp
        val ItemIcon = 18.dp
        val SeparatorVertical = 4.dp
        val SeparatorHorizontal = 8.dp
        val AnchorGap = 4.dp
        val ShadowOffsetY = 18.dp
        val ShadowBlur = 44.dp
    }

    object AlertDialog {
        val Width = 480.dp
        val WindowMargin = 24.dp
        val Padding = 24.dp
        val Radius = 28.dp
        val Gap = 16.dp
        val BodyGap = 10.dp
        val ShadowOffsetY = 24.dp
        val ShadowBlur = 64.dp
        val FooterTopPadding = 4.dp
        val FooterGap = 8.dp
        val CancelPadding = 18.dp
        val HintTop = 2.dp
    }

    object Checkbox {
        val Size = 20.dp
        val Radius = 6.dp
        val Ring = 2.dp
        val Check = 14.dp
    }

    object Snackbar {
        val Margin = 24.dp
        val ShadowOffsetY = 10.dp
        val ShadowBlur = 30.dp
    }

    object Browse {
        val StripHorizontalPadding = 12.dp
        val StripVerticalPadding = 10.dp
        val LabelEndPadding = 4.dp
        val ResultLineStartPadding = 4.dp
        val PanelTopPadding = 4.dp
        val HeroSectionGap = 10.dp
        val ShowOnlyGap = 4.dp
        val SectionLabelBottomPadding = 4.dp
    }

    object Discover {
        val SectionIconTile = 34.dp
        val SectionIconRadius = 11.dp
        val SectionIcon = 18.dp
        const val SectionTintAlpha = 0.16f
        val HudBasis = 480.dp
        const val HudGrow = 3f
        val SoundsBasis = 340.dp
        const val SoundsGrow = 2f
    }

    object Collection {
        val ArtHeight = 210.dp
        val ChipInset = 10.dp
        val ChipHeight = 26.dp
        val ChipStartPadding = 8.dp
        val ChipEndPadding = 10.dp
        val ChipGap = 6.dp
        val ChipDot = 7.dp
        val InfoGap = 10.dp
        val Portrait = 26.dp
        val PortraitRing = 2.dp
        val PortraitOverlap = 7.dp
        /** The two layers that peek out above a collection's card, and the room the row leaves above it for them. */
        val StackSpace = 12.dp
        val StackFrontPeek = 6.dp
        val StackFrontInset = 12.dp
        val StackBackInset = 26.dp
        val StackHeight = 40.dp
        val StackRadius = 12.dp
        val StackFrontFan = 2.dp
        val StackBackFan = 4.dp
        val HoverLift = 3.dp
        val FigureRise = 5.dp
        val MinCardWidth = 220.dp
        val RimBlur = 3.dp
        val RimOffsetX = 2.dp
        val RimOffsetY = 2.dp
        val BannerHeight = 320.dp
        val BannerRadius = 28.dp
        val BannerTextStart = 32.dp
        val BannerTextBottom = 30.dp
        val BannerTextMaxWidth = 460.dp
        val BannerChipGap = 14.dp
        val BannerAboutGap = 10.dp
        val BannerActionsGap = 20.dp
        val FilterGap = 10.dp
        val PageGap = 16.dp
        val CrumbHeight = 44.dp
        val CrumbGap = 6.dp
        val FooterTopPadding = 18.dp
        val FooterGap = 14.dp
        val SheetWidth = 560.dp
        val SheetRowGap = 4.dp
        val SheetRowHeight = 52.dp
        val SheetPortrait = 32.dp
    }

    object News {
        const val LeadWeight = 1.45f
        val ColumnGap = 12.dp
        val LeadMinHeight = 276.dp
        val Radius = 16.dp
        val LeadHorizontal = 24.dp
        val LeadVertical = 22.dp
        val LeadGap = 8.dp
        const val ScrimFraction = 0.78f
        val ExcerptMaxWidth = 560.dp
        val MetaGap = 10.dp
        val TagHeight = 24.dp
        val TagStart = 7.dp
        val TagEnd = 9.dp
        val TagGap = 5.dp
        val TagIcon = 13.dp
        val FootTop = 6.dp
        val ReadHeight = 36.dp
        val ReadStart = 16.dp
        val ReadEnd = 12.dp
        val ReadGap = 4.dp
        val ReadIcon = 16.dp
        val ListPadding = 6.dp
        val ListGap = 2.dp
        val RowMinHeight = 62.dp
        val RowStart = 10.dp
        val RowEnd = 12.dp
        val RowVertical = 8.dp
        val RowGap = 12.dp
        val RowRadius = 12.dp
        val RowTextGap = 2.dp
        val RowTimeTop = 4.dp
        val IconTile = 36.dp
        val IconRadius = 11.dp
        val Icon = 18.dp
        const val IconTintAlpha = 0.16f
        const val GlassAlpha = 0.45f
        const val ReadFillAlpha = 0.14f
        val ReaderWidth = 640.dp
        val ReaderImageHeight = 220.dp
        val ReaderGap = 14.dp
        val ReaderParagraphGap = 10.dp
        val ReaderBodyMaxHeight = 420.dp
    }

    object SoundRow {
        val PlayButton = 40.dp
        val PlayButtonStartMargin = 4.dp
        val WaveformWidth = 96.dp
        val WaveformHeight = 36.dp
        val WaveformStroke = 3.dp
        val WaveformBarPitch = 6.dp
    }

    object Fact {
        val IconTile = 36.dp
        val Gap = 12.dp
        val GridRowGap = 14.dp
        val GridColumnGap = 12.dp
    }

    object Tabs {
        val Height = 46.dp
        val HorizontalPadding = 14.dp
        val Gap = 2.dp
        val LabelGap = 7.dp
        val Indicator = 3.dp
        val IndicatorInset = 12.dp
        val AttentionDot = 7.dp
        val ContentShift = 24.dp
    }

    object Settings {
        val ContentMaxWidth = 760.dp
        val HeaderTopPadding = 6.dp
        val TitleHorizontalPadding = 4.dp
        val TabsTopMargin = 12.dp
        val ListTopPadding = 16.dp
        val ListBottomPadding = 8.dp
        val BottomPadding = 24.dp
        val CardPadding = 18.dp
        val CardGap = 14.dp
        val IconTile = 40.dp
        val TitleGap = 8.dp
        val DescriptionTopPadding = 2.dp
        val PathHeight = 44.dp
        val PathHorizontalPadding = 14.dp
        val PathGap = 10.dp
        val PathIcon = 16.dp
        val PathErrorOutline = 1.5.dp
        val MessageGap = 10.dp
        val MessageIcon = 18.dp
        val MessageIconTopPadding = 1.dp
        val ButtonGap = 6.dp
        val ButtonHorizontalPadding = 16.dp
        val StatusChipHeight = 24.dp
        val StatusChipStartPadding = 7.dp
        val StatusChipEndPadding = 9.dp
        val StatusChipGap = 4.dp
        val StatusChipIcon = 14.dp
        val GroupGap = 2.dp
        val RowHorizontalPadding = 18.dp
        val RowVerticalPadding = 16.dp
        val NoteTopPadding = 8.dp
        val PickerTopPadding = 12.dp
        val SwatchWidth = 76.dp
        val SwatchGap = 6.dp
        val SwatchRadius = 16.dp
        val SwatchDot = 40.dp
        val SwatchCheck = 18.dp
        val SwatchRingGap = 3.dp
        val SwatchRing = 2.dp
        val CardTileMaxWidth = 280.dp
        val CardTileGap = 12.dp
        val CardTilePadding = 12.dp
        val CardTileRadius = 20.dp
        val CardTilePreviewHeight = 118.dp
        val CardTilePreviewRadius = 14.dp
        val CardTileRadio = 18.dp
        val HeadingHorizontalPadding = 4.dp
        val HeadingTopPadding = 24.dp
        val FirstHeadingTopPadding = 2.dp
        val HeadingBottomPadding = 8.dp
        val ResultsHeight = 46.dp
        val EmptyTopPadding = 48.dp
        val LicencesWidth = 640.dp
        val LicencesGap = 16.dp
        val LicenceTextPadding = 14.dp
    }

    object Notice {
        val Padding = 14.dp
        val Gap = 12.dp
        val IconGap = 10.dp
        val ActionGap = 6.dp

        val FloatingMaxWidth = 460.dp
        val FloatingMargin = 24.dp
        val StackGap = 8.dp
    }

    object Author {
        val Avatar = 32.dp
        val Gap = 10.dp
        val ListGap = 10.dp
    }

    object EmptyState {
        val IconTile = 56.dp
        val VerticalPadding = 72.dp
        val HorizontalPadding = 24.dp
        val Gap = 10.dp
        val TitleTopPadding = 6.dp
        val ActionTopPadding = 8.dp
    }

    object Cover {
        val WaveformBarPitch = 7.dp
    }

    object ImageOverlay {
        val Button = 40.dp
    }

    object Lightbox {
        val ImagePaddingHorizontal = 96.dp
        val ImagePaddingTop = 72.dp
        val ImagePaddingBottom = 112.dp
        val BarPadding = 20.dp
        val ArrowButton = 48.dp
        val ArrowInset = 24.dp
        val Thumb = 56.dp
        val ThumbGap = 8.dp
        val ThumbsBottom = 24.dp
        val ThumbSelectedBorder = 2.dp
    }

    object AuthorStack {
        val Overlap = 10.dp
        val Ring = 2.dp
        val Gap = 10.dp
        val Padding = 4.dp
    }

    object SoundStage {
        val Height = 440.dp
        val WaveformHeight = 128.dp
        val WaveformMaxWidth = 720.dp
        val WaveformSideInset = 64.dp
        /** The waveform's centre, as a fraction of the stage height. */
        const val WaveformCenterFraction = 0.42f
        val BarPitch = 9.dp
        val BarStroke = 4.dp
        val CursorWidth = 2.dp
    }

    object Detail {
        /** Below this the top bar's Details button (narrow windows only) shows just its icon. */
        val CompactTopBarWindowWidth = 1100.dp
        val ColumnsHorizontalPadding = 4.dp
        val AboutGap = 12.dp
        val BreadcrumbGap = 8.dp
        val EyebrowHeroIcon = 18.dp
        val EyebrowHeroIconGap = 8.dp
    }

    object Stage {
        val Height = 560.dp
        val HintStart = 18.dp
        val HintEnd = 16.dp
        val HintTop = 14.dp
        val HintGap = 6.dp
        val WordTop = 14.dp
        val WordMinSize = 72.dp
        val WordMaxSize = 160.dp
        /** The design sizes the word at 11% of the window width, between the two limits above. */
        const val WordWindowFraction = 0.11f
        val ModelArea = 380.dp
        val ModelAreaMinInset = 32.dp
        /** The model area's centre sits at this fraction of the stage height. */
        const val ModelCenterFraction = 0.43f
        const val CapsuleInsetFraction = 0.27f
        val CapsuleHeight = 334.dp
        val CapsuleBorder = 1.5.dp
        val CapsuleDash = 4.dp
        val CapsuleDashGap = 3.dp
        val CapsuleGap = 8.dp
        val ScrimPadding = 24.dp
        val ErrorPadding = 16.dp
        const val CapsuleTopAlpha = 0.10f
        const val CapsuleBottomAlpha = 0.03f
        const val CapsuleBorderAlpha = 0.38f
        const val SpotlightRadiusFraction = 0.46f
        const val SpotlightCenterY = 0.44f
        const val SpotlightFadeStop = 0.72f
        const val FloorGlowWidthFraction = 0.6f
        const val FloorGlowHeightFraction = 0.34f
        const val FloorGlowFadeStop = 0.7f
        const val StudioMiddleStop = 0.6f
        val ConeWidth = 620.dp
        const val ConeHeightFraction = 0.82f
        const val ConeMiddleStop = 0.62f
        const val ConeTopInset = 0.43f
        const val ConeBottomInset = 0.16f
        const val DramaticConeTopInset = 0.46f
        const val DramaticConeBottomInset = 0.26f
        const val VignetteWidthFraction = 0.8f
        const val VignetteHeightFraction = 0.9f
        const val VignetteCenterY = 0.45f
        const val VignetteClearStop = 0.55f
        const val PhotoShadeMiddleStop = 0.7f
        const val PhotoTurntableAlpha = 0.82f
        const val LoadingAlpha = 0.35f
        val LoadingRing = 30.dp
        val LoadingRingStroke = 3.dp
        val ErrorGap = 10.dp
        val ErrorButtonGap = 8.dp
        val ErrorMaxWidth = 260.dp
    }

    object Turntable {
        val Width = 420.dp
        val Height = 116.dp
        val Overhang = 20.dp
        val CenterY = 58.dp
        val RadiusX = 200.dp
        val RadiusY = 44.dp
        val SideDepth = 10.dp
        val EdgeStroke = 1.5.dp
        val InnerRadiusX = 140.dp
        val InnerRadiusY = 30.8.dp
        val InnerStroke = 1.dp
        val ShadowCenterY = 60.dp
        val ShadowRadiusX = 92.dp
        val ShadowRadiusY = 19.dp
        const val Ticks = 48
        const val MajorTickEvery = 4
        const val TickInner = 0.94f
        const val TickOuter = 0.98f
        const val LoadingTickInner = 0.932f
        const val LoadingTickOuter = 0.986f
        val MajorTickStroke = 1.6.dp
        val MinorTickStroke = 1.dp
        val LoadingTickStroke = 2.dp
        const val MajorTickAlpha = 0.55f
        const val MinorTickAlpha = 0.25f
        const val UnlitTickAlpha = 0.14f
        const val LitTickAlpha = 0.95f
        const val SheenAlpha = 0.10f
        const val SheenCenterY = 0.3f
        const val SheenRadius = 0.6f
        const val ShadowAlpha = 0.75f
        val ContactShadowWidth = 220.dp
        val ContactShadowHeight = 40.dp
        val ContactShadowBottom = 6.dp
        const val ContactShadowAlpha = 0.7f
        const val SpinDegreesPerSecond = 12f
        const val ArrivalSpinDegrees = 30f
    }

    object Scene {
        val Width = 320.dp
        val Top = 2.dp
        val EndOverhang = 4.dp
        val Padding = 14.dp
        val Gap = 6.dp
        val Radius = 22.dp
        val ShadowBlur = 44.dp
        val ShadowOffsetY = 18.dp
        val HeaderStart = 4.dp
        val HeaderEnd = 2.dp
        val HeaderBottom = 4.dp
        val ResetHeight = 30.dp
        val ResetHorizontal = 10.dp
        val SectionTop = 8.dp
        val SectionHorizontal = 4.dp
        val SectionBottom = 2.dp
        val TileGap = 8.dp
        val TileHeight = 54.dp
        val TileRadius = 12.dp
        val TileLabelGap = 5.dp
        val TileLabelStart = 2.dp
        val TileRing = 2.dp
        val TileRingGap = 2.dp
        val TileEdge = 1.dp
        val AddOutline = 1.5.dp
        val AddIcon = 18.dp
        val RowMinHeight = 38.dp
        val RowHorizontal = 4.dp
        const val SwitchScale = 0.86f
        const val TileEdgeAlpha = 0.06f
    }

    object Variants {
        val Inset = 16.dp
        /** The closed strip's cap: a long file name gives way here, never the purpose label beside it. */
        val TriggerMaxWidth = 288.dp
        val LabelStart = 12.dp
        val LabelGap = 9.dp
        val RowMinHeight = 38.dp
        val RowVertical = 8.dp
        val RowHorizontal = 10.dp
        val RowRadius = 15.dp
        val RowGap = 10.dp
    }

    object ImagesStage {
        val Padding = 12.dp
        val Gap = 10.dp
        val Radius = 12.dp
        val NavButton = 44.dp
        val CornerInset = 14.dp
        val CountHeight = 28.dp
        val CountHorizontal = 10.dp
        val FullSizeHeight = 32.dp
        val FullSizeStart = 10.dp
        val FullSizeEnd = 12.dp
        val FullSizeGap = 6.dp
        val FullSizeIcon = 16.dp
        val ThumbWidth = 100.dp
        val ThumbHeight = 64.dp
        val ThumbRadius = 10.dp
        val ThumbGap = 8.dp
        val StripTop = 2.dp
        val ThumbRing = 2.dp
        val ThumbRingGap = 2.dp
        const val ThumbIdleAlpha = 0.55f
        const val ThumbHoverAlpha = 0.85f
    }

    object Changelog {
        val RowPadding = 10.dp
        val RowStart = 12.dp
        val RowRadius = 14.dp
        val RowGap = 12.dp
        val Chevron = 18.dp
        val HeaderStart = 8.dp
        val HeaderEnd = 12.dp
        val HeaderBottom = 6.dp
        val HeaderGap = 8.dp
        val CardHorizontal = 14.dp
        val CardTop = 14.dp
        val CardBottom = 16.dp
        val CardRadius = 16.dp
        val CardGap = 8.dp
        val CurrentRing = 1.5.dp
        val ListGap = 10.dp
        val VersionHeight = 22.dp
        val VersionHorizontal = 8.dp
        val VersionRadius = 7.dp
        val TagWidth = 62.dp
        val TagTop = 2.dp
        val LineGap = 10.dp
    }

    object Scrubber {
        val Bottom = 84.dp
        val MaxWidth = 560.dp
        val NameMaxWidth = 140.dp
        val SideInset = 64.dp
        val Gap = 10.dp
        val WaveStroke = 3.dp
        val Wavelength = 20.dp
        val WaveAmplitude = 2.5.dp
        val HandleWidth = 4.dp
        val HandleHeight = 20.dp
        val TrackHeight = 4.dp
    }

    object Toolbar {
        val Bottom = 16.dp
        val Height = 56.dp
        val Padding = 8.dp
        val Gap = 4.dp
        val SideInset = 32.dp
        val ShadowOffsetY = 10.dp
        val ShadowBlur = 30.dp
        val PillHeight = 40.dp
        val DividerHeight = 20.dp
        val DividerMargin = 4.dp
        val EnterOffset = 24.dp
        val ChangedDot = 6.dp
        val Chevron = 14.dp
        /** The chevron reaches this far into its pill's end padding. */
        val ChevronOverhang = 4.dp
    }

    object AnimationMenu {
        val Width = 256.dp
        val Padding = 6.dp
        val Radius = 20.dp
        val Gap = 8.dp
        /** How far the menu's edge reaches past its pill's. */
        val PillOverhang = 6.dp
        val StageInset = 16.dp
        val ShadowBlur = 44.dp
        val ShadowOffsetY = 18.dp
        val HeaderTop = 8.dp
        val HeaderHorizontal = 10.dp
        val HeaderBottom = 6.dp
        val ListMaxHeight = 336.dp
        val RowHeight = 36.dp
        val RowGap = 2.dp
        val RowRadius = 12.dp
        val RowStart = 6.dp
        val RowEnd = 10.dp
        val RowContentGap = 8.dp
        val NowPlayingSlot = 20.dp
        val TagHeight = 18.dp
        val TagHorizontal = 6.dp
        val TagRadius = 6.dp
        val FooterMarginTop = 6.dp
        val FooterTop = 8.dp
        val FooterHorizontal = 10.dp
        val FooterBottom = 4.dp
        val FooterGap = 6.dp
        val KeyCap = 20.dp
        val KeyCapRadius = 6.dp
        val KeyCapGlyph = 12.dp
    }

    object AbilityStrip {
        val End = 16.dp
        val Padding = 6.dp
        val Radius = 26.dp
        val Gap = 6.dp
        val Tile = 46.dp
        val TileRadius = 14.dp
        val DividerWidth = 20.dp
        val DividerHeight = 1.5.dp
        val DividerMargin = 2.dp
        val KeyInset = 3.dp
        val KeyHeight = 15.dp
        val KeyRadius = 5.dp
        val KeyHorizontal = 3.dp
        /** The selected tile's ring: a surface gap, then primary. */
        val RingGap = 2.dp
        val Ring = 2.dp
        val TooltipGap = 16.dp
        val TooltipRadius = 12.dp
        val TooltipHorizontal = 11.dp
        val TooltipVertical = 7.dp
    }

    object Tooltip {
        val HorizontalPadding = 8.dp
        val VerticalPadding = 5.dp
    }

    object InstallDialog {
        val Width = 560.dp
        val WindowMargin = 24.dp
        val Padding = 24.dp
        val SectionGap = 16.dp
        val Radius = 28.dp
        val ShadowOffsetY = 24.dp
        val ShadowBlur = 64.dp
        val HeaderGap = 14.dp
        val Thumb = 56.dp
        val ThumbRadius = 16.dp
        val CloseOutset = 6.dp
        val RowGap = 4.dp
        val RowMinHeight = 64.dp
        val RowRadius = 16.dp
        val RowStartPadding = 14.dp
        val RowEndPadding = 8.dp
        val RowVerticalPadding = 10.dp
        val RowContentGap = 14.dp
        val RowLineGap = 3.dp
        val PreviewIcon = 16.dp
        val GroupBorder = 1.dp
        val GroupPadding = 4.dp
        val GroupLabelTop = 6.dp
        val NoteRadius = 14.dp
        val NoteHorizontalPadding = 14.dp
        val NoteVerticalPadding = 12.dp
        val NoteGap = 10.dp
        val NoteIcon = 18.dp
        val NoteIconTop = 1.dp
        val NotesMaxHeight = 140.dp
        val ProfilesGap = 8.dp
        val FooterTopPadding = 4.dp
        val FooterGap = 8.dp
        val CancelPadding = 20.dp
        val InstallStartPadding = 20.dp
        val InstallEndPadding = 24.dp
    }

    object Scrollbar {
        val Thickness = 6.dp
        val ThicknessHovered = 8.dp
        val Inset = 3.dp
        val MinThumb = 32.dp
    }

    object OptionCard {
        val MinHeight = 76.dp
        val Radius = 20.dp
        val StartPadding = 14.dp
        val EndPadding = 16.dp
        val VerticalPadding = 14.dp
        val Gap = 14.dp
        val Tile = 46.dp
        val TileRadius = 15.dp
        val TitleGap = 8.dp
        val DetailGap = 3.dp
        val Ring = 2.dp
        val Radio = 22.dp
        val RadioDotInset = 5.dp
    }

    object CheckboxRow {
        val MinHeight = 62.dp
        val Radius = 18.dp
        val StartPadding = 14.dp
        val EndPadding = 16.dp
        val VerticalPadding = 10.dp
        val Gap = 14.dp
        val Box = 22.dp
        val DetailGap = 2.dp
        val CountMinWidth = 28.dp
    }

    object StatusCircle {
        val Size = 30.dp
        val Check = 16.dp
        val Ring = 2.dp
        val ProgressDiameter = 22.dp
        val ProgressStroke = 3.dp
    }

    object ArtPanel {
        val Radius = 20.dp
        val StageInset = 60.dp
        val CardRadius = 24.dp
        val CardShadow = 30.dp
    }
}
