package gg.hideouthq.viewer

import com.badlogic.gdx.graphics.glutils.ShaderProgram
import net.mgsx.gltf.scene3d.shaders.PBRShaderConfig
import net.mgsx.gltf.scene3d.shaders.PBRShaderProvider

internal class UniformReferenceShaders(config: PBRShaderConfig) : PBRShaderProvider(config) {
    private val joints = config.numBones

    override fun checkShaderCompilation(program: ShaderProgram) {
        if (!program.isCompiled) {
            val log = program.log
            program.dispose()
            throw UnsupportedScene("The PBR shader for $joints joints did not compile or link: ${log.trim().take(400)}")
        }
        val bones = program.uniforms.firstOrNull { it == "u_bones[0]" || it == "u_bones" }
        if (bones != null && program.getUniformSize(bones) != joints) {
            val size = program.getUniformSize(bones)
            program.dispose()
            throw UnsupportedScene("The PBR shader holds $size joints, not $joints")
        }
    }

    companion object {
        val factory: (PBRShaderConfig, BonePalettes) -> PBRShaderProvider = { config, _ -> UniformReferenceShaders(config) }
    }
}
