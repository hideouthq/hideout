package gg.hideouthq.tools

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import gg.hideouthq.App
import gg.hideouthq.AppContainer
import gg.hideouthq.core.data.ProcessScan
import gg.hideouthq.core.data.fake.FakeAppUpdate
import gg.hideouthq.core.data.fake.FakeConfig
import gg.hideouthq.core.data.fake.FakeProcesses
import gg.hideouthq.core.data.fake.InMemoryLibraryStore
import gg.hideouthq.core.data.fake.SampleGameInfo
import gg.hideouthq.core.data.fake.SampleGameRoot
import gg.hideouthq.core.data.fake.sampleGameFiles
import gg.hideouthq.core.data.fake.sampleLibraryManifest
import gg.hideouthq.core.library.CompatibilityPort
import gg.hideouthq.core.library.GameConfigReceipt
import gg.hideouthq.core.library.GameRoot
import gg.hideouthq.core.library.HideoutLines
import gg.hideouthq.core.library.Manager
import gg.hideouthq.core.library.Sha256
import gg.hideouthq.core.navigation.Route
import gg.hideouthq.core.ui.LocalWindowChrome
import gg.hideouthq.rememberHideoutAppState

private const val Back = "Mods back on"
private const val Waiting = "Mods off for this game"

internal val rewriteChipTargets: List<Target> = listOf(
    Target("rewrite_update", 1440, 900) { RewriteApp(Rewrite.Update) },
    Target("rewrite_update_open", 1440, 900, interact = { openChip(Back) }) { RewriteApp(Rewrite.Update) },
    Target("rewrite_update_open_narrow", 840, 700, scale = 1f, interact = { openChip(Back) }) { RewriteApp(Rewrite.Update) },
    Target("rewrite_waiting", 1440, 900) { RewriteApp(Rewrite.WhileRunning) },
    Target("rewrite_waiting_open", 1440, 900, interact = { openChip(Waiting) }) { RewriteApp(Rewrite.WhileRunning) },
    Target("rewrite_waiting_open_narrow", 840, 700, scale = 1f, interact = { openChip(Waiting) }) { RewriteApp(Rewrite.WhileRunning) },
    Target("rewrite_program_open", 1440, 900, interact = { openChip(Back) }) { RewriteApp(Rewrite.ManagerOpen) },
    Target("rewrite_program_open_narrow", 840, 700, scale = 1f, interact = { openChip(Back) }) { RewriteApp(Rewrite.ManagerOpen) },
)

/** How `gameinfo.gi` lost the block Hideout wrote on build 1, with an app update waiting beside it. */
private enum class Rewrite { Update, WhileRunning, ManagerOpen }

private class GameBuild(private val id: String) : CompatibilityPort by CompatibilityPort.Unavailable {
    override suspend fun game(root: GameRoot): String = id
}

@Composable
private fun RewriteApp(rewrite: Rewrite) {
    CompositionLocalProvider(LocalWindowChrome provides RenderedWindowChrome) {
        val container = remember {
            val config = FakeConfig(gameInfo = SampleGameInfo.Rewritten, appUpdate = FakeAppUpdate.Available)
            val written = checkNotNull(sampleGameFiles().gameInfo(SampleGameRoot))
            val receipt = GameConfigReceipt(SampleGameRoot, Sha256.image(written), HideoutLines(mods = true, customGames = false), game = "build:1")
            AppContainer.fake(
                config,
                libraryStore = InMemoryLibraryStore(sampleLibraryManifest(config).copy(gameConfigs = listOf(receipt))),
                processes = FakeProcesses(
                    when (rewrite) {
                        Rewrite.Update -> ProcessScan.Seen()
                        Rewrite.WhileRunning -> ProcessScan.Seen(game = true)
                        Rewrite.ManagerOpen -> ProcessScan.Seen(managers = setOf(Manager.DeadlockModManager))
                    },
                ),
                compatibility = GameBuild(if (rewrite == Rewrite.ManagerOpen) "build:1" else "build:2"),
            )
        }
        App(rememberHideoutAppState(container, Route.Discover))
    }
}
