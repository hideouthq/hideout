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

    private fun plan(blocks: List<Block>, taken: Set<Slot>, folders: Int = 1) = planNumbers(blocks, taken, folders)

    @Test
    fun noBlocksTakeNoNumbers() {
        assertEquals(emptyList(), plan(emptyList(), emptySet()))
    }

    @Test
    fun aLoneModInAnEmptyGameLandsAt99() {
        assertEquals(numbers(99), plan(listOf(fresh()), emptySet()))
        assertEquals(numbers(97), plan(listOf(fresh(sets = 3)), emptySet()))
    }

    @Test
    fun eachModInstalledOnTopRenamesNoOtherUntilEveryNumberIsTaken() {
        var held = emptyList<Slot>()
        repeat(99) { installed ->
            val planned = plan(listOf(fresh()) + held.map { held(it.value) }, emptySet())
            assertEquals(held, planned?.drop(1), "install ${installed + 1} keeps every number the ones below it hold")
            held = planned!!
        }
        assertEquals((1..99).map(::Slot), held)
        assertNull(plan(listOf(fresh()) + held.map { held(it.value) }, emptySet()), "a hundredth does not fit in addons")
    }

    @Test
    fun aLibraryPackedFrom01MovesOnceForTheFirstModOnTopAndThenRenamesNothing() {
        val packed = (1..26).map { held(it) }
        assertEquals((1..26).map(::Slot), plan(packed, emptySet()), "as it is, it keeps every number")
        val first = plan(listOf(fresh()) + packed, emptySet())!!
        assertEquals((73..99).map(::Slot), first, "nothing is free ahead of 01, so the 26 move to the end once and the new mod takes 73")
        assertEquals(listOf(Slot(72)) + first, plan(listOf(fresh()) + first.map { held(it.value) }, emptySet()), "the next takes 72 and renames nothing")
    }

    @Test
    fun modsTurnedOnBetweenTenAndTwentyTakeTheHighestNumbersBeforeTwentyAndRenameNothingElse() {
        assertEquals(numbers(10, 19, 20), plan(listOf(held(10), fresh(), held(20)), emptySet()))
        assertEquals(numbers(10, 18, 19, 20), plan(listOf(held(10), fresh(), fresh(), held(20)), emptySet()))
    }

    @Test
    fun aModTurnedOnBetweenAdjacentNeighboursRenamesOneOfThemAndTakesTheHighestNumbers() {
        val lockin = listOf(held(6), held(7), held(11), held(14), held(46, 47))
        assertEquals(numbers(6, 7, 10, 11, 14, 46), plan(lockin.take(2) + fresh() + lockin.drop(2), emptySet()), "between 07 and 11 it takes 10")
        assertEquals(numbers(6, 9, 10, 11, 14, 46), plan(lockin.take(1) + fresh() + lockin.drop(1), emptySet()), "between 06 and 07 the 07 mod goes to 10")
        assertEquals(numbers(5, 6, 7, 11, 14, 46), plan(listOf(fresh()) + lockin, emptySet()), "a new mod at the top takes 05")
        assertEquals(numbers(4, 5, 6, 7, 11, 14, 46), plan(listOf(fresh(), held(1)) + lockin, emptySet()), "a new mod above one at 01 takes 04 and moves it to 05")
        assertEquals(numbers(4, 6, 7, 11, 14), plan(listOf(held(46, 47)) + lockin.dropLast(1), emptySet()), "the 46-47 mod dragged to the top takes 04-05")
    }

    @Test
    fun aStuckModKeepsItsNumberWhileAnyPlanCanAndMovesWhenNoneCan() {
        val lockin = listOf(held(6, stuck = true), held(7), held(11), held(14), held(46, 47))
        assertEquals(numbers(6, 9, 10, 11, 14, 46), plan(lockin.take(1) + fresh() + lockin.drop(1), emptySet()), "between 06 and 07 the 07 mod moves down")
        assertEquals(numbers(6, 9, 10, 11, 14, 46), plan(lockin.take(1) + fresh() + held(7, files = listOf(40)) + lockin.drop(2), emptySet()), "however many files the other mod has")
        assertEquals(numbers(98, 99), plan(listOf(fresh(), held(1, stuck = true)), emptySet()), "a mod above one stuck at 01 moves it")
    }

    @Test
    fun aFixedModNeverMovesAndAPlanThatWouldMoveItDoesNotFit() {
        val lockin = listOf(held(6, fixed = true), held(7), held(11))
        assertEquals(numbers(6, 9, 10, 11), plan(lockin.take(1) + fresh() + lockin.drop(1), emptySet()), "between 06 and 07 the 07 mod moves down")
        assertEquals(numbers(5, 6, 7, 11), plan(listOf(fresh()) + lockin, emptySet()))
        assertNull(plan(List(6) { fresh() } + lockin, emptySet()), "six mods cannot load before the one fixed at 06")
        assertNull(plan(listOf(held(1, fixed = true), held(2, fixed = true)).reversed(), emptySet()), "nor can two fixed ones swap")
    }

    @Test
    fun modsHeldInOrderKeepEveryNumberTheyHold() {
        val blocks = listOf(held(3), held(5, 6), held(9), held(40), held(99))
        assertEquals(numbers(3, 5, 9, 40, 99), plan(blocks, emptySet()))
    }

    @Test
    fun draggingTheModAt30AboveTheModsAt10And20RenamesOnlyIt() {
        assertEquals(numbers(9, 10, 20), plan(listOf(held(30), held(10), held(20)), emptySet()))
    }

    @Test
    fun takenNumbersAreSkippedAndAModHoldingOneMoves() {
        assertEquals(numbers(3, 98), plan(listOf(held(3), fresh()), numbers(1, 2, 4, 99).toSet()))
        assertEquals(numbers(99), plan(listOf(held(3)), numbers(3).toSet()))
        assertEquals(numbers(98), plan(listOf(held(3, 4)), numbers(4).toSet()))
        assertEquals(numbers(97), plan(listOf(held(3, 4)), numbers(4, 99).toSet()))
    }

    @Test
    fun aWidthReservesNumbersPastTheModsSets() {
        assertEquals(numbers(5, 8), plan(listOf(held(5, width = 2), held(8)), emptySet()), "05-06 are free for the wider mod")
        assertEquals(numbers(5, 99), plan(listOf(held(5, width = 3), held(7)), emptySet()), "its neighbour moves to 99 rather than the wider mod to 04")
        assertNull(plan(listOf(fresh(width = 100)), emptySet()))
    }

    @Test
    fun aModOfSeveralSetsPaysForEachSetsFilesAndAReorderedOnePaysOnlyForTheSetsThatMove() {
        assertEquals(numbers(5, 97, 98), plan(listOf(held(5, files = listOf(12)), fresh(), held(6, 7, files = listOf(1, 10))), emptySet()), "11 files of its own move rather than 12 above it")
        assertEquals(numbers(4, 5, 6), plan(listOf(held(5, files = listOf(12)), fresh(), held(6, 7, files = listOf(1, 12))), emptySet()), "12 files above move rather than its 13")
        assertEquals(numbers(10), plan(listOf(held(10, 12, 11)), numbers(9, 13).toSet()), "two of three sets swap in place rather than all three moving lower")
    }

    @Test
    fun ninetyNineModsFillEveryNumberAndAHundredthDoesNotFit() {
        assertEquals((1..99).map(::Slot), plan(List(99) { fresh() }, emptySet()))
        assertNull(plan(List(100) { fresh() }, emptySet()))
    }

    @Test
    fun aHundredModsInTwoFoldersTakeTheLastNumberOfAddonsAndEveryNumberOfTheNext() {
        assertEquals((99..198).map(::Slot), plan(List(100) { fresh() }, emptySet(), folders = 2))
    }

    @Test
    fun aModsSetsNeverStraddleTwoFolders() {
        fun freeOnly(free: IntRange) = (1..198).filter { it !in free }.map(::Slot).toSet()
        assertEquals(numbers(97), plan(listOf(fresh(sets = 3)), freeOnly(97..101), folders = 2), "97 to 99 end addons, as 98 to 100 and 99 to 101 would cross into the next")
        assertEquals(numbers(100), plan(listOf(fresh(sets = 3)), freeOnly(98..102), folders = 2), "98 and 99 are too few, so it starts the next folder")
        assertEquals(numbers(98, 101), plan(listOf(fresh(sets = 2), fresh(sets = 2)), freeOnly(97..102), folders = 2))
        assertNull(plan(listOf(held(98, 99, 100, fixed = true)), emptySet(), folders = 2), "a mod fixed across the end of addons fits nowhere")
        assertNull(plan(listOf(fresh(width = 100)), emptySet(), folders = 10), "no folder holds a hundred")
    }

    @Test
    fun foreignNumbersLeavingNoRunWideEnoughGiveNoPlan() {
        val everyThird = (3..99 step 3).map(::Slot).toSet()
        assertNull(plan(listOf(fresh(sets = 3)), everyThird))
        assertEquals(numbers(97), plan(listOf(fresh(sets = 2)), everyThird))
    }

    @Test
    fun thePlanIsTheCheapestOfEveryPossiblePlanForUpToThreeMods() {
        val random = Random(20261027)
        repeat(300) { trial ->
            val blocks = blocks(random, held = 1..99)
            val taken = (1..99).filter { random.nextInt(4) == 0 }.map(::Slot).toSet()
            assertEquals(cheapest(blocks, taken, folders = 1), plan(blocks, taken), "trial $trial")
        }
    }

    @Test
    fun aPlanAcrossTheEndOfAddonsIsTheCheapestOfEveryPossiblePlan() {
        val random = Random(20261120)
        repeat(300) { trial ->
            val blocks = blocks(random, held = 85..115)
            val taken = (1..198).filter { it !in 80..120 || random.nextInt(4) == 0 }.map(::Slot).toSet()
            assertEquals(cheapest(blocks, taken, folders = 2), plan(blocks, taken, folders = 2), "trial $trial")
        }
    }

    private fun blocks(random: Random, held: IntRange): List<Block> = List(random.nextInt(1, 4)) {
        val sets = random.nextInt(1, 4)
        Block(
            held = List(sets) { if (random.nextInt(3) == 0) null else Slot(random.nextInt(held.first, held.last + 1)) },
            files = List(sets) { random.nextInt(1, 5) },
            width = sets + random.nextInt(0, 2),
            stuck = random.nextInt(4) == 0,
        )
    }

    private fun cheapest(blocks: List<Block>, taken: Set<Slot>, folders: Int): List<Slot>? {
        val top = folders * 99
        fun fits(block: Block, first: Int) =
            first + block.width - 1 <= top && (first - 1) / 99 == (first + block.width - 2) / 99 && (first until first + block.width).none { Slot(it) in taken }
        fun moved(block: Block, first: Int) = block.held.indices.filter { set -> block.held[set] != Slot(first + set) }
        fun stuckMoved(block: Block, first: Int) = if (block.stuck) moved(block, first).size else 0
        fun renamed(block: Block, first: Int) = if (block.stuck) 0 else moved(block, first).sumOf { block.files[it] }
        var found: Pair<List<Int>, List<Int>>? = null
        fun visit(placed: List<Int>) {
            if (placed.size == blocks.size) {
                val cost = listOf(placed.indices.sumOf { stuckMoved(blocks[it], placed[it]) }, placed.indices.sumOf { renamed(blocks[it], placed[it]) }, -placed.sum()) + placed.reversed().map { -it }
                if (found == null || compareLists(cost, found!!.first) < 0) found = cost to placed
                return
            }
            val from = placed.lastOrNull()?.let { it + blocks[placed.size - 1].width } ?: 1
            for (first in from..top) if (fits(blocks[placed.size], first)) visit(placed + first)
        }
        visit(emptyList())
        return found?.second?.map(::Slot)
    }

    private fun compareLists(a: List<Int>, b: List<Int>): Int = a.zip(b).firstOrNull { (x, y) -> x != y }?.let { (x, y) -> x.compareTo(y) } ?: 0
}
