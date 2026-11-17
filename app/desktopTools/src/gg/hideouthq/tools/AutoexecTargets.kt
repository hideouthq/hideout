package gg.hideouthq.tools

import androidx.compose.runtime.Composable
import gg.hideouthq.AppContainer
import gg.hideouthq.core.data.fake.FakeConfig
import gg.hideouthq.core.data.fake.SampleGame
import gg.hideouthq.core.navigation.Route

private const val ViewSettleMillis = 900L

internal val autoexecTargets: List<Target> = listOf(
    Target("autoexec_list", 1440, 1180) { AutoexecApp() },
    Target("autoexec_text", 1440, 1180, interact = { clickLabel("Text"); advance(ViewSettleMillis) }) { AutoexecApp() },
    Target("autoexec_library_audio", 1440, 1180, interact = { clickLabel("Audio"); advance(ViewSettleMillis) }) { AutoexecApp() },
    Target("autoexec_window", 1440, 900) { AutoexecApp() },
    Target("autoexec_window_scrolled", 1440, 900, interact = { moveToDp(1250f, 700f); repeat(8) { scroll(4f) }; advance(ViewSettleMillis) }) { AutoexecApp() },
    Target("autoexec_compact", 1000, 680) { AutoexecApp() },
    Target("autoexec_compact_scrolled", 1000, 680, interact = { moveToDp(600f, 400f); repeat(8) { scroll(4f) }; advance(ViewSettleMillis) }) { AutoexecApp() },
    Target("autoexec_state_no_game", 1440, 900) { AutoexecApp(FakeConfig(game = SampleGame.NotFound)) },
    Target("autoexec_state_no_file", 1440, 900) { AutoexecApp(FakeConfig()) },
)

@Composable
private fun AutoexecApp(config: FakeConfig = FakeConfig(autoexec = DesignAutoexec)) = AppWith({ AppContainer.fake(config) }, Route.Autoexec)

internal val DesignAutoexec = """
    // Written by Hideout. Lines you add by hand are kept.
    fps_max 240
    fps_max_ui 144
    engine_no_focus_sleep 0
    engine_low_latency_sleep_after_client_tick true
    snd_musicvolume 0
    // cl_showfps 1
    sensitivity 1.6
""".trimIndent() + "\n"
