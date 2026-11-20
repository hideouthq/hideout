package gg.hideouthq.core.library

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlanPlacementTest {
    private val root = GameRoot("/games/Deadlock")
    private val main = ProfileId("main")

    private fun on(first: Int): Placement = Placement.On(PakNumber(first))

    private fun numbers(values: Set<Int>) = values.mapTo(HashSet(), ::PakNumber)

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

    private fun library(random: Random): Library {
        val widths = List(random.nextInt(1, 45)) { List(random.nextInt(1, 4)) { random.nextInt(1, 4) } }
        val edited = widths.indices.filter { random.nextInt(8) == 0 }.toSet()
        val placements = arrayOfNulls<Placement>(widths.size)
        var next = 1
        for (index in widths.indices.shuffled(random)) {
            if (random.nextInt(3) == 0) continue
            val first = next + random.nextInt(0, 3)
            if (first + widths[index].size - 1 > 99) continue
            placements[index] = on(first)
            next = first + widths[index].size
        }
        val records = widths.mapIndexed { index, files -> mod(index, files, placements[index] ?: Placement.Off, edited = index in edited) }
        val ids = records.map { it.installId }
        val order = ids.shuffled(random)
        return Library(
            records,
            profile(ids.filterTo(HashSet()) { random.nextInt(10) < 7 }, order),
            (1..99).filterTo(HashSet()) { random.nextInt(12) == 0 },
            ids.filterTo(HashSet()) { random.nextInt(8) == 0 },
        )
    }

    @Test
    fun aLibraryThatFitsInAddonsGetsTodaysPlanExactly() {
        val random = Random(20261120)
        var fitting = 0
        repeat(600) { trial ->
            val library = library(random)
            val (frozen, frozenPast) = Frozen.planPlacement(library.records, library.profile, library.taken, library.stuck)
            if (frozenPast.isNotEmpty()) return@repeat
            fitting++
            val planned = planPlacement(library.records, library.profile, numbers(library.taken), stuck = library.stuck)
            assertEquals(frozen, planned.records.associate { it.installId to (it.placement as? Placement.On)?.first?.value }, "trial $trial")
            assertEquals(emptySet(), planned.pastLimit, "trial $trial")
        }
        assertTrue(fitting > 300, "only $fitting of the libraries fit in 99")
    }

    /** The planner as it was before Hideout loaded sets past `addons`, over plain numbers. Not to be changed with the planner. */
    private object Frozen {
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

        /** By install id, the first number each mod is placed on at, null for off; and the mods past the limit. */
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
