package gg.hideouthq.core.library

import gg.hideouthq.core.data.FolderEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HeldModsTest {
    private val root = GameRoot("/games/Deadlock")
    private val main = ProfileId("main")

    private fun mod(id: String, number: Int?, sets: Int = 1, edited: Boolean = false): ManifestRecord {
        val source = InstalledSource.Local(id, "$id.zip")
        return ManifestRecord(id, root, id, placedAt(number), (0 until sets).map { set ->
            val image = FileImage(10L + set, (id.first().code % 16).toString(16).repeat(63) + set)
            InstalledSet(source, null, null, SetOrigin.Imported(image), null, "part$set", listOf(directory(image)), edits = if (edited) mapOf(VpkPart.Directory to FileImage(99, "e".repeat(64))) else emptyMap())
        })
    }

    private fun profile(enabled: Set<String>, order: List<String>) = Profile(main, "Main", ProfileColour.Sage, enabled, mapOf(root to order))

    private fun numbers(planned: Planned) = planned.records.associate { it.installId to it.numbers.firstOrNull()?.value }

    @Test
    fun anEditedModKeepsItsPlaceInTheOrderWhenTheOthersFitAroundIt() {
        val records = listOf(mod("a", 1), mod("c", null), mod("h", 3, edited = true), mod("b", 5))
        val planned = planPlacement(records, profile(setOf("a", "c", "h", "b"), listOf("a", "c", "h", "b")), emptySet())
        assertEquals(mapOf("a" to 1, "c" to 2, "h" to 3, "b" to 5), numbers(planned), "c turned on between a and the edited mod takes 02")
    }

    @Test
    fun whenTheOthersCannotFitBeforeItTheyAreNumberedAroundItInTheirOrder() {
        val records = listOf(mod("a", null, sets = 3), mod("b", 1), mod("h", 2, edited = true), mod("c", 4))
        val planned = planPlacement(records, profile(setOf("a", "b", "h", "c"), listOf("a", "b", "h", "c")), emptySet())
        assertEquals(mapOf("a" to 3, "b" to 6, "h" to 2, "c" to 7), numbers(planned), "a's three sets cannot load before 02, so a, b and c go after it, still in their order")
    }

    @Test
    fun theOrderRunsOnPastAnEditedModIntoTheNextFolder() {
        val many = (1..99).map { mod("m$it", null) }
        val records = many + mod("h", 50, edited = true)
        val order = many.take(49).map { it.installId } + "h" + many.drop(49).map { it.installId }
        val planned = planPlacement(records, profile(order.toSet(), order), emptySet())
        assertEquals(emptySet(), planned.pastLimit)
        assertEquals((1..49).associate { "m$it" to it } + ("h" to 50) + (50..99).associate { "m$it" to it + 1 }, numbers(planned), "the edited mod keeps its number and m99 takes the first of hideout_addons2")
    }

    @Test
    fun modsThatFitAroundAnEditedModInAddonsStayThereRatherThanOpenTheNextFolder() {
        val after = (1..20).map { mod("m$it", null) }
        val records = listOf(mod("h", 90, edited = true)) + after
        val order = records.map { it.installId }
        val planned = planPlacement(records, profile(order.toSet(), order), emptySet())
        assertEquals(mapOf("h" to 90) + (1..20).associate { "m$it" to it }, numbers(planned), "nine numbers follow 90, so the twenty go around it as they always have")
    }

    @Test
    fun aChunkBesideASetTheUserEditedIsThatEditsAndTakesNoNumberOfAnotherMod() {
        val records = listOf(mod("h", 6, edited = true), mod("a", 7))
        val addons = listOf("pak06_dir.vpk", "pak06_000.vpk", "pak07_dir.vpk", "pak07_000.vpk", "pak09_dir.vpk")
        assertEquals(setOf(Slot(7), Slot(9)), foreignNumbers(addons.map { OnEntry(SetFolder.Addons, FolderEntry(it, EntryKind.File)) }, records), "a chunk beside a set nobody edited is another tool's")
    }

    @Test
    fun anEditedModStaysOnOrOffWhateverTheProfileSays() {
        val records = listOf(mod("h", 2, edited = true), mod("g", null, edited = true), mod("a", 1))
        val planned = planPlacement(records, profile(setOf("a", "g"), listOf("g", "h", "a")), emptySet())
        assertEquals(records.take(2), planned.records.take(2), "both keep their placement and every file")
        assertEquals(mapOf("h" to 2, "g" to null, "a" to 1), numbers(planned))
    }

    @Test
    fun theAppliedBookFollowsTheFilesAndReachesTheSavedOneButForTheEditedMods() {
        val records = listOf(mod("a", 3), mod("h", 2, edited = true), mod("b", 4), mod("x", null))
        val target = ProfileBook(main, listOf(profile(setOf("a", "b", "x"), listOf("x", "a", "h", "b")), Profile(ProfileId("other"), "Other", ProfileColour.Blue, setOf("h"), mapOf(root to listOf("h", "a", "b", "x")))))
        val held = setOf("h")
        val placed = records.map { if (it.installId == "x") it.placed(Placement.On(Slot(1))) else it }
        val applied = target.holding(placed)
        assertTrue(applied.follows(placed))
        assertEquals(listOf("x", "h", "a", "b"), applied.activeProfile.orders.getValue(root), "the held mod takes the place its number gives it")
        assertEquals(setOf("a", "b", "x", "h"), applied.activeProfile.enabled, "and stays on, as its files are")
        assertEquals(target.profiles.last(), applied.profiles.last(), "every other profile is the saved one")
        assertTrue(applied.reaches(target, held))
        assertFalse(applied.reaches(target, emptySet()))
        assertFalse(ProfileBook.main(records).reaches(target, held), "the others are not where the saved book wants them")
    }
}
