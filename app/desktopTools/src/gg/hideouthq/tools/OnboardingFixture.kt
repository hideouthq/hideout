package gg.hideouthq.tools

import gg.hideouthq.AppContainer
import gg.hideouthq.core.data.GameBananaClient
import gg.hideouthq.core.data.GameProcesses
import gg.hideouthq.core.data.MigrationRecords
import gg.hideouthq.core.data.fake.FakeConfig
import gg.hideouthq.core.data.fake.FakeGameBanana
import gg.hideouthq.core.data.fake.FakeProcesses
import gg.hideouthq.core.data.fake.ForeignSeed
import gg.hideouthq.core.data.fake.InMemoryGameFiles
import gg.hideouthq.core.data.fake.InMemoryMigrationRecords
import gg.hideouthq.core.data.fake.LibrarySeed
import gg.hideouthq.core.data.fake.SampleGame
import gg.hideouthq.core.data.fake.sampleGameFiles

internal object OnboardingFixture {
    fun container(
        game: SampleGame = SampleGame.Found,
        seed: LibrarySeed = LibrarySeed.Empty,
        foreign: ForeignSeed = ForeignSeed.None,
        gameBanana: GameBananaClient = FakeGameBanana(),
        processes: GameProcesses = FakeProcesses(),
        migrationRecords: MigrationRecords = InMemoryMigrationRecords(),
        files: InMemoryGameFiles = sampleGameFiles(FakeConfig(game = game, seed = seed, foreign = foreign)),
    ): AppContainer = AppContainer.fake(
        FakeConfig(game = game, seed = seed, setupDone = false, foreign = foreign),
        files = files, gameBanana = gameBanana, processes = processes, migrationRecords = migrationRecords,
    )
}
