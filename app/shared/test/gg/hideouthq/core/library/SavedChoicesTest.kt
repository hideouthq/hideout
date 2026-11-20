package gg.hideouthq.core.library

import gg.hideouthq.core.data.DownloadProblem
import gg.hideouthq.core.data.DownloadResult
import gg.hideouthq.core.data.FileListing
import gg.hideouthq.core.data.GameConfig
import gg.hideouthq.core.data.GameEntry
import gg.hideouthq.core.data.LibraryStore
import gg.hideouthq.core.data.ManifestLoad
import gg.hideouthq.core.data.ManifestSave
import gg.hideouthq.core.data.ModInstaller
import gg.hideouthq.core.data.Preferences
import gg.hideouthq.core.data.ProcessScan
import gg.hideouthq.core.data.PublishResult
import gg.hideouthq.core.data.RemoteFile
import gg.hideouthq.core.data.Removal
import gg.hideouthq.core.data.ReplaceResult
import gg.hideouthq.core.data.StageResult
import gg.hideouthq.core.data.LinkResult
import gg.hideouthq.core.data.fake.FakeDownloader
import gg.hideouthq.core.data.fake.FakeGameBanana
import gg.hideouthq.core.data.fake.FakeProcesses
import gg.hideouthq.core.data.fake.FakeVpk
import gg.hideouthq.core.data.fake.FaultPoint
import gg.hideouthq.core.data.fake.InMemoryGameFiles
import gg.hideouthq.core.data.fake.InMemoryLibraryStore
import gg.hideouthq.core.data.fake.MemoryStorage
import gg.hideouthq.core.data.fake.SimulatedCrash
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class SavedChoicesTest {
    private val root = GameRoot("/games/Deadlock")
    private val other = GameRoot("/other/Deadlock")
    private val main = ProfileId("main")
    private val addons = "game/citadel/addons"

    private class Spec(val id: String, val mod: Long, val number: Int?, val root: GameRoot, val suffixes: List<String> = listOf("dir"))

    private val defaults = listOf(
        Spec("a", 1, 3, root = root, suffixes = listOf("dir", "000")),
        Spec("b", 2, 5, root = root),
        Spec("c", 3, null, root = root),
        Spec("d", 4, 9, root = root),
        Spec("e", 5, 3, root = other),
        Spec("f", 6, null, root = other),
    )
    private val foreignPak01 = ByteArray(9) { 7 }

    private fun offPath(id: String): String = record(defaults.single { it.id == id }).offPath()

    private fun bytesOf(id: String, suffix: String) = "$id-$suffix".encodeToByteArray() + ByteArray(16 + id[0].code % 5) { (it * 3 + suffix.length).toByte() }

    private fun record(spec: Spec, number: Int? = spec.number) = oneSetRecord(
        installId = spec.id,
        gameRoot = spec.root,
        source = InstalledSource.Remote(SubmissionIdentity(SubmissionKind.Mod, spec.mod, spec.mod * 10, null)),
        title = "Mod ${spec.id}",
        variant = null,
        archive = ArchiveIdentity("/data/hideout/downloads/mod-${spec.mod}/${spec.id}.zip", 100, "a".repeat(64)),
        number = number,
        files = spec.suffixes.map { suffix -> SetFile(checkNotNull(VpkPart.parse(suffix)), Sha256.image(bytesOf(spec.id, suffix))) },
    )

    private inner class World(
        val specs: List<Spec> = defaults,
        foreign: Map<Pair<GameRoot, String>, ByteArray> = mapOf((root to "$addons/pak01_dir.vpk") to foreignPak01),
        private val roots: List<GameRoot> = listOf(root, other),
        running: Boolean = false,
    ) {
        val files = InMemoryGameFiles().apply {
            roots.forEach { putBytes(it, InMemoryGameFiles.GameInfo, HideoutGameInfo) }
            specs.forEach { spec -> record(spec).let { record -> record.files.forEach { putBytes(spec.root, it.relativePath(record), bytesOf(spec.id, it.part.label)) } } }
            foreign.forEach { (where, bytes) -> putBytes(where.first, where.second, bytes) }
        }
        val store = InMemoryLibraryStore(LibraryManifest(records = specs.map { record(it) }))
        val flaky = FlakyStore(store)
        var throwBefore: (LibraryManifest) -> Boolean = { false }
        val disk = object : LibraryStore {
            override suspend fun load(): ManifestLoad = flaky.load()
            override suspend fun save(manifest: LibraryManifest): ManifestSave {
                if (throwBefore(manifest)) throw IllegalStateException("the disk went away")
                return flaky.save(manifest)
            }
        }
        val processes = FakeProcesses().apply { if (running) scan = ProcessScan.Seen(game = true) }
        val banana = FakeGameBanana()
        val downloader = FakeDownloader(files)
        private var nextId = 0

        val attemptedWrites = mutableListOf<String>()
        private val GameEntry.where: String get() = "${folder.path}/$name"
        val installer = object : ModInstaller by files {
            override suspend fun stage(root: GameRoot, folder: SetFolder, vpk: StagedVpk, temporary: String): StageResult {
                attemptedWrites += "stage ${folder.path}/$temporary"
                return files.stage(root, folder, vpk, temporary)
            }
            override suspend fun publish(root: GameRoot, folder: SetFolder, temporary: String, name: String): PublishResult {
                attemptedWrites += "publish ${folder.path}/$name"
                return files.publish(root, folder, temporary, name)
            }
            override suspend fun link(existing: GameEntry, created: GameEntry): LinkResult {
                attemptedWrites += "link ${created.where}"
                return files.link(existing, created)
            }
            override suspend fun removeTwin(entry: GameEntry, witness: GameEntry, expected: FileImage): Removal {
                attemptedWrites += "remove ${entry.where}"
                return files.removeTwin(entry, witness, expected)
            }
            override suspend fun removeExact(entry: GameEntry, expected: FileImage): Removal {
                attemptedWrites += "remove ${entry.where}"
                return files.removeExact(entry, expected)
            }
        }
        val config = object : GameConfig by files {
            override suspend fun replace(edit: PendingChange.ConfigEdit, next: ByteArray): ReplaceResult {
                attemptedWrites += "gameinfo ${edit.gameRoot.path}"
                return files.replace(edit, next)
            }
        }

        val identities: Map<Pair<String, String>, Int?> = specs.flatMap { spec ->
            record(spec).let { record -> record.files.map { (spec.id to it.part.label) to files.identity(spec.root, it.relativePath(record)) } }
        }.toMap()

        private var session: CoroutineScope? = null

        fun TestScope.library(scope: CoroutineScope? = null): ModLibrary {
            session?.cancel()
            val owned = scope ?: CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext[Job]))
            session = owned
            return ModLibrary(
                FixedGameLocator(roots), Preferences(MemoryStorage()), disk, files,
                InstallPorts(banana, downloader, installer, config, processes), owned,
                ids = { "id${++nextId}" },
                watch = pollingWatchTiming,
            ).also {
                it.start()
                runCurrent()
            }
        }

        fun manifest(): LibraryManifest = assertNotNull(store.manifest)

        fun saved(): SavedChoices? = manifest().savedChoices

        fun journals(): List<String> = store.saved.mapNotNull { it.pending?.token() }.distinct()

        fun placement(): Map<String, Int?> = manifest().records.associate { it.installId to it.numbers.firstOrNull()?.value }

        fun assertFilesAt(expected: Map<String, Int?>) {
            for (spec in specs) {
                val record = record(spec, expected.getValue(spec.id))
                for (file in record.files) {
                    val path = file.relativePath(record)
                    assertEquals(identities[spec.id to file.part.label], files.identity(spec.root, path), "${spec.id} ${file.part.label} at $path")
                }
            }
            assertEquals(Sha256.image(foreignPak01), files.addonsFiles(root)["pak01_dir.vpk"], "the foreign pak01 stays")
        }

        fun offer(id: Long, content: ByteArray = FakeVpk.directory(seed = id.toInt())) {
            val ref = SubmissionRef(SubmissionKind.Mod, id)
            val remote = RemoteFile(id * 10, "skin-$id.zip", 100, null, null, 1, 1, "https://gamebanana.com/dl/${id * 10}")
            banana.listings = banana.listings + (ref to FileListing.Available("Skin $id", listOf(remote)))
            downloader.serve(id * 10, listOf("skin.vpk" to content))
        }
    }

    private fun PendingChange.token(): String = when (this) {
        is PendingChange.Arrange -> token.value
        is PendingChange.Uninstall -> token.value
        is PendingChange.Replace -> token.value
        is PendingChange.Relocate -> token.value
        is PendingChange.ArchiveRemoval -> token.value
        is PendingChange.ConfigEdit -> token
        is PendingChange.Install -> "install-${record.installId}"
        is PendingChange.Adopt -> "adopt-${plan.migration?.value}"
        is PendingChange.Revert -> token.value
        is PendingChange.MoveOut -> "move-out-${folder.value}"
    }

    private val start = mapOf("a" to 3, "b" to 5, "c" to null, "d" to 9, "e" to 3, "f" to null)

    private fun <T> MutationResult<T>.done(): T = assertIs<MutationResult.Done<T>>(this).value

    private fun ModLibrary.drop(vararg order: String) = reorder(assertNotNull(state.value.orderView), order.toList())

    private fun TestScope.poll(times: Int = 1) = repeat(times) {
        advanceTimeBy(1.seconds)
        runCurrent()
    }

    private fun TestScope.seen(world: World, scan: ProcessScan) {
        world.processes.scan = scan
        poll()
    }

    @Test
    fun everyChoiceSavesWhileDeadlockRunsTouchesNoFileSurvivesARestartAndAppliesOnceItCloses() = runTest {
        val world = World(running = true)
        val library = with(world) { library() }
        assertEquals(GameActivity.Running, library.state.value.deadlock)
        assertTrue(library.state.value.canEditChoices)
        assertFalse(library.state.value.canModify)
        val records = world.manifest().records

        val ranked = library.createProfile(NewProfile("Ranked", ProfileColour.Blue, ProfileSeed.Empty, activate = false)).done()
        library.renameProfile(ranked, "Competitive").done()
        library.recolourProfile(ranked, ProfileColour.Rose).done()
        val copy = library.duplicateProfile(ranked).done()
        library.setMembership(ranked, "a", true).done()
        library.setMembership(ranked, "d", true).done()
        assertEquals(SwitchSummary(ranked, off = 2, on = 0), library.switchProfile(ranked).done())
        library.deleteProfile(copy).done()
        library.drop("d", "a", "c", "b")
        runCurrent()

        val saved = assertNotNull(world.saved()).target
        assertEquals(library.state.value.profiles, saved)
        assertEquals(library.state.value.profiles, library.state.value.shownProfiles)
        assertEquals(ranked, saved.active)
        assertEquals(listOf("Main", "Competitive"), saved.profiles.map { it.name })
        assertEquals(ProfileColour.Rose, saved.activeProfile.colour)
        assertEquals(setOf("a", "d"), saved.activeProfile.enabled)
        assertEquals(mapOf(root to listOf("d", "a", "c", "b"), other to listOf("e", "f")), saved.activeProfile.orders)
        assertEquals(mapOf(root to listOf("a", "b", "d", "c"), other to listOf("e", "f")), saved.profiles.first().orders, "the drop was Competitive's alone")
        assertEquals(listOf("d", "a", "c", "b"), library.state.value.orderView?.sets)
        assertEquals(ChoiceSync.Waiting(GameActivity.Running), library.state.value.choiceSync)
        assertFalse(library.state.value.applying)
        assertFalse(library.state.value.saving)
        assertEquals(emptyList(), world.attemptedWrites, "nothing that could change a game file was called")
        assertEquals(emptyList(), world.journals(), "no physical change was journaled")
        assertEquals(records, world.manifest().records)
        assertEquals(main, world.manifest().profiles.active, "the applied book still says what the files are")
        assertEquals(setOf("a", "b", "d", "e"), world.manifest().profiles.activeProfile.enabled)
        world.assertFilesAt(start)

        val restarted = with(world) { library() }
        assertEquals(saved, restarted.state.value.profiles)
        assertEquals(listOf("d", "a", "c", "b"), restarted.state.value.orderView?.sets)
        assertEquals(ChoiceSync.Waiting(GameActivity.Running), restarted.state.value.choiceSync)
        poll(5)
        assertEquals(emptyList(), world.attemptedWrites)

        seen(world, ProcessScan.Seen())
        assertEquals(ChoiceSync.Settled, restarted.state.value.choiceSync)
        assertNull(world.saved(), "reached choices are no longer pending")
        assertEquals(saved, world.manifest().profiles)
        val reached = mapOf("a" to 3, "b" to null, "c" to null, "d" to 2, "e" to null, "f" to null)
        assertEquals(reached, world.placement())
        world.assertFilesAt(reached)
        assertNull(world.manifest().pending)
        assertEquals(listOf("d", "a", "c", "b"), restarted.state.value.orderView?.sets)
        assertTrue(restarted.state.value.canModify)
    }

    @Test
    fun unknownOrUnreadableProcessStateSavesButNeverWritesUntilDeadlockIsKnownClosed() = runTest {
        for (unknown in listOf("scan", "throw")) {
            val world = World()
            if (unknown == "scan") world.processes.scan = ProcessScan.Unknown("no process access") else world.processes.failure = "permission denied"
            val library = with(world) { library() }
            assertEquals(GameActivity.Unknown, library.state.value.deadlock, unknown)
            assertFalse(library.state.value.canModify, unknown)
            library.setMembership(main, "c", true).done()
            library.drop("b", "a", "c", "d")
            library.setEnabled("a", false)
            runCurrent()
            poll(3)
            assertEquals(ChoiceSync.Waiting(GameActivity.Unknown), library.state.value.choiceSync, unknown)
            assertEquals(setOf("b", "c", "d", "e"), world.saved()?.target?.activeProfile?.enabled, unknown)
            assertEquals(emptyList(), world.attemptedWrites, unknown)
            assertEquals(emptyList(), world.journals(), unknown)

            world.processes.failure = null
            seen(world, ProcessScan.Seen())
            assertEquals(ChoiceSync.Settled, library.state.value.choiceSync, unknown)
            val reached = start + mapOf("a" to null, "c" to 6)
            assertEquals(reached, world.placement(), unknown)
            world.assertFilesAt(reached)
        }
    }

    @Test
    fun startingWithDeadlockAlreadyClosedAppliesWhatWasSavedBefore() = runTest {
        val world = World(running = true)
        with(world) { library() }.let { first ->
            first.setMembership(main, "c", true).done()
            first.drop("d", "c", "b", "a")
            runCurrent()
        }
        assertEquals(emptyList(), world.attemptedWrites)
        world.processes.scan = ProcessScan.Seen()
        val library = with(world) { library() }
        assertEquals(ChoiceSync.Settled, library.state.value.choiceSync)
        assertNull(world.saved())
        val reached = start + mapOf("d" to 2, "c" to 3, "a" to 6)
        assertEquals(reached, world.placement())
        world.assertFilesAt(reached)
    }

    @Test
    fun editsThatTurnNothingOnOrOffAreAppliedAtOnceEvenWhileDeadlockRuns() = runTest {
        val world = World(running = true)
        val library = with(world) { library() }
        val copy = library.createProfile(NewProfile("Copy", ProfileColour.Blue, ProfileSeed.Copy(main), activate = false)).done()
        library.renameProfile(main, "First").done()
        library.recolourProfile(copy, ProfileColour.Rose).done()
        library.setMembership(copy, "c", true).done()
        val empty = library.createProfile(NewProfile("Empty", ProfileColour.Clay, ProfileSeed.Empty, activate = false)).done()
        library.deleteProfile(empty).done()
        runCurrent()
        assertNull(world.saved(), "nothing waits for the files")
        assertEquals(ChoiceSync.Settled, library.state.value.choiceSync)
        assertFalse(library.state.value.applying)
        assertEquals(listOf("First", "Copy"), world.manifest().profiles.profiles.map { it.name })
        assertEquals(setOf("a", "b", "c", "d", "e"), world.manifest().profiles.profiles.last().enabled)
        assertEquals(emptyList(), world.attemptedWrites)
        assertEquals(emptyList(), world.journals())

        library.setMembership(main, "c", true).done()
        runCurrent()
        assertEquals(ChoiceSync.Waiting(GameActivity.Running), library.state.value.choiceSync, "turning a set on in the active profile waits")
        assertEquals(setOf("a", "b", "d", "e"), world.manifest().profiles.activeProfile.enabled)
        assertEquals(emptyList(), world.attemptedWrites)
    }

    @Test
    fun aDragOfOnlyTheModsThatAreOffIsAppliedAtOnceEvenWhileDeadlockRuns() = runTest {
        val world = World(running = true)
        val library = with(world) { library() }
        library.drop("c", "a", "b", "d")
        runCurrent()
        assertNull(world.saved(), "nothing waits for the files")
        assertEquals(listOf("c", "a", "b", "d"), world.manifest().profiles.activeProfile.orders[root])
        assertEquals(ChoiceSync.Settled, library.state.value.choiceSync)
        assertEquals(emptyList(), world.attemptedWrites)
        assertEquals(emptyList(), world.journals())
        assertEquals(start, world.placement())
    }

    @Test
    fun aModTurnedOnAndOffAgainWhileDeadlockRunsSettlesWithNothingTouched() = runTest {
        val world = World(running = true)
        val library = with(world) { library() }
        library.setEnabled("c", true)
        runCurrent()
        assertEquals(ChoiceSync.Waiting(GameActivity.Running), library.state.value.choiceSync)
        library.setEnabled("c", false)
        runCurrent()
        assertEquals(ChoiceSync.Settled, library.state.value.choiceSync)
        assertNull(world.saved())
        assertEquals(emptyList(), world.attemptedWrites)
        assertEquals(emptyList(), world.journals())
        world.assertFilesAt(start)
    }

    @Test
    fun deletingTheActiveProfileWhileDeadlockRunsLeavesItInTheAppliedBookUntilTheSwitchIsReached() = runTest {
        val world = World()
        val library = with(world) { library() }
        val ranked = library.createProfile(NewProfile("Ranked", ProfileColour.Blue, ProfileSeed.Copy(main), activate = false)).done()
        library.setMembership(ranked, "b", false).done()
        library.switchProfile(ranked).done()
        runCurrent()
        assertEquals(ranked, world.manifest().profiles.active)
        seen(world, ProcessScan.Seen(game = true))
        library.deleteProfile(ranked).done()
        runCurrent()
        assertEquals(listOf("Main", "Ranked"), world.manifest().profiles.profiles.map { it.name }, "the applied book still says what the files are")
        assertEquals(listOf("Main"), world.saved()?.target?.profiles?.map { it.name })

        seen(world, ProcessScan.Seen())
        assertNull(world.saved())
        assertEquals(listOf("Main"), world.manifest().profiles.profiles.map { it.name })
        assertEquals(main, world.manifest().profiles.active)
        assertEquals(start + ("b" to 4), world.placement(), "b is on again, at the lowest number after a")
        world.assertFilesAt(start + ("b" to 4))
    }

    @Test
    fun modsTurnedOffTogetherAreOneSaveOneChangeOfFilesAndOneUndo() = runTest {
        val world = World(running = true)
        val library = with(world) { library() }
        val saves = world.store.saved.size
        library.setEnabledTogether(setOf("a", "b", "c"), false)
        runCurrent()
        assertEquals(saves + 1, world.store.saved.size, "one save turns all three")
        assertEquals(setOf("d", "e"), world.saved()!!.target.activeProfile.enabled)
        assertEquals(start, world.placement(), "no file moves while Deadlock runs")
        val token = assertNotNull(library.state.value.undo)
        assertEquals(Undoable.Turned(false, 2, profile = null), token.undoes, "c was already off")

        seen(world, ProcessScan.Seen())
        assertEquals(1, world.journals().size, "one change of files")
        assertEquals(start + ("a" to null) + ("b" to null), world.placement())

        library.undo(token).done()
        seen(world, ProcessScan.Seen())
        val undone = start + mapOf("a" to 2, "b" to 3)
        assertEquals(undone, world.placement(), "both come back on, before d, at the lowest numbers")

        library.setEnabledTogether(setOf("missing"), false)
        runCurrent()
        assertEquals(LibraryProblem.NotInstalled, library.state.value.choiceProblem)
        assertEquals(undone, world.placement())
        library.setEnabledTogether(setOf("b", "missing"), false)
        seen(world, ProcessScan.Seen())
        assertEquals(undone + ("b" to null), world.placement(), "a mod no longer installed is left out, not a reason to refuse the rest")
    }

    @Test
    fun savingWhatIsAlreadySavedAddsNoSaveAndNoWork() = runTest {
        val world = World(running = true)
        val library = with(world) { library() }
        val saves = world.store.saved.size
        assertEquals(SwitchSummary(main, 0, 0), library.switchProfile(main).done())
        library.setMembership(main, "a", true).done()
        library.drop("a", "b", "d", "c")
        runCurrent()
        assertEquals(saves, world.store.saved.size, "nothing new was saved")
        library.setMembership(main, "c", true).done()
        library.setMembership(main, "c", true).done()
        assertEquals(saves + 1, world.store.saved.size)
        seen(world, ProcessScan.Seen())
        assertEquals(1, world.journals().size)
        assertEquals(start + ("c" to 10), world.placement(), "c loads after d")
    }

    @Test
    fun newerSavedChoicesSurviveAnOlderArrangementFinishingOrRollingBack() = runTest {
        for (step in listOf(MoveStep.Releasing, MoveStep.Linking, MoveStep.Cleanup, MoveStep.RollingBack)) {
            val world = World()
            val first = with(world) { crashableScope() }
            if (step == MoveStep.RollingBack) world.files.failing += FaultPoint.Link(offPath("d"))
            world.store.afterStore = { if ((it.pending as? PendingChange.Arrange)?.step == step) throw SimulatedCrash(FaultPoint.ReplaceConfig) }
            val library = with(world) { library(first) }
            val ranked = library.createProfile(NewProfile("Ranked", ProfileColour.Blue, ProfileSeed.Copy(main), activate = false)).done()
            library.setMembership(ranked, "d", false).done()
            val before = world.manifest().profiles
            library.switchProfile(ranked).done()
            runCurrent()
            first.cancel()
            world.store.afterStore = {}
            world.files.failing.clear()
            val journal = assertIs<PendingChange.Arrange>(world.manifest().pending, step.name)
            assertEquals(step, journal.step)
            val orders = mapOf(root to listOf("a", "b", "c", "d"), other to listOf("e", "f"))
            val newer = ProfileBook(
                ranked,
                listOf(
                    Profile(main, "First", ProfileColour.Sage, setOf("a", "b", "d", "e"), orders),
                    Profile(ranked, "Second", ProfileColour.Clay, setOf("a", "c", "e"), orders),
                ),
            )
            world.store.save(world.manifest().copy(savedChoices = SavedChoices(newer)))
            val saves = world.store.saved.size

            val restarted = with(world) { library() }
            val afterJournal = world.store.saved.drop(saves).first { it.pending == null }
            assertEquals(newer, afterJournal.savedChoices?.target, "$step: the journal's end keeps the newer choices")
            assertEquals(if (step == MoveStep.RollingBack) before else journal.after, afterJournal.profiles, "$step: the journal ends where it was going")
            assertEquals(ChoiceSync.Settled, restarted.state.value.choiceSync, step.name)
            assertNull(world.saved(), step.name)
            assertEquals(newer, world.manifest().profiles, step.name)
            val reached = start + mapOf("b" to null, "c" to 4, "d" to null)
            assertEquals(reached, world.placement(), step.name)
            world.assertFilesAt(reached)
        }
    }

    @Test
    fun aJournalLeftUnresolvedRefusesNewChoicesAndKeepsTheSavedOnesExactly() = runTest {
        val world = World()
        val target = offPath("b")
        world.files.around = { point ->
            if (point == FaultPoint.LinkMade(target)) {
                world.files.around = null
                world.files.failing += FaultPoint.Look(target)
            }
        }
        val library = with(world) { library() }
        val ranked = library.createProfile(NewProfile("Ranked", ProfileColour.Blue, ProfileSeed.Copy(main), activate = false)).done()
        library.setMembership(ranked, "b", false).done()
        library.switchProfile(ranked).done()
        runCurrent()

        val journal = assertIs<PendingChange.Arrange>(world.manifest().pending)
        assertEquals(MoveStep.Linking, journal.step)
        assertNotNull(world.saved())
        assertIs<ChoiceSync.Failed>(library.state.value.choiceSync)
        assertFalse(library.state.value.canEditChoices)
        val manifest = world.manifest()
        val saves = world.flaky.asked.size

        assertIs<LibraryProblem.Interrupted>(assertIs<MutationResult.Refused>(library.setMembership(ranked, "c", true)).problem)
        assertIs<MutationResult.Refused>(library.renameProfile(ranked, "Later"))
        library.setEnabled("a", false)
        library.drop("d", "c", "b", "a")
        runCurrent()
        assertEquals(manifest, world.manifest(), "the journal and the saved choices are exactly as they were")
        assertEquals(saves, world.flaky.asked.size, "nothing was even asked to be saved")
        assertEquals(world.identities["b" to "dir"], world.files.identity(root, target), "the linked target stays")
    }

    @Test
    fun aSaveThatLandsBeforeItsAcknowledgementFailsIsDoneAndKept() = runTest {
        val world = World(running = true)
        var thrown = false
        world.store.afterStore = { if (!thrown && it.savedChoices != null) { thrown = true; throw IllegalStateException("acknowledgement lost") } }
        val library = with(world) { library() }
        library.setMembership(main, "c", true).done()
        assertTrue(thrown)
        assertTrue(library.state.value.canEditChoices)
        val restarted = with(world) { library() }
        assertEquals(setOf("a", "b", "c", "d", "e"), restarted.state.value.profiles?.activeProfile?.enabled)
    }

    @Test
    fun aSaveThatFailsBeforeLandingIsRefusedAndMovesNothing() = runTest {
        val world = World()
        world.throwBefore = { it.savedChoices != null }
        val library = with(world) { library() }
        val refused = assertIs<MutationResult.Refused>(library.setMembership(main, "c", true))
        assertEquals(LibraryProblem.ManifestSave("the disk went away"), refused.problem)
        library.drop("d", "c", "b", "a")
        runCurrent()
        assertEquals(LibraryProblem.ManifestSave("the disk went away"), library.state.value.choiceProblem)
        assertEquals(listOf("a", "b", "d", "c"), library.state.value.orderView?.sets, "the refused drop shows the saved order again")
        assertEquals(setOf("a", "b", "d", "e"), library.state.value.profiles?.activeProfile?.enabled)
        assertEquals(setOf("a", "b", "d", "e"), library.state.value.shownProfiles?.activeProfile?.enabled)
        poll(3)
        assertEquals(emptyList(), world.attemptedWrites)
        assertEquals(ChoiceSync.Settled, library.state.value.choiceSync)
        assertTrue(library.state.value.canEditChoices)
        library.dismissChoiceProblem()
        assertNull(library.state.value.choiceProblem)
    }

    @Test
    fun anUnconfirmableSaveStopsEverySaveUntilTheListIsReadAgain() = runTest {
        val world = World(running = true)
        val library = with(world) { library() }
        world.throwBefore = { it.savedChoices != null }
        world.flaky.unreadable = "library.json is locked"
        val refused = assertIs<MutationResult.Refused>(library.setMembership(main, "c", true))
        assertIs<LibraryProblem.Interrupted>(refused.problem)
        runCurrent()
        assertFalse(library.state.value.canEditChoices)
        assertEquals(setOf("a", "b", "d", "e"), library.state.value.profiles?.activeProfile?.enabled, "the last confirmed book stays shown as saved")
        val asked = world.flaky.asked.size
        world.throwBefore = { false }
        assertIs<MutationResult.Refused>(library.setMembership(main, "c", true))
        library.setEnabled("a", false)
        runCurrent()
        poll(3)
        assertEquals(asked, world.flaky.asked.size, "no save is tried while the list cannot be confirmed")

        world.flaky.unreadable = null
        library.retryChoices()
        runCurrent()
        assertTrue(library.state.value.canEditChoices)
        library.setMembership(main, "c", true).done()
        assertEquals(setOf("a", "b", "c", "d", "e"), world.saved()?.target?.activeProfile?.enabled)
        assertEquals(emptyList(), world.attemptedWrites)
    }

    @Test
    fun aFailedClearOfReachedChoicesNeverMovesFilesAgain() = runTest {
        val world = World()
        world.store.refuse = { "disk full".takeIf { _ -> it.savedChoices == null && it.pending == null } }
        val library = with(world) { library() }
        library.drop("d", "b", "c", "a")
        runCurrent()
        val reached = start + mapOf("a" to 6, "d" to 2)
        assertEquals(reached, world.placement())
        assertNotNull(world.saved(), "the clear was refused, so the reached choices stay saved")
        assertEquals(ChoiceSync.Settled, library.state.value.choiceSync)
        assertTrue(library.state.value.canModify)
        val writes = world.attemptedWrites.size

        world.store.refuse = { null }
        val restarted = with(world) { library() }
        assertNull(world.saved())
        assertEquals(writes, world.attemptedWrites.size, "only the manifest changed")
        assertEquals(listOf("d", "b", "c", "a"), restarted.state.value.orderView?.sets)
    }

    private fun World.failTurningBOff() {
        files.failing += FaultPoint.Link(offPath("b"))
    }

    private fun TestScope.cycles(world: World, times: Int) = repeat(times) {
        seen(world, ProcessScan.Seen(game = true))
        seen(world, ProcessScan.Seen())
    }

    @Test
    fun aFailureStaysShownAndIsTriedAgainWhenDeadlockClosesAndAtStartButNotOnPolls() = runTest {
        val world = World()
        world.failTurningBOff()
        val library = with(world) { library() }
        library.setMembership(main, "b", false).done()
        runCurrent()
        val failed = assertIs<ChoiceSync.Failed>(library.state.value.choiceSync)
        assertIs<LibraryProblem.PlacementFailed>(failed.problem)
        assertEquals(1, world.journals().size)
        assertTrue(library.state.value.canPlay, "a failed apply never holds Play")
        assertTrue(library.state.value.canEditChoices)
        world.assertFilesAt(start)

        poll(10)
        assertEquals(1, world.journals().size, "polls alone try nothing")
        cycles(world, 1)
        assertEquals(2, world.journals().size, "Deadlock closing tries again")
        assertEquals(failed, library.state.value.choiceSync, "and the failure stays shown")
        val restarted = with(world) { library() }
        runCurrent()
        assertEquals(3, world.journals().size, "so does a start")
        assertIs<ChoiceSync.Failed>(restarted.state.value.choiceSync)

        world.files.failing.clear()
        cycles(world, 1)
        assertEquals(ChoiceSync.Settled, restarted.state.value.choiceSync)
        assertEquals(start + ("b" to null), world.placement())
    }

    @Test
    fun everyNewChoiceTriesAFailedApplyAgain() = runTest {
        val world = World()
        world.failTurningBOff()
        val library = with(world) { library() }
        library.setMembership(main, "b", false).done()
        runCurrent()
        assertEquals(1, world.journals().size)
        library.setMembership(main, "c", true).done()
        runCurrent()
        assertEquals(2, world.journals().size)
        assertIs<ChoiceSync.Failed>(library.state.value.choiceSync)

        world.files.failing.clear()
        library.drop("b", "a", "c", "d")
        runCurrent()
        assertEquals(ChoiceSync.Settled, library.state.value.choiceSync)
        assertNull(world.placement()["b"])
        assertNotNull(world.placement()["c"])
    }

    @Test
    fun foreignPak03AndPak04AreNeverOverwrittenAdoptedOrRemoved() = runTest {
        val p = Spec("p", 7, null, root = root)
        val q = Spec("q", 8, 5, root = root)
        val pak03 = ByteArray(11) { 3 }
        val world = World(
            specs = listOf(p, q),
            foreign = mapOf((root to "$addons/pak01_dir.vpk") to foreignPak01, (root to "$addons/pak03_dir.vpk") to pak03, (root to "$addons/pak04_dir.vpk") to bytesOf("p", "dir")),
        )
        val pak03Identity = world.files.identity(root, "$addons/pak03_dir.vpk")
        val pak04Identity = world.files.identity(root, "$addons/pak04_dir.vpk")
        world.processes.scan = ProcessScan.Seen(game = true)
        val library = with(world) { library() }
        library.setMembership(main, "p", true).done()
        runCurrent()

        seen(world, ProcessScan.Seen())
        assertEquals(ChoiceSync.Settled, library.state.value.choiceSync, "a foreign number is planned around, never a reason to pause")
        assertEquals(mapOf("p" to 6, "q" to 5), world.placement(), "after q, clear of the foreign 03 and 04, and the same bytes at 04 prove nothing")
        library.drop("p", "q")
        runCurrent()
        assertEquals(mapOf("p" to 2, "q" to 5), world.placement(), "before q, below the foreign 03 and 04")
        assertEquals(listOf("p", "q"), onLoadOrder(world.manifest().records.filter { it.gameRoot == root }))
        assertEquals(pak03Identity, world.files.identity(root, "$addons/pak03_dir.vpk"))
        assertEquals(pak04Identity, world.files.identity(root, "$addons/pak04_dir.vpk"))
    }

    @Test
    fun deadlockStartingMidwayKeepsTheJournalWaitsWithoutFailingAndFinishesOnceClosed() = runTest {
        val world = World()
        var started = false
        world.files.around = { point -> if (!started && point is FaultPoint.LinkMade) { started = true; world.processes.scan = ProcessScan.Seen(game = true) } }
        val library = with(world) { library() }
        library.setMembership(main, "b", false).done()
        runCurrent()
        assertTrue(started)
        assertNotNull(world.manifest().pending)
        assertEquals(ChoiceSync.Waiting(GameActivity.Running), library.state.value.choiceSync, "a fence is not a failure")
        world.files.around = null
        val writes = world.attemptedWrites.size
        poll(5)
        with(world) { library() }
        poll(2)
        assertEquals(writes, world.attemptedWrites.size, "nothing changes while Deadlock runs, after a restart either")

        seen(world, ProcessScan.Seen())
        assertNull(world.manifest().pending)
        assertNull(world.saved())
        assertEquals(start + ("b" to null), world.placement())
        world.assertFilesAt(start + ("b" to null))
    }

    @Test
    fun eachProfileKeepsItsOwnOrderInEveryGameAndOneJournalSwitchesToItAndBack() = runTest {
        val world = World(running = true)
        val library = with(world) { library() }
        val copy = library.createProfile(NewProfile("Copy", ProfileColour.Blue, ProfileSeed.Copy(main), activate = false)).done()
        library.setMembership(copy, "f", true).done()
        library.drop("c", "d", "a", "b")
        assertEquals(FolderCheck.Valid(other), library.useGameFolder(other.path))
        library.drop("f", "e")
        runCurrent()
        val saved = assertNotNull(world.saved()).target
        assertEquals(mapOf(root to listOf("c", "d", "a", "b"), other to listOf("f", "e")), saved.activeProfile.orders, "the drops are Main's")
        assertEquals(mapOf(root to listOf("a", "b", "d", "c"), other to listOf("e", "f")), saved.profiles.single { it.id == copy }.orders, "Copy keeps the order it was made with")

        seen(world, ProcessScan.Seen())
        val dragged = mapOf("c" to null, "d" to 2, "a" to 3, "b" to 5, "f" to null, "e" to 3)
        assertEquals(dragged, world.placement())
        world.assertFilesAt(dragged)
        val journals = world.journals().size
        library.switchProfile(copy).done()
        runCurrent()
        val copied = start + mapOf("d" to 6, "f" to 4)
        assertEquals(copied, world.placement(), "Copy's order in both games, with f turned on")
        world.assertFilesAt(copied)
        assertEquals(journals + 1, world.journals().size, "one journal turns and renumbers sets in both games")
        library.switchProfile(main).done()
        runCurrent()
        assertEquals(dragged, world.placement(), "switching back restores Main's order")
        world.assertFilesAt(dragged)
        assertEquals(journals + 2, world.journals().size)
        assertNull(world.saved())
    }

    @Test
    fun aDragKeepsItsViewThroughItsOwnSaveApplyAndProcessChangesAndMayDropAgain() = runTest {
        val world = World(running = true)
        val library = with(world) { library() }
        val before = assertNotNull(library.state.value.orderView)
        library.drop("d", "b", "c", "a")
        val gesture = assertNotNull(library.state.value.orderView)
        assertNotSame(before, gesture, "an accepted drop shows its own view at once")
        assertTrue(library.state.value.saving)
        runCurrent()
        assertSame(gesture, library.state.value.orderView, "confirming the save shows the same order")
        seen(world, ProcessScan.Unknown("blink"))
        seen(world, ProcessScan.Seen(game = true))
        assertSame(gesture, library.state.value.orderView, "process changes alone keep it")
        library.switchProfile(library.createProfile(NewProfile("Copy", ProfileColour.Blue, ProfileSeed.Copy(main))).done()).done()
        runCurrent()
        assertSame(gesture, library.state.value.orderView, "a switch to a profile with the same order keeps it")
        seen(world, ProcessScan.Seen())
        assertEquals(start + mapOf("d" to 2, "a" to 6), world.placement())
        assertSame(gesture, library.state.value.orderView, "applying it keeps it")
        library.reorder(gesture, listOf("c", "b", "d", "a"))
        runCurrent()
        assertEquals(start + mapOf("b" to 2, "d" to 3, "a" to 6), world.placement(), "two single files move rather than a's two")
    }

    @Test
    fun dropsAcceptedWhileAnotherChangeHoldsTheFilesSaveInOrderAndTheLatestApplies() = runTest {
        val world = World()
        val library = with(world) { library() }
        val gate = CompletableDeferred<Unit>()
        world.processes.gate = gate
        val reapplied = launch(start = CoroutineStart.UNDISPATCHED) { library.reapply() }
        runCurrent()
        library.drop("b", "a", "c", "d")
        val first = assertNotNull(library.state.value.orderView)
        library.drop("c", "b", "a", "d")
        library.reorder(first, listOf("d", "c", "b", "a"))
        runCurrent()
        assertEquals(LibraryProblem.LoadOrderChanged, library.state.value.choiceProblem, "a replaced view asks for nothing")
        assertEquals(listOf("c", "b", "a", "d"), library.state.value.orderView?.sets)
        assertTrue(library.state.value.saving)
        assertTrue(library.state.value.applying)
        assertNull(world.saved(), "nothing is saved while the files are held")

        gate.complete(Unit)
        world.processes.gate = null
        runCurrent()
        reapplied.join()
        assertEquals(listOf("c", "b", "a", "d"), library.state.value.orderView?.sets)
        assertEquals(start + mapOf("b" to 2), world.placement())
        assertEquals(1, world.journals().size, "only the latest saved order reached the files")
        assertFalse(library.state.value.saving)
    }

    @Test
    fun aViewIsStaleAfterAnotherGameAnInstallOrAnUninstall() = runTest {
        val world = World()
        world.offer(21)
        val library = with(world) { library() }
        val first = assertNotNull(library.state.value.orderView)
        library.useGameFolder(other.path)
        library.useGameFolder(root.path)
        library.reorder(first, listOf("d", "c", "b", "a"))
        runCurrent()
        assertEquals(LibraryProblem.LoadOrderChanged, library.state.value.choiceProblem, "the game changed and came back")
        library.dismissChoiceProblem()

        val shown = assertNotNull(library.state.value.orderView)
        library.install("mod-21")
        runCurrent()
        val installed = world.manifest().records.single { it.source.modId == "mod-21" }
        assertEquals(listOf(installed.installId, "a", "b", "d", "c"), library.state.value.orderView?.sets, "a new mod goes first")
        library.reorder(shown, listOf("d", "c", "b", "a"))
        runCurrent()
        assertEquals(LibraryProblem.LoadOrderChanged, library.state.value.choiceProblem)
        library.dismissChoiceProblem()

        val withNew = assertNotNull(library.state.value.orderView)
        library.uninstall(assertNotNull(library.uninstallConfirmation(installed.installId)), deleteDownload = false)
        runCurrent()
        library.reorder(withNew, withNew.sets.reversed())
        runCurrent()
        assertEquals(LibraryProblem.LoadOrderChanged, library.state.value.choiceProblem)
        assertNull(world.saved())
        assertEquals(start, world.placement())
    }

    @Test
    fun anInstallRecoveredIntoSavedChoicesJoinsRecordsProfilesAndOrderInOneSave() = runTest {
        val world = World()
        world.offer(21)
        val first = with(world) { crashableScope() }
        world.store.afterStore = { if ((it.pending as? PendingChange.Install)?.step == InstallStep.Releasing) throw SimulatedCrash(FaultPoint.ReplaceConfig) }
        with(world) { library(first) }.install("mod-21")
        runCurrent()
        first.cancel()
        world.store.afterStore = {}
        val pending = assertIs<PendingChange.Install>(world.manifest().pending)
        val id = pending.record.installId
        assertEquals(Placement.Off, pending.record.placement)
        val book = world.manifest().profiles.let { it.copy(profiles = it.profiles.map { profile -> profile.copy(name = "Fixture") }) }
        world.store.save(world.manifest().copy(savedChoices = SavedChoices(book.withOrder(root, listOf("d", "c", "b", "a")))))
        val saves = world.store.saved.size

        with(world) { library() }
        val committed = world.store.saved.drop(saves).first { manifest -> manifest.records.any { it.installId == id } }
        assertNull(committed.pending)
        assertFalse(id in committed.profiles.activeProfile.enabled, "its files are off until the worker turns it on")
        val target = assertNotNull(committed.savedChoices).target
        assertTrue(id in target.activeProfile.enabled, "the captured profile gets it in the saved book")
        assertEquals(listOf(id, "d", "c", "b", "a"), target.activeProfile.orders[root], "first")
        assertNull(world.saved())
        assertEquals(listOf(id, "d", "b", "a"), onLoadOrder(world.manifest().records.filter { it.gameRoot == root }))
        assertEquals("Fixture", world.manifest().profiles.activeProfile.name)
    }

    @Test
    fun savedOrdersForMoreGamesThanAnyStepBudgetAllApplyWithoutPausing() = runTest {
        val games = (0 until 40).map { GameRoot("/games/$it/Deadlock") }
        val specs = games.flatMapIndexed { i, game ->
            listOf(Spec("x$i", 100L + 2 * i, 3, root = game), Spec("y$i", 101L + 2 * i, 5, root = game))
        }
        val world = World(specs, foreign = emptyMap(), roots = games)
        val book = world.manifest().profiles
        world.store.save(world.manifest().copy(savedChoices = SavedChoices(games.foldIndexed(book) { i, saved, game -> saved.withOrder(game, listOf("y$i", "x$i")) })))

        val library = with(world) { library() }

        assertEquals(ChoiceSync.Settled, library.state.value.choiceSync)
        assertNull(world.saved(), "reached, not paused")
        val applied = world.manifest().records.groupBy { it.gameRoot }.mapValues { (_, held) -> onLoadOrder(held) }
        assertEquals(games.mapIndexed { i, game -> game to listOf("y$i", "x$i") }.toMap(), applied)
    }

    @Test
    fun anUninstallRecoveredUnderSavedChoicesLeavesItInNoProfileOrOrder() = runTest {
        val world = World()
        val first = with(world) { crashableScope() }
        world.store.afterStore = { if ((it.pending as? PendingChange.Uninstall)?.step is UninstallStep.Settled) throw SimulatedCrash(FaultPoint.ReplaceConfig) }
        val doomed = with(world) { library(first) }
        doomed.uninstall(assertNotNull(doomed.uninstallConfirmation("b")), deleteDownload = false)
        runCurrent()
        first.cancel()
        world.store.afterStore = {}
        assertIs<PendingChange.Uninstall>(world.manifest().pending)
        val applied = world.manifest().profiles
        val book = applied.copy(profiles = applied.profiles + Profile(ProfileId("other"), "Other", ProfileColour.Rose, setOf("b", "c"), applied.activeProfile.orders))
        world.store.save(world.manifest().copy(savedChoices = SavedChoices(book.withOrder(root, listOf("b", "d", "c", "a")))))
        val saves = world.store.saved.size

        with(world) { library() }
        val settled = world.store.saved.drop(saves).first { manifest -> manifest.records.none { it.installId == "b" } }
        assertNull(settled.pending)
        assertTrue(settled.profiles.profiles.none { "b" in it.enabled })
        val target = assertNotNull(settled.savedChoices).target
        assertTrue(target.profiles.none { "b" in it.enabled })
        assertEquals(listOf("d", "c", "a"), target.activeProfile.orders[root])
        assertEquals(listOf("a", "d", "c"), target.profiles.single { it.name == "Other" }.orders[root], "every profile's order loses it")
        assertEquals(setOf("c"), world.manifest().profiles.profiles.single { it.name == "Other" }.enabled)
        assertEquals(listOf("d", "a"), onLoadOrder(world.manifest().records.filter { it.gameRoot == root }))
    }

    @Test
    fun anInstallGoesAheadWhileSavedChoicesAreFailedAndTriesThemAgain() = runTest {
        val world = World()
        world.offer(21)
        world.failTurningBOff()
        val library = with(world) { library() }
        library.setMembership(main, "b", false).done()
        runCurrent()
        assertIs<ChoiceSync.Failed>(library.state.value.choiceSync)
        assertTrue(library.state.value.canInstall)
        library.install("mod-21")
        runCurrent()
        assertNull(library.state.value.operations["mod-21"])
        val installed = world.manifest().records.single { it.source.modId == "mod-21" }
        assertFalse(installed.enabled, "it goes on with the rest of the choices, which still fail")
        assertEquals(2, world.journals().count { !it.startsWith("install-") }, "the install tried the choices again")
        assertIs<ChoiceSync.Failed>(library.state.value.choiceSync)
        assertTrue(installed.installId in world.saved()!!.target.activeProfile.enabled)

        world.files.failing.clear()
        library.retryChoices()
        runCurrent()
        assertEquals(ChoiceSync.Settled, library.state.value.choiceSync)
        assertEquals(start + mapOf("b" to null, installed.installId to 2), world.placement(), "a retry turns b off and the new mod on, first")
    }

    @Test
    fun anInstallWhileDeadlockRunsDownloadsAndPlacesTheSetOffThenTurnsItOnOnceClosed() = runTest {
        val world = World()
        world.offer(21)
        world.processes.scan = ProcessScan.Seen(game = true)
        val library = with(world) { library() }
        assertTrue(library.state.value.canInstall)
        assertFalse(library.state.value.canModify)
        val gate = CompletableDeferred<Unit>()
        world.downloader.gates[210] = gate
        library.install("mod-21")
        runCurrent()
        assertIs<OperationStage.Downloading>(library.state.value.operations["mod-21"]?.stage, "the download runs while Deadlock does")
        assertEquals(listOf(210L), world.downloader.requested)
        gate.complete(Unit)
        runCurrent()

        assertNull(library.state.value.operations["mod-21"], "the install is done, not waiting")
        val record = world.manifest().records.single { it.source.modId == "mod-21" }
        assertEquals(Placement.Off, record.placement)
        assertTrue(world.attemptedWrites.isNotEmpty() && world.attemptedWrites.all { "game/citadel/hideout_off/mod-21/" in it }, "only its own off folder was written: ${world.attemptedWrites}")
        assertTrue(record.installId in assertNotNull(world.saved()).target.activeProfile.enabled, "the active profile wants it on")
        assertEquals(ChoiceSync.Waiting(GameActivity.Running), library.state.value.choiceSync)
        assertEquals(record.installId, library.state.value.orderView?.sets?.first())

        val restarted = with(world) { library() }
        assertEquals(ChoiceSync.Waiting(GameActivity.Running), restarted.state.value.choiceSync, "it waits across a restart too")
        assertTrue(world.attemptedWrites.all { "hideout_off/mod-21/" in it })
        seen(world, ProcessScan.Seen())
        assertTrue(world.manifest().records.single { it.source.modId == "mod-21" }.enabled)
        assertTrue("pak02_dir.vpk" in world.files.addonsFiles(root))
        assertEquals(listOf(210L), world.downloader.requested, "nothing is downloaded again")
        assertEquals(ChoiceSync.Settled, restarted.state.value.choiceSync)
        assertNull(world.saved())
        world.assertFilesAt(start)
    }

    @Test
    fun anInstallWhileDeadlockRunsCanBeCancelledOrRetriedWithNothingLeftBehind() = runTest {
        val world = World()
        world.offer(21)
        world.processes.scan = ProcessScan.Unknown("no process access")
        val library = with(world) { library() }
        val gate = CompletableDeferred<Unit>()
        world.downloader.gates[210] = gate
        library.install("mod-21")
        runCurrent()
        assertTrue(library.cancel("mod-21"))
        gate.complete(Unit)
        runCurrent()
        assertNull(library.state.value.operations["mod-21"])
        assertTrue(world.manifest().records.none { it.source.modId == "mod-21" })
        assertEquals(emptyList(), world.attemptedWrites)

        world.downloader.gates.clear()
        world.downloader.results[210] = DownloadResult.Failed(DownloadProblem.Http(503))
        library.install("mod-21")
        runCurrent()
        assertEquals(LibraryProblem.Download(DownloadProblem.Http(503)), assertIs<OperationStage.Failed>(library.state.value.operations["mod-21"]?.stage).problem)
        assertEquals(emptyList(), world.attemptedWrites)
        world.downloader.results.clear()
        assertTrue(library.retry("mod-21"))
        runCurrent()
        assertNull(library.state.value.operations["mod-21"])
        assertFalse(world.manifest().records.single { it.source.modId == "mod-21" }.enabled)
        assertEquals(ChoiceSync.Waiting(GameActivity.Unknown), library.state.value.choiceSync)
    }

    private fun World.offerVersion(mod: Long, file: Long, content: ByteArray) {
        val ref = SubmissionRef(SubmissionKind.Mod, mod)
        banana.listings = banana.listings + (ref to FileListing.Available("Mod $mod", listOf(RemoteFile(file, "v-$file.zip", 100, null, null, 1, 1, "https://gamebanana.com/dl/$file"))))
        downloader.serve(file, listOf("skin.vpk" to content))
    }

    @Test
    fun aNewVersionOfASetThatIsOnWaitsForDeadlockToCloseAndOneThatIsOffIsPlacedAtOnce() = runTest {
        val world = World()
        val fresh = FakeVpk.directory(seed = 8)
        world.offerVersion(1, 11, fresh)
        world.offerVersion(3, 31, fresh)
        world.processes.scan = ProcessScan.Seen(game = true)
        val library = with(world) { library() }

        assertTrue(changeFilesTo(library, "a", 11))
        assertEquals(OperationStage.WaitingForGame(canCancel = true), library.state.value.operations["mod-1"]?.stage)
        assertEquals(emptyList(), world.attemptedWrites)
        assertTrue(library.cancel("mod-1"))
        runCurrent()
        assertNull(library.state.value.operations["mod-1"])
        assertEquals(10L, world.manifest().records.single { it.installId == "a" }.remoteIdentity.fileId)

        assertTrue(changeFilesTo(library, "c", 31))
        assertNull(library.state.value.operations["mod-3"], "an off set is replaced in its own folder at once")
        assertEquals(31L, world.manifest().records.single { it.installId == "c" }.remoteIdentity.fileId)
        assertTrue(world.attemptedWrites.isNotEmpty() && world.attemptedWrites.all { "game/citadel/hideout_off/mod-3/" in it }, "${world.attemptedWrites}")

        assertTrue(changeFilesTo(library, "a", 11))
        assertEquals(OperationStage.WaitingForGame(canCancel = true), library.state.value.operations["mod-1"]?.stage)
        seen(world, ProcessScan.Seen())
        assertNull(library.state.value.operations["mod-1"])
        val replaced = world.manifest().records.single { it.installId == "a" }
        assertEquals(11L, replaced.remoteIdentity.fileId)
        assertEquals(3 to true, replaced.number to replaced.enabled, "same number, still on")
        assertEquals(Sha256.image(fresh), world.files.addonsFiles(root)["pak03_dir.vpk"])
    }

    @Test
    fun onlyTheLatestUndoIsOfferedAndALaterEditEndsItAtOnce() = runTest {
        val world = World(running = true)
        val library = with(world) { library() }
        val empty = library.createProfile(NewProfile("Empty", ProfileColour.Blue, ProfileSeed.Empty, activate = false)).done()
        val spare = library.createProfile(NewProfile("Spare", ProfileColour.Blue, ProfileSeed.Empty, activate = false)).done()
        assertNull(library.state.value.undo)
        library.deleteProfile(spare).done()
        val undo = assertNotNull(library.state.value.undo)
        library.renameProfile(empty, "Later").done()
        assertNull(library.state.value.undo, "the rename ended it")
        assertEquals(MutationResult.Refused(LibraryProblem.UndoExpired), library.undo(undo))
    }

    @Test
    fun playTakesTheFilesAsTheyAreInBothModesAndWaitsOnlyForARunningGameOrADoubtfulList() = runTest {
        for (modded in listOf(true, false)) {
            val world = World()
            var dispatched = 0
            val dispatch: suspend (GameRoot) -> Unit = { dispatched++ }
            world.processes.scan = ProcessScan.Seen(game = true)
            val library = with(world) { library() }
            library.setMembership(main, "b", false).done()
            assertEquals(LibraryProblem.GameRunning, library.launch(modded, dispatch), "Deadlock runs")

            world.failTurningBOff()
            seen(world, ProcessScan.Seen())
            assertIs<ChoiceSync.Failed>(library.state.value.choiceSync)
            assertTrue(library.state.value.canPlay)
            assertNull(library.launch(modded, dispatch), "a failed apply never holds Play")
            assertEquals(1, dispatched, "modded=$modded")

            world.files.failing.clear()
            library.retryChoices()
            runCurrent()
            assertEquals(ChoiceSync.Settled, library.state.value.choiceSync)

            world.throwBefore = { it.savedChoices != null }
            world.flaky.unreadable = "locked"
            assertIs<MutationResult.Refused>(library.setMembership(main, "c", true))
            assertIs<LibraryProblem.Interrupted>(library.launch(modded, dispatch), "uncertain")
            assertEquals(1, dispatched, "modded=$modded")

            world.throwBefore = { false }
            world.flaky.unreadable = null
            library.retryChoices()
            runCurrent()
            assertNull(library.launch(modded, dispatch))
            assertEquals(2, dispatched, "modded=$modded")
        }
    }

    @Test
    fun aChoiceAskedForWhilePlayIsBeingAdmittedIsRefusedAndNeverRacesTheDispatch() = runTest {
        val world = World()
        val library = with(world) { library() }
        val gate = CompletableDeferred<Unit>()
        world.processes.gate = gate
        val dispatched = mutableListOf<LibraryManifest>()
        var result: LibraryProblem? = LibraryProblem.Busy
        val launch = launch(start = CoroutineStart.UNDISPATCHED) { result = library.launch(modded = true) { dispatched += world.manifest() } }
        runCurrent()
        assertTrue(library.state.value.launching)
        assertFalse(library.state.value.canEditChoices)
        val asked = async(start = CoroutineStart.UNDISPATCHED) { library.setMembership(main, "c", true) }
        library.drop("d", "c", "b", "a")
        val dropped = library.state.value.choiceProblem
        world.processes.gate = null
        gate.complete(Unit)
        launch.join()
        assertEquals(MutationResult.Refused(LibraryProblem.Busy), asked.await())
        assertEquals(LibraryProblem.Busy, dropped)
        assertNull(result)
        assertEquals(1, dispatched.size)
        assertNull(dispatched.single().savedChoices)
        assertFalse(library.state.value.launching)
        assertNull(world.saved())
        library.setMembership(main, "c", true).done()
    }

    @Test
    fun playAskedForWhileTheLibraryWorksWaitsForItsStepAndThenGoes() = runTest {
        val world = World()
        val library = with(world) { library() }
        val gate = CompletableDeferred<Unit>()
        world.processes.gate = gate
        launch(start = CoroutineStart.UNDISPATCHED) { library.reapply() }
        library.setEnabled("c", true)
        assertTrue(library.state.value.saving)
        var dispatched = 0
        val launched = async { library.launch(modded = true) { dispatched++ } }
        runCurrent()
        assertEquals(0, dispatched)
        world.processes.gate = null
        gate.complete(Unit)
        runCurrent()
        assertNull(launched.await())
        assertEquals(1, dispatched)
        assertEquals(start + ("c" to 10), world.placement(), "the choice saved before Play is not lost")
    }

    @Test
    fun switchingBackRestoresTheSavedBookWhileRunningAndUndoOutlivesTheLibrarysOwnSaves() = runTest {
        val world = World(running = true)
        val library = with(world) { library() }
        val empty = library.createProfile(NewProfile("Empty", ProfileColour.Blue, ProfileSeed.Empty, activate = false)).done()
        library.switchProfile(empty).done()
        assertNull(library.state.value.undo, "the profile left is one click away")
        library.switchProfile(main).done()
        assertEquals(main, library.state.value.profiles?.active)
        assertNull(world.saved(), "back to what the files are")
        assertEquals(listOf("Main", "Empty"), world.manifest().profiles.profiles.map { it.name }, "a profile that changes no file is applied at once")
        assertEquals(emptyList(), world.attemptedWrites)

        library.deleteProfile(empty).done()
        library.undo(assertNotNull(library.state.value.undo)).done()
        assertEquals(listOf("Main", "Empty"), library.state.value.profiles?.profiles?.map { it.name })

        seen(world, ProcessScan.Seen())
        library.setEnabledTogether(setOf("a", "b", "d", "e"), false)
        runCurrent()
        val again = assertNotNull(library.state.value.undo)
        assertTrue(world.journals().isNotEmpty())
        assertNull(world.saved(), "applied, journal saved and cleared by the library itself")
        library.undo(again).done()
        runCurrent()
        assertEquals(start + mapOf("a" to 2, "b" to 3, "d" to 4, "e" to 1), world.placement(), "every mod comes back on in Main's order at the lowest numbers")
    }

    @Test
    fun aChoiceAdmittedWhileFilesAreHeldIsSavedAndAppliedAfterItsCallerIsGone() = runTest {
        val world = World()
        val library = with(world) { library() }
        val gate = CompletableDeferred<Unit>()
        world.processes.gate = gate
        launch(start = CoroutineStart.UNDISPATCHED) { library.reapply() }
        val screen = CoroutineScope(Job() + StandardTestDispatcher(testScheduler))
        screen.launch(start = CoroutineStart.UNDISPATCHED) { library.setMembership(main, "c", true) }
        assertTrue(library.state.value.saving)
        assertEquals(setOf("a", "b", "c", "d", "e"), library.state.value.shownProfiles?.activeProfile?.enabled)
        assertEquals(setOf("a", "b", "d", "e"), library.state.value.profiles?.activeProfile?.enabled, "not saved yet")
        screen.cancel()
        world.processes.gate = null
        gate.complete(Unit)
        runCurrent()
        assertEquals(start + ("c" to 10), world.placement())
        assertNull(world.saved())
    }

    @Test
    fun aChoiceQueuedBehindAChangeThatLeavesAJournalIsRefusedNotReportedSaved() = runTest {
        val world = World()
        val target = offPath("b")
        val library = with(world) { library() }
        var queued = false
        world.files.around = { point ->
            if (point == FaultPoint.LinkMade(target)) {
                world.files.around = null
                world.files.failing += FaultPoint.Look(target)
                library.setEnabled("c", true)
                queued = library.state.value.saving
            }
        }
        library.setMembership(main, "b", false).done()
        runCurrent()
        assertTrue(queued, "the toggle was admitted while the journal ran")
        assertIs<LibraryProblem.Interrupted>(library.state.value.choiceProblem)
        assertFalse(library.state.value.saving)
        assertEquals(setOf("a", "d", "e"), world.saved()?.target?.activeProfile?.enabled, "the queued toggle was never saved")
        assertEquals(library.state.value.profiles, library.state.value.shownProfiles)
    }
}
