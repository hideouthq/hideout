package gg.hideouthq.library.compat

import gg.hideouthq.core.library.FileEvidence
import gg.hideouthq.core.library.FileImage
import gg.hideouthq.core.library.ReadSpan
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

internal class ReadLog {
    private val files = mutableListOf<() -> FileEvidence>()

    fun open(path: Path, installId: String?): RecordedSource {
        val source = try {
            FileSource.open(path)
        } catch (e: NoSuchFileException) {
            absent(path, installId)
            throw e
        }
        return RecordedSource(source).also { recorded -> synchronized(files) { files += { recorded.evidence(path, installId) } } }
    }

    fun whole(path: Path, installId: String?, image: FileImage) {
        val evidence = FileEvidence(path.toString(), installId, image.sizeBytes, listOf(ReadSpan(0, image.sizeBytes)), image.sha256)
        synchronized(files) { files += { evidence } }
    }

    fun absent(path: Path, installId: String?) {
        val evidence = FileEvidence(path.toString(), installId, null, emptyList(), "")
        synchronized(files) { files += { evidence } }
    }

    fun evidence(): Set<FileEvidence> = synchronized(files) { files.map { it() }.toSet() }

    internal class RecordedSource(private val source: FileSource) : ByteSource {
        private val reads = mutableListOf<ReadSpan>()
        private val digest = MessageDigest.getInstance("SHA-256")

        override val size: Long get() = source.size

        @Synchronized
        override fun read(position: Long, length: Int): ByteArray {
            val bytes = source.read(position, length)
            reads += ReadSpan(position, length.toLong())
            digest.update(bytes)
            return bytes
        }

        @Synchronized
        fun evidence(path: Path, installId: String?): FileEvidence =
            FileEvidence(path.toString(), installId, size, reads.toList(), (digest.clone() as MessageDigest).digest().toHexString())

        override fun close() = source.close()
    }

    companion object {
        private const val StepBytes = 1 shl 20

        fun holds(evidence: FileEvidence): Boolean {
            val channel = try {
                FileChannel.open(Path.of(evidence.location), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)
            } catch (e: NoSuchFileException) {
                return evidence.sizeBytes == null
            } catch (e: IOException) {
                return false
            }
            return channel.use {
                try {
                    if (evidence.sizeBytes == null || it.size() != evidence.sizeBytes) return@use false
                    val digest = MessageDigest.getInstance("SHA-256")
                    for (span in evidence.reads) {
                        var at = 0L
                        while (at < span.length) {
                            val buffer = ByteBuffer.allocate(minOf(StepBytes.toLong(), span.length - at).toInt())
                            while (buffer.hasRemaining()) if (it.read(buffer, span.position + at + buffer.position()) < 0) return@use false
                            digest.update(buffer.array())
                            at += buffer.capacity()
                        }
                    }
                    digest.digest().toHexString() == evidence.sha256
                } catch (e: IOException) {
                    false
                }
            }
        }
    }
}
