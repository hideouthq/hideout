package gg.hideouthq.core.data

import gg.hideouthq.core.model.Accent
import gg.hideouthq.core.model.Appearance
import gg.hideouthq.core.model.ModCardStyle
import gg.hideouthq.core.model.SceneSettings
import gg.hideouthq.core.model.SceneLighting
import gg.hideouthq.core.model.Backdrop
import gg.hideouthq.core.data.fake.MemoryStorage
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PreferencesTest {
    private fun stored(json: String) = Preferences(MemoryStorage(mapOf("preferences.json" to json)))

    @Test
    fun aMissingOrUnreadableFileIsNoChoicesMadeAndAnUnknownChoiceReadsAsTheDefault() = runTest {
        assertEquals(InstallDefaults(), Preferences(MemoryStorage()).installDefaults())
        assertEquals(InstallDefaults(), stored("not json").installDefaults())
        assertEquals(InstallDefaults(), stored("""{"askWhichVersion":false,"downloadFolder":"../downloads"}""").installDefaults(), "a folder that is not a plain absolute path")
        val older = stored("""{"gameFolder":"D:\\Games\\Deadlock"}""")
        assertEquals("D:\\Games\\Deadlock", older.gameFolder())
        assertEquals(InstallDefaults(), older.installDefaults(), "a file from before the install settings")
        assertEquals(StatsPreferences(), older.statsPreferences(), "and from before Stats")
        assertEquals(SceneSettings(bloom = true), stored("""{"sceneBackdrop":"Aquarium","sceneLighting":"Neon","sceneBloom":true}""").sceneSettings())
        assertEquals(true, older.apply { readAutoRepair() }.autoRepair.value, "skins are repaired automatically unless the user said otherwise")
        assertEquals(true, older.apply { readDownloadAppUpdates() }.downloadAppUpdates.value, "Hideout updates download automatically unless the user said otherwise")
    }

    @Test
    fun sharingMatchIdsIsOffUntilTurnedOnAndRemembersWhenItWas() = runTest {
        val storage = MemoryStorage()
        var now = 1_000L
        val clock = object : kotlin.time.Clock { override fun now() = kotlin.time.Instant.fromEpochSeconds(now) }
        val preferences = Preferences(storage, clock)
        assertFalse(preferences.statsPreferences().shareMatchIds, "off by default")

        preferences.setShareMatchIds(true)
        now = 2_000
        preferences.setShareMatchIds(true)
        assertEquals(1_000L, Preferences(storage).statsPreferences().shareMatchIdsSince, "on since it was first turned on, across a restart")

        preferences.setShareMatchIds(false)
        assertFalse(preferences.statsPreferences().shareMatchIds)
        preferences.setShareMatchIds(true)
        assertEquals(2_000L, preferences.stats.value?.shareMatchIdsSince, "turned on again, it shares only what is found from then")
    }

    @Test
    fun everyChoiceIsKeptAcrossRestartsWithoutTouchingTheOthersEvenWhenMadeAtOnce() = runTest {
        val storage = MemoryStorage()
        val preferences = Preferences(storage)
        val scene = SceneSettings(Backdrop.Custom, SceneLighting.Dramatic, turntable = false, bloom = true, customBackdrop = "/data/backdrops/a.png")
        listOf(
            async { preferences.setGameFolder("D:\\Games\\Deadlock") },
            async { preferences.setAskWhichVersion(false) },
            async { preferences.setKeepDownloads(false) },
            async { preferences.setDownloadFolder(DownloadFolder("/mnt/games/downloads")) },
            async { preferences.setLibraryLayout("cards") },
            async { preferences.setScene(scene) },
            async { preferences.setStatsAccount(1_157_792_213) },
            async { preferences.setStatsFilters("Ranked", "Week") },
            async { preferences.setStatsEnabled(false) },
            async { preferences.setPreferredHeroes(listOf("hero_inferno", "hero_haze", "hero_wraith")) },
            async { preferences.finishSetup() },
            async { preferences.setAutoRepair(false) },
            async { preferences.setDownloadAppUpdates(false) },
        ).awaitAll()
        val reopened = Preferences(storage)
        assertEquals("D:\\Games\\Deadlock", reopened.gameFolder())
        assertEquals(InstallDefaults(askWhichVersion = false, keepDownloads = false, downloadFolder = DownloadFolder("/mnt/games/downloads")), reopened.installDefaults())
        assertEquals("cards", reopened.libraryLayout())
        assertEquals(scene, reopened.sceneSettings())
        assertEquals(StatsPreferences(enabled = false, accountId = 1_157_792_213, mode = "Ranked", period = "Week"), reopened.statsPreferences())
        reopened.readSetup()
        assertEquals(true, reopened.setupDone.value)
        assertEquals(listOf("hero_inferno", "hero_haze", "hero_wraith"), reopened.preferredHeroes.value, "in the order picked")
        reopened.readAutoRepair()
        assertEquals(false, reopened.autoRepair.value)
        assertNull(reopened.downloadAppUpdates.value, "unknown until read")
        reopened.readDownloadAppUpdates()
        assertEquals(false, reopened.downloadAppUpdates.value)

        reopened.setDownloadFolder(null)
        reopened.setStatsAccount(null)
        assertEquals(InstallDefaults(askWhichVersion = false, keepDownloads = false), Preferences(storage).installDefaults(), "back to Hideout's own folder")
        assertNull(Preferences(storage).statsPreferences().accountId)
    }

    @Test
    fun theAccentAndCardStyleApplyAtOnceAndAreRememberedAcrossARestart() = runTest {
        val storage = MemoryStorage()
        val preferences = Preferences(storage)
        assertEquals(Appearance(Accent.Graphite, ModCardStyle.OnImage), preferences.appearance.value, "Graphite and titles on the image until chosen otherwise")

        preferences.setAccent(Accent.Ember)
        preferences.setModCards(ModCardStyle.UnderImage)
        assertEquals(Appearance(Accent.Ember, ModCardStyle.UnderImage), preferences.appearance.value, "a pick shows without a restart")
        assertEquals(Appearance(Accent.Ember, ModCardStyle.UnderImage), Preferences(storage).apply { readSetup() }.appearance.value, "and is what the app opens with")

        assertEquals(Appearance(), stored("""{"accent":"Chartreuse","modCards":"Sideways"}""").apply { readSetup() }.appearance.value, "a name this build does not know is none chosen")
    }

    @Test
    fun askingToHearWhenServersAreLiveIsOffUntilAskedAndRemembered() = runTest {
        val storage = MemoryStorage()
        val preferences = Preferences(storage).apply { readSetup() }
        assertFalse(preferences.notifyServers.value)
        preferences.setNotifyServers(true)
        assertTrue(Preferences(storage).apply { readSetup() }.notifyServers.value)
    }

    @Test
    fun readingSetupAlsoReadsTheSidebarSoTheShellOpensAsItWasLeft() = runTest {
        val storage = MemoryStorage()
        Preferences(storage).setSidebarCollapsed(true)
        val reopened = Preferences(storage)
        assertFalse(reopened.sidebarCollapsed.value, "nothing is read yet")
        reopened.readSetup()
        assertTrue(reopened.sidebarCollapsed.value)
    }

    @Test
    fun aChoiceShowsOnceItIsReadOrSaved() = runTest {
        val preferences = stored("""{"askWhichVersion":false}""")
        assertNull(preferences.defaults.value)
        assertNull(preferences.scene.value)
        assertNull(preferences.setupDone.value)
        preferences.installDefaults()
        assertEquals(InstallDefaults(askWhichVersion = false), preferences.defaults.value)
        preferences.setKeepDownloads(false)
        assertEquals(InstallDefaults(askWhichVersion = false, keepDownloads = false), preferences.defaults.value)
        assertEquals(SceneSettings(), preferences.scene.value, "a saved change shows every choice")
    }

    @Test
    fun aWriteTheDiskRefusesLeavesTheSavedValueShowingAndOnDisk() = runTest {
        var refuse = false
        val texts = mutableMapOf<String, String>()
        val storage = object : CacheStorage {
            override suspend fun read(name: String): String? = texts[name]
            override suspend fun write(name: String, text: String) {
                if (refuse) throw IllegalStateException("read-only disk")
                texts[name] = text
            }
        }
        val preferences = Preferences(storage)
        preferences.setAskWhichVersion(false)
        refuse = true
        assertFailsWith<IllegalStateException> { preferences.setKeepDownloads(false) }
        assertEquals(InstallDefaults(askWhichVersion = false), preferences.defaults.value)
        assertEquals(InstallDefaults(askWhichVersion = false), Preferences(storage).installDefaults(), "a restart reads what was saved")
    }
}
