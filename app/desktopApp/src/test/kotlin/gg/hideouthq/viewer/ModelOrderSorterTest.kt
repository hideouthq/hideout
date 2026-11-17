package gg.hideouthq.viewer

import com.badlogic.gdx.graphics.Camera
import com.badlogic.gdx.graphics.OrthographicCamera
import com.badlogic.gdx.graphics.g3d.Material
import com.badlogic.gdx.graphics.g3d.Renderable
import com.badlogic.gdx.graphics.g3d.Shader
import com.badlogic.gdx.graphics.g3d.attributes.BlendingAttribute
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute
import com.badlogic.gdx.graphics.g3d.utils.RenderContext
import net.mgsx.gltf.scene3d.scene.SceneRenderableSorter
import kotlin.test.Test
import kotlin.test.assertEquals

class ModelOrderSorterTest {
    /** A shader whose hash code is chosen, as an identity hash is chosen by whatever the thread hashed before. */
    private class Hashed(private val hash: Int) : Shader {
        override fun init() = Unit
        override fun compareTo(other: Shader?) = 0
        override fun canRender(instance: Renderable?) = true
        override fun begin(camera: Camera?, context: RenderContext?) = Unit
        override fun render(renderable: Renderable?) = Unit
        override fun end() = Unit
        override fun dispose() = Unit
        override fun hashCode() = hash
        override fun equals(other: Any?) = this === other
    }

    private fun part(name: String, hash: Int, z: Float, blended: Boolean = false) = Renderable().apply {
        meshPart.id = name
        shader = Hashed(hash)
        material = Material(name, ColorAttribute.createDiffuse(hash / 10f, 0f, 0f, 1f)).apply { if (blended) set(BlendingAttribute(true, 0.5f)) }
        worldTransform.setToTranslation(0f, 0f, z)
    }

    private fun sorted(sorter: com.badlogic.gdx.graphics.g3d.utils.RenderableSorter, parts: List<Renderable>): List<String> {
        val camera = OrthographicCamera().apply { position.set(0f, 0f, 10f) }
        return com.badlogic.gdx.utils.Array<Renderable>().apply { parts.forEach(::add) }.also { sorter.sort(camera, it) }.map { it.meshPart.id }
    }

    @Test
    fun opaquePartsKeepTheModelsOrderWhateverTheirShadersHashCodesAre() {
        val parts = listOf(part("gear", 3, 0f), part("body", 1, 0f), part("wings", 2, 0f))
        assertEquals(listOf("body", "wings", "gear"), sorted(SceneRenderableSorter(), parts), "gdx-gltf's sorter no longer orders opaque parts by shader hash code")
        assertEquals(listOf("gear", "body", "wings"), sorted(ModelOrderSorter(), parts))
        assertEquals(listOf("gear", "body", "wings"), sorted(ModelOrderSorter(), listOf(part("gear", 1, 0f), part("body", 2, 0f), part("wings", 3, 0f))))
    }

    @Test
    fun blendedPartsFollowBackToFrontWithTiesInTheModelsOrder() {
        val parts = listOf(part("near pane", 1, 5f, blended = true), part("body", 2, 0f), part("far pane", 3, -5f, blended = true), part("level pane", 4, 4.99f, blended = true), part("gun", 5, 1f))
        assertEquals(listOf("body", "gun", "far pane", "near pane", "level pane"), sorted(ModelOrderSorter(), parts))
    }
}
