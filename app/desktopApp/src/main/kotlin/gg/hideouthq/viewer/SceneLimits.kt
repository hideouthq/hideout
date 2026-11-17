package gg.hideouthq.viewer

import gg.hideouthq.preview.PreviewScene
import net.mgsx.gltf.loaders.shared.GLTFLoaderBase

object SceneLimits {
    const val MaxInfluenceSets = 2
    const val MaxMorphTargets = 8

    private const val MaxTextureBytes = 128L shl 20

    fun capabilityProblem(scene: PreviewScene, gl: GlInfo): String? = when {
        !gl.bc7 -> "This GPU's OpenGL has no BC7 textures (OpenGL 4.2 or ARB_texture_compression_bptc)"
        else -> BonePalettes.capabilityProblem(scene.maxJointsPerSkin, gl) ?: when {
            scene.influenceSets > MaxInfluenceSets -> "${scene.influenceSets} joint influence sets; the renderer takes $MaxInfluenceSets"
            scene.morphTargets > MaxMorphTargets -> "${scene.morphTargets} morph targets; the renderer takes $MaxMorphTargets"
            else -> scene.extensionsRequired.firstOrNull { it !in SceneDecoder.extensions && !GLTFLoaderBase.supportedExtensions.contains(it) }?.let { "The model requires $it" }
        }
    }

    fun texturesProblem(textures: Collection<Ktx2Texture>, gl: GlInfo): String? {
        textures.firstOrNull { it.width > gl.maxTextureSize || it.height > gl.maxTextureSize }?.let {
            return "${it.name} is ${it.width}x${it.height}; this GPU takes textures up to ${gl.maxTextureSize} on a side"
        }
        val bytes = textures.sumOf { it.gpuBytes() }
        if (bytes <= MaxTextureBytes) return null
        return "The scene's textures take ${(bytes + Mebibyte - 1) / Mebibyte} MiB of GPU memory; this viewer takes up to ${MaxTextureBytes / Mebibyte} MiB"
    }

    private const val Mebibyte = 1L shl 20
}
