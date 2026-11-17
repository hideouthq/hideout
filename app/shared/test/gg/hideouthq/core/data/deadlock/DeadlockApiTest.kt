package gg.hideouthq.core.data.deadlock

import gg.hideouthq.core.model.Hero
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class DeadlockApiTest {
    @Test
    fun theRosterIsReleasedAndPreReleaseHeroesWithTheirSmallIcon() = runTest {
        val body = """[
            {"id":1,"class_name":"hero_inferno","name":"Infernus","disabled":false,"development_state":"release","player_selectable":true,
             "images":{"icon_image_small":"https://cdn.test/inferno_sm.png","icon_image_small_webp":"https://cdn.test/inferno_sm.webp","minimap_image":"https://cdn.test/inferno_mm.png"},
             "starting_stats":{"max_health":{"value":550}}},
            {"id":6,"class_name":"hero_atlas","name":"Abrams","development_state":"release","images":{"icon_image_small":"https://cdn.test/bull_sm.png"}},
            {"id":78,"class_name":"hero_deadpack","name":"Deadman Danny","development_state":"pre_release","player_selectable":false,"images":{}},
            {"id":55,"class_name":"hero_testhero","name":"Test Hero","development_state":"in_development"},
            {"id":13,"class_name":"hero_haze","name":"Haze","disabled":true,"development_state":"release"}
        ]"""
        assertEquals(
            listOf(
                Hero("hero_inferno", "Infernus", "https://cdn.test/inferno_sm.webp", numericId = 1),
                Hero("hero_atlas", "Abrams", "https://cdn.test/bull_sm.png", numericId = 6),
                Hero("hero_deadpack", "Deadman Danny", null, numericId = 78),
            ),
            heroes(body),
        )
    }

    @Test
    fun statsGetTheNumericIdCardRenderAndAValidStyleColour() = runTest {
        val body = """[
            {"id":2,"class_name":"hero_gigawatt","name":"Seven","development_state":"release","player_selectable":true,
             "images":{"icon_hero_card":"https://cdn.test/gigawatt_card.png","icon_hero_card_webp":"https://cdn.test/gigawatt_card.webp"},
             "colors":{"ui":[237,149,60],"style_hex":"#CC861E"}},
            {"id":13,"class_name":"hero_haze","name":"Haze","development_state":"release","player_selectable":true,
             "images":{"icon_hero_card":"https://cdn.test/haze_card.png"},"colors":{"style_hex":"red"}}
        ]"""
        val heroes = heroes(body)
        assertEquals(listOf(2, 13), heroes.map { it.numericId })
        assertEquals(listOf("https://cdn.test/gigawatt_card.webp", "https://cdn.test/haze_card.png"), heroes.map { it.renderUrl })
        assertEquals(listOf("#CC861E", null), heroes.map { it.colorHex })
    }

    @Test
    fun signatureAbilitiesMapSlotsOneToFourFromTheItemsField() = runTest {
        val body = """[
            {"id":1,"class_name":"hero_inferno","name":"Infernus","development_state":"release","player_selectable":true,
             "items":{"weapon_primary":"citadel_weapon_inferno_set","signature1":"ability_incendiary_projectile",
                      "signature2":"ability_flame_dash","signature3":"ability_afterburn","signature4":"ability_fire_bomb"}},
            {"id":2,"class_name":"hero_atlas","name":"Abrams","development_state":"release","player_selectable":true,
             "items":{"signature1":"ability_siphon_life","signature3":"ability_seismic_impact"}},
            {"id":3,"class_name":"hero_haze","name":"Haze","development_state":"release","player_selectable":true}
        ]"""
        val heroes = heroes(body).associateBy { it.id }
        assertEquals(
            mapOf(1 to "ability_incendiary_projectile", 2 to "ability_flame_dash", 3 to "ability_afterburn", 4 to "ability_fire_bomb"),
            heroes.getValue("hero_inferno").signatureAbilities,
        )
        assertEquals(mapOf(1 to "ability_siphon_life", 3 to "ability_seismic_impact"), heroes.getValue("hero_atlas").signatureAbilities, "a missing slot is absent, not a guessed id")
        assertEquals(emptyMap(), heroes.getValue("hero_haze").signatureAbilities)
    }

    @Test
    fun theBackgroundPrefersWebpOverPngAndIsNullWhenNeitherIsGiven() = runTest {
        val body = """[
            {"id":1,"class_name":"hero_inferno","name":"Infernus","development_state":"release","player_selectable":true,
             "images":{"background_image":"https://cdn.test/infernus_bg.png","background_image_webp":"https://cdn.test/infernus_bg.webp"}},
            {"id":2,"class_name":"hero_atlas","name":"Abrams","development_state":"release","player_selectable":true,
             "images":{"background_image":"https://cdn.test/abrams_bg.png"}},
            {"id":3,"class_name":"hero_haze","name":"Haze","development_state":"release","player_selectable":true,"images":{}}
        ]"""
        assertEquals(
            listOf("https://cdn.test/infernus_bg.webp", "https://cdn.test/abrams_bg.png", null),
            heroes(body).map { it.backgroundUrl },
        )
    }

    private suspend fun heroes(body: String) =
        DeadlockApi(HttpClient(MockEngine { respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json")) })).heroes()
}
