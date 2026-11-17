package gg.hideouthq.core.customgames

import gg.hideouthq.AppContainer
import gg.hideouthq.core.data.GameLauncher
import gg.hideouthq.core.data.fake.FakeCustomGamesFeed
import gg.hideouthq.core.library.GameActivity
import gg.hideouthq.core.library.GameRoot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class CustomGamesContainerTest {
    @Test
    fun aHostThroughTheRealLibraryStartsDeadlockIntoItsGameAndHoldsTheFilesUntilTheSessionEnds() = runBlocking {
        val launched = CompletableDeferred<GameRoot>()
        val launcher = object : GameLauncher {
            override suspend fun launch(game: GameRoot) {
                launched.complete(game)
            }
        }
        val container = AppContainer.fake(launcher = launcher)
        val games = assertNotNull(container.customGames)
        val library = container.modLibrary
        withTimeout(10.seconds) {
            library.state.first { it.deadlock == GameActivity.Closed && it.canPlay }
            games.refreshModes()
            assertEquals(FakeCustomGamesFeed.Placeholder, games.modes.first { it.feed != null }.feed)

            games.host(FakeCustomGamesFeed.SampleFolder, Choices.None, null)
            games.live.first { (it.session as? Session.Hosting)?.own == OwnGame.In }
            assertNotNull(library.state.value.sessionHold)
            assertTrue(library.state.value.modsBusy, "a restart waits for the session")
            launched.await()

            games.stop()
            assertEquals(EndReason.PlayerStopped, (games.live.first { it.session is Session.Ended }.session as Session.Ended).reason)
            assertNull(library.state.value.sessionHold)
        }
    }

    @Test
    fun theFakeAppHostsBebopWarsToAFull2v2WithAScoreAndEndsIt() = runBlocking {
        val container = AppContainer.fake(launcher = object : GameLauncher {
            override suspend fun launch(game: GameRoot) = Unit
        })
        val games = assertNotNull(container.customGames)
        withTimeout(10.seconds) {
            container.modLibrary.state.first { it.deadlock == GameActivity.Closed && it.canPlay }
            games.refreshModes()
            val bebop = games.modes.first { it.feed != null }.modes.single()
            assertEquals("Bebop Wars", bebop.listing.name, "the only mode the fake app shows")
            val teams = bebop.listing.settings.first { it.key.value == "teams" }
            val twos = Choices.defaultsOf(bebop.listing).with(teams, SettingValue.parse("2v2")!!)
            games.host(bebop, twos, null)
            val full = games.live.first { (it.session as? Session.Hosting)?.players?.size == 4 && it.game?.reported?.isNotEmpty() == true }
            assertEquals(4, full.game?.capacity)
            assertIs<SectionState.Scoreboard>(full.game?.reported?.get(SectionKind.Scoreboard))
            games.stop()
            val ended = games.live.first { it.session is Session.Ended }
            assertEquals(true, ended.game?.started, "a game that ran ended, it didn't fail to start")
        }
    }
}
