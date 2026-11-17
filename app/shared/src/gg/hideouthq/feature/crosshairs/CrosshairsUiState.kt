package gg.hideouthq.feature.crosshairs

import gg.hideouthq.core.crosshair.CrosshairId
import gg.hideouthq.core.crosshair.CrosshairsState
import gg.hideouthq.core.crosshair.GameCrosshair
import gg.hideouthq.core.crosshair.SavedCrosshair
import gg.hideouthq.core.crosshair.community.BrowseQuery
import gg.hideouthq.core.crosshair.community.CommunityCrosshair
import gg.hideouthq.core.data.Loadable
import gg.hideouthq.core.data.SavedNote
import gg.hideouthq.core.model.CalendarDate
import gg.hideouthq.core.navigation.CrosshairsTab
import gg.hideouthq.core.ui.crosshair.CrosshairBackdrop
import java.time.ZoneId
import kotlin.time.Instant

data class CrosshairsUiState(
    val tab: CrosshairsTab,
    val backdrop: CrosshairBackdrop,
    /** What Deadlock runs, for the header's In use pill and Yours; null when no crosshair of Yours runs. */
    val inUse: SavedCrosshair?,
    val note: SavedNote,
    /** Why Use in game cannot reach the game; null once one is found, its saved settings have been read, and Yours can change. */
    val cannotUse: CannotUse?,
    val browse: BrowseUi,
    val yours: YoursUi,
    /** The Import box, while open. */
    val importing: ImportUi?,
)

data class BrowseUi(val query: BrowseQuery, val results: Loadable<BrowseResults>)

/** Every match, and the panel's crosshair: the one picked while it shows, else the first. */
data class BrowseResults(val cards: List<CommunityCard>, val selected: CommunityCard?)

/** Why Use in game is greyed out. */
enum class CannotUse { NoGame, Reading, NotSavedYet, Unreadable, YoursUnreadable }

/**
 * A community crosshair: [inUse] when Deadlock runs exactly its settings, [saved] when Yours keeps it unedited.
 * [canToggleSaved] is false when the crosshair in use is the entry of Yours saved from it, which Unsave keeps. [made] is
 * shown without its year when [thisYear].
 */
data class CommunityCard(val crosshair: CommunityCrosshair, val made: CalendarDate, val thisYear: Boolean, val inUse: Boolean, val saved: Boolean, val canToggleSaved: Boolean)

data class YoursUi(
    val cards: List<SavedCrosshair>,
    /** The editor's crosshair: the one picked, else the one in use, else the first. */
    val selected: SavedCrosshair?,
    val zoom: PreviewZoom,
    /** The name field as typed, while it differs from the name Yours keeps: blank, or with spaces Rename trims. */
    val nameDraft: String?,
)

/** The preview's sizes, in layout units per dp: Actual size is the crosshair as the game draws it at 1080p. */
enum class PreviewZoom(val zoom: Float) {
    Actual(1f),
    Three(3f),
    Six(6f),
}

/** The pasted text, and whether Add to yours found no crosshair in it. */
data class ImportUi(val text: String = "", val unreadable: Boolean = false)

internal data class CrosshairsLocal(
    val tab: CrosshairsTab,
    val backdrop: CrosshairBackdrop = CrosshairBackdrop.Dark,
    val query: BrowseQuery = BrowseQuery(),
    val community: String? = null,
    val yours: CrosshairId? = null,
    val nameDraft: String? = null,
    val zoom: PreviewZoom = PreviewZoom.Three,
    val importing: ImportUi? = null,
    val used: Boolean = false,
)

internal fun crosshairsUi(crosshairs: CrosshairsState, community: Loadable<List<CommunityCrosshair>>, local: CrosshairsLocal, now: Instant, zone: ZoneId): CrosshairsUiState {
    val inUse = crosshairs.inUse
    val entries = crosshairs.yours.entries
    val saved = entries.mapNotNullTo(mutableSetOf()) { it.savedFrom }
    val inUseFrom = inUse?.savedFrom
    val year = date(now, zone).year
    val results = when (community) {
        Loadable.Loading -> Loadable.Loading
        is Loadable.Failed -> community
        is Loadable.Ready -> {
            val cards = local.query.matches(community.value, now).map { crosshair ->
                val made = date(crosshair.createdAt, zone)
                CommunityCard(crosshair, made, made.year == year, inUse?.crosshair == crosshair.crosshair, crosshair.id in saved, canToggleSaved = inUseFrom != crosshair.id)
            }
            Loadable.Ready(BrowseResults(cards, cards.firstOrNull { it.crosshair.id == local.community } ?: cards.firstOrNull()))
        }
    }
    return CrosshairsUiState(
        tab = local.tab,
        backdrop = local.backdrop,
        inUse = inUse,
        note = crosshairs.note.takeUnless { it == SavedNote.GameFolderOnly && !local.used } ?: SavedNote.NextStart,
        cannotUse = when (crosshairs.game) {
            GameCrosshair.NoGame -> CannotUse.NoGame
            GameCrosshair.Reading -> CannotUse.Reading
            GameCrosshair.NotSavedYet -> CannotUse.NotSavedYet
            is GameCrosshair.Unreadable -> CannotUse.Unreadable
            is GameCrosshair.Saved -> CannotUse.YoursUnreadable.takeIf { crosshairs.yoursUnreadable }
        },
        browse = BrowseUi(local.query, results),
        yours = YoursUi(entries, entries.firstOrNull { it.id == local.yours } ?: inUse ?: entries.firstOrNull(), local.zoom, local.nameDraft),
        importing = local.importing,
    )
}

private fun date(at: Instant, zone: ZoneId): CalendarDate =
    java.time.Instant.ofEpochMilli(at.toEpochMilliseconds()).atZone(zone).let { CalendarDate(it.year, it.monthValue, it.dayOfMonth) }
