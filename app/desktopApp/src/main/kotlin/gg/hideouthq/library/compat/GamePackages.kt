package gg.hideouthq.library.compat

import gg.hideouthq.core.library.FileImage
import gg.hideouthq.preview.sha256Hex
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardOpenOption

internal class GamePackages private constructor(
    private val citadel: Path,
    private val dir: ByteSource,
    private val pack: VpkPackage,
    val identity: String,
    private val log: ReadLog?,
) : AutoCloseable {
    private val byPath: Map<String, List<VpkEntry>> = pack.entries.groupBy { it.path }
    private val chunks = HashMap<Int, ByteSource>()

    fun resource(path: String): ByteArray? {
        val entry = byPath[path.lowercase()]?.singleOrNull() ?: return null
        return VpkPackages.payload(pack, entry, dir, ::chunk, ModelRepairs.MaxResourceBytes)
    }

    @Synchronized
    private fun chunk(index: Int): ByteSource? = chunks[index] ?: run {
        if (index !in 0..999) return null
        val file = citadel.resolve("pak01_${index.toString().padStart(3, '0')}.vpk")
        val opened = try {
            log?.open(file, installId = null) ?: FileSource.open(file)
        } catch (e: NoSuchFileException) {
            return null
        }
        opened.also { chunks[index] = it }
    }

    @Synchronized
    override fun close() {
        chunks.values.forEach { it.close() }
    }

    companion object {
        private const val MaxDirectoryBytes = 512L * 1024 * 1024

        fun open(root: Path, log: ReadLog?, parse: (String, ByteArray) -> VpkPackage): GamePackages {
            val citadel = root.resolve("game").resolve("citadel")
            val (file, bytes, sha) = directory(citadel)
            log?.whole(file, null, FileImage(bytes.size.toLong(), sha))
            return GamePackages(citadel, ArraySource(bytes), parse(sha, bytes), "pak01:$sha", log)
        }

        fun identity(root: Path): String = "pak01:${directory(root.resolve("game").resolve("citadel")).third}"

        private fun directory(citadel: Path): Triple<Path, ByteArray, String> {
            val file = citadel.resolve("pak01_dir.vpk")
            val bytes = try {
                readWhole(file, MaxDirectoryBytes)
            } catch (e: NoSuchFileException) {
                throw IOException("the game's pak01_dir.vpk is not there")
            }
            return Triple(file, bytes, sha256Hex(bytes))
        }

        private fun readWhole(file: Path, limit: Long): ByteArray = FileChannel.open(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
            val size = channel.size()
            if (size > limit) throw IOException("$file is larger than it can be")
            val buffer = ByteBuffer.allocate(size.toInt())
            while (buffer.hasRemaining()) if (channel.read(buffer) < 0) throw IOException("$file ended early")
            if (channel.read(ByteBuffer.allocate(1)) >= 0) throw IOException("$file grew while it was read")
            buffer.array()
        }
    }
}
