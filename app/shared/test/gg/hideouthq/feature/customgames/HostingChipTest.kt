package gg.hideouthq.feature.customgames

import gg.hideouthq.core.customgames.DisplayName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HostingChipTest {
    private fun chip(status: Status, count: Pair<Int, Int>? = null, score: Pair<Int, Int>? = null, title: HostingTitle = HostingTitle.Hosting("Mode")) =
        HostingChipView(title, null, status, count, score, null, opens = true)

    @Test
    fun aGameOnItsWaySaysSoWithoutACount() {
        assertEquals(StatusDetail.Pending, statusDetail(chip(Status.Starting, count = 0 to 2)))
    }

    @Test
    fun aReadyHostShowsWhoIsInEvenWithALobbyScoreboard() {
        assertEquals(StatusDetail.Count(1, 2), statusDetail(chip(Status.Ready, count = 1 to 2, score = 0 to 0)))
    }

    @Test
    fun aMatchShowsItsScoreOverTheCount() {
        assertEquals(StatusDetail.Score(3, 2), statusDetail(chip(Status.InMatch, count = 2 to 2, score = 3 to 2)))
    }

    @Test
    fun aGuestWithNoScoreShowsTheStatusAlone() {
        assertEquals(StatusDetail.None, statusDetail(chip(Status.InMatch, title = HostingTitle.InGame(null))))
    }

    @Test
    fun aGuestInARunningMatchReadsAsWhoseGameItIs() {
        val friend = HostingTitle.InGame(DisplayName.parse("nori"))
        assertTrue(inFriendsGame(chip(Status.InMatch, title = friend)))
        assertFalse(inFriendsGame(chip(Status.LostConnection, title = friend)), "a dropped guest is told so")
        assertFalse(inFriendsGame(chip(Status.InMatch)), "a host's chip says the status")
    }
}
