package gg.hideouthq.core.crosshair

import gg.hideouthq.core.autoexec.mine
import gg.hideouthq.core.crosshair.community.CommunityCrosshair
import gg.hideouthq.core.data.CacheStorage
import gg.hideouthq.core.data.HeroSettingsCopy
import gg.hideouthq.core.data.Preferences
import gg.hideouthq.core.data.ProcessScan
import gg.hideouthq.core.data.SavedNote
import gg.hideouthq.core.data.fake.FakeProcesses
import gg.hideouthq.core.data.fake.InMemoryGameFiles
import gg.hideouthq.core.data.fake.InMemoryHeroSettings
import gg.hideouthq.core.data.fake.InMemoryLibraryStore
import gg.hideouthq.core.data.fake.MemoryStorage
import gg.hideouthq.core.data.fake.ports
import gg.hideouthq.core.library.ConfigTarget
import gg.hideouthq.core.library.FileImage
import gg.hideouthq.core.library.FixedGameLocator
import gg.hideouthq.core.library.GameRoot
import gg.hideouthq.core.library.HideoutGameInfo
import gg.hideouthq.core.library.LibraryManifest
import gg.hideouthq.core.library.LibraryProblem
import gg.hideouthq.core.library.Manager
import gg.hideouthq.core.library.ModLibrary
import gg.hideouthq.core.library.PendingChange
import gg.hideouthq.core.library.resourceBytes
import gg.hideouthq.core.library.testWatchTiming
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class CrosshairsTest {
    private val root = GameRoot("/games/Deadlock")
    private val classic = CrosshairId("preset-classic")
    private val wide = CrosshairId("preset-widegap")
    private val community = CommunityCrosshair("crosshair_1", "Mint cross", "kiri", Instant.parse("2026-10-25T00:00:00Z"), likes = 1, mine)
    private val gameFile = resourceBytes("deadlock/citadel_hero_settings.lst").decodeToString()

    private fun saving(crosshair: Crosshair) = checkNotNull(HeroSettings.parse(gameFile.encodeToByteArray())).with(crosshair).render()

    private inner class World(
        saved: Crosshair? = mine,
        cloud: Boolean = true,
        val data: MemoryStorage = MemoryStorage(),
        private val storage: (MemoryStorage) -> CacheStorage = { it },
        val settings: InMemoryHeroSettings = InMemoryHeroSettings(saved?.let { mapOf(HeroSettingsCopy.Game to saving(it), InMemoryHeroSettings.Cloud to saving(it)) }.orEmpty(), InMemoryHeroSettings.Account.takeIf { cloud }),
        val store: InMemoryLibraryStore = InMemoryLibraryStore(LibraryManifest()),
    ) {
        val files = InMemoryGameFiles().apply { putBytes(root, InMemoryGameFiles.GameInfo, HideoutGameInfo) }
        val processes = FakeProcesses()
        val logged = mutableListOf<String>()
        private var next = 0
        lateinit var owner: Crosshairs
        lateinit var library: ModLibrary

        fun TestScope.start(scope: CoroutineScope = backgroundScope): Crosshairs {
            library = ModLibrary(FixedGameLocator(listOf(root)), Preferences(MemoryStorage()), store, files, files.ports(processes = processes, heroSettings = settings), scope, ids = { "id${++next}" }, watch = testWatchTiming)
            library.start()
            owner = Crosshairs(library, storage(data), scope, ids = { "c${++next}" }, log = logged::add).apply { start() }
            runCurrent()
            return owner
        }

        fun saved(copy: HeroSettingsCopy = InMemoryHeroSettings.Cloud): Crosshair? = settings.file(copy)?.let { checkNotNull(HeroSettings.parse(it)).crosshair }

        suspend fun kept(): Kept? = data.read(KeptCodec.FileName)?.let(KeptCodec::decode)

        fun state() = owner.state.value
    }

    private fun TestScope.after(milliseconds: Long) {
        advanceTimeBy(milliseconds.milliseconds)
        runCurrent()
    }

    @Test
    fun theGamesSavedCrosshairShowsAsMineWithoutBeingKeptAndUsingAnotherKeepsIt() = runTest {
        val world = World()
        val crosshairs = with(world) { start() }
        after(1_000)
        val yours = world.state().yours
        assertEquals(listOf("Mine", "Classic", "Dot", "Wide gap"), yours.entries.map { it.name })
        assertEquals(CrosshairOrigin.Mine(mine), world.state().inUse?.origin)
        assertNull(world.kept(), "reading keeps nothing")
        assertEquals(0, world.settings.writes)

        crosshairs.use(CrosshairPick.Yours(classic))
        after(600)
        assertEquals(classic, world.state().yours.inUse)
        assertEquals(listOf(CrosshairPreset.Classic.crosshair, CrosshairPreset.Classic.crosshair), listOf(world.saved(InMemoryHeroSettings.Cloud), world.saved(HeroSettingsCopy.Game)))
        val kept = assertNotNull(world.kept())
        assertEquals(listOf("Mine", "Classic", "Dot", "Wide gap"), kept.crosshairs.map { it.name }, "switching away keeps Mine")
        assertEquals(classic, kept.lastUsed)
        after(600)
        assertNull(world.kept()?.waiting, "once written nothing waits")

        crosshairs.use(CrosshairPick.Yours(yours.inUse!!))
        after(600)
        assertEquals(yours.inUse, world.state().yours.inUse)
        assertEquals(mine, world.saved())
    }

    @Test
    fun withoutSteamAGameThatHasSavedNoCrosshairUsesItsDefaults() = runTest {
        val world = World(saved = null, cloud = false)
        with(world) { start() }
        assertEquals(Crosshair.GameDefault, world.state().inUse?.crosshair)
        assertEquals(GameCrosshair.Saved(Crosshair.GameDefault, cloud = false), world.state().game)
    }

    @Test
    fun withNoSteamCloudCopyForTheAccountNothingIsInUseAndUseInGameWritesNothing() = runTest {
        val world = World(settings = InMemoryHeroSettings(mapOf(HeroSettingsCopy.Game to saving(mine))))
        val crosshairs = with(world) { start() }
        after(2_100)
        assertEquals(GameCrosshair.NotSavedYet, world.state().game)
        assertNull(world.state().yours.inUse, "the game folder's copy may be another account's, so it is not in use")
        assertEquals(listOf("Classic", "Dot", "Wide gap"), world.state().yours.entries.map { it.name }, "and shows as no Mine")
        crosshairs.use(CrosshairPick.Yours(classic))
        after(600)
        assertEquals(0, world.settings.writes)
        assertNull(world.settings.file(InMemoryHeroSettings.Cloud))
    }

    @Test
    fun editingTheCrosshairInUseReachesTheGameAndAnyOtherOnlyYours() = runTest {
        val world = World()
        val crosshairs = with(world) { start() }
        crosshairs.use(CrosshairPick.Yours(classic))
        after(600)
        val writes = world.settings.writes
        crosshairs.change(YoursChange.Settings(wide, mine))
        after(600)
        assertEquals(writes, world.settings.writes, "a crosshair not in use reaches Yours alone")
        repeat(10) { step -> crosshairs.change(YoursChange.Settings(classic, CrosshairPreset.Classic.crosshair.with(gap = step.toDouble()))) }
        after(600)
        assertEquals(CrosshairPreset.Classic.crosshair.with(gap = 9.0), world.saved())
        assertEquals(writes + 2, world.settings.writes, "a slider's steps are one write of each copy")
        assertEquals(classic, world.state().yours.inUse)
        assertEquals(mine, world.state().yours.entries.single { it.id == wide }.crosshair)
        crosshairs.change(YoursChange.Reset(classic))
        after(600)
        assertEquals(CrosshairPreset.Classic.crosshair, world.saved())
    }

    @Test
    fun aSliderStepAndAUseEachMakeOneStateAndNoStateShowsAnotherCrosshairInUseOrAMine() = runTest {
        val world = World(saved = CrosshairPreset.Classic.crosshair)
        val crosshairs = with(world) { start() }
        after(1_000)
        val states = mutableListOf<CrosshairsState>()
        val watching = launch(Dispatchers.Unconfined) { crosshairs.state.collect { states += it } }
        states.clear()
        repeat(5) { step ->
            crosshairs.change(YoursChange.Settings(classic, CrosshairPreset.Classic.crosshair.with(gap = 10.0 + step)))
            assertEquals(step + 1, states.size, "slider step $step makes exactly one state")
        }
        crosshairs.use(CrosshairPick.Yours(wide))
        assertEquals(6, states.size, "Use makes exactly one state")
        after(600)
        crosshairs.change(YoursChange.Settings(wide, CrosshairPreset.WideGap.crosshair.with(gap = 3.0)))
        after(600)
        watching.cancel()
        val inUse = states.map { it.yours.inUse }
        assertEquals(listOf(classic), inUse.take(5).distinct(), "the steps keep Classic in use")
        assertEquals(listOf(wide), inUse.drop(5).distinct(), "and from Use on, Wide gap, the write and its next step included")
        assertTrue(states.none { state -> state.yours.entries.any { it.origin is CrosshairOrigin.Mine } }, "no Mine flashes in")
        assertEquals(CrosshairPreset.WideGap.crosshair.with(gap = 3.0), world.saved())
    }

    @Test
    fun aCrosshairMadeWithTheGamesOwnSettingsLeavesTheOneInUseInUse() = runTest {
        val world = World(saved = Crosshair.GameDefault)
        val crosshairs = with(world) { start() }
        val mine = assertNotNull(world.state().inUse)
        assertIs<CrosshairOrigin.Mine>(mine.origin)
        val made = assertNotNull(crosshairs.change(YoursChange.Create))
        assertEquals(mine.id, world.state().yours.inUse, "New starts from the game's defaults, which tie with Mine, and the tie stays with Mine")
        crosshairs.change(YoursChange.Settings(made, Crosshair.GameDefault.with(gap = 9.0)))
        after(600)
        assertEquals(0, world.settings.writes, "editing the new crosshair reaches Yours alone")
        assertEquals(Crosshair.GameDefault, world.saved())
    }

    @Test
    fun aPickMadeWhileAWriteIsInFlightIsWrittenAfterIt() = runTest {
        val world = World()
        val crosshairs = with(world) { start() }
        val before = assertNotNull(world.state().yours.inUse)
        val hold = CompletableDeferred<Unit>()
        world.settings.held = hold
        crosshairs.use(CrosshairPick.Yours(classic))
        after(600)
        crosshairs.use(CrosshairPick.Yours(before))
        hold.complete(Unit)
        runCurrent()
        after(600)
        assertEquals(mine, world.saved(), "the pick back to what the file held before the write is written after it")
        assertEquals(before, world.state().yours.inUse)
    }

    @Test
    fun anImportsCrosshairWaitsForYoursToBeRead() = runTest {
        val read = CompletableDeferred<Unit>()
        val world = World(saved = Crosshair.GameDefault, storage = { data -> object : CacheStorage by data { override suspend fun read(name: String): String? = read.await().let { data.read(name) } } })
        val crosshairs = with(world) { start() }
        val origin = CrosshairOrigin.FromManager(Manager.DeadlockModManager, mine)
        val adopted = async { crosshairs.changeWhenRead(YoursChange.Adopt(origin)) }
        runCurrent()
        assertTrue(adopted.isActive, "it waits while crosshairs.json is read")
        read.complete(Unit)
        runCurrent()
        assertEquals(origin, world.state().yours.entries.single { it.id == adopted.await() }.origin)
    }

    @Test
    fun whileDeadlockRunsAChangeIsInUseAtOnceAndWaitsToBeWrittenUntilItCloses() = runTest {
        val world = World()
        world.processes.scan = ProcessScan.Seen(game = true)
        val crosshairs = with(world) { start() }
        after(2_100)
        crosshairs.use(CrosshairPick.Yours(classic))
        assertEquals(classic, world.state().yours.inUse, "in use at once")
        after(5_000)
        assertEquals(mine, world.saved(), "nothing is written while Deadlock runs")
        assertEquals(SavedNote.Waiting, world.state().note)
        assertEquals(CrosshairPreset.Classic.crosshair, world.kept()?.waiting, "crosshairs.json keeps what waits")

        world.settings.put(InMemoryHeroSettings.Cloud, saving(mine))
        world.processes.scan = ProcessScan.Seen()
        after(2_100)
        after(600)
        assertEquals(CrosshairPreset.Classic.crosshair, world.saved(InMemoryHeroSettings.Cloud))
        assertEquals(CrosshairPreset.Classic.crosshair, world.saved(HeroSettingsCopy.Game))
        assertEquals(SavedNote.NextStart, world.state().note)
        assertEquals(classic, world.state().yours.inUse)
        after(600)
        assertNull(world.kept()?.waiting)
    }

    @Test
    fun aRestartWhileACrosshairWaitsKeepsItAndWritesItOnceDeadlockCloses() = runTest {
        val data = MemoryStorage()
        val settings = InMemoryHeroSettings(mapOf(HeroSettingsCopy.Game to saving(mine), InMemoryHeroSettings.Cloud to saving(mine)))
        val first = World(data = data, settings = settings)
        first.processes.scan = ProcessScan.Seen(game = true)
        val session = CoroutineScope(backgroundScope.coroutineContext + Job())
        with(first) { start(session) }.use(CrosshairPick.Yours(classic))
        after(600)
        session.cancel()

        val second = World(data = data, settings = settings)
        second.processes.scan = ProcessScan.Seen(game = true)
        with(second) { start() }
        after(1_000)
        assertEquals(classic, second.state().yours.inUse, "what waited is in use after the restart")
        assertEquals(mine, second.saved())
        second.processes.scan = ProcessScan.Seen()
        after(2_100)
        after(600)
        assertEquals(CrosshairPreset.Classic.crosshair, second.saved())
    }

    @Test
    fun withoutSteamOnlyTheGamesFolderIsWrittenAndTheNoteSaysTheGameMightNotUseIt() = runTest {
        val world = World(cloud = false)
        val crosshairs = with(world) { start() }
        crosshairs.use(CrosshairPick.Yours(classic))
        after(600)
        assertEquals(CrosshairPreset.Classic.crosshair, world.saved(HeroSettingsCopy.Game))
        assertEquals(mine, world.saved(InMemoryHeroSettings.Cloud))
        assertEquals(SavedNote.GameFolderOnly, world.state().note)
    }

    @Test
    fun aCopyWhoseFolderIsMissingNeverHoldsUpTheOthersWatchAndIsReadOnceItsFolderAppears() = runTest {
        val world = World(settings = InMemoryHeroSettings(mapOf(InMemoryHeroSettings.Cloud to saving(mine))).apply { unwatchable += HeroSettingsCopy.Game })
        with(world) { start() }
        assertEquals(mine, world.state().inUse?.crosshair)
        world.settings.put(InMemoryHeroSettings.Cloud, saving(CrosshairPreset.Classic.crosshair))
        after(200)
        assertEquals(classic, world.state().yours.inUse, "Steam Cloud's watch goes on while the game folder's cannot start")

        val first = World(settings = InMemoryHeroSettings(mapOf(HeroSettingsCopy.Game to saving(mine))).apply { unwatchable += InMemoryHeroSettings.Cloud })
        with(first) { start() }
        assertEquals(GameCrosshair.NotSavedYet, first.state().game)
        first.settings.unwatchable.clear()
        first.settings.put(InMemoryHeroSettings.Cloud, saving(mine))
        after(testWatchTiming.period.inWholeMilliseconds + 200)
        assertEquals(GameCrosshair.Saved(mine, cloud = true), first.state().game, "its watch started again on its own and read it")
    }

    @Test
    fun aWriteRefusedWhileTheLibraryFinishesAnInterruptedChangeWaitsWithoutAFailureAndIsMadeOnceItHas() = runTest {
        val interrupted = PendingChange.ConfigEdit(root, ConfigTarget.Autoexec, "q7w2", from = null, to = FileImage(5, "0".repeat(64)))
        val world = World(store = InMemoryLibraryStore(LibraryManifest(pending = interrupted)).apply { refuse = { "the disk is busy" } })
        val crosshairs = with(world) { start() }
        after(2_100)
        crosshairs.use(CrosshairPick.Yours(classic))
        after(600)
        assertEquals(mine, world.saved(), "nothing is written while the Library cannot finish its change")
        assertEquals(classic, world.state().yours.inUse)
        assertNull(world.state().refused, "and no failure shows")

        world.store.refuse = { null }
        world.library.writeAutoexec { it }
        after(600)
        assertEquals(CrosshairPreset.Classic.crosshair, world.saved(), "the crosshair is written once it has, with no Retry")
    }

    @Test
    fun aWriteThatFailsSaysWhyAndRetryWritesIt() = runTest {
        val world = World()
        val crosshairs = with(world) { start() }
        world.settings.failure = "disk full"
        crosshairs.use(CrosshairPick.Yours(classic))
        after(600)
        assertEquals(SavedNote.Failed.File(LibraryProblem.ReticleWriteFailed("disk full")), world.state().note)
        assertEquals(classic, world.state().yours.inUse, "it stays in use, waiting")
        world.settings.failure = null
        crosshairs.retry()
        runCurrent()
        assertEquals(CrosshairPreset.Classic.crosshair, world.saved())
        assertEquals(SavedNote.NextStart, world.state().note)
    }

    @Test
    fun settingsTheGameDidNotSaveAsKv3AreNeverWrittenOver() = runTest {
        val world = World(settings = InMemoryHeroSettings(mapOf(InMemoryHeroSettings.Cloud to "garbage".encodeToByteArray())))
        val crosshairs = with(world) { start() }
        assertIs<GameCrosshair.Unreadable>(world.state().game)
        crosshairs.use(CrosshairPick.Yours(classic))
        after(600)
        assertNull(world.state().yours.inUse)
        assertEquals(0, world.settings.writes)
    }

    @Test
    fun whileTheGamesSettingsCannotBeReadNothingIsInUseSoAnEditIsNotMadeToWait() = runTest {
        val world = World()
        world.processes.scan = ProcessScan.Seen(game = true)
        val crosshairs = with(world) { start() }
        after(2_100)
        crosshairs.use(CrosshairPick.Yours(classic))
        world.settings.put(InMemoryHeroSettings.Cloud, "garbage".encodeToByteArray())
        after(200)
        assertIs<GameCrosshair.Unreadable>(world.state().game)
        assertNull(world.state().yours.inUse, "no crosshair is known to be in use, so none says it applies next time")
        crosshairs.change(YoursChange.Settings(classic, CrosshairPreset.Classic.crosshair.with(gap = 9.0)))
        world.processes.scan = ProcessScan.Seen()
        after(2_100)
        after(600)
        assertNull(world.state().yours.inUse)
        assertEquals(CrosshairPreset.Classic.crosshair, world.kept()?.waiting, "the edit does not wait to be written; the pick before it still does")
        assertEquals(0, world.settings.writes)
    }

    @Test
    fun theCrosshairInUseCannotBeDeletedOrUnsavedAndACommunityOneJoinsYoursWhenUsed() = runTest {
        val world = World(saved = Crosshair.GameDefault)
        val crosshairs = with(world) { start() }
        crosshairs.use(CrosshairPick.Community(community))
        after(600)
        val used = assertNotNull(world.state().inUse)
        assertEquals("Mint cross" to CrosshairOrigin.Community("crosshair_1", "kiri", mine), used.name to used.origin)
        assertNull(crosshairs.change(YoursChange.Delete(used.id)))
        assertNull(crosshairs.change(YoursChange.Unsave("crosshair_1")))
        assertEquals(used.id, crosshairs.change(YoursChange.Save(community)), "saving it again keeps the one there")
        crosshairs.use(CrosshairPick.Community(community))
        assertEquals(listOf("Mine", "Classic", "Dot", "Wide gap", "Mint cross"), world.state().yours.entries.map { it.name }, "using it again adds nothing, and the defaults it replaced are kept")
        val copy = assertNotNull(crosshairs.change(YoursChange.Copy(community)))
        assertEquals(CrosshairOrigin.CommunityCopy("crosshair_1", "kiri", mine), world.state().yours.entries.single { it.id == copy }.origin)
        crosshairs.use(CrosshairPick.Yours(classic))
        after(600)
        assertEquals(used.id, crosshairs.change(YoursChange.Unsave("crosshair_1")))
        assertEquals(listOf("Mine", "Classic", "Dot", "Wide gap", "Mint cross"), world.state().yours.entries.map { it.name }, "the copy stays")
    }

    @Test
    fun unsavingTakesOnlyTheEntriesStillAsSavedAndNeverAnEditedOneOrADuplicate() = runTest {
        val world = World(saved = Crosshair.GameDefault)
        val crosshairs = with(world) { start() }
        val green = mine.with(gap = 4.0)
        fun entries() = world.state().yours.entries

        val edited = assertNotNull(crosshairs.change(YoursChange.Save(community)))
        crosshairs.change(YoursChange.Settings(edited, green))
        assertNull(crosshairs.change(YoursChange.Unsave("crosshair_1")), "an edited entry is the user's own, so nothing is saved to unsave")
        assertEquals(green, entries().single { it.id == edited }.crosshair)

        val saved = assertNotNull(crosshairs.change(YoursChange.Save(community)))
        val duplicate = assertNotNull(crosshairs.change(YoursChange.Duplicate(saved)))
        assertEquals(CrosshairOrigin.CommunityCopy("crosshair_1", "kiri", mine), entries().single { it.id == duplicate }.origin)
        assertEquals(saved, crosshairs.change(YoursChange.Unsave("crosshair_1")))
        assertEquals(listOf(edited, duplicate), entries().map { it.id }.filter { it == edited || it == saved || it == duplicate }, "the edit and the duplicate stay")
    }

    @Test
    fun usingACommunityCrosshairWhoseSavedEntryWasEditedAddsItAgainAndUnsavingLeavesTheEdit() = runTest {
        val world = World(saved = Crosshair.GameDefault)
        val crosshairs = with(world) { start() }
        val green = mine.with(gap = 4.0)
        val edited = assertNotNull(crosshairs.change(YoursChange.Save(community)))
        crosshairs.change(YoursChange.Settings(edited, green))

        crosshairs.use(CrosshairPick.Community(community))
        after(600)
        val used = assertNotNull(world.state().inUse)
        assertEquals(mine, used.crosshair, "Use in game uses the community crosshair as it is, not the edit")
        assertEquals(listOf(edited, used.id), world.state().yours.entries.filter { (it.origin as? CrosshairOrigin.Community)?.id == "crosshair_1" }.map { it.id })

        crosshairs.use(CrosshairPick.Yours(classic))
        after(600)
        assertEquals(used.id, crosshairs.change(YoursChange.Unsave("crosshair_1")))
        assertEquals(listOf(edited), world.state().yours.entries.filter { (it.origin as? CrosshairOrigin.Community)?.id == "crosshair_1" }.map { it.id }, "the edited entry stays")
    }

    @Test
    fun anImportsCrosshairJoinsYoursNotInUseAndOnlyOnce() = runTest {
        val world = World(saved = Crosshair.GameDefault)
        val crosshairs = with(world) { start() }
        val origin = CrosshairOrigin.FromManager(Manager.DeadlockModManager, mine)
        val id = assertNotNull(crosshairs.change(YoursChange.Adopt(origin)))
        assertEquals("Imported crosshair" to origin, world.state().yours.entries.single { it.id == id }.let { it.name to it.origin })
        assertEquals(Crosshair.GameDefault, world.state().inUse?.crosshair, "the game's crosshair stays in use")
        assertNull(crosshairs.change(YoursChange.Adopt(origin)), "importing again adds nothing")
        after(600)
        assertEquals(0, world.settings.writes)
    }

    @Test
    fun beforeExitTheCrosshairAndYoursAreSavedWithoutWaitingForTheTimer() = runTest {
        val world = World()
        val crosshairs = with(world) { start() }
        after(2_100)
        crosshairs.use(CrosshairPick.Yours(classic))
        crosshairs.change(YoursChange.Rename(classic, "Old school"))
        crosshairs.saveBeforeExit().join()
        assertEquals(CrosshairPreset.Classic.crosshair, world.saved(), "the game's settings hold it before the half second is up")
        assertEquals("Old school", world.kept()?.crosshairs?.single { it.id == classic }?.name, "and crosshairs.json the rename")
    }

    @Test
    fun beforeExitYoursIsSavedFirstSoAWriteOfTheGamesSettingsThatTakesLongCannotCostIt() = runTest {
        val world = World()
        val crosshairs = with(world) { start() }
        after(2_100)
        val slow = CompletableDeferred<Unit>()
        world.settings.held = slow
        crosshairs.use(CrosshairPick.Yours(classic))
        crosshairs.change(YoursChange.Rename(classic, "Old school"))
        val saving = crosshairs.saveBeforeExit()
        runCurrent()
        val kept = assertNotNull(world.kept(), "crosshairs.json is saved while the game's settings are still being written")
        assertEquals("Old school", kept.crosshairs.single { it.id == classic }.name)
        assertEquals(CrosshairPreset.Classic.crosshair, kept.waiting, "with the crosshair still waiting, so quitting now loses neither")
        slow.complete(Unit)
        saving.join()
        assertEquals(CrosshairPreset.Classic.crosshair, world.saved())
        assertNull(world.kept()?.waiting, "once it is written, crosshairs.json has it wait no more")
    }

    @Test
    fun aCrosshairsJsonThatCannotBeReadIsNeverReplacedAndYoursChangesNothingThisSession() = runTest {
        val unparsable = World(data = MemoryStorage(mapOf(KeptCodec.FileName to "{ not json")))
        val unreadable = World(storage = { data -> object : CacheStorage by data { override suspend fun read(name: String): String? = throw IOException("permission denied") } })
        for (world in listOf(unparsable, unreadable)) {
            val crosshairs = with(world) { start() }
            after(1_000)
            val yours = world.state().yours
            assertEquals(listOf("Mine"), yours.entries.map { it.name }, "no presets take its place, and what the game uses still shows")
            assertNull(crosshairs.change(YoursChange.Create))
            crosshairs.use(CrosshairPick.Yours(classic))
            after(1_000)
            assertEquals(yours, world.state().yours)
            assertEquals(if (world === unparsable) "{ not json" else null, world.data.read(KeptCodec.FileName), "the file is never written")
            assertEquals(0, world.settings.writes)
            assertEquals(1, world.logged.size, "the log says why")
        }
    }

    @Test
    fun aYoursThatCannotBeSavedIsLoggedAndSaysSoAndRetryWritesItAgain() = runTest {
        var full = false
        val world = World(storage = { data -> object : CacheStorage by data { override suspend fun write(name: String, text: String) = if (full) throw IOException("the disk is full") else data.write(name, text) } })
        val crosshairs = with(world) { start() }
        full = true
        crosshairs.change(YoursChange.Rename(classic, "Old school"))
        after(600)
        assertEquals(SavedNote.Failed.Yours, world.state().note)
        assertEquals(1, world.logged.count { KeptCodec.FileName in it }, "the log says why")

        full = false
        crosshairs.retry()
        runCurrent()
        assertEquals("Old school", world.kept()?.crosshairs?.single { it.id == classic }?.name, "Retry writes Yours again")
        assertEquals(SavedNote.NextStart, world.state().note)
    }
}
