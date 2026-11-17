package gg.hideouthq.feature.imports

import gg.hideouthq.core.library.GameRoot
import gg.hideouthq.core.library.ImportProblem
import gg.hideouthq.core.library.ImportSummary
import gg.hideouthq.core.library.ImportWait
import gg.hideouthq.core.library.Manager
import gg.hideouthq.core.library.Profile
import gg.hideouthq.core.library.ProfileColour
import gg.hideouthq.core.library.ProfileId
import gg.hideouthq.core.migration.FoundSource
import gg.hideouthq.core.migration.ImportChoice
import gg.hideouthq.core.migration.ImportPhase
import gg.hideouthq.core.migration.ImportRun
import gg.hideouthq.core.migration.ImportSource
import gg.hideouthq.core.migration.MigrationState
import gg.hideouthq.core.migration.ModsChoice
import gg.hideouthq.core.migration.Preview
import gg.hideouthq.core.migration.PreviewMod
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ImportUiTest {
    private val dmm = ImportSource.Installed(Manager.DeadlockModManager)
    private val preview = Preview.Ready(
        mods = listOf(PreviewMod("Neon Haze", "pak10_dir.vpk", true), PreviewMod("Ranked HUD", "700003_Ranked HUD.vpk", false)),
        profiles = listOf(Profile(ProfileId("p"), "Ranked", ProfileColour.Blue, setOf("a"), mapOf(GameRoot("/games/Deadlock") to listOf("a")))),
        crosshair = listOf("citadel_crosshair_pip_gap \"-7\"", "citadel_crosshair_color_g \"255\""),
        commands = emptyList(),
    )
    private val found = MigrationState(
        sources = listOf(FoundSource(Manager.DeadlockModManager, true), FoundSource(Manager.Grimoire, false)),
        previews = mapOf(dmm to preview),
    )

    private val both = found.copy(sources = listOf(FoundSource(Manager.DeadlockModManager, true), FoundSource(Manager.Grimoire, true)))

    private fun ui(pick: ImportPick = ImportPick(), imports: MigrationState = found) = importUi(pick, imports)

    private fun running(phase: ImportPhase) = found.copy(run = ImportRun(dmm, ImportChoice.Everything, phase))

    @Test
    fun theOneManagerFoundOpensOnWhatItBringsWithEverythingTicked() {
        val page = ui()
        assertFalse(page.listing, "one manager with something to bring is no choice of source")
        assertFalse(page.looking)
        assertEquals(SourceOption.Installed(Manager.DeadlockModManager), page.from)
        assertEquals(
            listOf(
                ImportRow(ImportItem.Mods, 2, ticked = true),
                ImportRow(ImportItem.Profiles, 1, ticked = true, names = listOf("Ranked")),
                ImportRow(ImportItem.Crosshair, 1, ticked = true),
            ),
            page.rows,
            "no Autoexec row without commands",
        )
        assertTrue(page.canImport)
        assertTrue(page.offers)
    }

    @Test
    fun theListOffersOnlySourcesThatCanBringSomething() {
        val asked = ui(ImportPick(listing = true))
        assertTrue(asked.listing, "the list shows when asked for")
        assertEquals(
            listOf(
                SourceCard(SourceOption.Installed(Manager.DeadlockModManager), found = true, counts = ImportCounts(2, 1, 1, 0)),
                SourceCard(SourceOption.Installed(Manager.Grimoire)),
                SourceCard(SourceOption.ExportFile),
            ),
            asked.sources,
            "Grimoire is not found but can be pointed at",
        )
        assertFalse(asked.canImport)
        val grimoireOnly = ui(imports = MigrationState(sources = listOf(FoundSource(Manager.Grimoire, true), FoundSource(Manager.DeadlockModManager, false)), previews = mapOf(ImportSource.Installed(Manager.Grimoire) to preview)))
        assertEquals(listOf(SourceOption.Installed(Manager.Grimoire), SourceOption.ExportFile), grimoireOnly.sources.map { it.option }, "Hideout cannot be pointed at Deadlock Mod Manager's data")
        assertTrue(ui(imports = both).listing, "two managers found is a choice")
        assertEquals(SourceOption.Installed(Manager.Grimoire), ui(ImportPick(source = ImportSource.Installed(Manager.Grimoire)), both).from)
    }

    @Test
    fun withNothingFoundThePageIsTheListAndSetupHasNothingToOffer() {
        val none = ui(imports = MigrationState(sources = Manager.entries.map { FoundSource(it, false) }))
        assertTrue(none.listing)
        assertFalse(none.offers)
        assertFalse(none.looking)
        assertEquals(listOf(SourceOption.Installed(Manager.Grimoire), SourceOption.ExportFile), none.sources.map { it.option })
        assertTrue(ui(imports = MigrationState()).looking, "until the managers were looked for")
        assertTrue(ui(imports = found.copy(previews = mapOf(dmm to Preview.Looking))).looking, "and each one found was counted")
        assertTrue(ui(imports = found.copy(previews = emptyMap())).looking)
    }

    @Test
    fun aManagerAlreadyImportedHasNothingToBringAndIsNotOffered() {
        val emptied = ui(imports = found.copy(previews = mapOf(dmm to preview.copy(mods = emptyList(), profiles = emptyList(), crosshair = emptyList()))))
        assertFalse(SourceOption.Installed(Manager.DeadlockModManager) in emptied.sources.map { it.option })
        assertTrue(emptied.listing)
        assertFalse(emptied.offers)
    }

    @Test
    fun profilesNeedTheirModsAndNothingTickedImportsNothing() {
        val modsOff = ui(ImportPick(choice = ImportChoice(ModsChoice.None, crosshair = true, commands = true)))
        assertEquals(ImportRow(ImportItem.Profiles, 1, ticked = false, enabled = false, names = listOf("Ranked")), modsOff.rows[1])
        assertTrue(modsOff.canImport, "the crosshair still comes")
        val nothing = ui(ImportPick(choice = ImportChoice(ModsChoice.None, crosshair = false, commands = true)))
        assertFalse(nothing.canImport, "a ticked choice with no row brings nothing")
    }

    @Test
    fun aPreviewThatFailedSaysWhyAndOffersNothing() {
        val failed = ui(ImportPick(source = dmm), imports = found.copy(previews = mapOf(dmm to Preview.Failed(ImportProblem.GameMissing))))
        assertEquals(ImportProblem.GameMissing, failed.problem)
        assertEquals(emptyList(), failed.rows)
        assertFalse(failed.canImport)
        assertFalse(failed.offers, "setup does not ask about a manager it could not count")
    }

    @Test
    fun aRunKeepsThePageWithItsRowsHeldAndNeedsNoOneOnceItWaitsOrFinished() {
        val placing = ui(imports = running(ImportPhase.Placing))
        assertTrue(placing.importing, "a run shows whoever started it")
        assertTrue(placing.rows.isNotEmpty() && placing.rows.none { it.enabled }, "what comes cannot change while it comes")
        assertFalse(placing.canImport)
        assertFalse(placing.settled, "a run at work is worth watching")
        val waiting = ui(imports = running(ImportPhase.Waiting(ImportWait.ManagerOpen(Manager.DeadlockModManager))))
        assertEquals(ImportWait.ManagerOpen(Manager.DeadlockModManager), waiting.wait)
        assertTrue(waiting.settled, "Library says what a run waits for")
        assertTrue(ui(imports = running(ImportPhase.Finished(ImportSummary(on = 1, off = 1, profiles = listOf("Ranked"), crosshair = true, commands = 0)))).settled)
    }

    @Test
    fun aRunThatStoppedSaysWhyAndCanBeTriedAgain() {
        val stopped = ui(imports = running(ImportPhase.Stopped(ImportProblem.BackupFailed("the disk is full"), null)))
        assertTrue(stopped.stopped)
        assertEquals(ImportProblem.BackupFailed("the disk is full"), stopped.problem)
        assertFalse(stopped.settled, "why it stopped stays up")
        assertTrue(stopped.canImport)
    }
}
