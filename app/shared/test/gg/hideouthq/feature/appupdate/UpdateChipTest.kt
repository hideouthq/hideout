package gg.hideouthq.feature.appupdate

import gg.hideouthq.core.appupdate.AppUpdatePhase
import gg.hideouthq.core.appupdate.AppUpdateState
import gg.hideouthq.core.appupdate.AppVersion
import gg.hideouthq.core.appupdate.PackageFormat
import gg.hideouthq.core.appupdate.ReleasePackage
import gg.hideouthq.core.appupdate.RestartStage
import gg.hideouthq.core.appupdate.UpdateFailure
import gg.hideouthq.feature.customgames.GameInProgress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UpdateChipTest {
    private val running = AppVersion(1, 3, 2)
    private val offered = AppVersion(1, 4, 0)
    private val pkg = ReleasePackage(offered, PackageFormat.AppImage, "https://example.test/h.AppImage", 18_000_000, "0".repeat(64))
    private val hosting = GameInProgress.Hosting("Bebop Wars", listOf("nori"))

    private fun chip(phase: AppUpdatePhase, game: GameInProgress? = null, opened: AppVersion? = null) =
        updateChipState(AppUpdateState(running, phase, openedFor = opened), game)

    @Test
    fun nothingOnOfferHidesTheChip() {
        assertNull(chip(AppUpdatePhase.Idle))
        assertNull(chip(AppUpdatePhase.Idle, hosting))
    }

    @Test
    fun anAvailableUpdateAsksUntilItsPanelWasOpenedForThatVersion() {
        val available = AppUpdatePhase.Available(pkg)
        assertEquals(UpdateChipState.Available, chip(available))
        assertEquals(UpdateChipState.Seen, chip(available, opened = offered))
        assertEquals(UpdateChipState.Available, chip(available, opened = AppVersion(1, 3, 9)), "opening an older version's panel says nothing about this one")
        assertEquals(UpdateChipState.Seen, chip(AppUpdatePhase.GitHubOnly(offered, "https://example.test/releases"), opened = offered))
    }

    @Test
    fun aGameKeepsTheChipToAnIconWithADot() {
        assertEquals(UpdateChipState.AvailableInGame, chip(AppUpdatePhase.Available(pkg), hosting, opened = offered))
        assertEquals(UpdateChipState.AvailableInGame, chip(AppUpdatePhase.Available(pkg), GameInProgress.Guest(null)))
        assertEquals(UpdateChipState.ReadyInGame, chip(AppUpdatePhase.Ready(pkg, RestartStage.Idle), hosting))
    }

    @Test
    fun aRestartAskedForDuringAGameWaitsForItsEnd() {
        assertEquals(UpdateChipState.AfterGame, chip(AppUpdatePhase.Ready(pkg, RestartStage.Waiting), hosting))
        assertEquals(UpdateChipState.Ready, chip(AppUpdatePhase.Ready(pkg, RestartStage.Waiting)), "with no game it only waits for mods")
        assertEquals(UpdateChipState.Ready, chip(AppUpdatePhase.Ready(pkg, RestartStage.Exiting), hosting), "Restart now is on its way out")
    }

    @Test
    fun downloadingReadyAndFailedShowTheirOwnFace() {
        assertEquals(UpdateChipState.Downloading, chip(AppUpdatePhase.Downloading(pkg, 1_000), hosting))
        assertEquals(UpdateChipState.Ready, chip(AppUpdatePhase.Ready(pkg, RestartStage.Idle), opened = offered))
        assertEquals(UpdateChipState.Failed, chip(AppUpdatePhase.Failed(offered, pkg, UpdateFailure.Connection, "https://example.test/releases"), hosting))
    }

    @Test
    fun theWarningNamesEveryoneARestartCutsOff() {
        assertEquals("nori", spokenList(listOf("nori")))
        assertEquals("nori and Ann", spokenList(listOf("nori", "Ann")))
        assertEquals("nori, Ann and Kit", spokenList(listOf("nori", "Ann", "Kit")))
    }
}
