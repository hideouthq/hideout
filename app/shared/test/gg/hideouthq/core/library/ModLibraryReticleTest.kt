package gg.hideouthq.core.library

import gg.hideouthq.core.crosshair.Crosshair
import gg.hideouthq.core.crosshair.CrosshairColor
import gg.hideouthq.core.crosshair.CrosshairPreset
import gg.hideouthq.core.crosshair.HeroSettings
import gg.hideouthq.core.data.HeldFile
import gg.hideouthq.core.data.HeroSettingsCopy
import gg.hideouthq.core.data.Preferences
import gg.hideouthq.core.data.ProcessScan
import gg.hideouthq.core.data.ReticleRead
import gg.hideouthq.core.data.deadlockstats.SteamAccountId
import gg.hideouthq.core.data.fake.FakeProcesses
import gg.hideouthq.core.data.fake.FaultPoint
import gg.hideouthq.core.data.fake.InMemoryGameFiles
import gg.hideouthq.core.data.fake.InMemoryHeroSettings
import gg.hideouthq.core.data.fake.InMemoryLibraryStore
import gg.hideouthq.core.data.fake.MemoryStorage
import gg.hideouthq.core.data.fake.ports
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class ModLibraryReticleTest {
    private val root = GameRoot("/games/Deadlock")
    private val cloudText = resourceBytes("deadlock/citadel_hero_settings.lst").decodeToString()
    private val gameText = cloudText.replace("\n", "\r\n")
    private val cyan = CrosshairPreset.Dot.crosshair

    private inner class World(cloud: String? = cloudText, game: String? = gameText, steam: Boolean = true) {
        val files = InMemoryGameFiles().apply { putBytes(root, InMemoryGameFiles.GameInfo, HideoutGameInfo) }
        val settings = InMemoryHeroSettings(listOfNotNull(cloud?.let { InMemoryHeroSettings.Cloud to it.encodeToByteArray() }, game?.let { HeroSettingsCopy.Game to it.encodeToByteArray() }).toMap(), account = InMemoryHeroSettings.Account.takeIf { steam })
        val processes = FakeProcesses()
        private var next = 0
        lateinit var library: ModLibrary

        fun TestScope.start(): ModLibrary {
            library = ModLibrary(FixedGameLocator(listOf(root)), Preferences(MemoryStorage()), InMemoryLibraryStore(LibraryManifest()), files, files.ports(processes = processes, heroSettings = settings), backgroundScope, ids = { "id${++next}" }, watch = testWatchTiming)
            library.start()
            runCurrent()
            return library
        }

        fun text(copy: HeroSettingsCopy): String? = settings.file(copy)?.decodeToString()

        fun backups(): List<BackupInfo> = kotlinx.coroutines.runBlocking { files.backups.list(root) }
    }

    private fun holding(text: String, crosshair: Crosshair) = checkNotNull(HeroSettings.parse(text.encodeToByteArray())).with(crosshair).render().decodeToString()

    @Test
    fun aCrosshairIsWrittenToBothCopiesEachKeepingEverythingElse() = runTest {
        val world = World()
        val library = with(world) { start() }
        val written = assertIs<ReticleWrite.Written>(library.writeReticle(cyan))
        assertEquals(holding(cloudText, cyan), world.text(InMemoryHeroSettings.Cloud))
        assertEquals(holding(gameText, cyan), world.text(HeroSettingsCopy.Game), "the game's copy keeps its CRLF")
        assertEquals(cyan, written.read.crosshair)
        assertEquals(cyan, (library.readReticle(root) as ReticleRead.Read).crosshair)
        val writes = world.settings.writes
        assertIs<ReticleWrite.Written>(library.writeReticle(cyan))
        assertEquals(writes, world.settings.writes, "the same crosshair again writes nothing")
    }

    @Test
    fun nothingIsWrittenWhileDeadlockRunsOrMightRun() = runTest {
        val world = World()
        val library = with(world) { start() }
        world.processes.scan = ProcessScan.Seen(game = true)
        assertEquals(ReticleWrite.Refused(LibraryProblem.GameRunning), library.writeReticle(cyan))
        world.processes.scan = ProcessScan.Unknown("no process list")
        assertIs<ReticleWrite.Refused>(library.writeReticle(cyan))
        assertEquals(listOf(cloudText, gameText), listOf(world.text(InMemoryHeroSettings.Cloud), world.text(HeroSettingsCopy.Game)))
        assertEquals(emptyList(), world.backups(), "no backup is taken for a write that waits")
        world.processes.scan = ProcessScan.Seen()
        assertIs<ReticleWrite.Written>(library.writeReticle(cyan))
        assertEquals(holding(cloudText, cyan), world.text(InMemoryHeroSettings.Cloud))
    }

    @Test
    fun aCustomGamesHoldStopsTheCrosshairOnlyWhileTheClientRuns() = runTest {
        val world = World()
        val library = with(world) { start() }
        assertIs<SessionAdmission.Held>(library.admitSession())
        world.processes.scan = ProcessScan.Seen(game = true)
        assertEquals(ReticleWrite.Refused(LibraryProblem.GameRunning), library.writeReticle(cyan))
        world.processes.scan = ProcessScan.Seen()
        assertIs<ReticleWrite.Written>(library.writeReticle(cyan))
        assertEquals(holding(cloudText, cyan), world.text(InMemoryHeroSettings.Cloud))
    }

    @Test
    fun theGamesCopyNotSavedYetIsMadeFromSteamCloudsOrWithoutSteamInTheGamesFormat() = runTest {
        val fromCloud = World(game = null)
        with(fromCloud) { start() }.writeReticle(cyan)
        assertEquals(holding(cloudText, cyan), fromCloud.text(HeroSettingsCopy.Game), "the mouse settings come along")

        val fresh = World(cloud = null, game = null, steam = false)
        with(fresh) { start() }.writeReticle(cyan)
        assertEquals(HeroSettings.New.with(cyan).render().decodeToString(), fresh.text(HeroSettingsCopy.Game))
    }

    @Test
    fun aSteamCloudCopyTheAccountHasNotSavedIsNeverMadeOrFilledFromTheGamesFolder() = runTest {
        for (game in listOf(gameText, null)) {
            val world = World(cloud = null, game = game)
            val library = with(world) { start() }
            assertEquals(ReticleRead.NotSavedYet, library.readReticle(root), "the game folder's copy may be another account's")
            assertIs<ReticleWrite.Refused>(library.writeReticle(cyan))
            assertEquals(listOf(null, game), listOf(world.text(InMemoryHeroSettings.Cloud), world.text(HeroSettingsCopy.Game)))
            assertEquals(emptyList(), world.backups(), "nothing is backed up for a write not made")
        }
    }

    @Test
    fun withoutSteamOnlyTheGamesOwnCopyIsWrittenAndRead() = runTest {
        val world = World(steam = false)
        val library = with(world) { start() }
        assertEquals(false, (library.readReticle(root) as ReticleRead.Read).cloud)
        library.writeReticle(cyan)
        assertEquals(holding(gameText, cyan), world.text(HeroSettingsCopy.Game))
        assertEquals(cloudText, world.text(InMemoryHeroSettings.Cloud), "a copy Steam was not found for is never touched")
    }

    @Test
    fun aCopyThatIsNotTheGamesSettingsIsNeverWrittenOver() = runTest {
        val world = World(cloud = "not kv3")
        val library = with(world) { start() }
        assertIs<LibraryProblem.ReticleWriteFailed>(assertIs<ReticleWrite.Refused>(library.writeReticle(cyan)).problem)
        assertEquals(listOf("not kv3", gameText), listOf(world.text(InMemoryHeroSettings.Cloud), world.text(HeroSettingsCopy.Game)))
    }

    @Test
    fun aWriteThatFailsSaysWhyAndTheNextTriesAgain() = runTest {
        val world = World()
        val library = with(world) { start() }
        world.settings.failure = "disk full"
        assertEquals(ReticleWrite.Refused(LibraryProblem.ReticleWriteFailed("disk full")), library.writeReticle(cyan))
        world.settings.failure = null
        assertIs<ReticleWrite.Written>(library.writeReticle(cyan))
        assertEquals(holding(gameText, cyan), world.text(HeroSettingsCopy.Game))
    }

    @Test
    fun theSessionsFirstSettingsWriteIsBackedUpOnceWithTheReticleAndAutoexec() = runTest {
        val world = World()
        world.files.putBytes(root, InMemoryGameFiles.Autoexec, "fps_max 240\n".encodeToByteArray())
        val library = with(world) { start() }
        library.writeReticle(cyan)
        library.writeReticle(Crosshair.GameDefault.copy(color = CrosshairColor(255, 0, 0)))
        library.writeAutoexec { it.withLines(listOf("volume 0.5")) }
        val backup = world.backups().single()
        assertEquals(BackupReason.BeforeGameSettings, backup.reason)
        val held = world.files.backups.held(root, backup.id)
        assertEquals(mapOf(HeldFile.Autoexec to "fps_max 240\n", HeldFile.CloudHeroSettings(InMemoryHeroSettings.Account) to cloudText, HeldFile.HeroSettings to gameText), held.mapValues { it.value.decodeToString() }, "each as it was before the first write")
    }

    @Test
    fun aRestorePutsEachCopyBackByteForByte() = runTest {
        val world = World()
        val library = with(world) { start() }
        library.writeReticle(cyan)
        val before = world.backups().single()
        world.settings.put(HeroSettingsCopy.Game, "the game wrote this".encodeToByteArray())
        library.restoreBackup(before)
        runCurrent()
        assertEquals(listOf(cloudText, gameText), listOf(world.text(InMemoryHeroSettings.Cloud), world.text(HeroSettingsCopy.Game)))
        val safety = world.backups().single { it.reason == BackupReason.BeforeRestore }
        assertContentEquals("the game wrote this".encodeToByteArray(), world.files.backups.held(root, safety.id)[HeldFile.HeroSettings], "what the restore replaced was backed up first")
    }

    @Test
    fun aRestoreChecksAgainThatDeadlockIsClosedJustBeforeItPutsTheCopiesBack() = runTest {
        val world = World()
        val library = with(world) { start() }
        library.writeReticle(cyan)
        val before = world.backups().single()
        world.files.around = { if (it is FaultPoint.Publish) world.processes.scan = ProcessScan.Seen(game = true) }
        library.restoreBackup(before)
        runCurrent()
        assertEquals(Maintenance.Backups(BackupWork.Revert(before), BackupStage.Refused(LibraryProblem.GameRunning)), library.state.value.maintenance)
        assertEquals(listOf(holding(cloudText, cyan), holding(gameText, cyan)), listOf(world.text(InMemoryHeroSettings.Cloud), world.text(HeroSettingsCopy.Game)), "neither copy is written while it runs")
    }

    @Test
    fun aRestoreWritesSteamCloudsCopyBackOnlyForTheAccountItWasTakenFrom() = runTest {
        val world = World()
        val library = with(world) { start() }
        library.writeReticle(cyan)
        val before = world.backups().single()
        val other = HeroSettingsCopy.Cloud(checkNotNull(SteamAccountId.ofAccountId(1157792213)))
        val othersText = holding(cloudText, CrosshairPreset.Classic.crosshair)
        world.settings.account = other.account
        world.settings.put(other, othersText.encodeToByteArray())
        world.settings.put(HeroSettingsCopy.Game, "the game wrote this".encodeToByteArray())
        library.restoreBackup(before)
        runCurrent()
        assertEquals(othersText, world.text(other), "another account's Steam Cloud copy is left as it is")
        assertEquals(gameText, world.text(HeroSettingsCopy.Game), "the game folder's copy comes back")

        world.settings.account = InMemoryHeroSettings.Account
        library.restoreBackup(before)
        runCurrent()
        assertEquals(cloudText, world.text(InMemoryHeroSettings.Cloud), "the account it was taken from gets its own back")
    }

    @Test
    fun aBackupThatHoldsNoCopyLeavesItAsItIs() = runTest {
        val world = World(cloud = null, game = null, steam = false)
        val library = with(world) { start() }
        library.writeReticle(cyan)
        val before = world.backups().single()
        assertNull(world.files.backups.held(root, before.id)[HeldFile.HeroSettings])
        library.restoreBackup(before)
        runCurrent()
        assertEquals(HeroSettings.New.with(cyan).render().decodeToString(), world.text(HeroSettingsCopy.Game))
    }
}
