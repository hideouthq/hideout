package gg.hideouthq.core.appupdate

import gg.hideouthq.AppContainer
import gg.hideouthq.core.data.FileListing
import gg.hideouthq.core.data.GameBananaClient
import gg.hideouthq.core.data.RemoteFile
import gg.hideouthq.core.data.fake.FakeAppUpdate
import gg.hideouthq.core.data.fake.FakeConfig
import gg.hideouthq.core.library.OperationStage
import gg.hideouthq.core.library.SubmissionKind
import gg.hideouthq.core.library.SubmissionRef
import gg.hideouthq.core.library.transfers
import gg.hideouthq.core.model.AppAbout
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class AppUpdatesFakeTest {
    private fun updates(script: FakeAppUpdate) = AppContainer.fake(FakeConfig(appUpdate = script), about = AppAbout(Running.toString())).appUpdates

    private fun settled(script: FakeAppUpdate, reached: (AppUpdateState) -> Boolean) = runBlocking {
        withTimeout(10.seconds) { updates(script).state.first(reached) }
    }

    private fun settled(script: FakeAppUpdate, phase: KClass<out AppUpdatePhase>) = settled(script) { phase.isInstance(it.phase) }

    @Test
    fun noScriptNeverChecks() {
        val state = updates(FakeAppUpdate.None).state.value
        assertEquals(AppUpdatePhase.Idle, state.phase)
        assertNull(state.feed)
    }

    @Test
    fun everyStateHasAScript() {
        assertEquals(Running, settled(FakeAppUpdate.UpToDate) { it.feed?.latest != null && it.phase == AppUpdatePhase.Idle }.feed?.latest)
        settled(FakeAppUpdate.Available, AppUpdatePhase.Available::class)
        settled(FakeAppUpdate.Downloading, AppUpdatePhase.Downloading::class)
        settled(FakeAppUpdate.Ready, AppUpdatePhase.Ready::class)
        settled(FakeAppUpdate.GitHubOnly, AppUpdatePhase.GitHubOnly::class)
        settled(FakeAppUpdate.Password, AppUpdatePhase.Ready::class)
    }

    @Test
    fun everyFailureLineHasAScript() {
        val table = mapOf(
            FakeAppUpdate.FailedConnection to UpdateFailure.Connection,
            FakeAppUpdate.FailedServer to UpdateFailure.Server,
            FakeAppUpdate.FailedMismatch to UpdateFailure.Mismatch,
            FakeAppUpdate.FailedDisk to UpdateFailure.Disk,
            FakeAppUpdate.FailedInstall to UpdateFailure.NotReplaced,
        )
        for ((script, why) in table) {
            assertEquals(why, assertIs<AppUpdatePhase.Failed>(settled(script, AppUpdatePhase.Failed::class).phase).why)
        }
    }

    @Test
    fun theToastScriptHasTheRunningVersionInstalled() = runBlocking {
        val updates = updates(FakeAppUpdate.JustUpdated)
        val state = withTimeout(10.seconds) { updates.state.first { it.justUpdatedTo != null && it.feed != null } }
        assertEquals(Running, state.justUpdatedTo)
        assertEquals(AppUpdatePhase.Idle, state.phase)
    }

    @Test
    fun aSampleBuildWithNoRealVersionStillRuns() = runBlocking {
        val updates = AppContainer.fake(FakeConfig(appUpdate = FakeAppUpdate.Ready)).appUpdates
        withTimeout(10.seconds) { updates.state.first { it.phase is AppUpdatePhase.Ready } }
        Unit
    }

    @Test
    fun aRestartInTheContainerWaitsForTheLibrarysModDownloads() = runBlocking {
        val answer = CompletableDeferred<FileListing>()
        val container = AppContainer.fake(FakeConfig(appUpdate = FakeAppUpdate.Ready), gameBanana = GameBananaClient { answer.await() })
        val updates = container.appUpdates
        withTimeout(10.seconds) {
            updates.state.first { it.phase is AppUpdatePhase.Ready }
            container.modLibrary.install(modId)
            container.modLibrary.state.first { it.transfers() != null }
            updates.restart()
            updates.reachesRestart(RestartStage.Waiting)
            answer.complete(FileListing.Unavailable("no answer"))
            updates.reachesRestart(RestartStage.Exiting)
        }
        Unit
    }

    @Test
    fun aRestartInTheContainerWaitsForAnOpenQuestionThatIsNoTransfer() = runBlocking {
        val files = listOf(1L, 2L).map { RemoteFile(it, "file-$it.zip", 1000, null, null, 1, 1, "https://gamebanana.com/dl/$it") }
        val container = AppContainer.fake(FakeConfig(appUpdate = FakeAppUpdate.Ready), gameBanana = GameBananaClient { FileListing.Available("Two files", files) })
        val updates = container.appUpdates
        withTimeout(10.seconds) {
            updates.state.first { it.phase is AppUpdatePhase.Ready }
            container.modLibrary.install(modId)
            val library = container.modLibrary.state.first { it.operations[modId]?.stage is OperationStage.ChoosingFile }
            assertNull(library.transfers())
            updates.restart()
            updates.reachesRestart(RestartStage.Waiting)
            assertTrue(container.modLibrary.cancel(modId))
            updates.reachesRestart(RestartStage.Exiting)
        }
        Unit
    }

    @Test
    fun aFailedOperationLeftOnScreenDoesNotHoldARestart() = runBlocking {
        val container = AppContainer.fake(FakeConfig(appUpdate = FakeAppUpdate.Ready), gameBanana = GameBananaClient { FileListing.Unavailable("no answer") })
        val updates = container.appUpdates
        withTimeout(10.seconds) {
            updates.state.first { it.phase is AppUpdatePhase.Ready }
            container.modLibrary.install(modId)
            container.modLibrary.state.first { it.operations[modId]?.stage is OperationStage.Failed }
            updates.restart()
            updates.reachesRestart(RestartStage.Exiting)
        }
        Unit
    }

    private val modId = SubmissionRef(SubmissionKind.Mod, 1).modId

    private suspend fun AppUpdates.reachesRestart(stage: RestartStage) = state.first { (it.phase as? AppUpdatePhase.Ready)?.restart == stage }
}
