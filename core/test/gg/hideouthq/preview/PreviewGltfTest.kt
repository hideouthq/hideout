package gg.hideouthq.preview

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PreviewGltfTest {
    private val manifest = sampleManifest()
    private val withWeapon = manifest.copy(assets = manifest.assets + PreviewAsset("weapon.glb", 10, "a".repeat(64), MODEL_MEDIA_TYPE))

    @Test
    fun sceneModelMatchingTheManifestHasNoProblems() {
        assertEquals(emptyList(), gltfProblems("model.glb.zst", sampleGltf(), manifest))
    }

    @Test
    fun binaryDataMustStayInTheGlb() {
        assertProblem(sampleGltf(buffers = """[{"byteLength":64,"uri":"model.bin"}]"""), "outside its own chunk")
        assertProblem(sampleGltf(buffers = """[{"byteLength":64},{"byteLength":8}]"""), "outside its own chunk")
    }

    @Test
    fun theBufferMustFitInTheBinChunk() {
        assertEquals(emptyList(), glbProblems("model.glb.zst", sampleGltf(), 64, manifest))
        assertEquals(emptyList(), glbProblems("model.glb.zst", sampleGltf(), 68, manifest))
        assertTrue(glbProblems("model.glb.zst", sampleGltf(), null, manifest).any { "no BIN chunk for its buffer" in it })
        assertTrue(glbProblems("model.glb.zst", sampleGltf(), 60, manifest).any { "BIN chunk shorter than its buffer" in it })
        for (buffers in listOf("""[{}]""", """[{"byteLength":0}]""", """[{"byteLength":"64"}]""", """[{"byteLength":64.5}]""")) {
            assertProblem(sampleGltf(buffers = buffers), "buffer without a byte length")
        }
    }

    @Test
    fun aGlbWithoutBinaryDataNeedsNoBinChunk() {
        val empty = """{"asset":{"version":"2.0"}}"""
        assertEquals(emptyList(), glbProblems("weapon.glb", empty, null, withWeapon))
        assertEquals(emptyList(), glbProblems("weapon.glb", empty, 0, withWeapon))
    }

    @Test
    fun bufferViewsMustLieInsideTheBuffer() {
        fun withViews(views: String, buffers: String = """[{"byteLength":64}]""") =
            """{"asset":{"version":"2.0"},"buffers":$buffers,"bufferViews":$views}"""
        assertEquals(emptyList(), gltfProblems("weapon.glb", withViews("""[{"buffer":0,"byteLength":60},{"buffer":0,"byteOffset":60,"byteLength":4}]"""), withWeapon))
        for (views in listOf(
            """[{"buffer":0,"byteOffset":60,"byteLength":8}]""",
            """[{"buffer":1,"byteLength":4}]""",
            """[{"buffer":"0","byteLength":4}]""",
            """[{"buffer":0}]""",
            """[{"buffer":0,"byteOffset":-4,"byteLength":8}]""",
        )) {
            assertTrue(gltfProblems("weapon.glb", withViews(views), withWeapon).any { "buffer views outside its buffer" in it }, views)
        }
        assertTrue(gltfProblems("weapon.glb", withViews("""[{"buffer":0,"byteLength":4}]""", buffers = "[]"), withWeapon).isNotEmpty())
    }

    @Test
    fun imagesArePackedAfterTheMeshesInTheBinChunk() {
        val meshopt = """{"buffer":1,"byteLength":64,"extensions":{"EXT_meshopt_compression":{"buffer":0,"byteOffset":8,"byteLength":24,"byteStride":4,"mode":"ATTRIBUTES","count":16}}}"""
        fun stored(views: String, images: String, accessors: String = """[{"bufferView":0}]""") =
            """{"asset":{"version":"2.0"},"buffers":[{"byteLength":200},{"byteLength":64,"extensions":{"EXT_meshopt_compression":{"fallback":true}}}],
               "bufferViews":$views,"accessors":$accessors,"images":$images}"""
        val image = """{"bufferView":2,"mimeType":"image/ktx2"}"""
        val packed = """[{"buffer":0,"byteLength":8},$meshopt,{"buffer":0,"byteOffset":32,"byteLength":100}]"""
        assertEquals(emptyList(), packedImageProblems("model.glb.zst", stored(packed, "[$image]")))
        assertEquals(emptyList(), packedImageProblems("model.glb.zst", stored(packed, "[$image,$image]")))
        assertEquals(emptyList(), packedImageProblems("model.glb.zst", stored(packed, "[]")))
        val notPacked = "is not an image/ktx2 texture packed in a buffer view"
        for (images in listOf(
            """[{"uri":"textures/body.ktx2","mimeType":"image/ktx2"}]""",
            """[{"uri":"textures/body.ktx2","bufferView":2,"mimeType":"image/ktx2"}]""",
            """[{"uri":"data:image/ktx2;base64,AAAA"}]""",
            """[{"bufferView":2,"mimeType":"image/png"}]""",
            """[{"bufferView":2}]""",
            """[{"bufferView":3,"mimeType":"image/ktx2"}]""",
            """[{"bufferView":"2","mimeType":"image/ktx2"}]""",
        )) {
            assertTrue(packedImageProblems("model.glb.zst", stored(packed, images)).any { notPacked in it }, images)
        }
        val notLast = "images are not packed in the buffer views after all the others"
        for (views in listOf(
            """[{"buffer":0,"byteLength":8},$meshopt,{"buffer":0,"byteOffset":24,"byteLength":100}]""",
            """[{"buffer":0,"byteLength":8},$meshopt,{"buffer":0,"byteOffset":32,"byteLength":100,"byteStride":4}]""",
            """[{"buffer":0,"byteLength":8},$meshopt,{"buffer":1,"byteOffset":32,"byteLength":10}]""",
            """[{"buffer":0,"byteLength":8},$meshopt,{"buffer":0,"byteOffset":32,"byteLength":100},{"buffer":0,"byteOffset":132,"byteLength":4}]""",
        )) {
            assertTrue(packedImageProblems("model.glb.zst", stored(views, "[$image]")).any { notLast in it }, views)
        }
        assertTrue(packedImageProblems("model.glb.zst", stored(packed, "[$image]", """[{"bufferView":2}]""")).any { notLast in it })
        val limit = """[{"buffer":0,"byteLength":8},$meshopt,{"buffer":0,"byteOffset":32,"byteLength":$MAX_IMAGE_BYTES}]"""
        assertEquals(emptyList(), packedImageProblems("model.glb.zst", stored(limit, "[$image]")))
        assertTrue(packedImageProblems("model.glb.zst", stored(limit.replace("$MAX_IMAGE_BYTES", "${MAX_IMAGE_BYTES + 1}"), "[$image]")).any { "more than" in it })
        assertEquals(1, packedImageProblems("model.glb.zst", """{"images":[1]}""").size)
    }

    @Test
    fun animationsMustBeTheListedClipsWithTheirDurations() {
        assertProblem(sampleGltf(animations = """[{"name":"idle","samplers":[{"input":0,"output":1}]}]"""), "not exactly the listed clips")
        assertProblem(
            sampleGltf(animations = """[{"name":"idle","samplers":[{"input":2,"output":1}]},{"name":"walk","samplers":[{"input":2,"output":3}]}]"""),
            "does not last 1250ms",
        )
        assertProblem(
            sampleGltf(animations = """[{"name":"idle","samplers":[{"input":1,"output":1}]},{"name":"walk","samplers":[{"input":2,"output":3}]}]"""),
            "has no input range",
        )
        assertProblem(
            sampleGltf(animations = """[{"samplers":[]},{"name":"idle","samplers":[{"input":0,"output":1}]},{"name":"walk","samplers":[{"input":2,"output":3}]}]"""),
            "unnamed animation",
        )
        assertProblem(
            sampleGltf(animations = """[{"name":"idle","samplers":[{"input":0}]},{"name":"walk","samplers":[{"input":2}]},{"name":"run","samplers":[{"input":2}]}]"""),
            "not exactly the listed clips",
        )
    }

    @Test
    fun aClipEndThatIsNotAFiniteJsonNumberIsNoInputRange() {
        for (end in listOf("\"1.25\"", "NaN", "1.25f", "-1.25")) {
            assertProblem(sampleGltf(accessors = """[{"max":[$end]},{},{"max":[0.8]},{},{},{},{}]"""), "animation \"idle\" has no input range")
        }
    }

    @Test
    fun sceneCountsAreMeasuredFromTheModel() {
        assertProblem(sampleGltf(skins = """[{"joints":[1,2,3]}]"""), "3 joints in its largest skin, not 2")
        assertProblem(
            sampleGltf(meshes = """[{"primitives":[{"attributes":{"JOINTS_0":5,"JOINTS_1":6}}]}]"""),
            "2 joint influence sets, not 1",
        )
        assertProblem(
            sampleGltf(meshes = """[{"primitives":[{"attributes":{"JOINTS_0":5},"targets":[{"POSITION":4}]}]}]"""),
            "1 morph targets, not 0",
        )
        assertProblem(sampleGltf(extensionsRequired = """["KHR_texture_basisu"]"""), "requires different extensions")
    }

    @Test
    fun otherModelsSkipSceneChecks() {
        assertEquals(emptyList(), gltfProblems("weapon.glb", sampleGltf(animations = "[]", skins = "[]"), withWeapon))
    }

    @Test
    fun malformedJsonIsAProblemNotACrash() {
        for (text in listOf("", "[]", "{", """{"buffers":{}}""", """{"bufferViews":[1]}""", """{"buffers":[{"uri":5}],"animations":null}""")) {
            assertEquals(1, gltfProblems("model.glb.zst", text, manifest).size, text)
        }
    }

    private fun assertProblem(gltf: String, fragment: String) {
        val problems = gltfProblems("model.glb.zst", gltf, manifest)
        assertTrue(problems.any { fragment in it }, "expected a problem containing \"$fragment\" in $problems")
    }
}
