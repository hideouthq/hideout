package gg.hideouthq.viewer

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.TextureData
import com.badlogic.gdx.graphics.g3d.Attributes
import com.badlogic.gdx.graphics.g3d.ModelInstance
import com.badlogic.gdx.graphics.g3d.Renderable
import com.badlogic.gdx.graphics.g3d.Shader
import com.badlogic.gdx.graphics.g3d.model.Node
import com.badlogic.gdx.graphics.g3d.shaders.BaseShader
import com.badlogic.gdx.graphics.g3d.shaders.DepthShader
import com.badlogic.gdx.graphics.glutils.ShaderProgram
import com.badlogic.gdx.math.Matrix4
import com.badlogic.gdx.utils.BufferUtils
import com.badlogic.gdx.utils.Disposable
import com.badlogic.gdx.utils.GdxRuntimeException
import net.mgsx.gltf.scene3d.shaders.PBRCommon
import net.mgsx.gltf.scene3d.shaders.PBRDepthShader
import net.mgsx.gltf.scene3d.shaders.PBRDepthShaderProvider
import net.mgsx.gltf.scene3d.shaders.PBRShader
import net.mgsx.gltf.scene3d.shaders.PBRShaderConfig
import net.mgsx.gltf.scene3d.shaders.PBRShaderProvider
import java.nio.FloatBuffer
import java.util.IdentityHashMap

object BonePaletteLayout {
    const val Uniform = "u_bonePalette"

    const val TexelsPerJoint = 4

    const val InternalFormat = 0x8814

    const val TextureUnit = 0
}

/**
 * A part's palette is found by the identity of its bones array, which is what each of its renderables carries. Rows
 * past a part's joints, and missing matrices, are identity, as DefaultShader.Setters.Bones made them.
 */
internal class BonePalettes(instance: ModelInstance, private val rows: Int) : Disposable {
    private val textures = IdentityHashMap<Array<Matrix4>, Texture>()
    private val staging: FloatBuffer = BufferUtils.newFloatBuffer(rows * MatrixFloats)

    init {
        fun collect(node: Node) {
            node.parts.forEach { part -> part.bones?.let { bones -> textures.getOrPut(bones) { Texture(PaletteData(rows)) } } }
            node.children.forEach(::collect)
        }
        instance.nodes.forEach(::collect)
    }

    val handles: List<Int> get() = textures.values.map { it.textureObjectHandle }

    fun upload() {
        for ((bones, texture) in textures) {
            staging.clear()
            for (row in 0 until rows) staging.put((bones.getOrNull(row) ?: Identity).`val`)
            staging.flip()
            texture.bind(BonePaletteLayout.TextureUnit)
            Gdx.gl.glTexSubImage2D(GL20.GL_TEXTURE_2D, 0, 0, 0, BonePaletteLayout.TexelsPerJoint, rows, GL20.GL_RGBA, GL20.GL_FLOAT, staging)
        }
    }

    fun bind(bones: Array<Matrix4>): Int {
        val texture = checkNotNull(textures[bones]) { "These bones belong to no skinned part of the scene" }
        texture.bind(BonePaletteLayout.TextureUnit)
        return BonePaletteLayout.TextureUnit
    }

    override fun dispose() {
        textures.values.forEach { it.dispose() }
        textures.clear()
    }

    companion object {
        private const val MatrixFloats = 16
        private val Identity = Matrix4()

        fun capabilityProblem(joints: Int, gl: GlInfo): String? = when {
            joints == 0 -> null
            gl.major < 3 -> "Skinning reads its bone palette with texelFetch, which needs OpenGL 3.0; this GPU runs ${gl.version}"
            gl.maxVertexTextureImageUnits < 1 -> "Skinning reads its bone palette in the vertex shader, where this GPU samples no textures"
            joints > gl.maxTextureSize -> "A skin of $joints joints needs a bone palette $joints texels tall; this GPU takes textures up to ${gl.maxTextureSize}"
            else -> null
        }
    }
}

/**
 * gdx-gltf 2.3.0's PBR and depth vertex shaders with their uniform bone array replaced by reads of the palette. A
 * zero-weight influence may name a joint past its skin, so the row is clamped to the palette: a weighted joint is
 * always within it, and every read stays defined.
 */
internal object PaletteGlsl {
    private const val Declaration = "uniform mat4 u_bones[numBones];"
    private val Read = Regex("""u_bones\[int\((a_boneWeight([0-7]))\.x\)\]""")
    private val Palette = """
        uniform sampler2D ${BonePaletteLayout.Uniform};
        mat4 paletteBone(float joint) {
            int row = min(int(joint), numBones - 1);
            return mat4(texelFetch(${BonePaletteLayout.Uniform}, ivec2(0, row), 0),
                        texelFetch(${BonePaletteLayout.Uniform}, ivec2(1, row), 0),
                        texelFetch(${BonePaletteLayout.Uniform}, ivec2(2, row), 0),
                        texelFetch(${BonePaletteLayout.Uniform}, ivec2(3, row), 0));
        }
    """.trimIndent()

    fun patch(vertexShader: String): String = Read.replace(vertexShader.replace(Declaration, Palette)) { "paletteBone(${it.groupValues[1]}.x)" }

    fun refuse(program: ShaderProgram, rows: Int) {
        if (program.isCompiled) return
        val log = program.log.trim().take(400)
        program.dispose()
        throw UnsupportedScene("The shader for $rows joints did not compile or link: $log")
    }
}

internal class PaletteShaders(config: PBRShaderConfig, private val palettes: BonePalettes) :
    PBRShaderProvider(config.apply { vertexShader = PaletteGlsl.patch(vertexShader ?: PBRShaderProvider.getDefaultVertexShader()) }) {
    private val rows = config.numBones

    override fun createShader(renderable: Renderable, config: PBRShaderConfig, prefix: String): PBRShader = PaletteShader(renderable, config, prefix, palettes)

    override fun checkShaderCompilation(program: ShaderProgram) = PaletteGlsl.refuse(program, rows)
}

internal class PaletteDepthShaders(config: DepthShader.Config, private val palettes: BonePalettes) :
    PBRDepthShaderProvider(config.apply { vertexShader = PaletteGlsl.patch(vertexShader ?: PBRDepthShaderProvider.getDefaultVertexShader()) }) {
    override fun createShader(renderable: Renderable): Shader {
        PBRCommon.checkVertexAttributes(renderable)
        val version = if (Gdx.graphics.glVersion.isVersionEqualToOrHigher(3, 0)) "#version 130\n" else ""
        val shader = PaletteDepthShader(renderable, config, version + DepthShader.createPrefix(renderable, config) + morphTargetsPrefix(renderable), palettes)
        PaletteGlsl.refuse(shader.program, config.numBones)
        return shader
    }
}

private class PaletteShader(renderable: Renderable, config: PBRShaderConfig, prefix: String, palettes: BonePalettes) : PBRShader(renderable, config, prefix) {
    private val skinned = renderable.bones != null

    init {
        register(BaseShader.Uniform(BonePaletteLayout.Uniform), PaletteSetter(palettes))
    }

    override fun canRender(renderable: Renderable): Boolean = (renderable.bones != null) == skinned && super.canRender(renderable)
}

private class PaletteDepthShader(renderable: Renderable, config: DepthShader.Config, prefix: String, palettes: BonePalettes) : PBRDepthShader(renderable, config, prefix) {
    private val skinned = renderable.bones != null

    init {
        register(BaseShader.Uniform(BonePaletteLayout.Uniform), PaletteSetter(palettes))
    }

    override fun canRender(renderable: Renderable): Boolean = (renderable.bones != null) == skinned && super.canRender(renderable)
}

private class PaletteSetter(private val palettes: BonePalettes) : BaseShader.LocalSetter() {
    override fun set(shader: BaseShader, inputID: Int, renderable: Renderable, combinedAttributes: Attributes) {
        shader.set(inputID, palettes.bind(checkNotNull(renderable.bones) { "An unskinned renderable reached a skinned shader" }))
    }
}

/** Managed, so libGDX counts it among live textures. */
private class PaletteData(private val rows: Int) : TextureData {
    override fun getType() = TextureData.TextureDataType.Custom

    override fun isPrepared() = true

    override fun prepare() = Unit

    override fun consumePixmap(): Pixmap = throw GdxRuntimeException("A bone palette has no pixmap")

    override fun disposePixmap() = false

    override fun consumeCustomData(target: Int) =
        Gdx.gl.glTexImage2D(target, 0, BonePaletteLayout.InternalFormat, BonePaletteLayout.TexelsPerJoint, rows, 0, GL20.GL_RGBA, GL20.GL_FLOAT, null)

    override fun getWidth() = BonePaletteLayout.TexelsPerJoint

    override fun getHeight() = rows

    override fun getFormat(): Pixmap.Format = Pixmap.Format.RGBA8888

    override fun useMipMaps() = false

    override fun isManaged() = true
}
