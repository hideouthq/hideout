package gg.hideouthq.viewer

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.TextureData
import com.github.luben.zstd.Zstd
import com.github.luben.zstd.ZstdDecompressCtx
import com.github.luben.zstd.ZstdException
import net.mgsx.gltf.data.GLTF
import net.mgsx.gltf.loaders.shared.GLTFTypes
import net.mgsx.gltf.loaders.shared.texture.TextureResolver
import org.lwjgl.system.MemoryUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Arrays

class Ktx2Texture private constructor(
    val name: String,
    val width: Int,
    val height: Int,
    private val file: ByteArray,
    private val levels: List<Level>,
) {
    private class Level(val width: Int, val height: Int, val offset: Int, val length: Int, val blockBytes: Int)

    fun gpuBytes(): Long = levels.sumOf { it.blockBytes.toLong() }

    internal val largestLevelBytes: Int get() = levels[0].blockBytes

    internal fun upload(target: Int, mipmapped: Boolean, scratch: ByteBuffer) {
        ZstdDecompressCtx().use { zstd ->
            for (i in 0 until if (mipmapped) levels.size else 1) {
                val level = levels[i]
                scratch.clear()
                val expanded = try {
                    zstd.decompressByteArrayToDirectByteBuffer(scratch, 0, level.blockBytes, file, level.offset, level.length)
                } catch (e: ZstdException) {
                    throw UnsupportedScene("$name: level $i does not expand: ${e.message}", e)
                }
                Gdx.gl.glCompressedTexImage2D(target, i, Bc7Unorm, level.width, level.height, 0, expanded, scratch.limit(expanded))
            }
        }
    }

    companion object {
        private const val Bc7Unorm = 0x8E8C
        private const val VkBc7Unorm = 145
        private const val ZstdSupercompression = 2
        private const val MaxSide = 16384
        private const val HeaderBytes = 80
        private const val LevelIndexBytes = 24
        private const val BlockBytes = 16
        internal val Identifier = byteArrayOf(0xAB.toByte()) + "KTX 20".encodeToByteArray() + byteArrayOf(0xBB.toByte(), 0x0D, 0x0A, 0x1A, 0x0A)

        internal val Bc7Descriptor: ByteArray = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(44).putInt(0).putInt(2 or (40 shl 16))
            .put(134.toByte()).put(1).put(1).put(0)
            .put(3).put(3).put(0).put(0)
            .put(BlockBytes.toByte()).put(ByteArray(7))
            .putShort(0).put(127).put(0).putInt(0).putInt(0).putInt(-1)
            .array()

        fun parse(name: String, file: ByteArray, start: Int = 0, size: Int = file.size - start): Ktx2Texture {
            fun refuse(reason: String): Nothing = throw UnsupportedScene("$name is not a viewer texture: $reason")
            require(start >= 0 && size >= 0 && start <= file.size - size) { "$name lies outside its bytes" }
            if (size < HeaderBytes || !Arrays.equals(file, start, start + Identifier.size, Identifier, 0, Identifier.size)) refuse("not a KTX2 file")
            val header = ByteBuffer.wrap(file, start, size).slice().order(ByteOrder.LITTLE_ENDIAN)
            val width = header.getInt(20)
            val height = header.getInt(24)
            val count = header.getInt(40)
            if (listOf(12, 16, 28, 32, 36, 44).map(header::getInt) != listOf(VkBc7Unorm, 1, 0, 0, 1, ZstdSupercompression)) {
                refuse("not a 2D, single-face, single-layer, zstd-supercompressed BC7 UNORM texture")
            }
            if (width !in 1..MaxSide || height !in 1..MaxSide) refuse("its ${width.toUInt()}x${height.toUInt()} size is outside 1 to $MaxSide per side")
            val chain = 32 - Integer.numberOfLeadingZeros(maxOf(width, height))
            if (count != chain) refuse("it has ${count.toUInt()} levels, not the $chain of a full ${width}x$height chain")
            val dfdAt = HeaderBytes + LevelIndexBytes * count
            val layout = listOf(48, 52, 56, 60).map { header.getInt(it).toLong() } + listOf(64, 72).map(header::getLong)
            if (layout != listOf(dfdAt.toLong(), Bc7Descriptor.size.toLong(), 0L, 0L, 0L, 0L) || dfdAt + Bc7Descriptor.size > size ||
                !Arrays.equals(file, start + dfdAt, start + dfdAt + Bc7Descriptor.size, Bc7Descriptor, 0, Bc7Descriptor.size)) {
                refuse("its data format descriptor is not BC7 UNORM's alone, right after the level index")
            }
            val levels = arrayOfNulls<Level>(count)
            var at = dfdAt + Bc7Descriptor.size
            for (i in count - 1 downTo 0) {
                val offset = header.getLong(HeaderBytes + LevelIndexBytes * i)
                val length = header.getLong(HeaderBytes + LevelIndexBytes * i + 8)
                val expanded = header.getLong(HeaderBytes + LevelIndexBytes * i + 16)
                if (offset != at.toLong() || length <= 0 || length > size - at) refuse("level $i is not stored right after the level below it in size")
                val levelWidth = maxOf(1, width shr i)
                val levelHeight = maxOf(1, height shr i)
                val blockBytes = (levelWidth + 3) / 4 * ((levelHeight + 3) / 4) * BlockBytes
                if (expanded != blockBytes.toLong()) refuse("level $i expands to ${expanded.toULong()} bytes, not its $blockBytes bytes of blocks")
                val stated = Zstd.getFrameContentSize(file, start + at, length.toInt())
                if (stated == ZstdContentSizeError) refuse("level $i is not a zstd frame")
                if (stated != expanded) refuse("level $i's zstd frame does not state its $blockBytes-byte content size")
                levels[i] = Level(levelWidth, levelHeight, start + at, length.toInt(), blockBytes)
                at += length.toInt()
            }
            if (at != size) refuse("bytes follow the largest level")
            return Ktx2Texture(name, width, height, file, levels.map { checkNotNull(it) })
        }

        private const val ZstdContentSizeError = -2L
    }
}

/** Given to GLTFLoaderBase, it keeps gdx-gltf from making an ImageResolver, so no image is decoded and SceneAsset.pixmaps stays null. */
internal class SceneTextures(gltf: GLTF, images: List<Ktx2Texture>) : TextureResolver() {
    init {
        glTextures = gltf.textures
        glSamplers = gltf.samplers
        val scratch = lazy { MemoryUtil.memAlloc(images.maxOf { it.largestLevelBytes }) }
        try {
            gltf.textures?.forEach { glTexture ->
                val mipmapped = glTexture.sampler?.let { GLTFTypes.isMipMapFilter(gltf.samplers[it]) } ?: false
                val built = if (mipmapped) texturesMipmap else texturesSimple
                if (!built.containsKey(glTexture.source)) {
                    val texture = images.getOrNull(glTexture.source) ?: throw UnsupportedScene("The model's texture names image ${glTexture.source}, which it does not pack")
                    built.put(glTexture.source, Texture(Bc7Upload(texture, mipmapped, scratch.value)))
                }
            }
        } catch (t: Throwable) {
            dispose()
            throw t
        } finally {
            if (scratch.isInitialized()) MemoryUtil.memFree(scratch.value)
        }
    }
}

/** A libGDX Texture keeps its TextureData, so this lets go of the stored texture once it has uploaded. */
private class Bc7Upload(texture: Ktx2Texture, private val mipmapped: Boolean, scratch: ByteBuffer) : TextureData {
    private var texture: Ktx2Texture? = texture
    private var scratch: ByteBuffer? = scratch
    private val width = texture.width
    private val height = texture.height

    override fun consumeCustomData(target: Int) {
        checkNotNull(texture) { "A viewer texture uploads once" }.upload(target, mipmapped, checkNotNull(scratch))
        texture = null
        scratch = null
    }

    override fun getType() = TextureData.TextureDataType.Custom
    override fun isPrepared() = true
    override fun prepare() = Unit
    override fun consumePixmap(): Pixmap = throw UnsupportedOperationException("Viewer textures upload compressed")
    override fun disposePixmap() = false
    override fun getWidth() = width
    override fun getHeight() = height
    override fun getFormat() = Pixmap.Format.RGBA8888
    override fun useMipMaps() = mipmapped
    override fun isManaged() = false
}
