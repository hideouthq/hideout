package gg.hideouthq.feature.settings

import kotlin.test.Test
import kotlin.test.assertEquals

class SettingsTabsTest {
    @Test
    fun developerSitsBeforeAboutAndShowsWhereStatsOrCustomGamesExist() {
        assertEquals(
            listOf(SettingsTab.Game, SettingsTab.Mods, SettingsTab.Stats, SettingsTab.Appearance, SettingsTab.Experimental, SettingsTab.Developer, SettingsTab.About),
            shownTabs(statsAvailable = true, customGamesAvailable = false),
        )
        assertEquals(listOf(SettingsTab.Game, SettingsTab.Mods, SettingsTab.Appearance, SettingsTab.Experimental, SettingsTab.About), shownTabs(statsAvailable = false, customGamesAvailable = false))
        assertEquals(listOf(SettingsTab.Game, SettingsTab.Mods, SettingsTab.Appearance, SettingsTab.Experimental, SettingsTab.Developer, SettingsTab.About), shownTabs(statsAvailable = false, customGamesAvailable = true))
    }
}
