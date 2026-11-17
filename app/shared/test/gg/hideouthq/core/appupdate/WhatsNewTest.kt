package gg.hideouthq.core.appupdate

import gg.hideouthq.core.model.CalendarDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WhatsNewTest {
    private val running = AppVersion(1, 3, 2)
    private val offered = AppVersion(1, 4, 0)
    private val pkg = ReleasePackage(offered, PackageFormat.AppImage, "https://example.test/a", 1000, "a".repeat(64))
    private val url = "https://example.test/releases"

    private fun notes(version: AppVersion) = ReleaseNotes(version, CalendarDate(2026, 11, 2), ReleaseKind.Fixes, emptyList(), emptyList(), emptyList(), emptyList())

    private val feed = ReleaseFeed(offered, url, listOf(notes(offered), notes(AppVersion(1, 3, 3)), notes(running), notes(AppVersion(1, 3, 1))), mapOf(pkg.format to pkg))

    private fun state(phase: AppUpdatePhase) = AppUpdateState(running, phase, feed)

    @Test
    fun theFooterAndHeadlineFollowThePhase() {
        val available = WhatsNewHeadline.Available(offered, running)
        val table = listOf(
            AppUpdatePhase.GitHubOnly(offered, url) to (available to WhatsNewFooter.GitHub),
            AppUpdatePhase.Available(pkg) to (available to WhatsNewFooter.Download(1000)),
            AppUpdatePhase.Downloading(pkg, 620) to (available to WhatsNewFooter.Downloading(62)),
            AppUpdatePhase.Failed(offered, pkg, UpdateFailure.Connection, url) to (available to WhatsNewFooter.Download(1000)),
            AppUpdatePhase.Failed(offered, null, UpdateFailure.Install, url) to (available to WhatsNewFooter.GitHub),
            AppUpdatePhase.Failed(offered, pkg, UpdateFailure.NotReplaced, url) to (available to WhatsNewFooter.GitHub),
            AppUpdatePhase.Ready(pkg, RestartStage.Idle) to (WhatsNewHeadline.Ready(offered, running) to WhatsNewFooter.Restart(RestartStage.Idle)),
            AppUpdatePhase.Ready(pkg, RestartStage.Waiting) to (WhatsNewHeadline.Ready(offered, running) to WhatsNewFooter.Restart(RestartStage.Waiting)),
        )
        for ((phase, expected) in table) {
            val view = whatsNew(state(phase))
            assertEquals(expected.first, view.headline, "$phase")
            assertEquals(expected.second, view.footer, "$phase")
            assertEquals(url, view.notesUrl)
        }
    }

    @Test
    fun withNothingOnOfferItSaysWhatIsInstalledAndWhenItWasReleased() {
        val view = whatsNew(state(AppUpdatePhase.Idle))
        assertEquals(WhatsNewHeadline.Installed(running, CalendarDate(2026, 11, 2)), view.headline)
        assertEquals(WhatsNewFooter.UpToDate, view.footer)
    }

    @Test
    fun aRunningVersionMissingFromTheFeedHasNoDate() {
        val unknown = AppVersion(1, 0, 0)
        assertEquals(WhatsNewHeadline.Installed(unknown, null), whatsNew(AppUpdateState(unknown, feed = feed)).headline)
    }

    @Test
    fun tagsMarkTheVersionOnOfferAndTheVersionRunningAndNoOther() {
        val tags = whatsNew(state(AppUpdatePhase.Available(pkg))).versions.map { it.notes.version.toString() to it.tag }
        assertEquals(
            listOf("1.4.0" to WhatsNewTag.New, "1.3.3" to null, "1.3.2" to WhatsNewTag.Installed, "1.3.1" to null),
            tags,
        )
    }

    @Test
    fun aVersionNewerThanRunningIsNotNewWhenNothingIsOnOffer() {
        assertEquals(listOf(null, null, WhatsNewTag.Installed, null), whatsNew(state(AppUpdatePhase.Idle)).versions.map { it.tag })
    }

    @Test
    fun onlyTheLastTenVersionsAreListed() {
        val many = (0..14).map { notes(AppVersion(1, 4, 14 - it)) }
        val view = whatsNew(AppUpdateState(running, feed = feed.copy(versions = many)))
        assertEquals(10, view.versions.size)
        assertEquals(AppVersion(1, 4, 14), view.versions.first().notes.version)
    }

    @Test
    fun withNoFeedYetThereIsNothingToListOrLinkTo() {
        val view = whatsNew(AppUpdateState(running))
        assertEquals(emptyList(), view.versions)
        assertNull(view.notesUrl)
    }
}
