package gg.hideouthq.core.navigation

import gg.hideouthq.core.model.ModCategory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HideoutNavigatorTest {
    private val browseSkins = Route.Browse(category = ModCategory.Skins)

    @Test
    fun backAndForwardWalkTheHistory() {
        val nav = HideoutNavigator(Route.Discover)
        nav.navigate(browseSkins)
        nav.navigate(Route.Library)
        assertTrue(nav.back())
        assertEquals(browseSkins, nav.current.route)
        assertTrue(nav.canGoForward)
        assertTrue(nav.forward())
        assertEquals(Route.Library, nav.current.route)
        assertFalse(nav.canGoForward)
    }

    @Test
    fun navigatingAfterGoingBackDropsTheForwardHistory() {
        val nav = HideoutNavigator(Route.Discover)
        nav.navigate(Route.Library)
        nav.back()
        nav.navigate(Route.Settings)
        assertFalse(nav.canGoForward)
        assertEquals(listOf(Route.Discover, Route.Settings), nav.backStack.map { it.route })
    }

    @Test
    fun clickingTheCurrentSectionScrollsToTopInsteadOfPushing() {
        val nav = HideoutNavigator(Route.Discover)
        val id = nav.current.id
        nav.openSection(Section.Discover)
        assertEquals(1, nav.backStack.size)
        assertEquals(1, nav.scrollToTopRequests(id))
    }

    @Test
    fun aModPageBelongsToTheSectionItWasOpenedFrom() {
        val nav = HideoutNavigator(Route.Discover)
        nav.openSection(Section.Browse)
        nav.navigate(Route.ModDetail("lilith"))
        assertEquals(Section.Browse, nav.activeSection)
        nav.openSection(Section.Browse)
        assertEquals(Route.Browse(), nav.current.route)
        assertTrue(nav.forward())
        assertEquals(Route.ModDetail("lilith"), nav.current.route)
    }

    @Test
    fun collectionPagesKeepDiscoverActive() {
        val nav = HideoutNavigator(Route.Discover)
        nav.navigate(Route.Collection("halloween"))
        assertEquals(Section.Discover, nav.activeSection)
        nav.navigate(Route.Collections)
        assertEquals(Section.Discover, nav.activeSection)
        assertEquals(Section.Discover, HideoutNavigator(Route.Collection("halloween")).activeSection)
    }

    @Test
    fun previewingAFileOpensItsModPageOnceAndAsksTheSamePageAgainInPlace() {
        val nav = HideoutNavigator(Route.Discover)
        nav.previewFile("mod-7", 11, "a".repeat(32))
        assertEquals(listOf(Route.Discover, Route.ModDetail("mod-7")), nav.backStack.map { it.route })
        val page = nav.current.id
        val first = assertNotNull(nav.previewRequest(page))
        assertEquals(11L to "a".repeat(32), first.fileId to first.fileMd5)

        nav.previewFile("mod-7", 12, null)
        assertEquals(2, nav.backStack.size, "the open page of the same mod is not pushed again")
        val second = assertNotNull(nav.previewRequest(page))
        assertEquals(12L, second.fileId)
        assertTrue(second.serial > first.serial)
        nav.previewFile("mod-7", 12, null)
        assertTrue(assertNotNull(nav.previewRequest(page)).serial > second.serial, "the same file asked again is a new request")
        assertNull(nav.previewRequest(0), "other entries are asked nothing")

        nav.previewFile("mod-8", 3, null)
        assertEquals(Route.ModDetail("mod-8"), nav.current.route)
        assertEquals(3L, assertNotNull(nav.previewRequest(nav.current.id)).fileId)
    }

    @Test
    fun aForwardHistoryDroppedByNavigatingForgetsItsRequests() {
        val nav = HideoutNavigator(Route.Discover)
        nav.previewFile("mod-7", 11, null)
        val page = nav.current.id
        nav.back()
        assertNotNull(nav.previewRequest(page), "a page that can still be returned to keeps its request")
        nav.navigate(Route.Library)
        assertNull(nav.previewRequest(page))
    }

    @Test
    fun aMatchBelongsToStatsAndTheSidebarReturnsToYourOwnStats() {
        val nav = HideoutNavigator(Route.Library)
        nav.openSection(Section.Stats)
        assertEquals(Route.Stats(), nav.current.route)
        nav.navigate(Route.StatsMatch(41_236_118))
        assertEquals(Section.Stats, nav.activeSection)
        nav.openSection(Section.Stats)
        assertEquals(Route.Stats(), nav.current.route, "the sidebar steps back out of the match")

        nav.navigate(Route.Stats(visiting = 7))
        assertEquals(Section.Stats, nav.activeSection, "another player's stats still light up Stats")
        nav.navigate(Route.StatsMatch(9, visiting = 7))
        nav.openSection(Section.Stats)
        assertEquals(Route.Stats(), nav.current.route, "the sidebar item means your own stats, not the visited player's")
        assertEquals(0, nav.scrollToTopRequests(nav.current.id))
        nav.openSection(Section.Stats)
        assertEquals(1, nav.scrollToTopRequests(nav.current.id))
    }

    @Test
    fun theSidebarsCrosshairsLeavesYoursForBrowse() {
        val nav = HideoutNavigator(Route.Autoexec)
        nav.navigate(Route.Crosshairs(CrosshairsTab.Yours))
        nav.openSection(Section.Crosshairs)
        assertEquals(Route.Crosshairs(), nav.current.route, "Yours, opened from Autoexec, is not where the sidebar leads")
        assertEquals(0, nav.scrollToTopRequests(nav.current.id))
        nav.openSection(Section.Crosshairs)
        assertEquals(1, nav.scrollToTopRequests(nav.current.id), "on Browse it scrolls to the top")
    }
}
