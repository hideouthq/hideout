package gg.hideouthq.library

import gg.hideouthq.core.library.FileImage
import gg.hideouthq.core.library.GameRoot
import gg.hideouthq.core.library.ResourceIndex
import gg.hideouthq.core.library.ResourceIndexSource
import gg.hideouthq.core.library.SetFolder
import gg.hideouthq.core.library.VpkFormat
import gg.hideouthq.core.library.VpkHead
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.DigestInputStream
import java.security.MessageDigest

class NioResourceIndexSource : ResourceIndexSource {
    override suspend fun read(root: GameRoot, relativePath: String, image: FileImage): ResourceIndex = withContext(Dispatchers.IO) {
        try {
            FileChannel.open(Path.of(root.path).resolve(relativePath), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
                if (channel.size() != image.sizeBytes) return@use ResourceIndex.Unavailable
                val digest = MessageDigest.getInstance("SHA-256")
                val input = DigestInputStream(Channels.newInputStream(channel), digest)
                val prefix = input.readNBytes(VpkFormat.PrefixBytes)
                val length = VpkFormat.headLength(prefix, image.sizeBytes) as? VpkHead.Length ?: return@use ResourceIndex.Unavailable
                val head = if (length.bytes <= prefix.size) prefix.copyOf(length.bytes) else prefix + input.readNBytes(length.bytes - prefix.size)
                val buffer = ByteArray(64 * 1024)
                while (input.read(buffer) >= 0) currentCoroutineContext().ensureActive()
                if (channel.size() != image.sizeBytes || digest.digest().toHexString() != image.sha256) ResourceIndex.Unavailable
                else VpkFormat.resources(head, image.sizeBytes)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            ResourceIndex.Unavailable
        }
    }

    override suspend fun readForeign(root: GameRoot, name: String): ResourceIndex = withContext(Dispatchers.IO) {
        try {
            val path = managedFolder(root, SetFolder.Addons, create = false).resolve(name)
            val size = attributesOrNull(path)?.takeIf { it.isRegularFile }?.size() ?: return@withContext ResourceIndex.Unavailable
            VpkFormat.resources(headOf(path, size), size)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            ResourceIndex.Unavailable
        }
    }
}
