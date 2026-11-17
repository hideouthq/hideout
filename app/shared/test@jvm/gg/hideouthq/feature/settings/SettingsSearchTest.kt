package gg.hideouthq.feature.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SettingsSearchTest {
    private val byId = SettingsRegistry.associateBy { it.id }
    private val texts = listOf(
        settingText(byId.getValue(SettingId.Deadlock), "Deadlock folder", null, "game, steam"),
        settingText(byId.getValue(SettingId.ModLoading), "Mod loading", "Deadlock reads the mods folder through gameinfo.gi.", "gameinfo"),
        settingText(byId.getValue(SettingId.DownloadFolder), "Download folder", null, "location"),
        settingText(byId.getValue(SettingId.SpaceUsed), "Space used", null, "storage, disk"),
    )

    private fun ids(query: String) = searchSettings(texts, query).sections.map { section -> section.tab to section.matches.map { it.text.setting.id } }

    @Test
    fun everyWordMustMatchATitleDescriptionOrKeywordIgnoringCaseAndSpacing() {
        assertEquals(
            listOf(SettingsTab.Game to listOf(SettingId.Deadlock, SettingId.ModLoading), SettingsTab.Mods to listOf(SettingId.DownloadFolder)),
            ids("folder"),
            "grouped by tab, in tab order",
        )
        assertEquals(listOf(SettingsTab.Mods to listOf(SettingId.DownloadFolder)), ids("  DOWNLOAD\tfolder "))
        assertEquals(listOf(SettingsTab.Mods to listOf(SettingId.SpaceUsed)), ids("storage"), "a keyword")
        assertEquals(emptyList(), ids("folder zebra"))
        assertEquals(emptyList(), ids(" \t "), "only whitespace searches nothing")
    }

    @Test
    fun theTextsShownAreHighlightedWhereTheyMatchAndKeywordsAreNot() {
        val matches = searchSettings(texts, "folder").sections.flatMap { it.matches }.associateBy { it.text.setting.id }
        assertEquals(listOf(9..14), matches.getValue(SettingId.Deadlock).title)
        val loading = matches.getValue(SettingId.ModLoading)
        assertEquals(emptyList(), loading.title)
        assertEquals("folder", loading.text.description!!.substring(loading.description.single()))
        val storage = searchSettings(texts, "storage").sections.single().matches.single()
        assertEquals(emptyList(), storage.title + storage.description)
    }

    @Test
    fun aRowIsSearchedAndHighlightedByTheDescriptionItShowsNotItsFixedOne() {
        val saved = "Saved bug.txt. Attach it to your bug report."
        val report = settingText(byId.getValue(SettingId.BugReport), "Save a bug report", saved, "log, bug")
        assertEquals(emptyList(), searchSettings(listOf(report), "left out").sections, "words only the fixed description has")
        val match = searchSettings(listOf(report), "attach report").sections.single().matches.single()
        assertEquals(listOf("Attach", "report"), match.description.map { saved.substring(it) })
    }

    @Test
    fun analyticsIsShownOnlyWhenConfiguredEvenAfterOptOut() {
        for (enabled in listOf(null, false, true)) {
            val setting = byId.getValue(SettingId.UsageAnalytics)
            assertEquals(enabled != null, setting.isShown(SettingsUiState(analyticsEnabled = enabled)))
        }
    }

    @Test
    fun patternCharactersAreLiteral() {
        listOf("[", "(", "*", ".*", "\\", "?").forEach { query -> assertEquals(emptyList(), ids(query), query) }
        assertEquals(listOf(SettingsTab.Game to listOf(SettingId.ModLoading)), ids("gameinfo.gi"), "a dot is a dot")
        val bracketed = settingText(byId.getValue(SettingId.Deadlock), "Deadlock [beta] folder", null, "")
        assertEquals(listOf(9..14), searchSettings(listOf(bracketed), "[beta]").sections.single().matches.single().title)
    }

    @Test
    fun highlightsAreOffsetsIntoTheOriginalTextEveryOccurrenceMerged() {
        assertEquals(listOf(0..5, 7..12), highlights("folder FOLDER", listOf("folder")))
        assertEquals(listOf(10..15), highlights("İstanbul, folder", listOf("FOLDER")), "a capital that lowercases to two characters shifts nothing")
        assertEquals(listOf(0..7), highlights("download", listOf("down", "load")), "touching matches merge")
        assertEquals(listOf(0..4), highlights("aaaaa", listOf("aa")), "overlapping matches merge")
        assertEquals(emptyList(), highlights("folder", listOf("")))
        assertTrue(highlights("", listOf("folder")).isEmpty())
    }
}
