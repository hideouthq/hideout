package gg.hideouthq.preview

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PreviewCodecTest {
    @Test
    fun identityHashesToTheBundleIdPythonWrote() {
        assertEquals(FIXTURE_BUNDLE_ID, bundleIdOf(fixtureInput))
    }

    @Test
    fun anyIdentityChangeMakesAnotherBundle() {
        val newerFile = fixtureInput.copy(file = fixtureInput.file.copy(id = 1500002))
        val newerBuild = fixtureInput.copy(game = fixtureInput.game.copy(build = 21000002))
        val otherRecipe = fixtureInput.copy(recipeSha256 = "0".repeat(64))
        val ids = listOf(fixtureInput, newerFile, newerBuild, otherRecipe).map { bundleIdOf(it) }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(ids.all { isBundleId(it) })
    }

    @Test
    fun canonicalJsonMatchesPythonSortedCompactAsciiDumps() {
        val u = "\\u"
        val document = PreviewJson.parseToJsonElement("""{"b":1,"a":{"${u}00e9":"${u}0001\"\\/${u}007f","Z":[true,null,"x",-12]}}""")
        assertEquals("""{"a":{"Z":[true,null,"x",-12],"${u}00e9":"${u}0001\"\\/${u}007f"},"b":1}""", canonicalJson(document))
        val escapes = PreviewJson.parseToJsonElement("""{"t":"\b\f\n\r\t${u}2028"}""")
        assertEquals("""{"t":"\b\f\n\r\t${u}2028"}""", canonicalJson(escapes))
    }

    @Test
    fun manifestRoundTripsThroughJson() {
        val manifest = sampleManifest()
        assertEquals(manifest, decodeBundleManifest(PreviewJson.encodeToString(BundleManifest.serializer(), manifest)))
        val index = indexOf(entryFor(manifest))
        assertEquals(index, decodePreviewIndex(PreviewJson.encodeToString(PreviewIndex.serializer(), index)))
    }

    @Test
    fun orderedDependenciesSurviveDecodingAndKeepThePublishedIdentity() {
        val base = buildJsonObject {
            put("submission", PreviewJson.encodeToJsonElement(SubmissionRef.serializer(), SubmissionRef("Mod", 719871)))
            put("file", PreviewJson.encodeToJsonElement(FileInput.serializer(), fixtureInput.file.copy(id = 1828054)))
        }
        val lower = buildJsonObject {
            put("submission", PreviewJson.encodeToJsonElement(SubmissionRef.serializer(), SubmissionRef("Mod", 719872)))
            put("file", PreviewJson.encodeToJsonElement(FileInput.serializer(), fixtureInput.file.copy(id = 1828055)))
        }
        val ids = listOf(listOf(base, lower), listOf(lower, base)).map { dependencies ->
            val text = manifestJson {
                val input = JsonObject(getValue("input").jsonObject + ("dependencies" to JsonArray(dependencies)))
                put("input", input)
                put("bundleId", JsonPrimitive(bundleIdOf(input)))
            }
            val manifest = decodeBundleManifest(text)
            assertEquals(manifest.bundleId, bundleIdOf(manifest.input))
            manifest.bundleId
        }
        assertEquals(2, ids.toSet().size)
        assertFalse(FIXTURE_BUNDLE_ID in ids)
    }

    @Test
    fun anInputWithoutItsDependenciesIsRefusedEvenUnderItsOwnBundleId() {
        val text = manifestJson {
            val input = JsonObject(getValue("input").jsonObject - "dependencies")
            put("input", input)
            put("bundleId", JsonPrimitive(bundleIdOf(input)))
        }
        assertFailsWith<InvalidPreview> { decodeBundleManifest(text) }
    }

    @Test
    fun documentsOfAnotherSchemaOrWithoutAnIntegerSchemaAreInvalid() {
        for (text in listOf("""{"schema":2,"items":[]}""", """{"generation":1}""", """{"schema":"1"}""", """{"schema":1.5}""", "[]", "{", "")) {
            assertFailsWith<InvalidPreview>(text) { decodePreviewIndex(text) }
        }
        assertFailsWith<InvalidPreview> { decodeBundleManifest(manifestJson { put("schema", JsonPrimitive(2)) }) }
    }

    @Test
    fun unknownKeysAreIgnoredOutsideTheIdentity() {
        val manifest = decodeBundleManifest(manifestJson { put("thumbnailHint", JsonPrimitive("later")) })
        assertEquals(sampleManifest(), manifest)
    }

    @Test
    fun unknownKeysInsideTheIdentityChangeTheBundleId() {
        val text = manifestJson {
            val input = getValue("input").jsonObject
            put("input", JsonObject(input + ("extra" to JsonPrimitive(true))))
        }
        val error = assertFailsWith<InvalidPreview> { decodeBundleManifest(text) }
        assertEquals(listOf("bundleId does not match the input identity"), error.problems)
    }

    @Test
    fun missingRequiredFieldsAndUnknownStatesAreRejected() {
        assertFailsWith<InvalidPreview> { decodeBundleManifest(manifestJson { remove("attribution") }) }
        assertFailsWith<InvalidPreview> {
            decodeBundleManifest(manifestJson { put("picture", buildJsonObject { put("state", JsonPrimitive("approved")) }) })
        }
        assertFailsWith<InvalidPreview> { decodeBundleManifest(manifestJson { put("fidelity", JsonPrimitive("game_parity")) }) }
    }

    @Test
    fun anOlderReadinessStateFailsTheWholeDocument() {
        val index = PreviewJson.encodeToJsonElement(PreviewIndex.serializer(), indexOf(entryFor(sampleManifest()))).jsonObject
        for (state in listOf("pending", "unsupported", "failed")) {
            assertFailsWith<InvalidPreview>(state) {
                decodeBundleManifest(manifestJson { put("interactive", buildJsonObject { put("state", JsonPrimitive(state)); put("reason", JsonPrimitive("older producer")) }) })
            }
            val entry = JsonObject(index.getValue("entries").jsonArray.single().jsonObject + ("picture" to JsonPrimitive(state)))
            assertFailsWith<InvalidPreview>(state) { decodePreviewIndex(JsonObject(index + ("entries" to JsonArray(listOf(entry)))).toString()) }
        }
    }

    @Test
    fun invalidDocumentsReportEveryProblem() {
        val text = manifestJson {
            put("createdAt", JsonPrimitive("yesterday"))
            put("attribution", JsonPrimitive(" "))
        }
        val error = assertFailsWith<InvalidPreview> { decodeBundleManifest(text) }
        assertEquals(2, error.problems.size, error.problems.toString())
    }

    private fun manifestJson(edit: MutableMap<String, JsonElement>.() -> Unit): String {
        val document = PreviewJson.encodeToJsonElement(BundleManifest.serializer(), sampleManifest()).jsonObject.toMutableMap()
        document.edit()
        return JsonObject(document).toString()
    }
}
