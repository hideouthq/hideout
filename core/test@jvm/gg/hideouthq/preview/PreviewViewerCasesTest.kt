package gg.hideouthq.preview

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PreviewViewerCasesTest {
    private fun bytes(path: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/preview/$path")) { "missing fixture $path" }.use { it.readBytes() }

    private fun resource(path: String): String = bytes(path).decodeToString()

    private fun gltfJson(glbPath: String): JsonElement {
        val glb = ByteBuffer.wrap(bytes(glbPath)).order(ByteOrder.LITTLE_ENDIAN)
        return PreviewJson.parseToJsonElement(String(glb.array(), 20, glb.getInt(12), Charsets.UTF_8))
    }

    @Test
    fun everyValidatorAcceptsExactlyTheValidCases() {
        val document = PreviewJson.parseToJsonElement(resource("viewer-cases.json")).jsonObject
        val basePath = document.getValue("base").jsonPrimitive.content
        val base = PreviewJson.parseToJsonElement(resource(basePath))
        val viewer = document.getValue("viewer").jsonArray.fold(base, ::apply)
        val model = gltfJson("model.glb")
        val cases = document.getValue("cases").jsonArray.map { it.jsonObject }
        assertTrue(cases.size > 50)
        for (case in cases) {
            val name = case.getValue("name").jsonPrimitive.content
            val start = if (case.getValue("start").jsonPrimitive.content == "viewer") viewer else base
            val manifest = case.getValue("ops").jsonArray.fold(start, ::apply)
            val problems = try {
                val decoded = decodeBundleManifest(manifest.toString())
                val gltf = case["model"]?.jsonArray.orEmpty().fold(model, ::apply)
                gltfProblems(decoded.scene!!.model, gltf.toString(), decoded) + packedImageProblems(decoded.scene.model, gltf.toString())
            } catch (e: InvalidPreview) {
                e.problems
            }
            assertEquals(case.getValue("valid").jsonPrimitive.boolean, problems.isEmpty(), "$name: $problems")
        }
    }

    @Test
    fun theViewerFixtureDecodesToTheTypedMetadata() {
        val document = PreviewJson.parseToJsonElement(resource("viewer-cases.json")).jsonObject
        val base = PreviewJson.parseToJsonElement(resource(document.getValue("base").jsonPrimitive.content))
        val manifest = decodeBundleManifest(document.getValue("viewer").jsonArray.fold(base, ::apply).toString())
        assertEquals(listOf(AnimationCategory.Idle, AnimationCategory.Movement), manifest.clips.map { it.category })
        assertEquals(listOf(ResourceOwner.Game, ResourceOwner.Mod), manifest.clips.map { it.source?.owner })
        assertEquals(listOf(false, true), manifest.clips.map { it.changedByMod })
        assertEquals(listOf(true, false, false, false), manifest.abilities.map { it.icon.changedByMod })
        assertEquals("walk", manifest.abilities[1].clipId)
        val molotov = ResourceSource(ResourceOwner.Mod, "panorama/images/hud/abilities/inferno_molotov_psd.vtex_c", "9".repeat(64))
        assertEquals(molotov, manifest.abilities[0].icon.source)
    }

    private fun apply(target: JsonElement, op: JsonElement): JsonElement {
        val o = op.jsonObject
        val remove = "remove" in o
        val path = o.getValue(if (remove) "remove" else "set").jsonArray
        return update(target, path.toList(), if (remove) null else o.getValue("value"))
    }

    private fun update(target: JsonElement, path: List<JsonElement>, value: JsonElement?): JsonElement {
        val key = path.first().jsonPrimitive
        val rest = path.drop(1)
        return when (target) {
            is JsonObject -> {
                val name = key.content
                val child = if (rest.isEmpty()) value else update(target.getValue(name), rest, value)
                JsonObject(if (child == null) target - name else target + (name to child))
            }
            is JsonArray -> {
                val index = key.int
                val items = target.toMutableList()
                val child = if (rest.isEmpty()) value else update(items[index], rest, value)
                when {
                    child == null -> items.removeAt(index)
                    index == items.size -> items.add(child)
                    else -> items[index] = child
                }
                JsonArray(items)
            }
            else -> error("cannot descend into $target")
        }
    }
}
