package gg.hideouthq.library

import gg.hideouthq.core.data.LocalImportResult
import gg.hideouthq.core.data.UnpackResult
import gg.hideouthq.core.data.fake.FakeVpk
import gg.hideouthq.core.library.UnpackLimits
import gg.hideouthq.core.library.groupVpkSets
import gg.hideouthq.preview.sha256Hex
import kotlinx.coroutines.runBlocking
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder.LITTLE_ENDIAN
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.CRC32
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class DesktopImportArchiveTest {
    private val project = generateSequence(Path.of("").toAbsolutePath()) { it.parent }.first { Files.exists(it.resolve("project.yaml")) }
    private val home = Files.createTempDirectory(Files.createDirectories(project.resolve("build/profiles/import-archive-fixtures")), "case-")
    private val copies = home.resolve("copies")
    private val importer = DesktopLocalImport(copies)
    private val installer = NioModInstaller(home.resolve("staging"))
    private val vpk = FakeVpk.directory(embeddedBytes = 8, seed = 3)

    @Test fun snapshotsOfAZipSevenZipRarAndBareVpkUnpackOnceTheirSourceIsGone() = runBlocking {
        val members = listOf("model/skin.vpk" to vpk, "sound/skin.vpk" to FakeVpk.directory(embeddedBytes = 16, seed = 7))
        val zipped = home.resolve("original.zip").also { Files.write(it, zip(*members.toTypedArray())) }
        val seven = home.resolve("original.7z")
        SevenZOutputFile(seven.toFile()).use { out ->
            members.forEach { (path, bytes) ->
                out.putArchiveEntry(SevenZArchiveEntry().apply { name = path })
                out.write(bytes)
                out.closeArchiveEntry()
            }
        }
        val rar = home.resolve("original.rar").also { Files.write(it, storedRar(members)) }
        val bare = home.resolve("original.vpk").also { Files.write(it, vpk) }
        for (source in listOf(zipped, seven, rar, bare)) {
            val snapshot = assertIs<LocalImportResult.Ready>(importer.snapshot(source.toFile().asLocalFile())).snapshot
            try {
                assertEquals(source.fileName.toString(), snapshot.originalName)
                Files.delete(source)
                val unpacked = assertIs<UnpackResult.Unpacked>(installer.unpack(snapshot.archive, UnpackLimits(), snapshot.originalName))
                try {
                    val expected = if (source == bare) listOf("original.vpk" to vpk) else members
                    assertEquals(expected.associate { it.first to sha256Hex(it.second) }, unpacked.vpks.associate { it.path to it.sha256 }, source.toString())
                    assertEquals(expected.size, groupVpkSets(unpacked.vpks).sets.size, source.toString())
                } finally {
                    installer.discard(unpacked.staging)
                }
            } finally {
                importer.release(snapshot)
            }
        }
        assertEquals(emptyList(), Files.list(copies).use { it.toList() })
    }

    private fun storedRar(members: List<Pair<String, ByteArray>>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1a, 0x07, 0x00))
        fun header(type: Int, flags: Int, body: ByteArray) {
            val data = ByteBuffer.allocate(7 + body.size).order(LITTLE_ENDIAN)
                .putShort(0).put(type.toByte()).putShort(flags.toShort()).putShort((7 + body.size).toShort()).put(body).array()
            val crc = CRC32().apply { update(data, 2, data.size - 2) }.value
            ByteBuffer.wrap(data).order(LITTLE_ENDIAN).putShort(crc.toShort())
            out.write(data)
        }
        header(0x73, 0, ByteArray(6))
        members.forEach { (name, bytes) ->
            val encodedName = name.toByteArray(Charsets.US_ASCII)
            val file = ByteBuffer.allocate(25 + encodedName.size).order(LITTLE_ENDIAN)
                .putInt(bytes.size).putInt(bytes.size).put(3.toByte())
                .putInt(CRC32().apply { update(bytes) }.value.toInt()).putInt(0)
                .put(20.toByte()).put(0x30.toByte()).putShort(encodedName.size.toShort()).putInt(0x81a4).put(encodedName)
            header(0x74, 0x8000, file.array())
            out.write(bytes)
        }
        header(0x7b, 0x4000, ByteArray(0))
        return out.toByteArray()
    }
}
