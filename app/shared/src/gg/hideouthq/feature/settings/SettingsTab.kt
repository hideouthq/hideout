package gg.hideouthq.feature.settings

import gg.hideouthq.resources.Res
import gg.hideouthq.resources.settings_tab_about
import gg.hideouthq.resources.settings_tab_appearance
import gg.hideouthq.resources.settings_tab_developer
import gg.hideouthq.resources.settings_tab_experimental
import gg.hideouthq.resources.settings_tab_game
import gg.hideouthq.resources.settings_tab_mods
import gg.hideouthq.resources.settings_tab_stats
import org.jetbrains.compose.resources.StringResource

enum class SettingsTab(val label: StringResource) {
    Game(Res.string.settings_tab_game),
    Mods(Res.string.settings_tab_mods),
    Stats(Res.string.settings_tab_stats),
    Appearance(Res.string.settings_tab_appearance),
    Experimental(Res.string.settings_tab_experimental),
    Developer(Res.string.settings_tab_developer),
    About(Res.string.settings_tab_about),
}

internal fun shownTabs(statsAvailable: Boolean, customGamesAvailable: Boolean): List<SettingsTab> = SettingsTab.entries.filter {
    when (it) {
        SettingsTab.Stats -> statsAvailable
        SettingsTab.Developer -> statsAvailable || customGamesAvailable
        else -> true
    }
}
