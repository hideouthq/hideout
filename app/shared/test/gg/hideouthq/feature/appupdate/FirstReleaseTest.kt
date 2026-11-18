package gg.hideouthq.feature.appupdate

import gg.hideouthq.core.appupdate.AppUpdateState
import gg.hideouthq.core.appupdate.AppVersion
import gg.hideouthq.core.appupdate.ReleaseFeed
import gg.hideouthq.core.appupdate.ReleaseKind
import gg.hideouthq.core.appupdate.ReleaseNotes
import gg.hideouthq.core.appupdate.whatsNew
import gg.hideouthq.core.model.CalendarDate
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FirstReleaseTest {
    private fun notes(patch: Int) = ReleaseNotes(AppVersion(0, 0, patch), CalendarDate(2026, 11, 18), ReleaseKind.Feature, emptyList(), emptyList(), emptyList(), emptyList())

    private fun state(vararg patches: Int): AppUpdateState {
        val versions = patches.map(::notes)
        return AppUpdateState(versions.first().version, feed = ReleaseFeed(versions.first().version, "https://example.test/releases", versions, emptyMap()))
    }

    @Test
    fun theOnlyVersionInTheFeedIsTheFirstRelease() {
        assertTrue(firstRelease(state(1), notes(1)))
    }

    @Test
    fun onlyTheOldestVersionIsTheFirstRelease() {
        val feed = state(3, 2, 1)
        assertTrue(firstRelease(feed, notes(1)))
        assertFalse(firstRelease(feed, notes(3)))
        assertFalse(firstRelease(feed, notes(2)))
    }

    @Test
    fun theLastVersionTheDialogListsIsNotTheFirstReleaseWhenTheFeedHasOlderOnes() {
        val feed = state(*(12 downTo 1).toList().toIntArray())
        val oldestListed = whatsNew(feed).versions.last().notes
        assertFalse(firstRelease(feed, oldestListed), "the dialog lists ${oldestListed.version}, but the feed goes back to 0.0.1")
        assertTrue(firstRelease(feed, notes(1)))
    }

    @Test
    fun withNoFeedNothingIsTheFirstRelease() {
        assertFalse(firstRelease(AppUpdateState(AppVersion(0, 0, 1)), notes(1)))
        assertFalse(firstRelease(state(1), null))
    }
}
