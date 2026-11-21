package gg.hideouthq.core.library

import gg.hideouthq.core.data.FolderEntry
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlanPlacementTest {
    private val root = GameRoot("/games/Deadlock")
    private val main = ProfileId("main")

    private fun on(first: Int): Placement = Placement.On(Slot(first))

    private fun numbers(values: Set<Int>) = values.mapTo(HashSet(), ::Slot)

    private fun mod(index: Int, files: List<Int>, placement: Placement = Placement.Off, edited: Boolean = false): ManifestRecord {
        val source = InstalledSource.Remote(SubmissionIdentity(SubmissionKind.Mod, 1_000L + index, 2_000L + index, null))
        return ManifestRecord("m$index", root, "Mod $index", placement, files.mapIndexed { set, count ->
            val parts = listOf(VpkPart.Directory) + (0 until count - 1).map(VpkPart::Chunk)
            val images = parts.map { Sha256.image("$index-$set-$it".encodeToByteArray()) }
            InstalledSet(
                source, null, null, SetOrigin.Imported(images.first()), null, "part$set", parts.zip(images).map { (part, image) -> SetFile(part, image) },
                edits = if (edited && set == 0) mapOf(VpkPart.Directory to Sha256.image("edited-$index".encodeToByteArray())) else emptyMap(),
            )
        })
    }

    private fun profile(enabled: Set<String>, order: List<String>) = Profile(main, "Main", ProfileColour.Sage, enabled, mapOf(root to order))

    private class Library(val records: List<ManifestRecord>, val profile: Profile, val taken: Set<Int>, val stuck: Set<String>)

    private fun library(random: Random, mods: Int = 45, folders: Int = 1, editing: Boolean = true): Library {
        val top = folders * PakNumber.PerFolder
        val widths = List(random.nextInt(1, mods)) { List(random.nextInt(1, 4)) { random.nextInt(1, 4) } }
        val edited = if (editing) widths.indices.filter { random.nextInt(8) == 0 }.toSet() else emptySet()
        val placements = arrayOfNulls<Placement>(widths.size)
        var next = 1
        for (index in widths.indices.shuffled(random)) {
            if (random.nextInt(3) == 0) continue
            val first = next + random.nextInt(0, 3)
            val last = first + widths[index].size - 1
            if (last > top || Slot(first).folder != Slot(last).folder) continue
            placements[index] = on(first)
            next = first + widths[index].size
        }
        val records = widths.mapIndexed { index, files -> mod(index, files, placements[index] ?: Placement.Off, edited = index in edited) }
        val ids = records.map { it.installId }
        val order = ids.shuffled(random)
        return Library(
            records,
            profile(ids.filterTo(HashSet()) { random.nextInt(10) < 7 }, order),
            (1..top).filterTo(HashSet()) { random.nextInt(12) == 0 },
            ids.filterTo(HashSet()) { random.nextInt(8) == 0 },
        )
    }

    private fun firsts(records: List<ManifestRecord>) = records.associate { it.installId to it.numbers.firstOrNull()?.value }

    @Test
    fun anEntryTakesItsSlotInWhicheverLoadingFolderItSitsAndOnlyHideoutsOwnPathIsExempt() {
        val past = mod(1, listOf(1), on(104))
        val edited = mod(2, listOf(2), on(110), edited = true)
        fun entries(folder: SetFolder.On, vararg names: String) = names.map { OnEntry(folder, FolderEntry(it, EntryKind.File)) }
        val loading = entries(SetFolder.Addons, "pak05_dir.vpk", "pak11_000.vpk") + entries(SetFolder.On(2), "pak05_dir.vpk", "pak09_dir.vpk", "pak11_000.vpk", "pak11_001.vpk") +
            entries(SetFolder.On(3), "pak01.txt")
        assertEquals(setOf(Slot(5), Slot(11), Slot(108), Slot(199)), foreignNumbers(loading, listOf(past, edited)), "hideout_addons2/pak05_dir.vpk is the mod's, addons/pak05_dir.vpk is not, and the edit's chunk is in its own folder only")
    }

    @Test
    fun aLibraryThatFitsInAddonsGetsTodaysPlanExactly() {
        val random = Random(20261120)
        var fitting = 0
        repeat(600) { trial ->
            val library = library(random)
            val (frozen, frozenPast) = PlannerUpTo99.planPlacement(library.records, library.profile, library.taken, library.stuck)
            if (frozenPast.isNotEmpty()) return@repeat
            fitting++
            val planned = planPlacement(library.records, library.profile, numbers(library.taken), stuck = library.stuck)
            assertEquals(frozen, planned.records.associate { it.installId to (it.placement as? Placement.On)?.first?.value }, "trial $trial")
            assertEquals(emptySet(), planned.pastLimit, "trial $trial")
        }
        assertTrue(fitting > 300, "only $fitting of the libraries fit in 99")
    }

    @Test
    fun aHundredAndFiftySetOrderFillsAddonsAndRunsOnInTheNextFolder() {
        val records = (1..150).map { mod(it, listOf(1)) }
        val order = records.map { it.installId }
        val planned = planPlacement(records, profile(order.toSet(), order), emptySet())
        assertEquals(emptySet(), planned.pastLimit)
        assertEquals(order.zip(1..150).toMap(), firsts(planned.records))
        assertEquals(List(99) { SetFolder.Addons } + List(51) { SetFolder.On(2) }, planned.records.map { it.setFolder() })
        assertEquals("game/citadel/hideout_addons2/pak51_dir.vpk", planned.records.last().let { it.files.single().relativePath(it) })
    }

    @Test
    fun aModTurnedOnBelowNinetyNineSetsTakesTheNextFolderAndRenamesNothingElse() {
        val records = (1..99).map { mod(it, listOf(1), on(it)) } + mod(100, listOf(2))
        val order = records.map { it.installId }
        val planned = planPlacement(records, profile(order.toSet(), order), emptySet())
        assertEquals(listOf(Move(records.last(), records.last().placed(on(100)))), movesBetween(records, planned.records))
    }

    @Test
    fun aLibraryPastAddonsThatShrinksToNinetyNineSetsGoesBackToAddons() {
        val records = (1..100).map { mod(it, listOf(1), on(it)) }
        val order = records.map { it.installId }
        val planned = planPlacement(records, profile(order.toSet() - "m50", order), emptySet())
        assertEquals((1..100).associate { "m$it" to if (it == 50) null else if (it > 50) it - 1 else it }, firsts(planned.records))
        assertEquals(0, planned.records.overflowIn(root))
    }

    @Test
    fun aLibraryWhollyInTheNextFolderThatFitsInAddonsMovesEverySetIntoAddonsOntoNamesNoOtherSetHolds() {
        val records = (1..30).map { mod(it, listOf(1, 2), on(98 + 2 * it)) } + (31..63).map { mod(it, listOf(1), on(168 + it)) }
        val order = records.map { it.installId }
        val taken = numbers(setOf(7, 40))
        val planned = planPlacement(records, profile(order.toSet(), order), taken)
        assertEquals(emptySet(), planned.pastLimit)
        assertTrue(records.sumOf { it.sets.size } <= PakNumber.PerFolder - taken.size, "the library fits in addons")
        assertEquals(setOf(SetFolder.On(2), SetFolder.On(3)), records.map { it.setFolder() }.toSet(), "the library starts past addons")
        assertEquals(order, onLoadOrder(planned.records))
        assertTrue(planned.records.all { it.setFolder() == SetFolder.Addons }, "every set is in addons")
        assertTrue(planned.records.flatMap { it.numbers }.none { it in taken })
        val moves = movesBetween(records, planned.records)
        assertEquals(records.size, moves.size)
        val held = records.flatMap { record -> record.files.map { it.relativePath(record) } }.toSet()
        assertTrue(moves.flatMap { move -> move.moving.flatten().map { (_, to) -> to.relativePath(move.target) } }.none { it in held }, "no set lands on a name a set holds")
        assertEquals(planned.records, planPlacement(planned.records, profile(order.toSet(), order), taken).records, "planning again moves nothing")
    }

    @Test
    fun aStuckModPastAddonsKeepsTheLibraryInItsFolders() {
        val records = (1..99).map { mod(it, listOf(1), on(it)) } + mod(100, listOf(1), on(100))
        val order = records.map { it.installId }
        val planned = planPlacement(records, profile(order.toSet() - "m50", order), emptySet(), stuck = setOf("m100"))
        assertEquals(listOf(Move(records[49], records[49].placed(Placement.Off))), movesBetween(records, planned.records), "m100's files can't be moved, so nothing renames down")
    }

    @Test
    fun aModWithMoreSetsThanAFolderHoldsStaysOffAndTheModsBelowItLoad() {
        val records = listOf(mod(1, List(100) { 1 })) + (2..3).map { mod(it, listOf(1)) }
        val order = records.map { it.installId }
        val planned = planPlacement(records, profile(order.toSet(), order), emptySet())
        assertEquals(setOf("m1"), planned.pastLimit)
        assertEquals(mapOf("m1" to null, "m2" to 1, "m3" to 2), firsts(planned.records))
    }

    @Test
    fun everyModALibraryPastAddonsTurnsOnLoadsInItsOrderAndPlanningAgainMovesNothing() {
        val random = Random(20261121)
        var past = 0
        repeat(150) { trial ->
            val library = library(random, mods = 120, folders = 4, editing = false)
            val taken = numbers(library.taken)
            val planned = planPlacement(library.records, library.profile, taken, stuck = library.stuck)
            assertEquals(emptySet(), planned.pastLimit, "trial $trial")
            assertEquals(library.profile.onOrder(root), onLoadOrder(planned.records), "trial $trial")
            assertTrue(planned.records.flatMap { it.numbers }.none { it in taken }, "trial $trial")
            assertEquals(planned.records, planPlacement(planned.records, library.profile, taken, stuck = library.stuck).records, "trial $trial")
            if (planned.records.any { record -> record.numbers.any { it.folder != SetFolder.Addons } }) past++
        }
        assertTrue(past > 50, "only $past of the libraries ran past addons")
    }

    /** The planner for `addons` alone, the oracle for libraries that fit there. Not to change with the planner. */
    private object PlannerUpTo99 {
        private const val Top = 99
        private const val Unplaced = Long.MAX_VALUE
        private const val StuckRename = 1 shl 20

        class Block(val held: List<Int?>, val files: List<Int>, val stuck: Boolean, val fixed: Boolean) {
            val width: Int get() = held.size
        }

        fun planNumbers(blocks: List<Block>, taken: Set<Int>): List<Int>? {
            if (blocks.isEmpty()) return emptyList()
            val free = IntArray(Top + 2)
            for (first in Top downTo 1) free[first] = if (first in taken) 0 else free[first + 1] + 1

            fun own(block: Block, first: Int): Long? {
                if (free[first] < block.width || block.fixed && block.held.first() != first) return null
                val renamed = block.held.indices.sumOf { set -> if (block.held[set] == first + set) 0 else if (block.stuck) StuckRename else block.files[set] }
                return (renamed.toLong() shl 32) + first
            }

            val best = Array(blocks.size) { LongArray(Top + 1) { Unplaced } }
            val previous = Array(blocks.size) { IntArray(Top + 1) }
            for (first in 1..Top) own(blocks[0], first)?.let { best[0][first] = it }
            for (index in 1 until blocks.size) {
                var cheapest = Unplaced
                var cheapestAt = 0
                for (first in 1..Top) {
                    val before = first - blocks[index - 1].width
                    if (before >= 1 && best[index - 1][before] < cheapest) {
                        cheapest = best[index - 1][before]
                        cheapestAt = before
                    }
                    if (cheapest == Unplaced) continue
                    val cost = own(blocks[index], first) ?: continue
                    best[index][first] = cheapest + cost
                    previous[index][first] = cheapestAt
                }
            }
            var first = (1..Top).filter { best.last()[it] != Unplaced }.minByOrNull { best.last()[it] } ?: return null
            val firsts = IntArray(blocks.size)
            for (index in blocks.indices.reversed()) {
                firsts[index] = first
                first = previous[index][first]
            }
            return firsts.toList()
        }

        fun planPlacement(records: List<ManifestRecord>, profile: Profile, taken: Set<Int>, stuck: Set<String>): Pair<Map<String, Int?>, Set<String>> {
            val root = records.first().gameRoot
            val byId = records.associateBy { it.installId }
            val edited = records.filter { it.edited }
            val wanted = profile.onOrder(root).mapNotNull(byId::get)
            val inOrder = wanted.filter { !it.edited || it.enabled }
            val around = edited - inOrder.toSet()
            val room = Top - edited.sumOf { it.numbers.size }
            var width = 0
            val most = inOrder.indexOfFirst { record -> !record.edited && (width + record.sets.size).also { width = it } > room }.takeIf { it >= 0 } ?: inOrder.size
            fun block(record: ManifestRecord) = Block(record.sets.map { record.numberOf(it)?.value }, record.sets.map { it.files.size }, record.installId in stuck, record.edited)
            val (kept, placed) = (most downTo 0).firstNotNullOf { fits ->
                val kept = inOrder.take(fits) + inOrder.drop(fits).filter { it.edited }
                placeAround(kept, around, taken, ::block)?.let { kept to it }
            }
            val firsts = records.associate { it.installId to (placed[it.installId] ?: if (it.edited) it.numbers.firstOrNull()?.value else null) }
            return firsts to (inOrder - kept.toSet()).mapTo(LinkedHashSet()) { it.installId }
        }

        private fun placeAround(on: List<ManifestRecord>, around: List<ManifestRecord>, taken: Set<Int>, block: (ManifestRecord) -> Block): Map<String, Int>? {
            val blocks = on.associateWith(block)
            fun place(on: List<ManifestRecord>, around: List<ManifestRecord>) = planNumbers(on.map(blocks::getValue), taken + around.flatMap { record -> record.numbers.map { it.value } })
                ?.let { firsts -> on.map { it.installId }.zip(firsts).toMap() }
            val fixed = on.filter { blocks.getValue(it).fixed }
            return place(on, around) ?: place(on - fixed.toSet(), around + fixed)
        }
    }
}
