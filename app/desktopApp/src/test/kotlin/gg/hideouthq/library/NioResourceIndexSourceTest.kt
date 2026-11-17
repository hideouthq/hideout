package gg.hideouthq.library

import gg.hideouthq.core.library.GameRoot
import gg.hideouthq.core.library.ResourceIndex
import gg.hideouthq.core.library.Sha256
import gg.hideouthq.core.library.sharedFiles
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.CRC32
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class NioResourceIndexSourceTest {
    private val project = generateSequence(Path.of("").toAbsolutePath()) { it.parent }.first { Files.exists(it.resolve("project.yaml")) }
    private val home = Files.createTempDirectory(Files.createDirectories(project.resolve("build/profiles/resource-index-fixtures")), "case-")
    private val root = GameRoot(home.toString())
    private val source = NioResourceIndexSource()

    @Test
    fun readsVersionOneAndTwoWithEachFilesCrcWithoutChunkFilesOrExtractingResources() = runBlocking {
        for (version in listOf(1, 2)) {
            val bytes = fixture(version)
            val path = "pak0${version}_dir.vpk"
            Files.write(home.resolve(path), bytes)
            assertEquals(ResourceIndex.Ready(mapOf("models/ivy.vmdl_c" to crc(Body))), source.read(root, path, Sha256.image(bytes)))
        }
        assertEquals(2L, Files.list(home).use { it.count() })
    }

    @Test
    fun twoVpksShareAFileOnlyWhenItsBytesDiffer() = runBlocking {
        suspend fun indexed(name: String, body: ByteArray): Map<String, Long> {
            val bytes = fixture(2, body)
            Files.write(home.resolve(name), bytes)
            return assertIs<ResourceIndex.Ready>(source.read(root, name, Sha256.image(bytes))).files
        }
        val first = indexed("pak01_dir.vpk", Body)
        assertEquals(emptyList(), sharedFiles(listOf(first, indexed("pak02_dir.vpk", Body))), "the same model in both looks alike whichever loads")
        assertEquals(listOf("models/ivy.vmdl_c"), sharedFiles(listOf(first, indexed("pak03_dir.vpk", "another ivy".encodeToByteArray()))))
    }

    @Test
    fun missingChangedTruncatedAndUnsupportedDirectoriesAreUnavailable() = runBlocking<Unit> {
        val path = "pak01_dir.vpk"
        val original = fixture(2)
        assertIs<ResourceIndex.Unavailable>(source.read(root, path, Sha256.image(original)))
        Files.write(home.resolve(path), original.copyOf(original.size - 1))
        assertIs<ResourceIndex.Unavailable>(source.read(root, path, Sha256.image(original)))
        val changed = original.copyOf().also { it[it.lastIndex - 3] = 1 }
        Files.write(home.resolve(path), changed)
        assertIs<ResourceIndex.Unavailable>(source.read(root, path, Sha256.image(original)), "same size but a different hash")
        val unsupported = fixture(3)
        Files.write(home.resolve(path), unsupported)
        assertIs<ResourceIndex.Unavailable>(source.read(root, path, Sha256.image(unsupported)))
    }

    @Test
    fun aFileHideoutDoesNotOwnIsReadByItsTreeAloneWhateverItsBytes() = runBlocking<Unit> {
        val addons = Files.createDirectories(home.resolve("game/citadel/addons"))
        val bytes = fixture(2)
        Files.write(addons.resolve("pak03_dir.vpk"), bytes.copyOf().also { it[it.lastIndex] = 0 })
        assertEquals(ResourceIndex.Ready(mapOf("models/ivy.vmdl_c" to crc(Body))), source.readForeign(root, "pak03_dir.vpk"))
        assertIs<ResourceIndex.Unavailable>(source.read(root, "game/citadel/addons/pak03_dir.vpk", Sha256.image(bytes)), "a set Hideout owns must hold its recorded bytes")
        Files.write(addons.resolve("pak04_dir.vpk"), "not a vpk".encodeToByteArray())
        assertIs<ResourceIndex.Unavailable>(source.readForeign(root, "pak04_dir.vpk"))
        assertIs<ResourceIndex.Unavailable>(source.readForeign(root, "pak05_dir.vpk"), "nothing is there")
    }

    private fun fixture(version: Int, body: ByteArray = Body): ByteArray {
        val tree = ByteArrayOutputStream()
        tree.write("vmdl_c\u0000models\u0000ivy\u0000".encodeToByteArray())
        tree.write(ByteBuffer.allocate(18).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(crc(body).toInt()).putShort(0).putShort(0x7FFF).putInt(0).putInt(body.size).putShort(0xFFFF.toShort()).array())
        tree.write(byteArrayOf(0, 0, 0))
        val header = ByteBuffer.allocate(if (version == 1) 12 else 28).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(0x55AA1234).putInt(version).putInt(tree.size())
        if (version == 2) header.putInt(body.size)
        return header.array() + tree.toByteArray() + body
    }

    private fun crc(bytes: ByteArray): Long = CRC32().apply { update(bytes) }.value

    private companion object {
        val Body = "ivy body".encodeToByteArray()
    }
}
