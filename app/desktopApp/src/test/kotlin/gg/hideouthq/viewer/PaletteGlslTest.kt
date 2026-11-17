package gg.hideouthq.viewer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PaletteGlslTest {
    private fun shader(path: String) = checkNotNull(javaClass.getResource("/net/mgsx/gltf/shaders/$path")) { "gdx-gltf has no $path" }.readText()

    @Test
    fun theColourAndDepthShadersReadEveryInfluenceFromThePaletteAndNoUniformArray() {
        for (path in listOf("pbr/pbr.vs.glsl", "depth.vs.glsl")) {
            val patched = PaletteGlsl.patch(shader(path))
            assertFalse("u_bones" in patched, "$path still reads u_bones")
            assertEquals(1, patched.split("uniform sampler2D ${BonePaletteLayout.Uniform};").size - 1, path)
            assertEquals((0..7).map { "paletteBone(a_boneWeight$it.x)" }, Regex("""paletteBone\(a_boneWeight\d\.x\)""").findAll(patched).map { it.value }.toList(), path)
            assertTrue("min(int(joint), numBones - 1)" in patched, "$path does not clamp a zero-weight joint past the palette")
            assertEquals(4, Regex("""texelFetch\(${BonePaletteLayout.Uniform}, ivec2\([0-3], row\), 0\)""").findAll(patched).count(), path)
        }
    }
}
