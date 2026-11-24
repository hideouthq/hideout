package gg.hideouthq.tools

import androidx.compose.runtime.Composable
import gg.hideouthq.AppContainer
import gg.hideouthq.core.data.FileListing
import gg.hideouthq.core.data.GameBananaClient
import gg.hideouthq.core.data.GameProcesses
import gg.hideouthq.core.data.MigrationRecords
import gg.hideouthq.core.data.RecordWrite
import gg.hideouthq.core.data.fake.FakeConfig
import gg.hideouthq.core.data.fake.FakeGameBanana
import gg.hideouthq.core.data.fake.FakeProcesses
import gg.hideouthq.core.data.fake.ForeignSeed
import gg.hideouthq.core.data.fake.InMemoryMigrationRecords
import gg.hideouthq.core.data.fake.LibrarySeed
import gg.hideouthq.core.data.fake.SampleGame
import gg.hideouthq.core.library.CitadelPath
import gg.hideouthq.core.library.MigrationId
import gg.hideouthq.core.navigation.Route
import gg.hideouthq.tools.preview.advanceUntil
import kotlinx.coroutines.CompletableDeferred

internal const val StepMillis = 900L

internal val onboardingTargets: List<Target> = listOf(
    Target("onboarding_game_not_found", 1440, 900, interact = { advanceUntil("the question") { hasText("Where is Deadlock?") } }) {
        Setup { OnboardingFixture.container(SampleGame.NotFound) }
    },
    Target("onboarding_import", 1440, 900, interact = { toImport() }) { Setup { dmm() } },
    Target("onboarding_import_unticked", 1440, 900, interact = { toImport(); steps("Crosshair") }) { Setup { dmm() } },
    Target("onboarding_import_sources", 1440, 900, interact = { toImport(); steps("Another source…") }) { Setup { dmm() } },
    HeldImport().let { held ->
        Target("onboarding_importing", 1440, 900, interact = { toImport(); steps("Import"); advanceUntil("the run") { hasText("Importing…") } }) { Setup(held::container) }
    },
    Target("onboarding_import_stopped", 1440, 900, interact = { toImport(); steps("Import"); advanceUntil("the stop") { hasText("The import stopped.") } }) {
        Setup { dmm(migrationRecords = FullDisk) }
    },
    Target("library_more_menu", 1440, 900, interact = { steps("More") }) {
        AppWith({ AppContainer.fake(FakeConfig(foreign = ForeignSeed.Dmm)) }, Route.Library)
    },
    Target("library_import", 1440, 900, interact = { steps("More", "Import from a manager…"); advanceUntil("what comes along") { hasText("With their on/off state and load order") } }) {
        AppWith({ AppContainer.fake(FakeConfig(foreign = ForeignSeed.Dmm)) }, Route.Library)
    },
    Target("library_import_sources", 1440, 900, interact = { steps("More", "Import from a manager…"); advanceUntil("the sources") { hasText("An export file") } }) {
        AppWith({ AppContainer.fake(FakeConfig()) }, Route.Library)
    },
    Target("onboarding_import_found", 1440, 900, interact = { advanceUntil("the sources") { hasText("An export file") }; steps("Grimoire"); toImport() }) {
        Setup { OnboardingFixture.container(seed = LibrarySeed.Empty, foreign = ForeignSeed.Both) }
    },
    Target("onboarding_import_compact", 1000, 680, scale = 1f, interact = { toImport() }) { Setup { dmm() } },
)

internal fun Script.steps(vararg labels: String) {
    labels.forEach { label ->
        clickLabel(label)
        advance(StepMillis)
    }
    moveToDp(1f, 1f)
    advance(StepMillis)
}

internal fun Script.toImport() {
    advanceUntil("what comes along") { hasText("With their on/off state and load order") }
    advance(StepMillis)
}

internal fun dmm(processes: GameProcesses = FakeProcesses(), migrationRecords: MigrationRecords = InMemoryMigrationRecords(), gameBanana: GameBananaClient = FakeGameBanana()) =
    OnboardingFixture.container(seed = LibrarySeed.Empty, foreign = ForeignSeed.Dmm, gameBanana = gameBanana, processes = processes, migrationRecords = migrationRecords)

private object FullDisk : MigrationRecords {
    override suspend fun begin(id: MigrationId, record: ByteArray, copies: Map<CitadelPath, ByteArray>) = RecordWrite.Failed("the disk is full")

    override suspend fun update(id: MigrationId, record: ByteArray) = RecordWrite.Failed("the disk is full")

    override suspend fun all(): Map<MigrationId, ByteArray> = emptyMap()
}

internal class HeldImport {
    val processes = FakeProcesses()
    private val held = CompletableDeferred<Unit>()

    fun container(): AppContainer = dmm(processes, gameBanana = { submission ->
        if (submission.id != FirstMod) held.await()
        FileListing.Unavailable("no answer for ${submission.id}")
    })

    fun answer() = held.complete(Unit)

    private companion object {
        const val FirstMod = 700_001L
    }
}

@Composable
private fun Setup(container: () -> AppContainer) {
    AppWith(container, Route.Discover)
}
