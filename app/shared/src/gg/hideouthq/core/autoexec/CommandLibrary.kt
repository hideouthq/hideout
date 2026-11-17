package gg.hideouthq.core.autoexec

object CommandLibrary {
    val Commands: List<LibraryCommand> = listOf(
        LibraryCommand("fps_max", "240", CommandCategory.Performance, "Caps the frame rate in matches. 0 takes the cap off."),
        LibraryCommand("fps_max_ui", "120", CommandCategory.Performance, "Caps the frame rate in menus, so they don't work the graphics card for nothing."),
        LibraryCommand("engine_no_focus_sleep", "0", CommandCategory.Performance, "Keeps the frame rate up while the game is in the background."),
        LibraryCommand("engine_low_latency_sleep_after_client_tick", "true", CommandCategory.Performance, "With low latency mode on, waits after the game's tick instead of before it, which can cut input delay."),
        LibraryCommand("cl_showfps", "1", CommandCategory.HudAndUi, "Shows a frame rate counter in the corner."),
        LibraryCommand("citadel_hint_system_disable", "true", CommandCategory.HudAndUi, "Turns off the tutorial hints."),
        LibraryCommand("citadel_always_show_active_hud_stats", "true", CommandCategory.HudAndUi, "Keeps your stats on the HUD all the time."),
        LibraryCommand("citadel_show_all_purchase_toasts", "true", CommandCategory.HudAndUi, "Shows a notice for every item you buy, not just the ones in your quick-buy queue."),
        LibraryCommand("citadel_hide_replay_hud", "true", CommandCategory.HudAndUi, "Hides the replay controls while you watch a replay."),
        LibraryCommand("deadlock_disable_post_match_survey", "true", CommandCategory.HudAndUi, "Skips the survey after a match."),
        LibraryCommand("cl_hud_telemetry_net_quality_graph_show", "1", CommandCategory.HudAndUi, "Shows a graph of jitter and lost packets when your connection is poor. 2 shows it always."),
        LibraryCommand("cl_hud_telemetry_net_misdelivery_show", "1", CommandCategory.HudAndUi, "Shows how many packets go missing when your connection is poor. 2 shows it always."),
        LibraryCommand("citadel_minimap_player_width", "6.5", CommandCategory.HudAndUi, "Size of the heroes' icons on the minimap."),
        LibraryCommand("citadel_minimap_local_player_width", "10", CommandCategory.HudAndUi, "Size of your own icon on the minimap."),
        LibraryCommand("rate", "786432", CommandCategory.Network, "The most data the server may send each second, in bytes."),
        LibraryCommand("cl_updaterate", "60", CommandCategory.Network, "How many updates a second the game asks the server for."),
        LibraryCommand("cl_interp_ratio", "1", CommandCategory.Network, "How many server updates movement is smoothed over. Lower is snappier on a steady connection."),
        LibraryCommand("volume", "0.5", CommandCategory.Audio, "Master volume, from 0 to 1."),
        LibraryCommand("snd_gamevolume", "1", CommandCategory.Audio, "Volume of the game's sounds, from 0 to 1."),
        LibraryCommand("snd_musicvolume", "0", CommandCategory.Audio, "Music volume, from 0 to 1."),
        LibraryCommand("snd_gamevoicevolume", "1", CommandCategory.Audio, "Volume of the heroes' voice lines, from 0 to 1."),
        LibraryCommand("snd_voipvolume", "1", CommandCategory.Audio, "Volume of voice chat, from 0 to 1."),
        LibraryCommand("snd_mute_losefocus", "0", CommandCategory.Audio, "Keeps the sound on while the game is in the background."),
        LibraryCommand("voice_modenable", "1", CommandCategory.Audio, "Turns voice chat on, or off with 0."),
        LibraryCommand("voice_vox", "0", CommandCategory.Audio, "1 sends your voice whenever you talk; 0 waits for the push-to-talk key."),
        LibraryCommand("m_yaw", "0.022", CommandCategory.Mouse, "How far the view turns sideways for each count of the mouse."),
        LibraryCommand("m_pitch", "0.022", CommandCategory.Mouse, "How far the view turns up and down for each count. A minus sign turns it upside down."),
        LibraryCommand("zoom_sensitivity_ratio", "1", CommandCategory.Mouse, "Mouse speed while zoomed in, as a share of the normal speed."),
        LibraryCommand("cl_citadel_zoom_is_toggle", "true", CommandCategory.Mouse, "Zoom stays on until you press the key again, instead of only while it's held."),
    )

    private val byName: Map<String, LibraryCommand> = Commands.associateBy { it.name.lowercase() }

    fun find(name: String): LibraryCommand? = byName[name.lowercase()]

    fun has(name: String): Boolean = name.lowercase() in byName
}

data class LibraryCommand(val name: String, val suggested: String, val category: CommandCategory, val description: String)

enum class CommandCategory { Performance, HudAndUi, Network, Audio, Mouse }
