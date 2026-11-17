package gg.hideouthq.core.navigation

import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** One visit in the history. The same route can appear more than once, each with its own state. */
data class HistoryEntry(val id: Long, val route: Route)

enum class NavDirection { Forward, Back }

/**
 * Asks a mod page to load its 3D preview: of exactly the GameBanana file [fileId] when it names one, whose MD5 is
 * [fileMd5] when GameBanana lists one, else of the file the page shows. [serial] makes a repeat a new request.
 */
data class PreviewRequest(val fileId: Long?, val fileMd5: String?, val serial: Int)

/**
 * A browser-style history: [backStack] holds the visited entries up to the current one and a
 * forward stack holds the ones stepped back from. Both stacks retain entry state; only [current] is displayed.
 */
@Stable
class HideoutNavigator(start: Route) {
    private var nextId = 0L
    val backStack = mutableStateListOf(newEntry(start))
    private val forwardStack = mutableStateListOf<HistoryEntry>()
    private val scrollToTop = mutableStateMapOf<Long, Int>()
    private val previewRequests = mutableStateMapOf<Long, PreviewRequest>()
    private var lastPreviewRequest = 0

    var direction by mutableStateOf(NavDirection.Forward)
        private set

    var navigationCount by mutableIntStateOf(0)
        private set

    /** The entry shown before the latest navigation. */
    var previous by mutableStateOf<HistoryEntry?>(null)
        private set

    /**
     * The entry the display has finished animating to, or null while a navigation is still moving. Something that
     * waits for the screen to settle compares it with [current].
     */
    var settledEntryId by mutableStateOf<Long?>(null)
        internal set

    internal val retainedEntries: List<HistoryEntry> get() = backStack + forwardStack.asReversed()
    val current: HistoryEntry get() = backStack.last()
    val canGoBack: Boolean get() = backStack.size > 1
    val canGoForward: Boolean get() = forwardStack.isNotEmpty()

    /** The sidebar section that is active: the current route's, or the one a mod or collection page was opened from. */
    val activeSection: Section by derivedStateOf {
        backStack.asReversed().firstNotNullOfOrNull { it.route.ownSection }
            ?: when (current.route) {
                is Route.ModDetail -> Section.Browse
                is Route.StatsMatch -> Section.Stats
                is Route.CustomGameMode -> Section.CustomGames
                else -> Section.Discover
            }
    }

    fun navigate(route: Route) {
        if (route == current.route) {
            requestScrollToTop()
            return
        }
        previous = current
        direction = NavDirection.Forward
        forwardStack.forEach { previewRequests.remove(it.id) }
        forwardStack.clear()
        backStack.add(newEntry(route))
        navigationCount++
    }

    /**
     * Shows exactly [fileId] of [modId] in a mod page's viewer: in place when that mod's page is the current one, so
     * history gains no copy of it, and on a newly opened page otherwise.
     */
    fun previewFile(modId: String, fileId: Long, fileMd5: String?) = requestPreview(modId, fileId, fileMd5)

    /** Loads [modId]'s 3D preview of whatever file its page shows, whatever the setting for loading them by default. */
    fun previewMod(modId: String) = requestPreview(modId, fileId = null, fileMd5 = null)

    private fun requestPreview(modId: String, fileId: Long?, fileMd5: String?) {
        if ((current.route as? Route.ModDetail)?.modId != modId) navigate(Route.ModDetail(modId))
        previewRequests[current.id] = PreviewRequest(fileId, fileMd5, ++lastPreviewRequest)
    }

    /** The latest exact-file request made of the entry, if any. */
    fun previewRequest(entryId: Long): PreviewRequest? = previewRequests[entryId]

    fun back(): Boolean {
        if (!canGoBack) return false
        previous = current
        direction = NavDirection.Back
        forwardStack.add(backStack.removeAt(backStack.lastIndex))
        navigationCount++
        return true
    }

    fun forward(): Boolean {
        if (!canGoForward) return false
        previous = current
        direction = NavDirection.Forward
        backStack.add(forwardStack.removeAt(forwardStack.lastIndex))
        navigationCount++
        return true
    }

    /**
     * A sidebar click. On the section's own screen it scrolls back to the top; on a page that belongs
     * to the section (a mod page opened from Browse) it steps back to the section; otherwise it
     * navigates to the section. Another player's stats belong to Stats, but its sidebar item means your own, and
     * Crosshairs opened on Yours belongs to Crosshairs, but its sidebar item opens Browse. The game screen belongs to Custom
     * games, and its sidebar item opens the library.
     */
    fun openSection(section: Section) {
        val target = backStack.indexOfLast { it.route.ownSection != null }
        when {
            current.route.isHomeOf(section) -> requestScrollToTop()
            target >= 0 && backStack[target].route.isHomeOf(section) -> {
                previous = current
                direction = NavDirection.Back
                while (backStack.lastIndex > target) forwardStack.add(backStack.removeAt(backStack.lastIndex))
                navigationCount++
            }
            else -> navigate(section.root)
        }
    }

    /** How many times the entry has been asked to scroll to the top. */
    fun scrollToTopRequests(entryId: Long): Int = scrollToTop[entryId] ?: 0

    private fun requestScrollToTop() {
        scrollToTop[current.id] = scrollToTopRequests(current.id) + 1
    }

    private fun newEntry(route: Route) = HistoryEntry(nextId++, route)

    private fun Route.isHomeOf(section: Section) = ownSection == section && when (this) {
        is Route.Stats -> visiting == null
        is Route.Crosshairs -> tab == CrosshairsTab.Browse
        Route.CustomGame -> false
        else -> true
    }
}
