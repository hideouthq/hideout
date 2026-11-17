package gg.hideouthq.library

import gg.hideouthq.core.data.GameLauncher
import gg.hideouthq.core.library.DeadlockAppId
import gg.hideouthq.core.library.GameRoot
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible

/** The desktop's `steam://` handler may be another Steam than the one that owns the game. */
class SteamLauncher(
    private val steamInstalls: () -> List<SteamInstall> = ::steamInstalls,
    private val handoff: DesktopHandoff = DesktopHandoff(),
) : GameLauncher {
    override suspend fun launch(game: GameRoot) = runInterruptible(Dispatchers.IO) {
        when (owningInstalls(game, steamInstalls()).singleOrNull()?.client) {
            SteamClient.Flatpak -> handoff.subprocess(listOf("flatpak", "run", FlatpakSteamId, RunGame.toString()))
            SteamClient.Snap -> handoff.subprocess(listOf("snap", "run", "steam", RunGame.toString()))
            SteamClient.Desktop, null -> handoff.browse(RunGame)
        }
    }

    companion object {
        val RunGame: URI = URI("steam://rungameid/$DeadlockAppId")
    }
}
