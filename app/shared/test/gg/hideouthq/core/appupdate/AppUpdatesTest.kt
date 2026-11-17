package gg.hideouthq.core.appupdate

import gg.hideouthq.core.appupdate.AppUpdatePhase.Available
import gg.hideouthq.core.appupdate.AppUpdatePhase.Downloading
import gg.hideouthq.core.appupdate.AppUpdatePhase.Failed
import gg.hideouthq.core.appupdate.AppUpdatePhase.GitHubOnly
import gg.hideouthq.core.appupdate.AppUpdatePhase.Idle
import gg.hideouthq.core.appupdate.AppUpdatePhase.Ready
import gg.hideouthq.core.appupdate.RestartStage.Exiting
import gg.hideouthq.core.appupdate.RestartStage.Installing
import gg.hideouthq.core.appupdate.RestartStage.Waiting
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class AppUpdatesTest {
    private val offeredPkg = packageOf(Offered)
    private val newerPkg = packageOf(Newer)
    private val debPkg = packageOf(Offered, PackageFormat.Deb)
    private val ready = Ready(offeredPkg, RestartStage.Idle)

    private fun TestScope.rig() = Rig(this)

    private fun TestScope.started(rig: Rig, method: AppUpdateMethod? = rig.methods.atExit): AppUpdates =
        rig.updates(method).apply {
            start()
            runCurrent()
        }

    private fun TestScope.readyRun(rig: Rig, method: AppUpdateMethod? = rig.methods.atExit): AppUpdates {
        rig.feed.offers(Offered, method?.format)
        rig.packages.onDisk += packageOf(Offered, method?.format ?: PackageFormat.AppImage)
        return started(rig, method)
    }

    private fun TestScope.downloadingRun(rig: Rig): AppUpdates {
        rig.packages.result = null
        rig.feed.offers(Offered)
        return started(rig)
    }

    private fun TestScope.checked(updates: AppUpdates) {
        updates.checkNow()
        runCurrent()
    }

    private fun TestScope.restarted(updates: AppUpdates) {
        updates.restart()
        runCurrent()
    }

    private fun TestScope.collectExits(updates: AppUpdates): MutableList<Unit> {
        val exits = mutableListOf<Unit>()
        backgroundScope.launch { updates.exitRequests.collect { exits += it } }
        runCurrent()
        return exits
    }

    @Test
    fun acknowledgingTheToastClearsIt() = runTest {
        val rig = rig()
        rig.records.write(RecordFile, recordTo(Running))
        val updates = started(rig)
        updates.acknowledgeUpdated()
        assertNull(updates.state.value.justUpdatedTo)
    }

    @Test
    fun aFailedInstallWithNoPackageForThisCopyLeadsToGitHubOnTryAgain() = runTest {
        val rig = rig()
        rig.records.write(RecordFile, recordTo(Offered))
        rig.feed.offers(Offered, null)
        val updates = started(rig)
        assertEquals(Failed(Offered, null, UpdateFailure.NotReplaced, NotesUrl), updates.state.value.phase)
        updates.update()
        runCurrent()
        assertEquals(GitHubOnly(Offered, NotesUrl), updates.state.value.phase)
    }

    @Test
    fun aMalformedRecordCountsAsNoneAndIsReplacedByTheNextStart() = runTest {
        for (text in listOf("not json", "{}", """{"to":"newest"}""")) {
            val rig = rig()
            rig.records.write(RecordFile, text)
            rig.feed.offers(Running)
            val updates = started(rig)
            assertEquals(Idle, updates.state.value.phase, text)
            assertNull(updates.state.value.justUpdatedTo, text)
            assertEquals("", rig.records.read(RecordFile), text)
        }
    }

    @Test
    fun theKeptFeedIsThereBeforeTheFirstAnswerAndStaysWhenOffline() = runTest {
        val rig = rig()
        val kept = feedOf(Running)
        rig.feed.lastGood = KnownFeed(kept, Instant.fromEpochSeconds(77))
        rig.feed.gate = CompletableDeferred()
        val updates = started(rig)
        assertEquals(kept, updates.state.value.feed)
        assertEquals(Instant.fromEpochSeconds(77), updates.state.value.lastChecked)
        assertTrue(updates.state.value.checking)

        rig.feed.gate!!.complete(Unit)
        runCurrent()
        assertEquals(kept, updates.state.value.feed)
        assertEquals(Instant.fromEpochSeconds(77), updates.state.value.lastChecked, "a failed check isn't a check")
        assertFalse(updates.state.value.checking)
        assertTrue(updates.state.value.lastCheckFailed)
    }

    @Test
    fun startingTwiceChecksOnce() = runTest {
        val rig = rig()
        rig.feed.offers(Offered)
        val updates = rig.updates()
        updates.start()
        updates.start()
        runCurrent()
        assertEquals(1, rig.feed.fetches)
    }

    @Test
    fun itChecksEverySixHoursAndStampsTheCheck() = runTest {
        val rig = rig()
        rig.feed.offers(Running)
        val updates = started(rig)
        assertEquals(1, rig.feed.fetches)
        assertEquals(Instant.fromEpochSeconds(0), updates.state.value.lastChecked)

        rig.feed.answer = FeedFetch.Fresh(KnownFeed(feedOf(Running), Instant.fromEpochSeconds(99)))
        advanceTimeBy(6.hours - 1.minutes)
        runCurrent()
        assertEquals(1, rig.feed.fetches)
        advanceTimeBy(1.minutes)
        runCurrent()
        assertEquals(2, rig.feed.fetches)
        assertEquals(Instant.fromEpochSeconds(99), updates.state.value.lastChecked)
        advanceTimeBy(6.hours)
        runCurrent()
        assertEquals(3, rig.feed.fetches)
    }

    @Test
    fun withNoFeedSourceNothingIsCheckedOrReadAndStateStaysIdle() = runTest {
        val rig = rig()
        rig.records.write(RecordFile, recordTo(Running))
        val updates = rig.updates(source = null).apply {
            start()
            checkNow()
            update()
            restart()
            restartNow()
            cancelRestart()
            opened()
            cancelDownload()
        }
        advanceTimeBy(7.hours)
        runCurrent()
        assertEquals(AppUpdateState(Running), updates.state.value)
        assertEquals(0, rig.feed.fetches)
        assertEquals(recordTo(Running), rig.records.read(RecordFile))
    }

    @Test
    fun aFeedNoNewerThanRunningIsIdleAndClearsThePackages() = runTest {
        for (latest in listOf(Running, AppVersion(1, 3, 1))) {
            val rig = rig()
            rig.feed.offers(latest)
            rig.packages.onDisk += offeredPkg
            val updates = started(rig)
            assertEquals(Idle, updates.state.value.phase)
            assertEquals(null, rig.packages.kept.last())
            assertEquals(emptySet(), rig.packages.onDisk)
        }
    }

    @Test
    fun aMissingPackageDownloadsWithThePreferenceOnAndIsReadyWhenVerified() = runTest {
        val rig = rig()
        val updates = downloadingRun(rig)
        assertEquals(Downloading(offeredPkg, 0), updates.state.value.phase)
        rig.packages.progress(620)
        assertEquals(Downloading(offeredPkg, 620), updates.state.value.phase)
        rig.packages.hold.complete(PackageDownload.Verified)
        runCurrent()
        assertEquals(ready, updates.state.value.phase)
    }

    @Test
    fun eachStepOfAnUpdateIsLoggedOnceAndItsProgressIsNot() = runTest {
        val rig = rig()
        val updates = downloadingRun(rig)
        rig.packages.progress(620)
        rig.packages.hold.complete(PackageDownload.Verified)
        runCurrent()
        restarted(updates)
        assertEquals(
            listOf(
                "Downloading Hideout $Offered",
                "Hideout $Offered is downloaded and checked",
                "Restart into Hideout $Offered waits for mod changes and any custom game to end",
                "Closing to finish updating to Hideout $Offered",
            ),
            rig.logged,
        )
    }

    @Test
    fun aMissingPackageWaitsForUpdateWithThePreferenceOff() = runTest {
        val rig = rig()
        rig.preferences.setDownloadAppUpdates(false)
        rig.feed.offers(Offered)
        val updates = started(rig)
        assertEquals(Available(offeredPkg), updates.state.value.phase)
        assertEquals(emptyList(), rig.packages.downloads)
    }

    @Test
    fun turningThePreferenceOnWithAvailableStartsTheDownload() = runTest {
        val rig = rig()
        rig.preferences.setDownloadAppUpdates(false)
        rig.feed.offers(Offered)
        val updates = started(rig)
        rig.preferences.setDownloadAppUpdates(true)
        runCurrent()
        assertEquals(listOf(offeredPkg), rig.packages.downloads)
        assertEquals(ready, updates.state.value.phase)
    }

    @Test
    fun anotherStartAfterDownloadingFindsThePackageInsteadOfDownloadingAgain() = runTest {
        val rig = rig()
        rig.feed.offers(Offered)
        started(rig)
        val second = started(rig)
        assertEquals(ready, second.state.value.phase)
        assertEquals(1, rig.packages.downloads.size)
    }

    @Test
    fun aReCheckOfTheSameVersionLeavesThePhaseAlone() = runTest {
        val downloading = rig()
        val updates = downloadingRun(downloading)
        downloading.packages.progress(300)
        checked(updates)
        assertEquals(Downloading(offeredPkg, 300), updates.state.value.phase)
        assertEquals(1, downloading.packages.downloads.size)

        val readyRig = rig()
        val readyUpdates = readyRun(readyRig)
        checked(readyUpdates)
        assertEquals(ready, readyUpdates.state.value.phase)
    }

    @Test
    fun aDroppedConnectionOrServerErrorRetriesOnTheNextCheckWithThePreferenceOn() = runTest {
        for (why in listOf(UpdateFailure.Connection, UpdateFailure.Server)) {
            val rig = rig()
            rig.packages.result = PackageDownload.Failed(why)
            rig.feed.offers(Offered)
            val updates = started(rig)
            assertEquals(Failed(Offered, offeredPkg, why, NotesUrl), updates.state.value.phase)

            rig.packages.result = PackageDownload.Verified
            checked(updates)
            assertEquals(ready, updates.state.value.phase, "$why")
        }
    }

    @Test
    fun aFailedDownloadWaitsForTryAgainWithThePreferenceOff() = runTest {
        val rig = rig()
        rig.preferences.setDownloadAppUpdates(false)
        rig.packages.result = PackageDownload.Failed(UpdateFailure.Connection)
        rig.feed.offers(Offered)
        val updates = started(rig)
        updates.update()
        runCurrent()
        checked(updates)
        assertEquals(UpdateFailure.Connection, assertIs<Failed>(updates.state.value.phase).why)
        assertEquals(1, rig.packages.downloads.size)
    }

    @Test
    fun aDamagedOrUninstallableOrDiskFullDownloadNeverLoopsByItself() = runTest {
        for (why in listOf(UpdateFailure.Mismatch, UpdateFailure.Disk)) {
            val rig = rig()
            rig.packages.result = PackageDownload.Failed(why)
            rig.feed.offers(Offered)
            val updates = started(rig)
            checked(updates)
            advanceTimeBy(7.hours)
            runCurrent()
            assertEquals(why, assertIs<Failed>(updates.state.value.phase).why)
            assertEquals(1, rig.packages.downloads.size, "$why")
        }
    }

    @Test
    fun aDifferentNewerVersionReplacesADownloadInProgressAndCancelsIt() = runTest {
        val rig = rig()
        val updates = downloadingRun(rig)
        assertEquals(Downloading(offeredPkg, 0), updates.state.value.phase)

        rig.feed.offers(Newer)
        checked(updates)
        assertEquals(Downloading(newerPkg, 0), updates.state.value.phase)
        assertEquals(listOf(offeredPkg, newerPkg), rig.packages.downloads)
        assertEquals(1, rig.packages.mostAtOnce, "the old download ended before the new one began")
        assertEquals(newerPkg, rig.packages.kept.last())

        rig.packages.hold.complete(PackageDownload.Verified)
        runCurrent()
        assertEquals(Ready(newerPkg, RestartStage.Idle), updates.state.value.phase)
    }

    @Test
    fun aDifferentNewerVersionReplacesAReadyOrFailedVersionAndDropsItsPackage() = runTest {
        val rig = rig()
        val updates = readyRun(rig)
        rig.feed.offers(Newer)
        checked(updates)
        assertEquals(Ready(newerPkg, RestartStage.Idle), updates.state.value.phase)
        assertFalse(offeredPkg in rig.packages.onDisk)

        val failing = rig()
        failing.packages.result = PackageDownload.Failed(UpdateFailure.Mismatch)
        failing.feed.offers(Offered)
        val failed = started(failing)
        failing.packages.result = PackageDownload.Verified
        failing.feed.offers(Newer)
        checked(failed)
        assertEquals(Ready(newerPkg, RestartStage.Idle), failed.state.value.phase)
    }

    private fun ScriptedFeed.offersReSigned(pkg: ReleasePackage) {
        answer = FeedFetch.Fresh(KnownFeed(feedOf(pkg.version, pkg.format).copy(packages = mapOf(pkg.format to pkg)), Instant.fromEpochSeconds(1)))
    }

    private val reSigned = offeredPkg.copy(sha256 = "b".repeat(64))

    @Test
    fun aReSignedPackageOfTheSameVersionReplacesAReadyOneAndDownloads() = runTest {
        val rig = rig()
        val updates = readyRun(rig)
        rig.feed.offersReSigned(reSigned)
        checked(updates)
        assertEquals(Ready(reSigned, RestartStage.Idle), updates.state.value.phase)
        assertEquals(listOf(reSigned), rig.packages.downloads)
        assertEquals(setOf(reSigned), rig.packages.onDisk)
    }

    @Test
    fun aReSignedPackageEndsAMismatchWithoutWaitingForTheNextStart() = runTest {
        val rig = rig()
        rig.packages.result = PackageDownload.Failed(UpdateFailure.Mismatch)
        rig.feed.offers(Offered)
        val updates = started(rig)
        assertEquals(UpdateFailure.Mismatch, assertIs<Failed>(updates.state.value.phase).why)

        rig.packages.result = PackageDownload.Verified
        rig.feed.offersReSigned(reSigned)
        checked(updates)
        assertEquals(Ready(reSigned, RestartStage.Idle), updates.state.value.phase)

        checked(updates)
        assertEquals(listOf(offeredPkg, reSigned), rig.packages.downloads, "once for each package")
    }

    @Test
    fun aReSignedPackageReplacesADownloadInProgressAndCallsOffAWaitForMods() = runTest {
        val downloading = rig()
        val updates = downloadingRun(downloading)
        downloading.feed.offersReSigned(reSigned)
        checked(updates)
        assertEquals(Downloading(reSigned, 0), updates.state.value.phase)
        assertEquals(1, downloading.packages.mostAtOnce)

        val waiting = rig()
        waiting.modsBusy.value = true
        val held = readyRun(waiting)
        val exits = collectExits(held)
        restarted(held)
        waiting.feed.offersReSigned(reSigned)
        checked(held)
        assertEquals(Ready(reSigned, RestartStage.Idle), held.state.value.phase)
        waiting.modsBusy.value = false
        runCurrent()
        assertEquals(0, exits.size)
    }

    @Test
    fun aSameVersionFeedWithNoPackageForThisCopyLeavesTheOfferAlone() = runTest {
        val rig = rig()
        val updates = readyRun(rig)
        rig.feed.offers(Offered, PackageFormat.Msi)
        checked(updates)
        assertEquals(ready, updates.state.value.phase)
        assertEquals(setOf(offeredPkg), rig.packages.onDisk)
    }

    @Test
    fun gitHubOnlyStaysPutCheckAfterCheck() = runTest {
        for (method in listOf(null, AppUpdateMethod.AtExit(PackageFormat.Msi) { _, _ -> InstallOutcome.Done })) {
            val rig = rig()
            rig.feed.offers(Offered)
            val updates = started(rig, method)
            val keptOnce = rig.packages.kept.toList()
            repeat(3) {
                checked(updates)
            }
            assertEquals(GitHubOnly(Offered, NotesUrl), updates.state.value.phase)
            assertEquals(keptOnce, rig.packages.kept, "no new offer each check")
            assertEquals(emptyList(), rig.packages.downloads)
        }
    }

    @Test
    fun aFailedInstallWithNoPackageStaysPutCheckAfterCheck() = runTest {
        val rig = rig()
        rig.records.write(RecordFile, recordTo(Offered))
        rig.feed.offers(Offered, null)
        val updates = started(rig)
        val keptOnce = rig.packages.kept.toList()
        repeat(3) {
            checked(updates)
        }
        assertEquals(Failed(Offered, null, UpdateFailure.NotReplaced, NotesUrl), updates.state.value.phase)
        assertEquals(keptOnce, rig.packages.kept, "no new offer each check")
        assertEquals(emptyList(), rig.packages.downloads)
    }

    @Test
    fun aFeedThatNoLongerOffersAnythingNewerCancelsTheDownload() = runTest {
        val rig = rig()
        val updates = downloadingRun(rig)
        rig.feed.offers(Running)
        checked(updates)
        assertEquals(Idle, updates.state.value.phase)
        assertEquals(0, rig.packages.downloadsRunning)
    }

    @Test
    fun checkFailuresNeverOfferAnythingAndAreLogged() = runTest {
        for (failure in listOf(FeedFetch.Unreachable("no route"), FeedFetch.Refused("bad signature"))) {
            val rig = rig()
            rig.feed.answer = failure
            val updates = started(rig)
            val state = updates.state.value
            assertEquals(Idle, state.phase)
            assertTrue(state.lastCheckFailed)
            assertNull(state.lastChecked)
            assertEquals(1, rig.logged.size)
            assertTrue(rig.logged.single().contains(if (failure is FeedFetch.Unreachable) "no route" else "bad signature"))
        }
    }

    @Test
    fun aCheckFailureLeavesAnOfferInPlaceAndTheNextGoodCheckClearsTheFlag() = runTest {
        val rig = rig()
        val updates = readyRun(rig)
        rig.feed.answer = FeedFetch.Unreachable("no route")
        checked(updates)
        assertEquals(ready, updates.state.value.phase)
        assertTrue(updates.state.value.lastCheckFailed)
        rig.feed.offers(Offered)
        checked(updates)
        assertFalse(updates.state.value.lastCheckFailed)
    }

    @Test
    fun checkNowWhileACheckIsRunningDoesNothing() = runTest {
        val rig = rig()
        rig.feed.offers(Running)
        rig.feed.gate = CompletableDeferred()
        val updates = started(rig)
        updates.checkNow()
        checked(updates)
        assertEquals(1, rig.feed.fetches)
        rig.feed.gate!!.complete(Unit)
        runCurrent()
        assertFalse(updates.state.value.checking)
    }

    @Test
    fun cancelGoesToAvailableAndAutoLeavesItUntilANewerVersion() = runTest {
        val rig = rig()
        val updates = downloadingRun(rig)
        updates.cancelDownload()
        updates.cancelDownload()
        runCurrent()
        assertEquals(Available(offeredPkg), updates.state.value.phase)
        assertEquals(0, rig.packages.downloadsRunning)

        checked(updates)
        advanceTimeBy(6.hours)
        runCurrent()
        assertEquals(Available(offeredPkg), updates.state.value.phase, "a re-check doesn't restart what the user cancelled")
        assertEquals(1, rig.packages.downloads.size)

        rig.feed.offers(Newer)
        checked(updates)
        assertEquals(Downloading(newerPkg, 0), updates.state.value.phase, "a newer version is a new question")
    }

    @Test
    fun theNextStartForgetsACancel() = runTest {
        val rig = rig()
        downloadingRun(rig).cancelDownload()
        runCurrent()
        val next = started(rig)
        assertEquals(Downloading(offeredPkg, 0), next.state.value.phase)
    }

    @Test
    fun aDownloadThatFailsAfterItWasCancelledStaysCancelled() = runTest {
        val rig = rig()
        rig.packages.result = null
        rig.packages.ignoresCancellation = true
        rig.feed.offers(Offered)
        val updates = started(rig)
        updates.cancelDownload()
        runCurrent()
        rig.packages.hold.complete(PackageDownload.Failed(UpdateFailure.Connection))
        runCurrent()
        assertEquals(Available(offeredPkg), updates.state.value.phase)
    }

    @Test
    fun aDownloadOfANewVersionWaitsForTheCancelledOneToLetGo() = runTest {
        val rig = rig()
        rig.packages.result = null
        rig.packages.windDown = 2.seconds
        rig.feed.offers(Offered)
        val updates = started(rig)
        rig.feed.offers(Newer)
        checked(updates)
        assertEquals(listOf(offeredPkg), rig.packages.downloads, "the old one still holds its file")
        advanceTimeBy(2.seconds)
        runCurrent()
        assertEquals(listOf(offeredPkg, newerPkg), rig.packages.downloads)
        assertEquals(1, rig.packages.mostAtOnce)
    }

    @Test
    fun updateRightAfterACancelWaitsForTheCancelledDownloadToLetGo() = runTest {
        val rig = rig()
        rig.packages.result = null
        rig.packages.windDown = 2.seconds
        rig.feed.offers(Offered)
        val updates = started(rig)
        updates.cancelDownload()
        updates.update()
        runCurrent()
        assertEquals(Downloading(offeredPkg, 0), updates.state.value.phase, "the chip shows it at once")
        assertEquals(1, rig.packages.downloads.size)
        advanceTimeBy(2.seconds)
        runCurrent()
        assertEquals(2, rig.packages.downloads.size)
        assertEquals(1, rig.packages.mostAtOnce)
        rig.packages.hold.complete(PackageDownload.Verified)
        advanceTimeBy(2.seconds)
        runCurrent()
        assertEquals(ready, updates.state.value.phase)
    }

    @Test
    fun theOpenedVersionIsRememberedAcrossStartsUntilAnotherIsOnOffer() = runTest {
        val rig = rig()
        rig.preferences.setDownloadAppUpdates(false)
        rig.feed.offers(Offered)
        val updates = started(rig)
        assertNull(updates.state.value.openedFor)
        updates.opened()
        updates.opened()
        runCurrent()
        assertEquals(Offered, updates.state.value.openedFor)
        assertEquals(Offered, started(rig).state.value.openedFor, "kept with the preferences")

        rig.feed.offers(Newer)
        checked(updates)
        assertEquals(Offered, updates.state.value.openedFor, "a newer version on offer was never opened")
    }

    @Test
    fun openingWithNothingOnOfferRemembersNothing() = runTest {
        val rig = rig()
        rig.feed.offers(Running)
        val updates = started(rig)
        updates.opened()
        runCurrent()
        assertNull(updates.state.value.openedFor)
        assertNull(rig.preferences.appUpdateOpened())
    }

    @Test
    fun updateFromAvailableProbesFirstAndDownloadsOnlyWhatIsMissing() = runTest {
        val rig = rig()
        rig.preferences.setDownloadAppUpdates(false)
        val updates = downloadingRun(rig)
        updates.update()
        updates.update()
        runCurrent()
        assertEquals(Downloading(offeredPkg, 0), updates.state.value.phase)
        assertEquals(1, rig.packages.downloads.size)

        val found = rig()
        found.preferences.setDownloadAppUpdates(false)
        found.feed.offers(Offered)
        val foundUpdates = started(found)
        found.packages.onDisk += offeredPkg
        foundUpdates.update()
        runCurrent()
        assertEquals(ready, foundUpdates.state.value.phase)
        assertEquals(emptyList(), found.packages.downloads)
    }

    @Test
    fun tryAgainAfterEveryFailureDownloadsAgain() = runTest {
        for (why in listOf(UpdateFailure.Connection, UpdateFailure.Server, UpdateFailure.Mismatch, UpdateFailure.Disk)) {
            val rig = rig()
            rig.packages.result = PackageDownload.Failed(why)
            rig.feed.offers(Offered)
            val updates = started(rig)
            rig.packages.result = PackageDownload.Verified
            updates.update()
            runCurrent()
            assertEquals(ready, updates.state.value.phase, "$why")
        }
    }

    @Test
    fun updateDoesNothingWhereThereIsNothingToGet() = runTest {
        val rig = rig()
        val updates = readyRun(rig)
        updates.update()
        runCurrent()
        assertEquals(ready, updates.state.value.phase)
        assertEquals(emptyList(), rig.packages.downloads)
    }

    @Test
    fun restartWithAtExitAsksToExitWithoutInstallingYet() = runTest {
        val rig = rig()
        val updates = readyRun(rig)
        val exits = collectExits(updates)
        updates.restart()
        restarted(updates)
        assertEquals(Ready(offeredPkg, Exiting), updates.state.value.phase)
        assertEquals(1, exits.size)
        assertEquals(emptyList(), rig.methods.replaces)
    }

    @Test
    fun anExitRequestWaitsForWhoeverCollectsLate() = runTest {
        val rig = rig()
        val updates = readyRun(rig)
        restarted(updates)
        assertEquals(Unit, updates.exitRequests.first())
    }

    @Test
    fun aRestartHasBegunBeforeItReadsWhetherAnythingIsBusy() = runTest {
        val rig = rig()
        lateinit var updates: AppUpdates
        var begunWhenRead: Boolean? = null
        rig.busy = flow {
            begunWhenRead = updates.restartBegun
            emit(false)
        }
        updates = readyRun(rig)
        collectExits(updates)
        assertEquals(false, updates.restartBegun)
        restarted(updates)
        assertEquals(true, begunWhenRead, "a custom game admitted from here on sees the restart, and one admitted earlier is seen by it")
        assertEquals(Ready(offeredPkg, Exiting), updates.state.value.phase)
    }

    @Test
    fun aRestartCalledOffWhileWaitingHasNotBegunAnyMore() = runTest {
        val rig = rig()
        rig.modsBusy.value = true
        val updates = readyRun(rig)
        restarted(updates)
        assertEquals(true, updates.restartBegun)
        updates.cancelRestart()
        assertEquals(false, updates.restartBegun)
    }

    @Test
    fun restartWaitsForModsThenProceeds() = runTest {
        val rig = rig()
        rig.modsBusy.value = true
        val updates = readyRun(rig)
        val exits = collectExits(updates)
        updates.restart()
        restarted(updates)
        assertEquals(Ready(offeredPkg, Waiting), updates.state.value.phase)
        assertEquals(0, exits.size)

        rig.modsBusy.value = false
        runCurrent()
        assertEquals(Ready(offeredPkg, Exiting), updates.state.value.phase)
        assertEquals(1, exits.size)
    }

    @Test
    fun cancelDuringTheWaitCallsOffTheRestart() = runTest {
        val rig = rig()
        rig.modsBusy.value = true
        val updates = readyRun(rig)
        val exits = collectExits(updates)
        restarted(updates)
        updates.cancelRestart()
        assertEquals(ready, updates.state.value.phase)

        rig.modsBusy.value = false
        runCurrent()
        assertEquals(ready, updates.state.value.phase, "mods finishing no longer restarts anything")
        assertEquals(0, exits.size)
    }

    @Test
    fun restartNowDuringTheWaitInstallsWithoutWaitingForTheGame() = runTest {
        val rig = rig()
        rig.modsBusy.value = true
        val updates = readyRun(rig)
        val exits = collectExits(updates)
        updates.restartNow()
        runCurrent()
        assertEquals(ready, updates.state.value.phase, "Restart now only cuts a wait short")
        restarted(updates)
        assertEquals(Ready(offeredPkg, Waiting), updates.state.value.phase)
        updates.restartNow()
        runCurrent()
        assertEquals(Ready(offeredPkg, Exiting), updates.state.value.phase)
        assertEquals(1, exits.size)

        rig.modsBusy.value = false
        runCurrent()
        assertEquals(1, exits.size, "the wait it cut short asks for no second exit")
    }

    @Test
    fun aNewerVersionDuringTheWaitReplacesItAndNothingRestarts() = runTest {
        val rig = rig()
        rig.modsBusy.value = true
        val updates = readyRun(rig)
        val exits = collectExits(updates)
        restarted(updates)
        rig.feed.offers(Newer)
        checked(updates)
        assertEquals(Ready(newerPkg, RestartStage.Idle), updates.state.value.phase)
        rig.modsBusy.value = false
        runCurrent()
        assertEquals(0, exits.size)
    }

    @Test
    fun restartWithWhileOpenWritesTheRecordThenInstallsAndAsksToExit() = runTest {
        val rig = rig()
        val updates = readyRun(rig, rig.methods.whileOpen)
        val exits = collectExits(updates)
        rig.methods.hold = CompletableDeferred()
        restarted(updates)
        assertEquals(Ready(debPkg, Installing), updates.state.value.phase)
        assertEquals(listOf(debPkg), rig.methods.installs)
        assertEquals(0, exits.size)
        assertEquals(recordTo(Offered), rig.methods.recordAtCall.single(), "written before the install began")

        updates.restart()
        updates.cancelRestart()
        updates.restartNow()
        runCurrent()
        assertEquals(Ready(debPkg, Installing), updates.state.value.phase, "an install under way can't be called off or started again")
        assertEquals(listOf(debPkg), rig.methods.installs)

        rig.methods.hold!!.complete(Unit)
        runCurrent()
        assertEquals(Ready(debPkg, Exiting), updates.state.value.phase)
        assertEquals(1, exits.size)
        assertEquals(listOf(debPkg), rig.methods.installs)
    }

    @Test
    fun aRefusedPasswordClearsTheRecordAndGoesBackToReady() = runTest {
        val rig = rig()
        rig.methods.outcome = InstallOutcome.Cancelled
        val updates = readyRun(rig, rig.methods.whileOpen)
        val exits = collectExits(updates)
        restarted(updates)
        assertEquals(Ready(debPkg, RestartStage.Idle), updates.state.value.phase)
        assertEquals("", rig.records.read(RecordFile))
        assertEquals(0, exits.size)
    }

    @Test
    fun aFailedWhileOpenInstallClearsTheRecordAndFails() = runTest {
        val rig = rig()
        rig.methods.outcome = InstallOutcome.Failed("exit 100")
        val updates = readyRun(rig, rig.methods.whileOpen)
        restarted(updates)
        assertEquals(Failed(Offered, debPkg, UpdateFailure.Install, NotesUrl), updates.state.value.phase)
        assertEquals("", rig.records.read(RecordFile))
        assertTrue(rig.logged.any { "exit 100" in it })
    }

    @Test
    fun aMethodThatThrowsIsAFailedInstall() = runTest {
        val rig = rig()
        val throwing = AppUpdateMethod.WhileOpen(PackageFormat.Deb, install = { error("no pkexec") }, relaunch = { InstallOutcome.Done })
        val updates = readyRun(rig, throwing)
        restarted(updates)
        assertEquals(UpdateFailure.Install, assertIs<Failed>(updates.state.value.phase).why)
    }

    @Test
    fun aFailedRecordWriteIsAFailedInstallAndNothingIsInstalled() = runTest {
        val rig = Rig(this, records = RefusingStorage())
        val updates = readyRun(rig, rig.methods.whileOpen)
        restarted(updates)
        assertEquals(Failed(Offered, debPkg, UpdateFailure.Install, NotesUrl), updates.state.value.phase)
        assertEquals(emptyList(), rig.methods.installs)
    }

    @Test
    fun aNewerVersionDoesNotDisturbAnInstallUnderWay() = runTest {
        val rig = rig()
        rig.methods.hold = CompletableDeferred()
        val updates = readyRun(rig, rig.methods.whileOpen)
        restarted(updates)
        rig.feed.offers(Newer, PackageFormat.Deb)
        checked(updates)
        assertEquals(Ready(debPkg, Installing), updates.state.value.phase)
        assertEquals(Offered, updates.state.value.feed?.latest)
        assertFalse(updates.state.value.checking)
        assertEquals(emptyList(), rig.packages.downloads)
    }

    @Test
    fun leavingAfterRestartReplacesAndRelaunches() = runTest {
        val rig = rig()
        val updates = readyRun(rig)
        restarted(updates)
        assertTrue(updates.leave())
        assertEquals(listOf(offeredPkg to true), rig.methods.replaces)
        assertEquals(recordTo(Offered), rig.methods.recordAtCall.single(), "written before the files were touched")
    }

    @Test
    fun quittingDuringAWaitForModsInstallsWithoutReopening() = runTest {
        val rig = rig()
        rig.modsBusy.value = true
        val updates = readyRun(rig)
        restarted(updates)
        assertTrue(updates.leave())
        assertEquals(listOf(offeredPkg to false), rig.methods.replaces)
    }

    @Test
    fun aFailedReplaceOnRestartStaysOpenFailedAndClearsTheRecord() = runTest {
        val rig = rig()
        rig.methods.outcome = InstallOutcome.Failed("read-only")
        val updates = readyRun(rig)
        restarted(updates)
        assertFalse(updates.leave())
        assertEquals(Failed(Offered, offeredPkg, UpdateFailure.Install, NotesUrl), updates.state.value.phase)
        assertEquals("", rig.records.read(RecordFile))
        assertTrue(rig.logged.any { "read-only" in it })
    }

    @Test
    fun tryAgainAfterAFailedReplaceCanLeaveForReal() = runTest {
        val rig = rig()
        rig.methods.outcome = InstallOutcome.Failed("read-only")
        val updates = readyRun(rig)
        restarted(updates)
        assertFalse(updates.leave())

        rig.methods.outcome = InstallOutcome.Done
        updates.update()
        runCurrent()
        assertEquals(ready, updates.state.value.phase)
        restarted(updates)
        assertTrue(updates.leave())
        assertEquals(2, rig.methods.replaces.size)
    }

    @Test
    fun aFailedReplaceOnQuitLogsLeavesAndTheNextStartReportsIt() = runTest {
        val rig = rig()
        rig.methods.outcome = InstallOutcome.Failed("read-only")
        val updates = readyRun(rig)
        assertTrue(updates.leave())
        assertTrue(rig.logged.any { "read-only" in it })

        val next = started(rig)
        assertEquals(Failed(Offered, offeredPkg, UpdateFailure.NotReplaced, NotesUrl), next.state.value.phase)
    }

    @Test
    fun aReplaceThatThrowsCountsAsFailed() = runTest {
        val rig = rig()
        val throwing = AppUpdateMethod.AtExit(PackageFormat.AppImage) { _, _ -> error("read-only") }
        val updates = readyRun(rig, throwing)
        restarted(updates)
        assertFalse(updates.leave())
        assertEquals(UpdateFailure.Install, assertIs<Failed>(updates.state.value.phase).why)
    }

    @Test
    fun aFailedRecordWriteMeansNoReplaceOnEitherWayOut() = runTest {
        val restarting = Rig(this, records = RefusingStorage())
        val restart = readyRun(restarting)
        restarted(restart)
        assertFalse(restart.leave())
        assertEquals(UpdateFailure.Install, assertIs<Failed>(restart.state.value.phase).why)
        assertEquals(emptyList(), restarting.methods.replaces)

        val quitting = Rig(this, records = RefusingStorage())
        assertTrue(readyRun(quitting).leave())
        assertEquals(emptyList(), quitting.methods.replaces)
    }

    @Test
    fun aPackageDamagedBeforeARestartAtExitStaysOpenAsAMismatchAndTryAgainDownloadsIt() = runTest {
        val rig = rig()
        val updates = readyRun(rig)
        restarted(updates)
        rig.packages.onDisk.clear()

        assertFalse(updates.leave())

        assertEquals(Failed(Offered, offeredPkg, UpdateFailure.Mismatch, NotesUrl), updates.state.value.phase)
        assertEquals(emptyList(), rig.methods.replaces)
        assertEquals("", rig.records.read(RecordFile).orEmpty(), "nothing was attempted, so nothing is left to judge")
        assertTrue(rig.logged.any { "no longer matches" in it })

        updates.update()
        runCurrent()
        assertEquals(listOf(offeredPkg), rig.packages.downloads)
        restarted(updates)
        assertTrue(updates.leave(), "the failed leave doesn't answer the next one")
        assertEquals(listOf(offeredPkg to true), rig.methods.replaces)
    }

    @Test
    fun aPackageDamagedBeforeAQuitAtExitLeavesWithNothingInstalledAndTheNextStartGetsItAgain() = runTest {
        val rig = rig()
        val updates = readyRun(rig)
        rig.packages.onDisk.clear()

        assertTrue(updates.leave())

        assertEquals(emptyList(), rig.methods.replaces)
        assertNull(rig.records.read(RecordFile), "nothing was attempted, so nothing is left to judge")
        assertTrue(rig.logged.any { "no longer matches" in it })

        assertEquals(ready, started(rig).state.value.phase)
        assertEquals(listOf(offeredPkg), rig.packages.downloads)
    }

    @Test
    fun aPackageDamagedBeforeARestartWhileOpenFailsAsAMismatchWithNoPrompt() = runTest {
        val rig = rig()
        val updates = readyRun(rig, rig.methods.whileOpen)
        val exits = collectExits(updates)
        rig.packages.onDisk.clear()

        restarted(updates)

        assertEquals(Failed(Offered, debPkg, UpdateFailure.Mismatch, NotesUrl), updates.state.value.phase)
        assertEquals(emptyList(), rig.methods.installs)
        assertNull(rig.records.read(RecordFile), "nothing was attempted, so nothing is left to judge")
        assertEquals(0, exits.size)
        assertTrue(rig.logged.any { "no longer matches" in it })
    }

    @Test
    fun aPackageDamagedDuringTheWaitForModsFailsAsAMismatchWhenTheWaitEnds() = runTest {
        val rig = rig()
        rig.modsBusy.value = true
        val updates = readyRun(rig, rig.methods.whileOpen)
        restarted(updates)
        rig.packages.onDisk.clear()

        rig.modsBusy.value = false
        runCurrent()

        assertEquals(UpdateFailure.Mismatch, assertIs<Failed>(updates.state.value.phase).why)
        assertEquals(emptyList(), rig.methods.installs)
    }

    @Test
    fun whileOpenRelaunchesOnlyAfterItsInstallAndNeverInstallsOnQuit() = runTest {
        val rig = rig()
        val updates = readyRun(rig, rig.methods.whileOpen)
        restarted(updates)
        assertTrue(updates.leave())
        assertEquals(1, rig.methods.relaunches)

        val quitting = rig()
        val quit = readyRun(quitting, quitting.methods.whileOpen)
        assertTrue(quit.leave())
        assertEquals(0, quitting.methods.relaunches)
        assertEquals(emptyList(), quitting.methods.installs)
        assertNull(quitting.records.read(RecordFile), "nothing was attempted, so nothing is left to judge")
    }

    @Test
    fun aFailedRelaunchStillLeaves() = runTest {
        val rig = rig()
        val updates = readyRun(rig, rig.methods.whileOpen)
        restarted(updates)
        rig.methods.outcome = InstallOutcome.Failed("gone")
        assertTrue(updates.leave())
        assertTrue(rig.logged.any { "gone" in it })
    }

    @Test
    fun withNothingToInstallLeavingJustLeaves() = runTest {
        val idle = rig()
        idle.feed.offers(Running)
        assertTrue(started(idle).leave())
        assertEquals(emptyList(), idle.methods.replaces)

        val downloading = rig()
        downloading.packages.result = null
        downloading.feed.offers(Offered)
        assertTrue(started(downloading).leave())
        assertEquals(emptyList(), downloading.methods.replaces)

        val never = rig()
        assertTrue(never.updates(source = null).leave())
    }

    @Test
    fun aReplaceThatHangsGivesWayAfterTwoMinutes() = runTest {
        val rig = rig()
        rig.methods.hold = CompletableDeferred()
        val updates = readyRun(rig)
        val answer = async { updates.leave() }
        advanceTimeBy(2.minutes - 1.seconds)
        runCurrent()
        assertFalse(answer.isCompleted)
        advanceTimeBy(1.seconds)
        runCurrent()
        assertTrue(answer.await())
    }

    @Test
    fun aSecondLeaveJoinsTheFirstAndTheFilesAreReplacedOnce() = runTest {
        val rig = rig()
        rig.methods.hold = CompletableDeferred()
        val updates = readyRun(rig)
        val first = async { updates.leave() }
        val second = async { updates.leave() }
        runCurrent()
        assertEquals(1, rig.methods.replaces.size)
        rig.methods.hold!!.complete(Unit)
        assertTrue(first.await())
        assertTrue(second.await())
        assertTrue(updates.leave(), "and after it finished")
        assertEquals(1, rig.methods.replaces.size)
    }

    @Test
    fun theRecordALeaveWroteBecomesTheToastInTheNewVersion() = runTest {
        val rig = rig()
        val updates = readyRun(rig)
        restarted(updates)
        assertTrue(updates.leave())

        val updated = rig.updates(running = Offered).apply { start() }
        runCurrent()
        assertEquals(Offered, updated.state.value.justUpdatedTo)
        assertEquals(Idle, updated.state.value.phase)
        assertEquals("", rig.records.read(RecordFile))
    }
}
