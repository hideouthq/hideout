package gg.hideouthq.core.customgames

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModeListingTest {
    private val cases: Path = Path.of("../../core/testResources/customgames/listings")

    private fun cases(kind: String): List<Path> = Files.list(cases.resolve(kind)).use { it.toList() }.sorted()

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private fun read(file: Path): ModeListing? = parseListing(json(file.readText())) { "https://feed.example/$it" }

    private val bebop by lazy { assertNotNull(read(cases.resolve("valid/bebop-wars.json"))) }

    private fun key(text: String) = assertNotNull(SettingKey.parse(text))

    private fun value(text: String) = assertNotNull(SettingValue.parse(text))

    @Test
    fun everySharedValidListingReads() {
        val valid = cases("valid")
        assertTrue(valid.size >= 2)
        for (file in valid) assertNotNull(read(file), file.name)
    }

    @Test
    fun everySharedInvalidListingIsRefused() {
        val invalid = cases("invalid")
        assertTrue(invalid.size >= 20)
        for (file in invalid) assertNull(read(file), file.name)
    }

    @Test
    fun bebopWarsReadsAsItsAuthorWroteIt() {
        assertEquals("Bebop Wars", bebop.name)
        assertEquals(ModeKind.Teams, bebop.kind)
        assertEquals(2..6, bebop.players)
        assertNull(bebop.minutes)
        assertEquals(LocalDate.of(2026, 11, 6), bebop.released)
        assertEquals(listOf(SectionKind.Scoreboard), bebop.sections)
        assertEquals(listOf(StepIcon.Hook, StepIcon.Souls, StepIcon.Trophy), bebop.howToPlay.map { it.icon })
        assertEquals("Hook your opponent across the canal to score. Spend souls on upgrades in the Winter Shop. First to 25 wins.", bebop.summary)
        assertEquals(LegendEntry("The canal", 0x2E6E9E), bebop.legend[1])
        assertEquals(ModeFact("To win", "First to 25", "Overtime if time runs out"), bebop.facts.first())
        assertEquals("https://feed.example/art/card-background@2x.jpg", bebop.art.card)
        assertEquals("https://feed.example/art/bebop-hook-layer@2x.png", bebop.art.overlay)
        assertEquals(3, bebop.art.screenshots.size)
        assertEquals(listOf("teams"), bebop.settings.map { it.key.value })
        assertEquals(value("2v2"), bebop.settings.single().default)
    }

    @Test
    fun aRenderAndAnOverlayMakeTheLayeredArtAndEitherAloneDoesNot() {
        assertTrue(bebop.art.layered)
        assertFalse(bebop.art.copy(overlay = null).layered)
        assertFalse(bebop.art.copy(render = null).layered)
        val unplaced = parseListing(json(cases.resolve("valid/bebop-wars.json").readText())) { it.takeUnless { path -> "hook-layer" in path } }
        assertFalse(assertNotNull(unplaced).art.layered, "an overlay the resolver can't place leaves the plain art")
        assertFalse(assertNotNull(read(cases.resolve("valid/minimal.json"))).art.layered)
    }

    @Test
    fun anUpcomingListingMayLeaveOutOnlyItsReleaseDate() {
        val upcoming = cases("upcoming")
        assertEquals(3, upcoming.size)
        for (file in upcoming) {
            val listing = assertNotNull(parseUpcomingListing(json(file.readText())) { null }, file.name)
            assertNull(listing.released)
            assertNull(read(file), "${file.name} is no released mode's listing")
        }
        for (file in cases("invalid").filter { it.name != "no-released.json" }) {
            assertNull(parseUpcomingListing(json(file.readText())) { null }, file.name)
        }
    }

    @Test
    fun aStepIconThisHideoutDoesNotDrawShowsTheStepsNumber() {
        val minimal = cases.resolve("valid/minimal.json").readText().trimEnd().removeSuffix("}")
        val listing = assertNotNull(parseListing(json(minimal + ""","howToPlay":[{"title":"Run","body":"Fast.","icon":"rocket"},{"title":"Win","body":"First.","icon":"trophy"}]}""")) { null })
        assertEquals(listOf(null, StepIcon.Trophy), listing.howToPlay.map { it.icon })
    }

    @Test
    fun theFakeAppsBebopWarsIsTheSharedPlaceholder() {
        val shared = assertNotNull(parseListing(json(cases.resolve("valid/bebop-wars.json").readText())) { null })
        assertEquals(shared, gg.hideouthq.core.data.fake.FakeCustomGamesFeed.BebopWars.listing)
    }

    @Test
    fun theTeamSizeTheHostPicksIsTheMatchSize() {
        val defaults = Choices.defaultsOf(bebop)
        assertEquals(4, bebop.capacity(defaults))
        assertEquals(2, bebop.capacity(defaults.with(bebop.settings[0], value("1v1"))))
        assertEquals(6, bebop.capacity(Choices.None), "with no pick the mode's most is the size")
        assertEquals(2..6, bebop.capacities)
    }

    @Test
    fun aModeWithNoSizeSettingTakesItsMost() {
        val plain = assertNotNull(read(cases.resolve("valid/minimal.json")))
        assertEquals(8, plain.capacity(Choices.defaultsOf(plain)))
        assertEquals(1..8, plain.capacities)
        assertEquals(Choices.None, Choices.defaultsOf(plain))
    }

    @Test
    fun aPickThatIsNoOptionLeavesTheChoicesAsTheyWere() {
        val defaults = Choices.defaultsOf(bebop)
        assertEquals(defaults, defaults.with(bebop.settings[0], value("4v4")))
        assertEquals(listOf("teams=3v3"), defaults.with(bebop.settings[0], value("3v3")).args)
        assertEquals(listOf("2v2"), defaults.labels(bebop))
        assertEquals(emptyList(), defaults.labels(null))
    }

    @Test
    fun anInviteCarriesNoMorePicksThanAListingMayDeclare() {
        val five = (1..5).associate { key("k$it") to value("v") }
        assertNull(Choices.of(five))
        assertEquals(listOf("k1=v"), assertNotNull(Choices.of(five.entries.take(1).associate { it.toPair() })).args)
    }

    @Test
    fun whatALaterHideoutMayKnowIsSkippedNotRefused() {
        val minimal = cases.resolve("valid/minimal.json").readText().trimEnd().removeSuffix("}")
        val later = parseListing(
            json(
                minimal + """,
                "kind": "battle-royale",
                "sections": ["scoreboard", "team-picker", "scoreboard"],
                "settings": [{"key": "map", "label": "Map", "type": "map-picker"}],
                "art": {"card": "art/card.png", "tile": "art/tile.png"},
                "newField": 1
                }""",
            ),
        ) { it.takeIf { path -> "card" in path } }
        assertNotNull(later)
        assertNull(later.kind)
        assertEquals(listOf(SectionKind.Scoreboard), later.sections)
        assertEquals(emptyList(), later.settings)
        assertEquals(ModeArt(card = "art/card.png"), later.art, "an image the resolver can't place is left out")
    }

    @Test
    fun anInstalledRecordKeepsEveryListedFieldAndItsPackages() {
        val rules = Artifact("https://feed.example/rules.zip", 9, assertNotNull(Sha256.parse("a".repeat(64))))
        val mode = Mode(ModeId("bebop-wars"), "1.0.0", bebop, ModeOrigin.Feed(rules, null))
        val record = InstalledMode(mode, 41, kotlin.time.Instant.fromEpochMilliseconds(1_800_000_000_000))
        assertEquals(mapOf(mode.id to record), parseInstalled(encodeInstalled(listOf(record))))
        assertEquals(emptyMap(), parseInstalled("{"))
        val folder = mode.copy(origin = ModeOrigin.Folder("/built"))
        assertEquals(emptyMap(), parseInstalled(encodeInstalled(listOf(InstalledMode(folder, 1, null)))), "only a feed mode is ever installed")
    }

    @Test
    fun keysAndValuesAreSafeOnACommandLine() {
        for (bad in listOf("", "A", "a b", "a=b", "a,b", "k".repeat(17), "-\n")) {
            assertNull(SettingKey.parse(bad), bad)
            assertNull(SettingValue.parse(bad), bad)
        }
        assertFalse(SettingKey.parse("first_to-5") == null)
    }
}
