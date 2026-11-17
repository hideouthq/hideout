package gg.hideouthq.library

import gg.hideouthq.core.data.MigrationRecords
import gg.hideouthq.core.data.RecordWrite
import gg.hideouthq.core.library.CitadelPath
import gg.hideouthq.core.library.MigrationId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

class FileMigrationRecords(private val folder: Path) : MigrationRecords {
    override suspend fun begin(id: MigrationId, record: ByteArray, copies: Map<CitadelPath, ByteArray>): RecordWrite = withContext(Dispatchers.IO) {
        try {
            Files.createDirectories(folder)
            val own = Files.createDirectory(folder.resolve(id.value))
            for ((path, bytes) in copies) {
                val file = own.resolve(FilesName).resolve(path.value)
                Files.createDirectories(file.parent)
                replaceAtomically(file, bytes)
            }
            replaceAtomically(own.resolve(RecordName), record)
            RecordWrite.Written
        } catch (e: FileAlreadyExistsException) {
            RecordWrite.Failed("a record ${id.value} exists already")
        } catch (e: IOException) {
            RecordWrite.Failed(e.message ?: "the record could not be written")
        }
    }

    override suspend fun update(id: MigrationId, record: ByteArray): RecordWrite = withContext(Dispatchers.IO) {
        val own = folder.resolve(id.value)
        try {
            if (!Files.isRegularFile(own.resolve(RecordName), LinkOption.NOFOLLOW_LINKS)) return@withContext RecordWrite.Failed("there is no record ${id.value}")
            replaceAtomically(own.resolve(RecordName), record)
            RecordWrite.Written
        } catch (e: IOException) {
            RecordWrite.Failed(e.message ?: "the record could not be written")
        }
    }

    override suspend fun all(): Map<MigrationId, ByteArray> = withContext(Dispatchers.IO) {
        if (!Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)) return@withContext emptyMap()
        Files.newDirectoryStream(folder).use { entries -> entries.toList() }.mapNotNull { own ->
            val id = runCatching { MigrationId(own.fileName.toString()) }.getOrNull() ?: return@mapNotNull null
            val file = own.resolve(RecordName)
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return@mapNotNull null
            runCatching { id to Files.readAllBytes(file) }.getOrNull()
        }.toMap()
    }

    private companion object {
        const val FilesName = "files"
        const val RecordName = "record.json"
    }
}
