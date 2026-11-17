package gg.hideouthq.viewer

import com.github.luben.zstd.Zstd
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path

internal object TestKtx2 {
    val cases: Path = Path.of(checkNotNull(System.getProperty("preview.textures")) { "Run with ./kotlin test, which sets preview.textures from module.yaml" })

    fun case(name: String): ByteArray = Files.readAllBytes(cases.resolve("$name.ktx2"))

    val Red = Rgba(255, 0, 0)
    val Blue = Rgba(0, 0, 255)

    data class Rgba(val r: Int, val g: Int, val b: Int, val a: Int = 255)

    /**
     * Every block is BC7 mode 5 with all indices 0, whose colour endpoints keep 7 bits and alpha 8, so 0 and 255 per
     * colour channel and any alpha are sampled exactly (test_viewer_textures.solid).
     */
    fun solid(width: Int, height: Int, colour: Rgba): ByteArray {
        var bits = 1L shl 5
        listOf(colour.r shr 1, 0, colour.g shr 1, 0, colour.b shr 1, 0).forEachIndexed { n, value -> bits = bits or (value.toLong() shl (8 + 7 * n)) }
        bits = bits or (colour.a.toLong() shl 50)
        val block = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).putLong(bits).array()
        val count = 32 - Integer.numberOfLeadingZeros(maxOf(width, height))
        val levels = (0 until count).map { i ->
            val blocks = (maxOf(1, width shr i) + 3) / 4 * ((maxOf(1, height shr i) + 3) / 4)
            val bytes = ByteArray(blocks * 16) { block[it % 16] }
            Zstd.compress(bytes, 3) to bytes.size
        }
        val descriptor = Ktx2Texture.Bc7Descriptor
        val dfdAt = 80 + 24 * count
        val out = ByteBuffer.allocate(dfdAt + descriptor.size + levels.sumOf { it.first.size }).order(ByteOrder.LITTLE_ENDIAN)
        out.put(Ktx2Texture.Identifier)
        listOf(145, 1, width, height, 0, 0, 1, count, 2, dfdAt, descriptor.size, 0, 0).forEach { out.putInt(it) }
        out.putLong(0).putLong(0)
        var at = dfdAt + descriptor.size
        val offsets = IntArray(count)
        for (i in count - 1 downTo 0) {
            offsets[i] = at
            at += levels[i].first.size
        }
        levels.forEachIndexed { i, (frame, bytes) -> out.putLong(offsets[i].toLong()).putLong(frame.size.toLong()).putLong(bytes.toLong()) }
        out.put(descriptor)
        for (i in count - 1 downTo 0) out.put(levels[i].first)
        return out.array()
    }
}
