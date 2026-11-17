package gg.hideouthq.core.library

import gg.hideouthq.core.data.Preferences
import gg.hideouthq.core.data.ProcessScan
import gg.hideouthq.core.data.fake.FakeDownloader
import gg.hideouthq.core.data.fake.FakeGameBanana
import gg.hideouthq.core.data.fake.FakeProcesses
import gg.hideouthq.core.data.fake.FaultPoint
import gg.hideouthq.core.data.fake.InMemoryGameFiles
import gg.hideouthq.core.data.fake.InMemoryLibraryStore
import gg.hideouthq.core.data.fake.MemoryStorage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
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
class ModLibraryReorderTest {
    private val root = GameRoot("/games/Deadlock")
    private val other = GameRoot("/other/Deadlock")
    private val addons = SetFolder.Addons.path

    private class Spec(val id: String, val mod: Long, val number: Int?, val suffixes: List<String>, val sets: Int = 1)

    private val specs = listOf(
        Spec("a", 1, 3, listOf("dir", "000", "002")),
        Spec("b", 2, 5, listOf("dir")),
        Spec("c", 3, null, listOf("dir", "000")),
        Spec("d", 4, 9, listOf("dir", "000")),
    )

    private val before = specs.associate { it.id to it.number }

    private val switching = listOf(
        Spec("a", 1, 3, listOf("dir")),
        Spec("b", 2, 2, listOf("dir")),
        Spec("c", 3, null, listOf("dir", "000"), sets = 2),
    )
    private val ranked = ProfileId("ranked")
    private val switched = mapOf("a" to 4, "b" to null, "c" to 2)

    private fun rankedBook(records: List<ManifestRecord>): ProfileBook {
        val main = ProfileBook.main(records)
        return main.copy(profiles = main.profiles + Profile(ranked, "Ranked", ProfileColour.Blue, setOf("a", "c", "e"), main.activeProfile.orders + (root to listOf("c", "a", "b"))))
    }

    private val strangeFiles = mapOf("pak01_dir.vpk" to ByteArray(9) { 7 }, "my_own_skin.vpk" to ByteArray(5) { 8 })

    private fun bytesOf(id: String, suffix: String) = "$id-$suffix".encodeToByteArray() + ByteArray(16 + id[0].code % 5) { (it * 3 + suffix.length).toByte() }

    private fun name(number: Int, suffix: String) = "pak${number.toString().padStart(2, '0')}_$suffix.vpk"

    private fun Spec.offFolder(): SetFolder = SetFolder.Off(SubmissionRef(SubmissionKind.Mod, mod))

    private fun Spec.bytes(set: Int, suffix: String) = bytesOf(if (set == 0) id else "$id$set", suffix)

    private fun record(spec: Spec, number: Int? = spec.number, order: List<Int> = (0 until spec.sets).toList()): ManifestRecord {
        val source = InstalledSource.Remote(SubmissionIdentity(SubmissionKind.Mod, spec.mod, spec.mod * 10, null))
        val archive = ArchiveIdentity("/data/hideout/downloads/mod-${spec.mod}/${spec.mod * 10}/${spec.id}.zip", 100, "a".repeat(64))
        val sets = order.map { set ->
            val files = spec.suffixes.map { suffix -> SetFile(checkNotNull(VpkPart.parse(suffix)), Sha256.image(spec.bytes(set, suffix))) }
            InstalledSet(source, null, null, SetOrigin.Downloaded(archive), null, if (spec.sets == 1) "pak01_dir.vpk" else "part$set", files)
        }
        return ManifestRecord(spec.id, root, "Mod ${spec.id}", number?.let { Placement.On(PakNumber(it)) } ?: Placement.Off, sets)
    }

    private fun placedFiles(spec: Spec, record: ManifestRecord): List<Triple<Int, String, OwnedFile>> = record.sets.flatMap { set ->
        val index = if (spec.sets == 1) 0 else set.componentKey.removePrefix("part").toInt()
        record.filesOf(set).map { Triple(index, it.part.label, it) }
    }

    private val elsewhere = record(Spec("e", 5, 3, listOf("dir"))).copy(gameRoot = other)

    private inner class World(
        info: ByteArray = VanillaGameInfo,
        val specs: List<Spec> = this@ModLibraryReorderTest.specs,
        foreign: Map<String, ByteArray> = strangeFiles,
        book: (List<ManifestRecord>) -> ProfileBook = { ProfileBook.main(it) },
    ) : CrashWorld {
        override val files = InMemoryGameFiles().apply {
            putBytes(root, InMemoryGameFiles.GameInfo, info)
            putBytes(other, InMemoryGameFiles.GameInfo, VanillaGameInfo)
            for (spec in specs) for ((set, suffix, file) in placedFiles(spec, record(spec))) putBytes(root, file.relativePath(record(spec)), spec.bytes(set, suffix))
            foreign.forEach { (name, bytes) -> putBytes(root, "$addons/$name", bytes) }
            putBytes(other, "$addons/pak03_dir.vpk", bytesOf("e", "dir"))
        }
        override val store = InMemoryLibraryStore((specs.map { record(it) } + elsewhere).let { LibraryManifest(records = it, profiles = book(it)) })
        val disk = FlakyStore(store)
        val processes = FakeProcesses()
        private val gameInfo = info
        private var nextId = 0

        val identities: Map<Triple<String, Int, String>, Int?> = specs.flatMap { spec ->
            placedFiles(spec, record(spec)).map { (set, suffix, file) -> Triple(spec.id, set, suffix) to files.identity(root, file.relativePath(record(spec))) }
        }.toMap()

        private val owners: Map<Int?, Pair<String, Int>> = identities.entries.associate { (key, identity) -> identity to (key.first to key.second) }

        val strangers: MutableMap<String, Int?> = foreign.keys.associateWith { files.identity(root, "$addons/$it") }.toMutableMap()
        private val elsewhereIdentity = files.identity(other, "$addons/pak03_dir.vpk")

        val mountProblems = mutableListOf<String>()

        private var session: CoroutineScope? = null

        fun TestScope.library(scope: CoroutineScope? = null): ModLibrary {
            session?.cancel()
            val owned = scope ?: CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext[Job]))
            session = owned
            return ModLibrary(
                FixedGameLocator(listOf(root, other)), Preferences(MemoryStorage()), disk, files,
                InstallPorts(FakeGameBanana(), FakeDownloader(files), files, files, processes), owned,
                ids = { "id${++nextId}" },
                watch = pollingWatchTiming,
            ).also {
                it.start()
                runCurrent()
            }
        }

        override fun TestScope.open(scope: CoroutineScope) = library(scope)

        override fun check(point: FaultPoint) = checkMounts(point)

        fun manifest() = assertNotNull(store.manifest)

        fun placement(): Map<String, Int?> = manifest().records.filter { it.gameRoot == root }.associate { it.installId to it.numbers.firstOrNull()?.value }

        fun numbers(): Map<String, Int> = placement().mapNotNull { (id, number) -> number?.let { id to it } }.toMap()

        fun checkMounts(at: FaultPoint) {
            val mounted = files.addonsFiles(root).keys.filter { TemporaryInfix !in it }
            for (spec in specs) for (set in 0 until spec.sets) {
                val directory = identities.getValue(Triple(spec.id, set, "dir"))
                for (shown in mounted.filter { it.endsWith("_dir.vpk") && files.identity(root, "$addons/$it") == directory }) {
                    val number = shown.substring(3, 5).toInt()
                    for (suffix in spec.suffixes - "dir") {
                        if (files.identity(root, "$addons/${name(number, suffix)}") != identities[Triple(spec.id, set, suffix)]) mountProblems += "at $at $shown was visible without its chunk $suffix"
                    }
                }
            }
            mounted.filter { it.startsWith("set-") }.forEach { mountProblems += "at $at the off name $it was in addons" }
            for (spec in specs) files.filesIn(root, spec.offFolder()).keys.filter { it.startsWith("pak") }.forEach { mountProblems += "at $at the pak name $it was in ${spec.offFolder().path}" }
            mounted.filter { it.startsWith("pak") }.groupBy { it.substring(3, 5) }.forEach { (number, names) ->
                val sets = names.mapNotNull { owners[files.identity(root, "$addons/$it")] }.distinct()
                if (sets.size > 1) mountProblems += "at $at number $number held files of $sets"
            }
        }

        fun watchMounts(points: MutableList<FaultPoint>? = null, also: (FaultPoint) -> Unit = {}) {
            files.around = { point ->
                points?.add(point)
                checkMounts(point)
                also(point)
            }
        }

        fun snapshot(): Map<String, Int?> =
            (listOf(SetFolder.Addons) + specs.map { it.offFolder() }).flatMap { folder ->
                files.filesIn(root, folder).keys.map { "${folder.path}/$it" to files.identity(root, "${folder.path}/$it") }
            }.toMap()

        fun assertWhole(placement: Map<String, Int?>) {
            assertEquals(emptyList(), mountProblems)
            assertNull(manifest().pending)
            val placed = specs.map { record(it, placement.getValue(it.id)) }
            assertEquals(placed + elsewhere, manifest().records)
            for ((spec, record) in specs.zip(placed)) {
                assertEquals(if (record.enabled) emptySet() else record.files.map { it.name }.toSet(), files.filesIn(root, spec.offFolder()).keys, "${spec.id} is alone in its off folder")
                for ((set, suffix, file) in placedFiles(spec, record)) {
                    val path = file.relativePath(record)
                    assertEquals(identities[Triple(spec.id, set, suffix)], files.identity(root, path), "$path is ${spec.id}'s own $suffix")
                    assertContentEquals(spec.bytes(set, suffix), files.bytes(root, path), path)
                }
            }
            val ours = placed.filter { it.enabled }.flatMap { record -> record.files.map { it.name } }
            assertEquals(strangers.keys + ours, files.addonsFiles(root).keys)
            strangers.forEach { (name, identity) -> assertEquals(identity, files.identity(root, "$addons/$name"), "$name is the same file") }
            assertEquals(elsewhereIdentity, files.identity(other, "$addons/pak03_dir.vpk"), "the other game is untouched")
            assertContentEquals(gameInfo, files.gameInfo(root), "gameinfo.gi is untouched")
        }
    }

    private fun ModLibrary.ready(): LibraryStatus.Ready = assertIs<LibraryStatus.Ready>(state.value.library)

    private fun ModLibrary.failed(): LibraryProblem? = (state.value.choiceSync as? ChoiceSync.Failed)?.problem

    private fun TestScope.reorder(library: ModLibrary, vararg order: String) {
        library.reorder(assertNotNull(library.state.value.orderView), order.toList())
        runCurrent()
    }

    private fun World.loadOrder() = onLoadOrder(manifest().records.filter { it.gameRoot == root })

    private fun inAddons(paths: List<String>) = paths.filter { it.startsWith("$addons/") && TemporaryInfix !in it }

    private val swap = listOf("d", "b", "c", "a") to mapOf("a" to 11, "b" to 10, "c" to null, "d" to 9)

    private val cycle = listOf("d", "c", "a", "b") to mapOf("a" to 3, "b" to 5, "c" to null, "d" to 2)

    private val back = mapOf("a" to 11, "b" to 12, "c" to null, "d" to 13)

    @Test
    fun aDragRenamesTheFewestFilesKeepingEveryFileItsBytesAndIdentity() = runTest {
        val world = World()
        world.watchMounts()
        val library = with(world) { library() }
        assertEquals(listOf("a", "b", "d"), world.loadOrder())
        reorder(library, *swap.first.toTypedArray())

        world.assertWhole(swap.second)
        assertEquals(ChoiceSync.Settled, library.state.value.choiceSync)
        assertEquals(listOf("d", "b", "a"), onLoadOrder(library.ready().records.filter { it.gameRoot == root }))
        assertEquals(emptyMap(), library.ready().damaged)
        assertEquals(
            listOf("pak03_dir.vpk", "pak03_000.vpk", "pak03_002.vpk", "pak05_dir.vpk").map { "$addons/$it" },
            inAddons(world.files.removedPaths),
            "each set leaves directory first, and d never moves",
        )
        assertEquals(
            listOf("pak11_000.vpk", "pak11_002.vpk", "pak11_dir.vpk", "pak10_dir.vpk").map { "$addons/$it" },
            inAddons(world.files.linkedPaths),
            "each set comes back chunks first, directory last",
        )
    }

    @Test
    fun aStaleOrForeignViewOrAnOrderThatIsNotARearrangementIsRefusedWithNothingSavedOrTouched() = runTest {
        val world = World()
        val library = with(world) { library() }
        val stale = assertNotNull(library.state.value.orderView)
        reorder(library, *swap.first.toTypedArray())
        val current = assertNotNull(library.state.value.orderView)
        val saves = world.store.saved.size
        val touched = world.files.linkedPaths.size + world.files.removedPaths.size
        val cases = listOf(
            "a view shown before the last drop" to (stale to listOf("a", "b", "c", "d")),
            "a set left out of the order" to (current to listOf("d", "b", "c")),
            "a set twice" to (current to listOf("d", "d", "c", "a")),
            "a set Hideout does not hold" to (current to listOf("d", "b", "c", "zz")),
        )
        for ((name, case) in cases) {
            library.reorder(case.first, case.second)
            runCurrent()
            assertEquals(LibraryProblem.LoadOrderChanged, library.state.value.choiceProblem, name)
            assertSame(current, library.state.value.orderView, "$name: the order shown stays")
            library.dismissChoiceProblem()
            assertNull(library.state.value.choiceProblem, name)
        }

        assertEquals(saves, world.store.saved.size)
        assertEquals(touched, world.files.linkedPaths.size + world.files.removedPaths.size)
        world.assertWhole(swap.second)
    }

    @Test
    fun turningModsOnAroundAModWhoseFileChangedMovesOthersAndNeverIt() = runTest {
        val owners = listOf(
            Spec("blackout", 6, 6, listOf("dir")),
            Spec("speedometer", 7, 7, listOf("dir")),
            Spec("witch", 11, 11, listOf("dir")),
            Spec("fov", 14, 14, listOf("dir")),
            Spec("mario", 46, 46, listOf("dir"), sets = 2),
            Spec("y", 20, null, listOf("dir")),
            Spec("z", 21, null, listOf("dir")),
        )
        val world = World(specs = owners)
        val changed = "Blackout as the user edited it".encodeToByteArray()
        world.files.rewrite(root, "$addons/pak06_dir.vpk", changed)
        val library = with(world) { library() }
        assertEquals(FilesDamage.Changed, library.ready().damaged["blackout"])
        val held = mapOf("blackout" to 6, "witch" to 11, "fov" to 14, "mario" to 46)

        reorder(library, "blackout", "y", "speedometer", "witch", "fov", "mario", "z")
        library.setEnabled("y", true)
        runCurrent()
        assertEquals(held + mapOf("y" to 7, "speedometer" to 8), world.numbers(), "dropped between 06 and 07, the mod at 07 moves up rather than the changed one at 06 down")

        reorder(library, "z", "blackout", "y", "speedometer", "witch", "fov", "mario")
        library.setEnabled("z", true)
        runCurrent()
        assertEquals(held + mapOf("z" to 2, "y" to 7, "speedometer" to 8), world.numbers(), "on top it takes the lowest free number, 01 being foreign")
        assertEquals(ChoiceSync.Settled, library.state.value.choiceSync)
        assertContentEquals(changed, world.files.bytes(root, "$addons/pak06_dir.vpk"), "the changed file stays as it is")
    }

    @Test
    fun aForeignFileAtANameTheOrderWantsIsPlannedAroundAndLeftAsItIs() = runTest {
        val world = World()
        val target = "$addons/pak11_002.vpk"
        world.files.putBytes(root, target, bytesOf("a", "002"))
        world.strangers["pak11_002.vpk"] = world.files.identity(root, target)
        val library = with(world) { library() }
        reorder(library, *swap.first.toTypedArray())
        assertEquals(ChoiceSync.Settled, library.state.value.choiceSync)
        assertEquals(listOf("d", "b", "a"), world.loadOrder())
        world.assertWhole(mapOf("a" to 12, "b" to 10, "c" to null, "d" to 9))
    }

    @Test
    fun aForeignFileAppearingAtATargetMidwayRollsEveryMoveBack() = runTest {
        val world = World()
        val target = "$addons/pak11_002.vpk"
        world.watchMounts { point ->
            if (point == FaultPoint.Link(target)) {
                world.files.putBytes(root, target, bytesOf("a", "002"))
                world.strangers["pak11_002.vpk"] = world.files.identity(root, target)
            }
        }
        val library = with(world) { library() }
        reorder(library, *swap.first.toTypedArray())

        assertEquals(LibraryProblem.NameTaken(target), library.failed())
        world.assertWhole(before)
        assertEquals(emptyMap(), library.ready().damaged)
    }

    private fun ordered(vararg order: String): (List<ManifestRecord>) -> ProfileBook = { records -> ProfileBook.main(records).withOrder(root, order.toList()) }

    private fun TestScope.crashAtEveryStep(world: () -> World, after: Map<String, Int?>, act: CoroutineScope.(ModLibrary) -> Unit) {
        val dry = world()
        val start = dry.placement()
        val first = dry.manifest().profiles
        val trace = traced(dry, act)
        dry.assertWhole(after)
        val last = dry.manifest().profiles
        assertTrue(trace.points.size > 10 && trace.saves > 0, "the trace has ${trace.points.size} points and ${trace.saves} saves")
        assertEquals(1, dry.store.arranges(), "one journal")

        crashAtEveryStep(trace, world, act) { world, _ ->
            val placement = world.placement()
            assertTrue(placement == start || placement == after, "every mod is placed as it was or as asked, or none is: $placement")
            world.assertWhole(placement)
            assertEquals(if (placement == start) first else last, world.manifest().profiles, "the applied book goes with the files")
        }
    }

    @Test
    fun aCrashAtAnyStepOfADragAndDuringItsRecoveryConvergesOnOneWholeOrder() = runTest {
        crashAtEveryStep({ World() }, swap.second) { reorder(it, *swap.first.toTypedArray()) }
    }

    @Test
    fun aCrashAtAnyStepOfTurningAModOnIntoAGapAndDuringItsRecoveryConvergesOnOneWholeOrder() = runTest {
        crashAtEveryStep({ World(HideoutGameInfo, book = ordered("a", "b", "c", "d")) }, mapOf("a" to 3, "b" to 5, "c" to 6, "d" to 9)) { it.setEnabled("c", true) }
    }

    @Test
    fun aCrashAtAnyStepOfTurningAModOnThatMovesANeighbourAndDuringItsRecoveryConvergesOnOneWholeOrder() = runTest {
        crashAtEveryStep({ World(HideoutGameInfo, foreign = strangeFiles + ("pak04 copy.vpk" to ByteArray(3)), book = ordered("a", "c", "b", "d")) }, mapOf("a" to 3, "b" to 6, "c" to 5, "d" to 9)) { it.setEnabled("c", true) }
    }

    @Test
    fun aCrashAtAnyStepOfASwitchThatHandsNumbersOnToAModOfTwoSetsAndDuringItsRecoveryConvergesOnOneWholeProfile() = runTest {
        crashAtEveryStep({ World(HideoutGameInfo, switching, book = ::rankedBook) }, switched) { library ->
            launch { library.switchProfile(ranked) }
        }
    }

    @Test
    fun aSwitchTurnsModsOffAndOnInOneJournalAndSwitchingBackTurnsThemBackInMainsOrder() = runTest {
        val world = World(HideoutGameInfo, switching, book = ::rankedBook)
        world.watchMounts()
        val library = with(world) { library() }
        val first = world.manifest().profiles
        val main = first.active
        assertEquals(SwitchSummary(ranked, off = 1, on = 1), assertIs<MutationResult.Done<SwitchSummary>>(library.switchProfile(ranked)).value)
        runCurrent()

        world.assertWhole(switched)
        assertEquals(1, world.store.arranges())
        assertEquals(first.copy(active = ranked), world.manifest().profiles)
        assertEquals(listOf("c", "a", "b"), library.state.value.orderView?.sets)
        assertNull(world.manifest().savedChoices)

        assertIs<MutationResult.Done<SwitchSummary>>(library.switchProfile(main))
        runCurrent()
        world.assertWhole(mapOf("a" to 4, "b" to 2, "c" to null))
        assertEquals(listOf("b", "a"), world.loadOrder(), "b is on again before a, and a keeps 04")
        assertEquals(first, world.manifest().profiles)
        assertEquals(2, world.store.arranges())
        assertEquals(main, world.manifest().profiles.active)
    }

    @Test
    fun aDragChangesOnlyTheActiveProfileAndADropFromBeforeASwitchIsRefused() = runTest {
        val world = World(HideoutGameInfo, switching, book = ::rankedBook)
        val library = with(world) { library() }
        val main = world.manifest().profiles.active
        reorder(library, "a", "b", "c")
        world.assertWhole(mapOf("a" to 3, "b" to 4, "c" to null))
        assertEquals(listOf("a", "b", "c"), world.manifest().profiles.activeProfile.orders[root])
        assertEquals(listOf("c", "a", "b"), world.manifest().profiles.profiles.single { it.id == ranked }.orders[root], "Ranked keeps its own")

        val shown = assertNotNull(library.state.value.orderView)
        assertIs<MutationResult.Done<SwitchSummary>>(library.switchProfile(ranked))
        runCurrent()
        assertNotSame(shown, library.state.value.orderView, "another order is another view")
        val saves = world.store.saved.size
        library.reorder(shown, listOf("b", "a", "c"))
        runCurrent()
        assertEquals(LibraryProblem.LoadOrderChanged, library.state.value.choiceProblem)
        assertEquals(saves, world.store.saved.size)
        assertEquals(listOf("a", "b", "c"), world.manifest().profiles.profiles.single { it.id == main }.orders[root])
        assertEquals(listOf("c", "a"), world.loadOrder())
        world.assertWhole(mapOf("a" to 4, "b" to null, "c" to 2))
    }

    @Test
    fun aSwitchWithRoomForPartOfItsOrderPlacesTheTopAndLeavesTheRestPastTheLimit() = runTest {
        val crowd = (4..99).associate { name(it, "dir") to ByteArray(4) }
        val world = World(HideoutGameInfo, switching, foreign = strangeFiles + crowd, book = ::rankedBook)
        val library = with(world) { library() }
        assertIs<MutationResult.Done<SwitchSummary>>(library.switchProfile(ranked))
        runCurrent()
        assertEquals(ChoiceSync.Settled, library.state.value.choiceSync, "c's two sets take 02 and 03, and a has nowhere left")
        world.assertWhole(mapOf("a" to null, "b" to null, "c" to 2))
        assertEquals(setOf("a"), library.state.value.pastLimit)
        assertEquals(ranked, world.manifest().savedChoices?.target?.active, "the switch stays saved with a on")
        assertTrue("a" in world.manifest().savedChoices?.target?.activeProfile?.enabled.orEmpty())
    }

    @Test
    fun aHundredthSetTurnedOnStaysOutUntilAnotherIsTurnedOffOrItMovesUp() = runTest {
        val many = (1..99).map { Spec("m$it", 100L + it, it, listOf("dir")) } + Spec("x", 500, null, listOf("dir"))
        val world = World(HideoutGameInfo, many, foreign = emptyMap())
        val library = with(world) { library() }
        library.setEnabled("x", true)
        runCurrent()
        assertEquals(ChoiceSync.Settled, library.state.value.choiceSync, "nothing is refused, so Play is never held")
        assertEquals(setOf("x"), library.state.value.pastLimit)
        assertEquals(0, world.store.arranges())
        world.assertWhole(many.associate { it.id to it.number })

        val view = assertNotNull(library.state.value.orderView)
        library.reorder(view, listOf("x") + view.sets.filter { it != "x" })
        runCurrent()
        assertEquals(ChoiceSync.Settled, library.state.value.choiceSync)
        assertEquals(setOf("m99"), library.state.value.pastLimit, "the top wins, so the last one goes out")
        assertEquals(null, world.placement()["m99"])
        assertEquals(1, world.store.arranges())

        library.setEnabled("m98", false)
        runCurrent()
        assertEquals(ChoiceSync.Settled, library.state.value.choiceSync)
        assertEquals(emptySet(), library.state.value.pastLimit)
        assertEquals(2, world.store.arranges())
        assertEquals(null, world.placement()["m98"])
        assertNotNull(world.placement()["m99"], "turning one off lets the next one in without a retry")
    }

    @Test
    fun aRestartPlansAgainAndFindsTheSameModsPastTheLimitWithoutMovingAFile() = runTest {
        val many = (1..99).map { Spec("m$it", 100L + it, it, listOf("dir")) } + Spec("x", 500, null, listOf("dir"))
        val world = World(HideoutGameInfo, many, foreign = emptyMap())
        with(world) { library() }.setEnabled("x", true)
        runCurrent()
        val again = with(world) { library() }
        runCurrent()
        assertEquals(ChoiceSync.Settled, again.state.value.choiceSync)
        assertEquals(setOf("x"), again.state.value.pastLimit)
        assertEquals(0, world.store.arranges())
    }

    @Test
    fun aRestartWhileDeadlockRunsSaysNothingWaitsWhenOnlyModsPastTheLimitDiffer() = runTest {
        val many = (1..99).map { Spec("m$it", 100L + it, it, listOf("dir")) } + Spec("x", 500, null, listOf("dir"))
        val world = World(HideoutGameInfo, many, foreign = emptyMap())
        with(world) { library() }.setEnabled("x", true)
        runCurrent()
        world.processes.scan = ProcessScan.Seen(game = true)
        val again = with(world) { library() }
        runCurrent()
        assertEquals(GameActivity.Running, again.state.value.deadlock)
        assertEquals(ChoiceSync.Settled, again.state.value.choiceSync)
        assertEquals(setOf("x"), again.state.value.pastLimit)
        assertEquals(0, world.store.arranges())
    }

    @Test
    fun aHundredAndFiftyModsSwitchBetweenTwoProfilesInOneJournalAndOnlyTheModsOnHoldNumbers() = runTest {
        val many = (1..150).map { Spec("m$it", 100L + it, it.takeIf { n -> n <= 60 }, listOf("dir")) }
        val party = ProfileId("party")
        val world = World(HideoutGameInfo, many, foreign = emptyMap()) { records ->
            val main = ProfileBook.main(records)
            val orders = main.activeProfile.orders.let { it + (root to it.getValue(root).reversed()) }
            main.copy(profiles = main.profiles + Profile(party, "Party", ProfileColour.Rose, (61..120).map { "m$it" }.toSet() + "e", orders))
        }
        val library = with(world) { library() }
        assertIs<MutationResult.Done<SwitchSummary>>(library.switchProfile(party))
        runCurrent()
        assertEquals(ChoiceSync.Settled, library.state.value.choiceSync)
        assertEquals(1, world.store.arranges())
        val sixty = (120 downTo 61).mapIndexed { index, it -> "m$it" to index + 1 }.toMap()
        world.assertWhole(many.associate { it.id to sixty[it.id] })

        library.setEnabled("m60", true)
        runCurrent()
        assertEquals(sixty + ("m60" to 61), world.numbers(), "a mod Party orders after its sixty takes the next number, and nothing else moves")
    }

    @Test
    fun deadlockStartingAtAnyStepStopsTheReorderAtTheNextFileAndItSettlesOnceDeadlockCloses() = runTest {
        val (order, after) = cycle
        val dry = World()
        val points = mutableListOf<FaultPoint>()
        dry.watchMounts(points)
        reorder(with(dry) { library() }, *order.toTypedArray())

        for (index in points.indices) {
            val world = World()
            var seen = 0
            world.watchMounts { if (seen++ == index) world.processes.scan = ProcessScan.Seen(game = true) }
            val library = with(world) { library() }
            reorder(library, *order.toTypedArray())
            val sync = library.state.value.choiceSync
            assertTrue(sync == ChoiceSync.Settled || sync == ChoiceSync.Waiting(GameActivity.Running), "step $index ${points[index]}: $sync")

            val stopped = world.snapshot()
            val touched = mutableListOf<FaultPoint>()
            world.watchMounts { if (it !is FaultPoint.Look) touched += it }
            with(world) { library() }
            advanceTimeBy(5.seconds)
            assertEquals(stopped, world.snapshot(), "step $index: nothing changes while Deadlock runs")
            assertEquals(emptyList(), touched, "step $index")

            world.processes.scan = ProcessScan.Seen()
            val settled = with(world) { library() }
            assertEquals(after, world.placement(), "step $index: the saved order is reached once Deadlock closes")
            world.assertWhole(after)
            assertNull(settled.ready().interrupted, "step $index")
        }
    }

    private fun parted(aOff: Boolean = false) = listOf(Spec("a", 1, if (aOff) null else 3, listOf("dir", "000"), sets = 2), Spec("b", 2, 6, listOf("dir")))

    private fun World.keys() = manifest().records.single { it.installId == "a" }.sets.map { it.key }

    private fun World.assertParts(order: List<Int>) {
        assertEquals(emptyList(), mountProblems)
        assertNull(manifest().pending)
        val spec = specs.single { it.id == "a" }
        val a = record(spec, spec.number, order)
        assertEquals(a, manifest().records.single { it.installId == "a" })
        for ((part, suffix, file) in placedFiles(spec, a)) {
            assertEquals(identities[Triple("a", part, suffix)], files.identity(root, file.relativePath(a)), "${file.name} is part $part's own $suffix")
            assertContentEquals(spec.bytes(part, suffix), files.bytes(root, file.relativePath(a)), file.name)
        }
        assertEquals(strangers.keys + a.files.filter { a.enabled }.map { it.name } + name(6, "dir"), files.addonsFiles(root).keys)
        assertEquals(identities[Triple("b", 0, "dir")], files.identity(root, "$addons/${name(6, "dir")}"))
    }

    @Test
    fun reorderingTheOnModsPartsRenamesThemInsideItsOwnNumbersAndIsRefusedWhileDeadlockRuns() = runTest {
        val world = World(HideoutGameInfo, parted())
        world.watchMounts()
        world.processes.scan = ProcessScan.Seen(game = true)
        val library = with(world) { library() }
        val (first, second) = world.keys()
        assertTrue(library.reorderParts("a", listOf(second, first)))
        runCurrent()
        val refused = library.state.value.operations.values.single { it.installId == "a" }.stage
        assertEquals(LibraryProblem.GameRunning, assertIs<OperationStage.Failed>(refused).problem, "the game loads the files it would rename")
        world.assertParts(listOf(0, 1))

        world.processes.scan = ProcessScan.Seen()
        advanceTimeBy(5.seconds)
        assertTrue(library.reorderParts("a", listOf(second, first)))
        runCurrent()
        assertEquals(emptyMap(), library.state.value.operations)
        world.assertParts(listOf(1, 0))
        assertEquals(mapOf("a" to 3, "b" to 6), world.numbers(), "the mod keeps its place in the load order")
        assertEquals(1, world.store.arranges())
        assertFalse(library.reorderParts("a", world.keys()), "the order it has asks for nothing")
        assertFalse(library.reorderParts("a", world.keys().take(1)), "every part, once")
    }

    @Test
    fun reorderingTheOffModsPartsSavesTheirOrderAndMovesNoFileWhileDeadlockRuns() = runTest {
        val world = World(HideoutGameInfo, parted(aOff = true))
        world.processes.scan = ProcessScan.Seen(game = true)
        val library = with(world) { library() }
        val (first, second) = world.keys()
        assertTrue(library.reorderParts("a", listOf(second, first)))
        runCurrent()
        assertEquals(emptyMap(), library.state.value.operations)
        world.assertParts(listOf(1, 0))
        assertEquals(emptyList(), world.files.linkedPaths + world.files.removedPaths, "an off mod's file names hold no order")
        assertEquals(0, world.store.arranges())
    }

    @Test
    fun aCrashAtAnyStepOfReorderingAModsPartsAndDuringItsRecoveryLeavesThemWholeInOneOrder() = runTest {
        val dry = World(HideoutGameInfo, parted())
        val reversed = dry.keys().reversed()
        val act: CoroutineScope.(ModLibrary) -> Unit = { it.reorderParts("a", reversed) }
        val trace = traced(dry, act)
        dry.assertParts(listOf(1, 0))
        assertTrue(trace.points.size > 10 && trace.saves > 0, "the trace has ${trace.points.size} points and ${trace.saves} saves")

        crashAtEveryStep(trace, { World(HideoutGameInfo, parted()) }, act) { world, _ ->
            world.assertParts(if (world.keys() == reversed) listOf(1, 0) else listOf(0, 1))
        }
    }

    private fun ModLibrary.drop(vararg order: String) = reorder(assertNotNull(state.value.orderView), order.toList())

    @Test
    fun anOrderReturningToTheFirstWhileAnotherAppliesStillReachesTheFiles() = runTest {
        val world = World()
        val library = with(world) { library() }
        val gate = CompletableDeferred<Unit>()
        world.processes.gate = gate
        library.drop(*swap.first.toTypedArray())
        runCurrent()
        library.drop("a", "b", "d", "c")
        runCurrent()
        assertEquals(listOf("a", "b", "d", "c"), library.state.value.orderView?.sets)

        gate.complete(Unit)
        runCurrent()
        assertEquals(ChoiceSync.Settled, library.state.value.choiceSync)
        world.assertWhole(back)
        assertEquals(listOf("a", "b", "d"), world.loadOrder())
        assertEquals(2, world.store.arranges(), "the swap reached the files, so going back is a change of its own")
    }

    @Test
    fun aSwitchToTheSameOrderAndTogglesKeepTheView() = runTest {
        val world = World(info = HideoutGameInfo)
        val library = with(world) { library() }
        val main = library.state.value.profiles!!.active
        val copy = assertIs<MutationResult.Done<ProfileId>>(library.createProfile(NewProfile("Copy", ProfileColour.Blue, ProfileSeed.Copy(main), activate = false))).value
        val kept = assertNotNull(library.state.value.orderView)
        library.switchProfile(copy)
        library.switchProfile(main)
        library.setEnabled("a", false)
        runCurrent()
        library.setEnabled("a", true)
        runCurrent()
        assertSame(kept, library.state.value.orderView, "a profile with the same order and sets turned on or off keep the view")
        library.reorder(kept, cycle.first)
        runCurrent()
        assertNull(library.state.value.choiceProblem)
        assertEquals(listOf("d", "a", "b"), world.loadOrder())
    }

    private fun World.holdNextScanAfterFirstLink(): CompletableDeferred<Unit> {
        val hold = CompletableDeferred<Unit>()
        var armed = true
        watchMounts { point ->
            if (armed && point is FaultPoint.LinkMade) {
                armed = false
                processes.holdNext = hold
            }
        }
        return hold
    }

    @Test
    fun deadlockSeenByTheWatcherMidwayLetsTheRunningChangeEndAndTheLaterOrdersWaitForClosed() = runTest {
        for (seen in listOf(ProcessScan.Seen(game = true), ProcessScan.Unknown("no /proc"))) {
            val world = World()
            val library = with(world) { library() }
            val hold = world.holdNextScanAfterFirstLink()
            library.drop(*swap.first.toTypedArray())
            runCurrent()
            library.drop(*cycle.first.toTypedArray())
            runCurrent()
            val shown = assertNotNull(library.state.value.orderView)

            world.processes.scan = seen
            advanceTimeBy(1.seconds)
            runCurrent()
            assertSame(shown, library.state.value.orderView, "a process change alone keeps the order shown")
            assertEquals(ChoiceSync.Applying, library.state.value.choiceSync, "the swap's files keep their one worker")
            world.processes.scan = ProcessScan.Seen()
            advanceTimeBy(1.seconds)
            runCurrent()
            assertEquals(GameActivity.Closed, library.state.value.deadlock)
            assertTrue(library.state.value.canEditChoices)
            library.drop("a", "b", "d", "c")
            assertNull(library.state.value.choiceProblem)

            world.store.afterStore = { saved -> if (saved.pending == null && world.store.arranges() > 0) world.processes.scan = ProcessScan.Unknown("looked again") }
            hold.complete(Unit)
            runCurrent()
            assertEquals(ChoiceSync.Waiting(GameActivity.Unknown), library.state.value.choiceSync, "it ends with the swap, then the saved order waits")
            world.assertWhole(swap.second)
            assertEquals(1, world.store.arranges())
            assertEquals(listOf("a", "b", "d", "c"), world.manifest().savedChoices?.target?.activeProfile?.orders?.get(root))
            world.store.afterStore = {}
            world.processes.scan = ProcessScan.Seen()
            advanceTimeBy(5.seconds)
            runCurrent()
            world.assertWhole(back)
            assertEquals(2, world.store.arranges())
        }
    }

    @Test
    fun aReorderWhoseFilesMoveWhileTheWatcherSeesDeadlockBlinkKeepsItsDragsView() = runTest {
        val world = World()
        val library = with(world) { library() }
        val hold = world.holdNextScanAfterFirstLink()
        library.drop(*swap.first.toTypedArray())
        runCurrent()
        val gesture = assertNotNull(library.state.value.orderView)
        world.processes.scan = ProcessScan.Seen(game = true)
        advanceTimeBy(1.seconds)
        runCurrent()
        world.processes.scan = ProcessScan.Seen()
        advanceTimeBy(1.seconds)
        runCurrent()
        assertSame(gesture, library.state.value.orderView)

        hold.complete(Unit)
        runCurrent()
        assertEquals(ChoiceSync.Settled, library.state.value.choiceSync, "the swap was already reaching the files, and nothing newer waits")
        world.assertWhole(swap.second)
        assertSame(gesture, library.state.value.orderView)
        library.reorder(gesture, cycle.first)
        runCurrent()
        world.assertWhole(mapOf("a" to 11, "b" to 12, "c" to null, "d" to 9))
        assertEquals(2, world.store.arranges())
    }

    @Test
    fun choicesAskedForWhileAReorderIsPendingAreSavedInOrderAndPhysicalChangesWaitForThem() = runTest {
        val world = World(info = HideoutGameInfo)
        val library = with(world) { library() }
        val main = library.state.value.profiles!!.active
        val copy = assertIs<MutationResult.Done<ProfileId>>(library.createProfile(NewProfile("Copy", ProfileColour.Blue, ProfileSeed.Copy(main), activate = false))).value
        runCurrent()
        val restore = assertNotNull(library.restoreConfirmation())
        val gate = CompletableDeferred<Unit>()
        world.processes.gate = gate
        library.drop(*swap.first.toTypedArray())
        runCurrent()
        library.drop(*cycle.first.toTypedArray())
        val switched = async { library.switchProfile(copy) }
        val renamed = async { library.renameProfile(copy, "Renamed") }
        runCurrent()
        assertTrue(library.state.value.saving)
        assertEquals(copy, library.state.value.shownProfiles?.active)
        assertEquals("Renamed", library.state.value.shownProfiles?.profiles?.last()?.name)
        library.restoreOriginal(restore)
        val reapplied = async { library.reapply() }
        runCurrent()

        gate.complete(Unit)
        runCurrent()
        assertIs<MutationResult.Done<SwitchSummary>>(switched.await())
        assertIs<MutationResult.Done<Unit>>(renamed.await())
        assertNull(reapplied.await())
        assertEquals(Maintenance.Restore(restore, RestoreStage.Restored), library.state.value.maintenance, "the restore waited for the saved choices and then ran")
        assertEquals(back, world.placement(), "the swap reached the files, then Copy's order, the one it was made with")
        assertEquals(copy, world.manifest().profiles.active)
        assertEquals("Renamed", world.manifest().profiles.activeProfile.name)
        assertEquals(cycle.first, world.manifest().profiles.profiles.single { it.id == main }.orders[root], "the drops were Main's")
        assertNull(world.manifest().savedChoices)
        assertEquals(ChoiceSync.Settled, library.state.value.choiceSync)
    }
}
