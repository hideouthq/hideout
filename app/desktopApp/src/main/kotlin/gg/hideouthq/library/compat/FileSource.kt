package gg.hideouthq.library.compat

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption

internal class FileSource private constructor(private val channel: FileChannel) : ByteSource {
    override val size: Long = channel.size()

    override fun read(position: Long, length: Int): ByteArray {
        if (position < 0 || length < 0 || position + length > size) throw Kv3Failure("a read runs past the file")
        val buffer = ByteBuffer.allocate(length)
        var at = position
        while (buffer.hasRemaining()) {
            val read = channel.read(buffer, at)
            if (read < 0) throw IOException("the file ended early")
            at += read
        }
        return buffer.array()
    }

    override fun close() = channel.close()

    companion object {
        fun open(path: Path): FileSource = FileSource(FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))
    }
}
