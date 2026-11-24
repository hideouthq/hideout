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
import gg.hideouthq.core.designsystem.image.NoRemoteImages
import gg.hideouthq.core.designsystem.image.RemoteImages
import gg.hideouthq.core.model.Mod

internal object OnboardingFixture {
    fun container(
        game: SampleGame = SampleGame.Found,
        seed: LibrarySeed = LibrarySeed.Empty,
        foreign: ForeignSeed = ForeignSeed.None,
        gameBanana: GameBananaClient = FakeGameBanana(),
        processes: GameProcesses = FakeProcesses(),
        migrationRecords: MigrationRecords = InMemoryMigrationRecords(),
        files: InMemoryGameFiles = sampleGameFiles(FakeConfig(game = game, seed = seed, foreign = foreign)),
        catalogued: List<Mod> = emptyList(),
        images: RemoteImages = NoRemoteImages,
    ): AppContainer = AppContainer.fake(
        FakeConfig(game = game, seed = seed, setupDone = false, foreign = foreign, catalogued = catalogued),
        files = files, images = images, gameBanana = gameBanana, processes = processes, migrationRecords = migrationRecords,
    )
}
