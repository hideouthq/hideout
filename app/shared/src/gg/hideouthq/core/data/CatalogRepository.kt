package gg.hideouthq.core.data

import gg.hideouthq.core.library.FilesObservation
import gg.hideouthq.core.library.SubmissionRef
import gg.hideouthq.core.model.CalendarDate
import gg.hideouthq.core.model.DiscoverFeed
import gg.hideouthq.core.model.Hero
import gg.hideouthq.core.model.Mod
import gg.hideouthq.core.model.ModDetail
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * The public catalogue: what exists, independent of what is installed. The streams emit once the
 * catalogue holds data; until then (for example during a first check) they stay silent, and
 * [freshness] says whether data is on its way or the source could not be reached.
 */
interface CatalogRepository {
    val mods: Flow<List<Mod>>
    val heroes: Flow<List<Hero>>
    val previewsAvailable: Boolean

    val popularHeroIds: Flow<List<String>>
    val discoverFeed: Flow<DiscoverFeed>

    val today: CalendarDate

    val freshness: StateFlow<CatalogFreshness>

    /** What the source last said about each installed submission's current files, for telling whether they changed. */
    val installedFiles: StateFlow<Map<SubmissionRef, FilesObservation>>

    /**
     * Asks about the installed submissions' files, then checks the catalogue, now, however recently it last did. Asked
     * during a check, it checks once more after.
     */
    fun refresh()

    fun detail(modId: String): Flow<Loadable<ModDetail>>

    /**
     * What the catalogue already holds in memory for [modId], without waiting. A page opened from a
     * card uses it to show the card's mod from its first frame, so the card can turn into the page.
     */
    fun cachedMod(modId: String): Mod?

    /** The hero, if the catalogue already holds it in memory. */
    fun cachedHero(heroId: String): Hero?
}
