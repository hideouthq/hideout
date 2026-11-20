package gg.hideouthq.core.library

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlanNumbersTest {
    private fun numbers(vararg values: Int) = values.map(::Slot)

    private fun held(vararg numbers: Int?, files: List<Int> = numbers.map { 1 }, width: Int = numbers.size, stuck: Boolean = false, fixed: Boolean = false) =
        Block(numbers.map { it?.let(::Slot) }, files, width, stuck, fixed)

    private fun fresh(sets: Int = 1, files: Int = 1, width: Int = sets) = Block(List(sets) { null }, List(sets) { files }, width)

    @Test
    fun noBlocksTakeNoNumbers() {
        assertEquals(emptyList(), planNumbers(emptyList(), emptySet()))
    }

    @Test
    fun aLoneModInAnEmptyGameLandsAt01() {
        assertEquals(numbers(1), planNumbers(listOf(fresh()), emptySet()))
        assertEquals(numbers(1), planNumbers(listOf(fresh(sets = 3)), emptySet()))
    }

    @Test
    fun modsTurnedOnBetweenTenAndTwentyTakeTheLowestNumbersAfterTenAndRenameNothingElse() {
        assertEquals(numbers(10, 11, 20), planNumbers(listOf(held(10), fresh(), held(20)), emptySet()))
        assertEquals(numbers(10, 11, 12, 20), planNumbers(listOf(held(10), fresh(), fresh(), held(20)), emptySet()))
    }

    @Test
    fun aModTurnedOnBetweenAdjacentNeighboursRenamesOneOfThemAndTakesTheLowestNumbers() {
        val lockin = listOf(held(6), held(7), held(11), held(14), held(46, 47))
        assertEquals(numbers(6, 7, 8, 11, 14, 46), planNumbers(lockin.take(2) + fresh() + lockin.drop(2), emptySet()), "between 07 and 11 it takes 08")
        assertEquals(numbers(1, 2, 7, 11, 14, 46), planNumbers(lockin.take(1) + fresh() + lockin.drop(1), emptySet()), "between 06 and 07 the 06 mod goes to 01")
        assertEquals(numbers(1, 6, 7, 11, 14, 46), planNumbers(listOf(fresh()) + lockin, emptySet()), "a new mod at the top takes 01")
        assertEquals(numbers(1, 2, 6, 7, 11, 14, 46), planNumbers(listOf(fresh(), held(1)) + lockin, emptySet()), "a second new mod takes 01 and the first moves to 02")
        assertEquals(numbers(1, 6, 7, 11, 14), planNumbers(listOf(held(46, 47)) + lockin.dropLast(1), emptySet()), "the 46-47 mod dragged to the top takes 01-02")
    }

    @Test
    fun aStuckModKeepsItsNumberWhileAnyPlanCanAndMovesWhenNoneCan() {
        val lockin = listOf(held(6, stuck = true), held(7), held(11), held(14), held(46, 47))
        assertEquals(numbers(6, 7, 8, 11, 14, 46), planNumbers(lockin.take(1) + fresh() + lockin.drop(1), emptySet()), "between 06 and 07 the 07 mod moves up")
        assertEquals(numbers(6, 7, 8, 11, 14, 46), planNumbers(lockin.take(1) + held(7, files = listOf(40)) + fresh() + lockin.drop(2), emptySet()), "however many files the other mod has")
        assertEquals(numbers(1, 2), planNumbers(listOf(fresh(), held(1, stuck = true)), emptySet()), "a mod above one stuck at 01 moves it")
    }

    @Test
    fun aFixedModNeverMovesAndAPlanThatWouldMoveItDoesNotFit() {
        val lockin = listOf(held(6, fixed = true), held(7), held(11))
        assertEquals(numbers(6, 7, 8, 11), planNumbers(lockin.take(1) + fresh() + lockin.drop(1), emptySet()), "between 06 and 07 the 07 mod moves up")
        assertEquals(numbers(1, 6, 7, 11), planNumbers(listOf(fresh()) + lockin, emptySet()))
        assertNull(planNumbers(List(6) { fresh() } + lockin, emptySet()), "six mods cannot load before the one fixed at 06")
        assertNull(planNumbers(listOf(held(1, fixed = true), held(2, fixed = true)).reversed(), emptySet()), "nor can two fixed ones swap")
    }

    @Test
    fun modsHeldInOrderKeepEveryNumberTheyHold() {
        val blocks = listOf(held(3), held(5, 6), held(9), held(40), held(99))
        assertEquals(numbers(3, 5, 9, 40, 99), planNumbers(blocks, emptySet()))
    }

    @Test
    fun draggingTheModAt30AboveTheModsAt10And20RenamesOnlyIt() {
        assertEquals(numbers(1, 10, 20), planNumbers(listOf(held(30), held(10), held(20)), emptySet()))
    }

    @Test
    fun takenNumbersAreSkippedAndAModHoldingOneMoves() {
        assertEquals(numbers(3, 5), planNumbers(listOf(held(3), fresh()), numbers(1, 2, 4).toSet()))
        assertEquals(numbers(1), planNumbers(listOf(held(3)), numbers(3).toSet()))
        assertEquals(numbers(1), planNumbers(listOf(held(3, 4)), numbers(4).toSet()))
        assertEquals(numbers(2), planNumbers(listOf(held(3, 4)), numbers(1, 4).toSet()))
    }

    @Test
    fun aWidthReservesNumbersPastTheModsSets() {
        assertEquals(numbers(5, 8), planNumbers(listOf(held(5, width = 2), held(8)), emptySet()), "05-06 are free for the wider mod")
        assertEquals(numbers(1, 7), planNumbers(listOf(held(5, width = 3), held(7)), emptySet()), "the wider mod moves down rather than its neighbour up")
        assertNull(planNumbers(listOf(fresh(width = 100)), emptySet()))
    }

    @Test
    fun aModOfSeveralSetsPaysForEachSetsFilesAndAReorderedOnePaysOnlyForTheSetsThatMove() {
        assertEquals(numbers(5, 6, 7), planNumbers(listOf(held(5, files = listOf(12)), fresh(), held(6, 7, files = listOf(1, 10))), emptySet()), "11 files of its own move rather than 12 above it")
        assertEquals(numbers(1, 2, 6), planNumbers(listOf(held(5, files = listOf(12)), fresh(), held(6, 7, files = listOf(1, 12))), emptySet()), "12 files above move rather than its 13")
        assertEquals(numbers(10), planNumbers(listOf(held(10, 12, 11)), numbers(9, 13).toSet()), "two of three sets swap in place rather than all three moving lower")
    }

    @Test
    fun ninetyNineModsFillEveryNumberAndAHundredthDoesNotFit() {
        assertEquals((1..99).map(::Slot), planNumbers(List(99) { fresh() }, emptySet()))
        assertNull(planNumbers(List(100) { fresh() }, emptySet()))
    }

    @Test
    fun foreignNumbersLeavingNoRunWideEnoughGiveNoPlan() {
        val everyThird = (3..99 step 3).map(::Slot).toSet()
        assertNull(planNumbers(listOf(fresh(sets = 3)), everyThird))
        assertEquals(numbers(1), planNumbers(listOf(fresh(sets = 2)), everyThird))
    }

    @Test
    fun thePlanIsTheCheapestOfEveryPossiblePlanForUpToThreeMods() {
        val random = Random(20261027)
        repeat(300) { trial ->
            val blocks = List(random.nextInt(1, 4)) {
                val sets = random.nextInt(1, 4)
                Block(
                    held = List(sets) { if (random.nextInt(3) == 0) null else Slot(random.nextInt(1, 100)) },
                    files = List(sets) { random.nextInt(1, 5) },
                    width = sets + random.nextInt(0, 2),
                    stuck = random.nextInt(4) == 0,
                )
            }
            val taken = (1..99).filter { random.nextInt(4) == 0 }.map(::Slot).toSet()
            assertEquals(cheapest(blocks, taken), planNumbers(blocks, taken), "trial $trial")
        }
    }

    private fun cheapest(blocks: List<Block>, taken: Set<Slot>): List<Slot>? {
        fun fits(block: Block, first: Int) = first + block.width - 1 <= 99 && (first until first + block.width).none { Slot(it) in taken }
        fun moved(block: Block, first: Int) = block.held.indices.filter { set -> block.held[set] != Slot(first + set) }
        fun stuckMoved(block: Block, first: Int) = if (block.stuck) moved(block, first).size else 0
        fun renamed(block: Block, first: Int) = if (block.stuck) 0 else moved(block, first).sumOf { block.files[it] }
        var found: Pair<List<Int>, List<Int>>? = null
        fun visit(placed: List<Int>) {
            if (placed.size == blocks.size) {
                val cost = listOf(placed.indices.sumOf { stuckMoved(blocks[it], placed[it]) }, placed.indices.sumOf { renamed(blocks[it], placed[it]) }, placed.sum()) + placed.reversed()
                if (found == null || compareLists(cost, found!!.first) < 0) found = cost to placed
                return
            }
            val from = placed.lastOrNull()?.let { it + blocks[placed.size - 1].width } ?: 1
            for (first in from..99) if (fits(blocks[placed.size], first)) visit(placed + first)
        }
        visit(emptyList())
        return found?.second?.map(::Slot)
    }

    private fun compareLists(a: List<Int>, b: List<Int>): Int = a.zip(b).firstOrNull { (x, y) -> x != y }?.let { (x, y) -> x.compareTo(y) } ?: 0
}
