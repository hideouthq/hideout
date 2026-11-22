package gg.hideouthq.library

import gg.hideouthq.OnWindows
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.io.path.writeBytes
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class Sha256Test {
    private val sizes = listOf(0, 1, 63, 64, 65, 65535, 65536, 65537, 3 * 1024 * 1024 + 7)
    private val system = assertNotNull(Sha256Library.load(OnWindows), "this computer's crypto library loads")

    @Test
    fun `the system library hashes what the JDK does at every size`() {
        for (size in sizes) {
            val bytes = Random(size).nextBytes(size)
            assertContentEquals(jdk(bytes), sha256(system).digest(bytes), "$size bytes at once")
        }
    }

    @Test
    fun `small writes add up to the same digest as one`() {
        val bytes = Random(7).nextBytes(sizes.last())
        for (step in listOf(1000, 65536)) {
            val digest = sha256(system)
            for (at in bytes.indices step step) digest.update(bytes, at, minOf(step, bytes.size - at))
            assertContentEquals(jdk(bytes), digest.digest(), "$step bytes a write")
        }
        val single = sha256(system)
        bytes.copyOf(300_000).forEach(single::update)
        assertContentEquals(jdk(bytes.copyOf(300_000)), single.digest(), "a byte a write")
    }

    @Test
    fun `a file read to its end gives its size and digest`() {
        val file = Files.createTempFile("sha256", ".bin")
        try {
            for (size in sizes) {
                val bytes = Random(size).nextBytes(size)
                file.writeBytes(bytes)
                val digest = sha256(system)
                val md5 = MessageDigest.getInstance("MD5")
                assertEquals(size.toLong(), FileChannel.open(file).use { it.digestRest(digest, md5) }, "$size bytes read")
                assertContentEquals(jdk(bytes), digest.digest(), "$size bytes hashed")
                assertContentEquals(MessageDigest.getInstance("MD5").digest(bytes), md5.digest(), "$size bytes into a second digest")
            }
        } finally {
            Files.delete(file)
        }
    }

    @Test
    fun `a digest starts over after it finishes or resets`() {
        val digest = sha256(system)
        digest.update("discarded".encodeToByteArray())
        digest.reset()
        assertContentEquals(jdk("abc".encodeToByteArray()), digest.digest("abc".encodeToByteArray()))
        assertContentEquals(jdk("second".encodeToByteArray()), digest.digest("second".encodeToByteArray()))
    }

    @Test
    fun `without the library the JDK's digest hashes instead`() {
        val missing = Sha256Library.load(windows = false, libcrypto = listOf("libhideout-absent.so.3"))
        assertNull(missing)
        val digest = sha256(missing)
        assertEquals(MessageDigest.getInstance("SHA-256").provider, digest.provider)
        val bytes = Random(1).nextBytes(65537)
        assertContentEquals(jdk(bytes), digest.digest(bytes))
    }

    private fun jdk(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
}
